#!/usr/bin/env python3
"""Deterministic generator for the per-effect screen post-effect shaders (sfx_000..sfx_419).

Running `python3 tools/gen_screen_shaders.py` (re)writes ALL of
src/main/resources/assets/bubbleshield/shaders/screenfx/sfx_000.fsh .. sfx_419.fsh
plus tools/screen_manifest.json AND a byte-identical classpath copy at
src/main/resources/assets/bubbleshield/screen_manifest.json (the
screenTemplateMatchesJson gametest cross-checks every EffectRegistry row's screen
family against it on the dedicated-server classpath). Regeneration is byte-stable:
a fixed global seed feeds a self-contained splitmix64 PRNG, iteration is in sorted
id order, and floats use fixed precision -- so diffs stay reviewable.

Design (see /tmp/shader_plan.md section 4.2 and AGENTS.md):

* Frozen screen contract: `#version 330`; `#moj_import <minecraft:globals.glsl>`
  (GameTime is the only animation source); `uniform sampler2D InSampler;`,
  `in vec2 texCoord;`, std140 `SamplerInfo { vec2 OutSize; vec2 InSize; }`; ONE
  config block; opaque output (`fragColor.a = 1.0`).

* Standardized config block, in this EXACT member order for every generated file
  (tools/validate_shaders.py regex-checks it against each post_effect JSON):

      layout(std140) uniform FxConfig {
          vec4 Primary;
          vec4 Secondary;
          vec4 ParamsA;
          vec4 ParamsB;
      };

  Uniform semantics (values are packed by tools/gen_post_effects.py -- see its
  header for the per-family packing table): Primary/Secondary = the effect's
  palette; ParamsA = [Speed, Strength, Scale, Aux] (family-interpreted knobs
  derived from the FROZEN paramA/paramB formulas); ParamsB = [Phase, Drift,
  TintMix, LumaFloor] (id-phase, secondary motion rate, palette-lean weight,
  gameplay luminance floor).

* 28 screen technique families -- the 16 original template looks refactored as
  parameterized modules (tint, wobble, vignette, chroma, pixelate, desat,
  bloomglow, ripple, scanlines, edgeglow, frostlens, heathaze, posterize,
  radialblur, glitch, duotone) + 4 v2 ones (kaleido refraction, huedrift,
  dreamblur + sparkle, moire interference) + 8 v5 ones held ready for the next
  catalogue flip (spectral ghost echoes, aberration lens fringing, underwater
  caustic refraction, thermal false-color, sketch edge ink, starburst streaks,
  vhs tape artifacts, gloom breathing dusk). Each id's family comes from
  EffectRegistry.java (parsed at generation time as ground truth), so the v5
  modules emit nothing until registry rows reference them -- adding them does
  not perturb a single existing byte. The structural stack (family, variant,
  motion, overlay) is chosen by deterministic probing so all 420 stacks are
  pairwise distinct.

* Gameplay safety, enforced by the shared composer (not per-module discretion):
  every non-identity scene sample is routed through
  `safeOffset()` = clamp to +/-MAX_UV_OFFSET (0.02) per axis; the final color is
  floored at `base * ParamsB.w` (never crush the world below ~0.35x); output is
  opaque (`fragColor = vec4(outColor, 1.0)`).

* Richness pass (v3): every file ends its composition with a bounded
  soft-contrast curve plus a vibrance (saturation) lift BEFORE the luma
  floor -- richer, deeper effect colors without touching the
  gameplay-visibility guarantees (both passes are hue-preserving and the
  floor still applies last).

* Per-family calibration (v4, from the 10-way screenshot review):
  - edgeglow: additive glow rides min(strength, 1.0) * (1 - 0.5*baseLuma)
    so edges accent bright scenes instead of blowing them out;
  - glitch: tear/split amplitudes ride min(ParamsA.y, 1.0) and every flash
    tint is scaled by (1 - baseLuma);
  - frostlens: the sheet variant's floor is raised to ~0.32+, the frosted
    layer whitens over bright backgrounds (baseLuma^2 term) and sparse
    crystal facets carry animated glints, so the rime is actually visible;
  - scanlines: the line count is resolution-aware -- min(ParamsA.z,
    safeInSize * 0.25) per axis -- so the raster cannot alias into shimmer
    soup on small framebuffers.
  The Speed/scale knobs of the strobe-prone families are additionally
  clamped on the JSON side (tools/gen_post_effects.py SPEED_CLAMP + the
  min(paramA, 2.0) scale clamps); ids 0..104 are unaffected by construction.

* Compile safety: conservative GLSL 330 subset only -- const-bounded for loops
  (blur/streak <= 12 taps, 3x3 kernels), no while, no switch, no arrays of
  structs, explicit float literals, every function defined before use, no
  uniforms beyond the three declared blocks/samplers.

Usage:
    python3 tools/gen_screen_shaders.py                  # all 420 + manifests
    python3 tools/gen_screen_shaders.py --only 0-15      # subset (same bytes)
    python3 tools/gen_screen_shaders.py --only 0-15 --out /tmp/probe
"""

import argparse
import json
import math
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SCREENFX_DIR = REPO_ROOT / "src/main/resources/assets/bubbleshield/shaders/screenfx"
REGISTRY_JAVA = REPO_ROOT / "src/main/java/com/bubbleshield/effect/EffectRegistry.java"
DEFAULT_MANIFEST = REPO_ROOT / "tools/screen_manifest.json"
CLASSPATH_MANIFEST = REPO_ROOT / "src/main/resources/assets/bubbleshield/screen_manifest.json"

COUNT = 840
GLOBAL_SEED = 0x5C4EE7F1
MAX_UV_OFFSET = 0.02  # gameplay-safety bound for any scene-sample displacement

# First id of the 840-flip catalogue rows. Safety fixes that would otherwise
# change pre-flip bytes (the rate-capped overlay clocks below) are gated to
# ids >= this, so regeneration leaves sfx_000..419 byte-identical.
V5_FLIP_START = 420

# Set per-file by emit_shader (True for the 840-flip ids >= V5_FLIP_START).
# Read by family modules whose id range straddles the flip (fam_dreamblur's
# built-in twinkle; the shared overlays take an explicit parameter instead)
# so the photosensitivity rate caps apply ONLY to the new ids.
_RATE_CAPPED = False

FAMILIES = [
    "tint", "wobble", "vignette", "chroma", "pixelate", "desat",
    "bloomglow", "ripple", "scanlines", "edgeglow", "frostlens", "heathaze",
    "posterize", "radialblur", "glitch", "duotone",
    "kaleido", "huedrift", "dreamblur", "moire",
    # v5 families: referenced only by the 840-flip rows 420..839, so every
    # pre-flip sfx byte (ids 0..419) is untouched by regeneration.
    "spectral", "aberration", "underwater", "thermal",
    "sketch", "starburst", "vhs", "gloom",
]

# Structural variant axis per family (baked GLSL structure, not just numbers).
VARIANTS = {
    "tint": ["grade", "splitlat", "radial", "breathe"],
    "wobble": ["sincos", "harmonic", "diag", "swirl"],
    "vignette": ["round", "box", "noisy", "bands"],
    "chroma": ["radial", "linear", "rotating", "zoned"],
    "pixelate": ["square", "wide", "diamond", "breathe"],
    "desat": ["flat", "shadows", "tunnel", "twotone"],
    "bloomglow": ["cross", "diag", "ring", "starburst"],
    "ripple": ["center", "twin", "linear", "pond"],
    "scanlines": ["horizontal", "vertical", "rolling", "grid"],
    "edgeglow": ["sobel", "pulse", "duo", "thick"],
    "frostlens": ["creep", "veins", "sheet", "breath"],
    "heathaze": ["rising", "full", "columns", "embers"],
    "posterize": ["breathe", "dither", "lumaonly", "banded"],
    "radialblur": ["zoom", "spin", "zoomspin", "pulsezoom"],
    "glitch": ["tear", "blocks", "rgbdrift", "rowcol"],
    "duotone": ["soft", "hard", "tritone", "shifting"],
    "kaleido": ["wedge4", "wedge6", "wedge8", "mirror"],
    "huedrift": ["global", "radial", "waves", "split"],
    "dreamblur": ["soft9", "cross5", "glowdream", "edgehalo"],
    "moire": ["linlin", "ringring", "linring", "rotmoire"],
    "spectral": ["twin", "triple", "orbit", "fade"],
    "aberration": ["barrel", "pincushion", "breathing", "cornered"],
    "underwater": ["shallows", "deep", "shafted", "surgewash"],
    "thermal": ["classic", "inverted", "banded", "hotspots"],
    "sketch": ["ink", "crosshatch", "charcoal", "blueprint"],
    "starburst": ["four", "five", "six", "spinning"],
    "vhs": ["tracking", "dropout", "headswitch", "worn"],
    "gloom": ["dusk", "creeping", "heartbeat", "hollow"],
}

MOTIONS = ["steady", "pulse", "drift", "surge"]
OVERLAYS = ["none", "grain", "sparkle", "pulseglow"]

MASK64 = (1 << 64) - 1


def _splitmix(z: int) -> int:
    z = (z + 0x9E3779B97F4A7C15) & MASK64
    z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & MASK64
    z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & MASK64
    return (z ^ (z >> 31)) & MASK64


def mix_seed(*parts: int) -> int:
    h = GLOBAL_SEED & MASK64
    for p in parts:
        h = _splitmix((h ^ (p & MASK64)) & MASK64)
    return h


class Rng:
    """Tiny deterministic splitmix64 stream -- byte-stable across Python versions."""

    def __init__(self, seed: int):
        self.state = seed & MASK64

    def next64(self) -> int:
        self.state = (self.state + 0x9E3779B97F4A7C15) & MASK64
        z = self.state
        z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & MASK64
        z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & MASK64
        return (z ^ (z >> 31)) & MASK64

    def unit(self) -> float:
        return self.next64() / float(1 << 64)

    def randint(self, lo: int, hi: int) -> int:
        return lo + self.next64() % (hi - lo + 1)


def F(x: float) -> str:
    """Formats a float as an explicit GLSL float literal (always has a '.')."""
    return f"{x:.4f}"


def parse_registry_screen_families() -> dict:
    """Parses EffectRegistry.java rows: id -> screen family (last string arg)."""
    text = REGISTRY_JAVA.read_text()
    pattern = re.compile(r'row\((\d+),[^\n]*"([a-z_]+)"\)\);')
    rows = {}
    for m in pattern.finditer(text):
        rows[int(m.group(1))] = m.group(2)
    if sorted(rows) != list(range(COUNT)):
        sys.exit(f"EffectRegistry.java parse failed: found {len(rows)} rows, expected ids 0..{COUNT - 1}")
    unknown = sorted({fam for fam in rows.values() if fam not in FAMILIES})
    if unknown:
        sys.exit(f"EffectRegistry.java uses screen families this generator does not know: {unknown}")
    return rows


def build_assignments() -> list:
    """Builds the full COUNT-row assignment table (always computed over ALL ids so
    partial --only runs emit byte-identical files to a full run)."""
    families = parse_registry_screen_families()
    rows = []
    used = set()
    for effect_id in range(COUNT):
        family = families[effect_id]
        rng = Rng(mix_seed(effect_id, 1))
        v0 = rng.randint(0, 3)
        m0 = rng.randint(0, 3)
        o0 = rng.randint(0, 3)
        chosen = None
        for k in range(64):
            variant = VARIANTS[family][(v0 + k // 16) % 4]
            motion = MOTIONS[(m0 + (k // 4) % 4) % 4]
            overlay = OVERLAYS[(o0 + k) % 4]
            tup = (family, variant, motion, overlay)
            if tup not in used:
                chosen = tup
                break
        if chosen is None:
            sys.exit(f"assignment probing exhausted for id {effect_id} (family {family})")
        used.add(chosen)
        rows.append({
            "id": effect_id,
            "family": chosen[0],
            "variant": chosen[1],
            "motion": chosen[2],
            "overlay": chosen[3],
            "seed": mix_seed(effect_id, 2),
        })
    assert len(used) == COUNT, "assignment stacks are not pairwise distinct"
    return rows


# ---------------------------------------------------------------------------
# Helper snippet bank (inlined per file, only the helpers a shader uses; every
# function is defined before use via HELPER_ORDER).
# ---------------------------------------------------------------------------

HELPERS = {
    "luma": """float luma(vec3 c) {
    return dot(c, vec3(0.3, 0.59, 0.11));
}""",
    "invsmooth": """// 1 - smoothstep with ASCENDING edges. Replaces every reversed-edge
// smoothstep(hi, lo, x) call: edge0 >= edge1 is undefined by the GLSL
// spec; this form is numerically identical on conforming drivers.
float invsmooth(float lo, float hi, float x) {
    return 1.0 - smoothstep(lo, hi, x);
}""",
    "hash11": """// small-multiplier hash (Hoskins 0.1031 style): stays alive in fp32 for
// inputs up to ~1e5, unlike the fract(p * 443.8975) form which collapses
// to 0 once time-derived inputs grow past a few minutes of GameTime.
float hash11(float p) {
    p = fract(p * 0.1031);
    p *= p + 33.33;
    p *= p + p;
    return fract(p);
}""",
    "hash21": """float hash21(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}""",
    "vnoise": """float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}""",
    "safeAtan": """// two-argument atan is undefined at the exact origin; guard it
float safeAtan(float y, float x) {
    return (abs(x) < 1e-6 && abs(y) < 1e-6) ? 0.0 : atan(y, x);
}""",
    "safeNormalize": """// normalize() is undefined for the zero vector; this stays finite
vec2 safeNormalize(vec2 v) {
    return v * inversesqrt(max(dot(v, v), 1e-8));
}""",
    "safeOffset": f"""// Gameplay-safety: any scene-sample displacement is bounded per axis.
// Call sites pass the TOTAL displacement (all offsets summed) so the bound
// cannot be defeated by stacking two half-size offsets.
vec2 safeOffset(vec2 off) {{
    return clamp(off, vec2(-{F(MAX_UV_OFFSET)}), vec2({F(MAX_UV_OFFSET)}));
}}""",
    "sampleAt": """vec3 sampleAt(vec2 uv) {
    return texture(InSampler, clamp(uv, 0.0, 1.0)).rgb;
}""",
    "brightTap": """// Bright-pass sample: keeps only the luma above the threshold.
vec3 brightTap(vec2 uv, float threshold) {
    vec3 texel = texture(InSampler, clamp(uv, 0.0, 1.0)).rgb;
    return texel * max(luma(texel) - threshold, 0.0);
}""",
    "lumaAt": """float lumaAt(vec2 uv) {
    return dot(texture(InSampler, clamp(uv, 0.0, 1.0)).rgb, vec3(0.3, 0.59, 0.11));
}""",
    "hueRotate": """// Rodrigues rotation of the color vector around the grey axis.
vec3 hueRotate(vec3 c, float a) {
    const vec3 k = vec3(0.57735027);
    float ca = cos(a);
    float sa = sin(a);
    return c * ca + cross(k, c) * sa + k * dot(k, c) * (1.0 - ca);
}""",
}

HELPER_ORDER = ["luma", "invsmooth", "safeAtan", "safeNormalize", "hash11", "hash21", "vnoise",
                "safeOffset", "sampleAt", "brightTap", "lumaAt", "hueRotate"]

VNOISE_DEPS = {"vnoise": ["hash21"]}


def resolve_helpers(names: set) -> list:
    resolved = set()
    for n in names:
        resolved.add(n)
        for dep in VNOISE_DEPS.get(n, []):
            resolved.add(dep)
    return [n for n in HELPER_ORDER if n in resolved]


# ---------------------------------------------------------------------------
# Family modules. Each returns (lines, helper-set); lines assume the shared
# prelude (base/baseLuma/centered/aspectCentered/centerDist/anim/animAmp/
# strength) and must end by defining `vec3 outColor`.
# ---------------------------------------------------------------------------


def fam_tint(v, u):
    if v == "grade":
        return [
            "// Color-grade: shadows lean toward Secondary, highlights toward Primary.",
            f"vec3 graded = mix(Secondary.rgb, Primary.rgb, baseLuma) * ({F(u(0, 0.85, 1.1))} * baseLuma + {F(u(1, 0.02, 0.1))});",
            "vec3 outColor = mix(base, graded, clamp(strength, 0.0, 1.0));",
        ], {"luma"}
    if v == "splitlat":
        return [
            "// Split-tone by screen latitude: lower half sinks into Secondary, upper into Primary.",
            f"float band = smoothstep({F(u(0, 0.2, 0.35))}, {F(u(1, 0.6, 0.8))}, texCoord.y);",
            "vec3 shadowTone = base * mix(vec3(1.0), Secondary.rgb, ParamsB.z);",
            "vec3 lightTone = base * mix(vec3(1.0), Primary.rgb, ParamsB.z);",
            f"vec3 graded = mix(shadowTone, lightTone, band) * ({F(u(2, 0.8, 0.95))} + {F(u(3, 0.25, 0.4))} * baseLuma);",
            "vec3 outColor = mix(base, graded, clamp(strength, 0.0, 1.0));",
        ], {"luma"}
    if v == "radial":
        return [
            "// Radial grade: warm Primary core fading into a Secondary-graded rim.",
            f"float ring = smoothstep({F(u(0, 0.1, 0.2))}, {F(u(1, 0.6, 0.8))}, centerDist);",
            f"vec3 inner = mix(base, base * Primary.rgb * {F(u(2, 1.1, 1.35))}, strength * (1.0 - ring));",
            "vec3 outColor = mix(inner, inner * mix(vec3(1.0), Secondary.rgb, ParamsB.z), strength * ring);",
        ], {"luma"}
    # breathe
    return [
        "// Breathing grade curve: the luma remap exponent oscillates on GameTime.",
        f"float curve = pow(baseLuma, {F(u(0, 0.8, 1.2))} + {F(u(1, 0.15, 0.3))} * sin(anim * {F(u(2, 0.5, 0.9))}));",
        f"vec3 graded = mix(Secondary.rgb, Primary.rgb, curve) * ({F(u(3, 0.3, 0.45))} + {F(u(4, 0.55, 0.7))} * curve);",
        "vec3 outColor = mix(base, graded, clamp(strength, 0.0, 1.0));",
    ], {"luma"}


def fam_wobble(v, u):
    tail = [
        "vec3 scene = sampleAt(texCoord + safeOffset(off));",
        "vec3 outColor = mix(scene, scene * Primary.rgb, ParamsB.z);",
    ]
    if v == "sincos":
        return [
            "// Perpendicular sine/cosine sway of the whole scene (bounded by safeOffset).",
            "vec2 off = vec2(",
            "    sin(texCoord.y * ParamsA.z + anim),",
            f"    cos(texCoord.x * ParamsA.z * {F(u(0, 0.8, 1.25))} + anim)",
            ") * ParamsA.y * animAmp;",
        ] + tail, {"safeOffset", "sampleAt"}
    if v == "harmonic":
        return [
            "// Two out-of-phase harmonics per axis give a rolling, watery sway.",
            "vec2 off = (vec2(sin(texCoord.y * ParamsA.z + anim), cos(texCoord.x * ParamsA.z + anim))",
            f"    + {F(u(0, 0.35, 0.6))} * vec2(sin(texCoord.y * {F(u(1, 40.0, 70.0))} - anim * 1.7),",
            f"        cos(texCoord.x * {F(u(2, 35.0, 60.0))} + anim * 1.3))) * ParamsA.y * animAmp;",
        ] + tail, {"safeOffset", "sampleAt"}
    if v == "diag":
        ang = u(0, 0.4, 1.2)
        ax, ay = math.cos(ang), math.sin(ang)
        return [
            "// A single diagonal wavefront slides across the screen.",
            f"vec2 axis = vec2({F(ax)}, {F(ay)});",
            f"float wave = sin(dot(texCoord, axis) * ParamsA.z * {F(u(1, 1.1, 1.7))} + anim);",
            "vec2 off = axis * wave * ParamsA.y * animAmp;",
        ] + tail, {"safeOffset", "sampleAt"}
    # swirl
    return [
        "// Tangential swirl around the screen center, calm in the middle.",
        "float angle = safeAtan(aspectCentered.y, aspectCentered.x);",
        f"float swirl = sin(angle * {F(float(int(u(0, 2.0, 5.99))))} + anim - centerDist * {F(u(1, 4.0, 9.0))});",
        "vec2 tangent = centerDist > 0.0001 ? vec2(-aspectCentered.y, aspectCentered.x) / centerDist : vec2(0.0);",
        f"vec2 off = tangent * swirl * ParamsA.y * animAmp * smoothstep(0.05, {F(u(2, 0.3, 0.5))}, centerDist);",
    ] + tail, {"safeAtan", "safeOffset", "sampleAt"}


def fam_vignette(v, u):
    if v == "round":
        return [
            "// Classic breathing ring of the effect color around the screen edge.",
            f"float edge = smoothstep({F(u(0, 0.2, 0.3))}, {F(u(1, 0.62, 0.78))}, centerDist);",
            "vec3 outColor = mix(base, Primary.rgb, edge * clamp(strength, 0.0, 1.0));",
        ], set()
    if v == "box":
        return [
            "// Squared-off frame vignette, graded top-to-bottom between the palette colors.",
            "vec2 axed = abs(centered) * 2.0;",
            f"float boxDist = max(axed.x, axed.y * {F(u(0, 1.0, 1.4))});",
            f"float edge = smoothstep({F(u(1, 0.5, 0.65))}, {F(u(2, 0.9, 1.05))}, boxDist);",
            "vec3 outColor = mix(base, mix(Secondary.rgb, Primary.rgb, texCoord.y), edge * clamp(strength, 0.0, 1.0));",
        ], set()
    if v == "noisy":
        return [
            "// Noise-eaten vignette: the creep line wanders and shimmers.",
            f"float n = vnoise(texCoord * {F(u(0, 5.0, 11.0))} + vec2(anim * {F(u(1, 0.04, 0.09))}, 0.0));",
            f"float edge = smoothstep({F(u(2, 0.24, 0.34))}, {F(u(3, 0.6, 0.75))}, centerDist + (n - 0.5) * {F(u(4, 0.18, 0.3))});",
            f"vec3 outColor = mix(base, Primary.rgb * ({F(u(5, 0.7, 0.85))} + {F(u(6, 0.3, 0.5))} * n), edge * clamp(strength, 0.0, 1.0));",
        ], {"vnoise"}
    # bands
    return [
        "// Letterbox bands: the top and bottom of the view sink into the palette.",
        f"float band = smoothstep({F(u(0, 0.5, 0.65))}, {F(u(1, 0.85, 1.0))}, abs(texCoord.y - 0.5) * 2.0);",
        "vec3 outColor = mix(base, mix(Primary.rgb, Secondary.rgb, texCoord.y), band * clamp(strength, 0.0, 1.0));",
    ], set()


def fam_chroma(v, u):
    tail = [
        "float r = sampleAt(texCoord + safeOffset(shift)).r;",
        "float g = base.g;",
        "float b = sampleAt(texCoord - safeOffset(shift)).b;",
        "vec3 fringed = vec3(r, g, b);",
        "vec3 outColor = mix(fringed, fringed * Primary.rgb, ParamsB.z);",
    ]
    if v == "radial":
        return [
            "// Lens fringe: channel separation grows toward the screen edges.",
            "vec2 shift = centered * ParamsA.y * animAmp;",
        ] + tail, {"safeOffset", "sampleAt"}
    if v == "linear":
        ang = u(0, 0.0, 3.14)
        return [
            "// Fixed-axis fringe: the whole frame splits along one direction.",
            f"vec2 shift = vec2({F(math.cos(ang))}, {F(math.sin(ang))}) * ParamsA.y * {F(u(1, 0.35, 0.6))} * animAmp;",
        ] + tail, {"safeOffset", "sampleAt"}
    if v == "rotating":
        return [
            "// The split axis slowly rotates on GameTime.",
            f"float splitAngle = anim * {F(u(0, 0.12, 0.3))};",
            f"vec2 shift = vec2(cos(splitAngle), sin(splitAngle)) * ParamsA.y * {F(u(1, 0.35, 0.6))} * animAmp;",
        ] + tail, {"safeOffset", "sampleAt"}
    # zoned
    return [
        "// Ring-zoned fringe: concentric zones alternate their separation strength.",
        f"float zone = 0.5 + 0.5 * sin(centerDist * {F(u(0, 9.0, 18.0))} - anim);",
        "vec2 shift = centered * ParamsA.y * animAmp * zone;",
    ] + tail, {"safeOffset", "sampleAt"}


def fam_pixelate(v, u):
    tail = [
        "vec3 posterized = cell - fract(cell * ParamsA.z) / ParamsA.z;",
        "vec3 outColor = mix(posterized, posterized * Primary.rgb, ParamsB.z);",
    ]
    if v == "square":
        return [
            "// Mosaic + posterize, following vanilla's bits.fsh. The mosaic scale is",
            "// clamped >= 1 before every division so the cell math cannot blow up.",
            "vec2 mosaicInSize = max(safeInSize / max(ParamsA.y, 1.0), vec2(1.0));",
            "vec2 fractPix = fract(texCoord * mosaicInSize) / mosaicInSize;",
            "vec3 cell = sampleAt(texCoord + safeOffset(-fractPix));",
        ] + tail, {"safeOffset", "sampleAt"}
    if v == "wide":
        return [
            "// Rectangular scan-cells: wider than tall, like a broken broadcast.",
            f"vec2 mosaicInSize = max(safeInSize / max(ParamsA.y * vec2({F(u(0, 1.8, 3.0))}, 1.0), vec2(1.0)), vec2(1.0));",
            "vec2 fractPix = fract(texCoord * mosaicInSize) / mosaicInSize;",
            "vec3 cell = sampleAt(texCoord + safeOffset(-fractPix));",
        ] + tail, {"safeOffset", "sampleAt"}
    if v == "diamond":
        return [
            "// 45-degree diamond cells via a skewed grid.",
            f"vec2 gridN = max(safeInSize / max(ParamsA.y * {F(u(0, 1.2, 1.8))}, 1.0), vec2(1.0));",
            "vec2 sk = vec2(texCoord.x + texCoord.y, texCoord.x - texCoord.y) * 0.5;",
            "vec2 cellCenter = (floor(sk * gridN) + 0.5) / gridN;",
            "vec2 uvCell = vec2(cellCenter.x + cellCenter.y, cellCenter.x - cellCenter.y);",
            "vec3 cell = sampleAt(texCoord + safeOffset(uvCell - texCoord));",
        ] + tail, {"safeOffset", "sampleAt"}
    # breathe
    return [
        "// The mosaic cell size breathes on GameTime.",
        f"float mosaic = max(ParamsA.y * (1.0 + {F(u(0, 0.25, 0.45))} * sin(anim * {F(u(1, 0.5, 1.1))})), 1.0);",
        "vec2 mosaicInSize = max(safeInSize / mosaic, vec2(1.0));",
        "vec2 fractPix = fract(texCoord * mosaicInSize) / mosaicInSize;",
        "vec3 cell = sampleAt(texCoord + safeOffset(-fractPix));",
    ] + tail, {"safeOffset", "sampleAt"}


def fam_desat(v, u):
    if v == "flat":
        return [
            "// Uniform grey-out; the greys pick up a whisper of the primary color.",
            "vec3 grey = vec3(baseLuma) * mix(vec3(1.0), Primary.rgb, ParamsB.z);",
            "vec3 outColor = mix(base, grey, clamp(strength, 0.0, 1.0));",
        ], {"luma"}
    if v == "shadows":
        return [
            "// Shadow drain: dark areas lose their color first, highlights stay alive.",
            f"float mask = smoothstep({F(u(0, 0.15, 0.3))}, {F(u(1, 0.75, 0.95))}, 1.0 - baseLuma);",
            "vec3 grey = vec3(baseLuma) * mix(vec3(1.0), Secondary.rgb, ParamsB.z);",
            "vec3 outColor = mix(base, grey, clamp(strength * mask, 0.0, 1.0));",
        ], {"luma"}
    if v == "tunnel":
        return [
            "// Tunnel vision: color survives at the center, the rim goes ashen.",
            f"float mask = smoothstep({F(u(0, 0.12, 0.24))}, {F(u(1, 0.55, 0.75))}, centerDist);",
            "vec3 grey = vec3(baseLuma) * mix(vec3(1.0), Primary.rgb, ParamsB.z);",
            "vec3 outColor = mix(base, grey, clamp(strength * mask, 0.0, 1.0));",
        ], {"luma"}
    # twotone
    return [
        "// Grey-out remapped into a faint palette duotone.",
        "vec3 grey = vec3(baseLuma);",
        f"vec3 toned = grey * mix(Secondary.rgb, Primary.rgb, smoothstep({F(u(0, 0.1, 0.2))}, {F(u(1, 0.8, 0.9))}, baseLuma));",
        "vec3 outColor = mix(base, toned, clamp(strength, 0.0, 1.0));",
    ], {"luma"}


def fam_bloomglow(v, u):
    r = F(u(0, 3.0, 6.0))
    tail = [
        f"vec3 outColor = base + glow * mix(vec3(1.0), Primary.rgb, {F(u(9, 0.5, 0.75))}) * strength;",
    ]
    if v == "cross":
        return [
            "// 5-tap cross blur of the bright pass, weighted toward the center.",
            f"vec2 texel = {r} / safeInSize;",
            "vec3 glow = brightTap(texCoord, ParamsA.w) * 0.4",
            "    + brightTap(texCoord + safeOffset(vec2(texel.x, 0.0)), ParamsA.w) * 0.15",
            "    + brightTap(texCoord + safeOffset(vec2(-texel.x, 0.0)), ParamsA.w) * 0.15",
            "    + brightTap(texCoord + safeOffset(vec2(0.0, texel.y)), ParamsA.w) * 0.15",
            "    + brightTap(texCoord + safeOffset(vec2(0.0, -texel.y)), ParamsA.w) * 0.15;",
        ] + tail, {"luma", "brightTap", "safeOffset"}
    if v == "diag":
        return [
            "// X-shaped bright-pass blur: diagonal streaks around hot pixels.",
            f"vec2 texel = {r} / safeInSize;",
            "vec3 glow = brightTap(texCoord, ParamsA.w) * 0.36",
            "    + brightTap(texCoord + safeOffset(texel), ParamsA.w) * 0.16",
            "    + brightTap(texCoord + safeOffset(-texel), ParamsA.w) * 0.16",
            "    + brightTap(texCoord + safeOffset(vec2(texel.x, -texel.y)), ParamsA.w) * 0.16",
            "    + brightTap(texCoord + safeOffset(vec2(-texel.x, texel.y)), ParamsA.w) * 0.16;",
        ] + tail, {"luma", "brightTap", "safeOffset"}
    if v == "ring":
        return [
            "// 8-tap ring blur: an even halo around anything bright.",
            f"vec2 texel = {r} / safeInSize;",
            "vec3 glow = brightTap(texCoord, ParamsA.w) * 0.28;",
            "for (int i = 0; i < 8; i++) {",
            "    float a = float(i) * 0.7853982;",
            "    glow += brightTap(texCoord + safeOffset(vec2(cos(a), sin(a)) * texel), ParamsA.w) * 0.09;",
            "}",
        ] + tail, {"luma", "brightTap", "safeOffset"}
    # starburst
    return [
        "// 6-tap three-axis starburst around bright sources.",
        f"vec2 texel = {r} / safeInSize;",
        "vec3 glow = brightTap(texCoord, ParamsA.w) * 0.34;",
        "for (int i = 0; i < 3; i++) {",
        f"    float a = float(i) * 1.0471976 + {F(u(1, 0.0, 0.6))};",
        "    vec2 arm = vec2(cos(a), sin(a)) * texel;",
        "    glow += brightTap(texCoord + safeOffset(arm), ParamsA.w) * 0.11;",
        "    glow += brightTap(texCoord + safeOffset(-arm), ParamsA.w) * 0.11;",
        "}",
    ] + tail, {"luma", "brightTap", "safeOffset"}


def fam_ripple(v, u):
    tint = [
        f"float crest = smoothstep(0.5, 1.0, ring) * fade;",
        f"vec3 outColor = mix(scene, scene * Primary.rgb, crest * ParamsB.z * animAmp);",
    ]
    if v == "center":
        return [
            "// Radial ripples from the screen center displace the sample outward.",
            "float ring = sin(centerDist * ParamsA.z - anim * 3.0);",
            f"float fade = smoothstep(0.05, 0.25, centerDist) * invsmooth({F(u(1, 0.45, 0.6))}, {F(u(0, 0.85, 1.0))}, centerDist);",
            "vec2 dir = centerDist > 0.0001 ? aspectCentered / centerDist : vec2(0.0);",
            f"vec2 off = dir * ring * fade * {F(u(2, 0.006, 0.01))} * ParamsA.y * animAmp;",
            "vec3 scene = sampleAt(texCoord + safeOffset(off));",
        ] + tint, {"invsmooth", "safeOffset", "sampleAt"}
    if v == "twin":
        c1x, c1y = u(0, 0.2, 0.4), u(1, 0.3, 0.5)
        c2x, c2y = u(2, 0.6, 0.8), u(3, 0.5, 0.7)
        return [
            "// Two off-center ripple sources interfere across the frame.",
            f"vec2 d1 = texCoord - vec2({F(c1x)}, {F(c1y)});",
            f"vec2 d2 = texCoord - vec2({F(c2x)}, {F(c2y)});",
            "float ring = 0.5 * sin(length(d1) * ParamsA.z - anim * 2.6) + 0.5 * sin(length(d2) * ParamsA.z - anim * 3.4);",
            "float fade = invsmooth(0.4, 0.95, centerDist);",
            f"vec2 off = (safeNormalize(d1) + safeNormalize(d2)) * ring * fade * {F(u(4, 0.004, 0.007))} * ParamsA.y * animAmp;",
            "vec3 scene = sampleAt(texCoord + safeOffset(off));",
        ] + tint, {"invsmooth", "safeNormalize", "safeOffset", "sampleAt"}
    if v == "linear":
        ang = u(0, 0.2, 1.4)
        return [
            "// Planar wavefronts sweep across the screen in one direction.",
            f"vec2 dir = vec2({F(math.cos(ang))}, {F(math.sin(ang))});",
            f"float ring = sin(dot(texCoord, dir) * ParamsA.z - anim * {F(u(1, 2.2, 3.6))});",
            "float fade = invsmooth(0.35, 1.0, centerDist);",
            f"vec2 off = dir * ring * fade * {F(u(2, 0.005, 0.009))} * ParamsA.y * animAmp;",
            "vec3 scene = sampleAt(texCoord + safeOffset(off));",
        ] + tint, {"invsmooth", "safeOffset", "sampleAt"}
    # pond
    return [
        "// Noise-phased pond rings: raindrop-like irregular ripples.",
        f"float phase = vnoise(texCoord * {F(u(0, 3.0, 7.0))}) * {F(u(1, 2.0, 4.0))};",
        "float ring = sin(centerDist * ParamsA.z - anim * 3.0 + phase);",
        "float fade = smoothstep(0.03, 0.2, centerDist) * invsmooth(0.5, 0.95, centerDist);",
        "vec2 dir = centerDist > 0.0001 ? aspectCentered / centerDist : vec2(0.0);",
        f"vec2 off = dir * ring * fade * {F(u(2, 0.005, 0.009))} * ParamsA.y * animAmp;",
        "vec3 scene = sampleAt(texCoord + safeOffset(off));",
    ] + tint, {"invsmooth", "safeOffset", "sampleAt", "vnoise"}


def fam_scanlines(v, u):
    # Resolution-aware line density: the packed line count is capped at a
    # quarter of the framebuffer's pixel rows (columns for the vertical
    # variant) so the raster can never alias into shimmer soup on small
    # windows -- min(scale, safeInSize * 0.25).
    if v == "horizontal":
        return [
            "// CRT rows: per-row sync jitter plus rolling dark scanlines.",
            "// The frame counter wraps at 1024 so the hash input stays small enough",
            "// for fp32 (an unbounded counter would freeze the jitter within minutes).",
            "float lineCount = min(ParamsA.z, safeInSize.y * 0.25);",
            "float row = floor(texCoord.y * lineCount);",
            "float frame = mod(floor(anim * 8.0), 1024.0);",
            f"float jitter = (hash11(row + frame * 91.7) - 0.5) * {F(u(0, 0.002, 0.0035))} * ParamsA.y;",
            "vec3 scene = sampleAt(texCoord + safeOffset(vec2(jitter, 0.0)));",
            f"float scan = 0.5 + 0.5 * sin((texCoord.y * lineCount - anim * {F(u(1, 0.4, 0.7))}) * 6.2831);",
            f"float darken = 1.0 - ParamsA.y * {F(u(2, 0.3, 0.4))} * scan * animAmp;",
            "vec3 outColor = mix(scene * darken, scene * darken * Primary.rgb, ParamsB.z);",
        ], {"hash11", "safeOffset", "sampleAt"}
    if v == "vertical":
        return [
            "// Vertical raster columns with per-column shimmer.",
            "// Frame counter wrapped at 1024: keeps the hash input fp32-friendly.",
            "float lineCount = min(ParamsA.z, safeInSize.x * 0.25);",
            "float col = floor(texCoord.x * lineCount);",
            "float frame = mod(floor(anim * 6.0), 1024.0);",
            f"float jitter = (hash11(col + frame * 47.3) - 0.5) * {F(u(0, 0.002, 0.0035))} * ParamsA.y;",
            "vec3 scene = sampleAt(texCoord + safeOffset(vec2(0.0, jitter)));",
            f"float scan = 0.5 + 0.5 * sin((texCoord.x * lineCount + anim * {F(u(1, 0.3, 0.6))}) * 6.2831);",
            f"float darken = 1.0 - ParamsA.y * {F(u(2, 0.28, 0.38))} * scan * animAmp;",
            "vec3 outColor = mix(scene * darken, scene * darken * Primary.rgb, ParamsB.z);",
        ], {"hash11", "safeOffset", "sampleAt"}
    if v == "rolling":
        return [
            "// A bright readout band rolls down the raster.",
            "float lineCount = min(ParamsA.z, safeInSize.y * 0.25);",
            f"float scan = 0.5 + 0.5 * sin(texCoord.y * lineCount * 6.2831 - anim * {F(u(0, 2.0, 3.5))});",
            f"float bandPos = fract(anim * {F(u(1, 0.05, 0.11))});",
            f"float roll = invsmooth(0.0, {F(u(2, 0.06, 0.12))}, abs(texCoord.y - bandPos));",
            f"float darken = 1.0 - ParamsA.y * {F(u(3, 0.25, 0.35))} * scan * animAmp;",
            "vec3 lined = base * darken + Primary.rgb * roll * ParamsA.y * 0.25;",
            "vec3 outColor = mix(lined, lined * Primary.rgb, ParamsB.z);",
        ], {"invsmooth"}
    # grid
    return [
        "// Faint raster grid: both axes carry drifting line sets.",
        "float lineY = min(ParamsA.z, safeInSize.y * 0.25);",
        f"float lineX = min(ParamsA.z * {F(u(1, 0.6, 0.9))}, safeInSize.x * 0.25);",
        f"float scanY = 0.5 + 0.5 * sin((texCoord.y * lineY - anim * {F(u(0, 0.3, 0.6))}) * 6.2831);",
        f"float scanX = 0.5 + 0.5 * sin((texCoord.x * lineX + anim * {F(u(2, 0.2, 0.5))}) * 6.2831);",
        f"float darken = 1.0 - ParamsA.y * animAmp * ({F(u(3, 0.18, 0.26))} * scanY + {F(u(4, 0.12, 0.2))} * scanX);",
        "vec3 outColor = mix(base * darken, base * darken * Primary.rgb, ParamsB.z);",
    ], set()


def fam_edgeglow(v, u):
    radius = "2.0" if v == "thick" else "1.0"
    sobel = [
        f"vec2 texel = {radius} / safeInSize;",
        "float tl = lumaAt(texCoord + safeOffset(texel * vec2(-1.0, -1.0)));",
        "float tc = lumaAt(texCoord + safeOffset(texel * vec2(0.0, -1.0)));",
        "float tr = lumaAt(texCoord + safeOffset(texel * vec2(1.0, -1.0)));",
        "float ml = lumaAt(texCoord + safeOffset(texel * vec2(-1.0, 0.0)));",
        "float mr = lumaAt(texCoord + safeOffset(texel * vec2(1.0, 0.0)));",
        "float bl = lumaAt(texCoord + safeOffset(texel * vec2(-1.0, 1.0)));",
        "float bc = lumaAt(texCoord + safeOffset(texel * vec2(0.0, 1.0)));",
        "float br = lumaAt(texCoord + safeOffset(texel * vec2(1.0, 1.0)));",
        "float gx = (tr + 2.0 * mr + br) - (tl + 2.0 * ml + bl);",
        "float gy = (bl + 2.0 * bc + br) - (tl + 2.0 * tc + tr);",
        "float edge = clamp(length(vec2(gx, gy)), 0.0, 1.0);",
        "// Additive-family calibration: the glow strength is capped at 1.0 and",
        "// attenuated on already-bright pixels so edges accent, not overpower.",
        "float glowK = min(strength, 1.0) * (1.0 - 0.5 * baseLuma);",
    ]
    if v == "sobel":
        return ["// 3x3 Sobel over scene luma; edges glow in the primary color."] + sobel + [
            "vec3 outColor = base + Primary.rgb * edge * glowK;",
        ], {"lumaAt", "safeOffset"}
    if v == "pulse":
        return ["// Sobel edges whose glow breathes on GameTime."] + sobel + [
            f"float breath = {F(u(0, 0.55, 0.7))} + {F(u(1, 0.3, 0.45))} * sin(anim * {F(u(2, 1.2, 2.2))} + ParamsB.x * 6.2831);",
            "vec3 outColor = base + Primary.rgb * edge * glowK * breath;",
        ], {"lumaAt", "safeOffset"}
    if v == "duo":
        return ["// Direction-split edges: horizontal gradients glow Primary, vertical Secondary."] + sobel + [
            "vec3 glowColor = mix(Secondary.rgb, Primary.rgb, clamp(0.5 + 0.5 * (abs(gx) - abs(gy)) * 2.0, 0.0, 1.0));",
            "vec3 outColor = base + glowColor * edge * glowK;",
        ], {"lumaAt", "safeOffset"}
    # thick
    return ["// Wide-radius Sobel: thick painterly outlines."] + sobel + [
        f"vec3 outColor = base + Primary.rgb * pow(edge, {F(u(0, 0.6, 0.8))}) * glowK * {F(u(1, 0.75, 0.9))};",
    ], {"lumaAt", "safeOffset"}


def fam_frostlens(v, u):
    refract = [
        "vec2 grain = vec2(",
        "    vnoise(texCoord * ParamsA.z + 13.7) - 0.5,",
        "    vnoise(texCoord * ParamsA.z + 71.3) - 0.5",
        ");",
        "vec3 scene = sampleAt(texCoord + safeOffset(grain * 0.012 * frost));",
        "vec3 iceColor = mix(Secondary.rgb, Primary.rgb, crystals);",
        f"vec3 frosted = mix(scene, iceColor * (0.6 + 0.4 * crystals), {F(u(9, 0.45, 0.6))});",
        "// Frost visibility calibration: real rime scatters WHITE over bright",
        "// backgrounds (sky), so the frosted layer whitens with baseLuma, and",
        "// sparse crystal facets catch glints that keep the sheet readable.",
        f"frosted = mix(frosted, vec3(1.0), baseLuma * baseLuma * {F(u(7, 0.25, 0.35))} * crystals);",
        f"float glint = smoothstep({F(u(8, 0.78, 0.86))}, 1.0, crystals) * (0.5 + 0.5 * sin(anim * {F(u(6, 0.8, 1.5))} + crystals * 37.0));",
        "vec3 outColor = mix(scene, frosted, frost) + iceColor * glint * frost * 0.4;",
    ]
    if v == "creep":
        return [
            "// Crystalline frost creeps inward from the screen edges.",
            f"float crystals = vnoise(texCoord * ParamsA.z) * 0.6 + vnoise(texCoord * ParamsA.z * {F(u(0, 2.2, 3.2))}) * 0.4;",
            f"float frost = smoothstep({F(u(1, 0.24, 0.32))}, {F(u(2, 0.55, 0.7))}, centerDist + (crystals - 0.5) * {F(u(3, 0.22, 0.34))}) * strength;",
        ] + refract, {"vnoise", "safeOffset", "sampleAt"}
    if v == "veins":
        return [
            "// Ridged ice veins crawl across the whole pane.",
            f"float ridge = 1.0 - abs(2.0 * vnoise(texCoord * ParamsA.z + vec2(anim * {F(u(0, 0.01, 0.03))}, 0.0)) - 1.0);",
            f"float crystals = ridge * 0.7 + vnoise(texCoord * ParamsA.z * 2.3) * 0.3;",
            f"float frost = smoothstep({F(u(1, 0.55, 0.68))}, {F(u(2, 0.8, 0.95))}, crystals * (0.75 + 0.25 * centerDist)) * strength;",
        ] + refract, {"vnoise", "safeOffset", "sampleAt"}
    if v == "sheet":
        return [
            "// A thin full-pane rime sheet, heaviest in the corners. The floor is",
            "// raised (~0.32+) so the sheet stays visible even at screen center.",
            f"float crystals = vnoise(texCoord * ParamsA.z) * 0.55 + vnoise(texCoord * ParamsA.z * 2.7) * 0.45;",
            "vec2 corner = abs(centered) * 2.0;",
            f"float frost = clamp({F(u(0, 0.32, 0.40))} + {F(u(1, 0.45, 0.6))} * max(corner.x, corner.y) * crystals, 0.0, 1.0) * strength;",
        ] + refract, {"vnoise", "safeOffset", "sampleAt"}
    # breath
    return [
        "// The frost line advances and recedes like breath on a window.",
        f"float crystals = vnoise(texCoord * ParamsA.z) * 0.6 + vnoise(texCoord * ParamsA.z * 2.5) * 0.4;",
        f"float reach = {F(u(0, 0.3, 0.38))} + {F(u(1, 0.08, 0.14))} * sin(anim * {F(u(2, 0.35, 0.7))} + ParamsB.x * 6.2831);",
        f"float frost = smoothstep(reach, reach + {F(u(3, 0.25, 0.35))}, centerDist + (crystals - 0.5) * 0.28) * strength;",
    ] + refract, {"vnoise", "safeOffset", "sampleAt"}


def fam_heathaze(v, u):
    grade = [
        "// Palette-aware haze cast: lean toward the effect's own Primary hue",
        "// (normalized to its max channel so brightness holds) instead of a",
        "// hard-coded amber -- recolor-safe for non-fire palettes.",
        f"vec3 hazeTint = mix(vec3(1.0), Primary.rgb / max(max(Primary.r, max(Primary.g, Primary.b)), 0.001), {F(u(7, 0.15, 0.25))});",
        "vec3 warm = scene * hazeTint;",
        f"vec3 outColor = mix(scene, warm * mix(vec3(1.0), Primary.rgb, ParamsB.z), {F(u(9, 0.5, 0.7))});",
    ]
    if v == "rising":
        return [
            "// Rising shimmer, strongest in the lower half where the hot air is.",
            "float rising = invsmooth(0.2, 0.9, texCoord.y);",
            "vec2 off = vec2(",
            f"    sin(texCoord.y * {F(u(0, 70.0, 110.0))} + anim * 4.0) + sin(texCoord.y * {F(u(1, 35.0, 55.0))} - anim * 2.6),",
            f"    cos(texCoord.x * {F(u(2, 50.0, 75.0))} + anim * 3.1) * 0.4",
            f") * {F(u(3, 0.0013, 0.002))} * ParamsA.y * animAmp * (0.4 + 0.6 * rising);",
            "vec3 scene = sampleAt(texCoord + safeOffset(off));",
        ] + grade, {"invsmooth", "safeOffset", "sampleAt"}
    if v == "full":
        return [
            "// Whole-frame heat shimmer, as if standing inside a furnace bloom.",
            "vec2 off = vec2(",
            f"    sin(texCoord.y * {F(u(0, 55.0, 85.0))} + anim * 3.4),",
            f"    sin(texCoord.x * {F(u(1, 45.0, 70.0))} - anim * 2.8)",
            f") * {F(u(2, 0.001, 0.0016))} * ParamsA.y * animAmp;",
            "vec3 scene = sampleAt(texCoord + safeOffset(off));",
        ] + grade, {"safeOffset", "sampleAt"}
    if v == "columns":
        return [
            "// Distinct thermal columns shimmer where the noise mask is hot.",
            f"float column = smoothstep({F(u(0, 0.45, 0.55))}, 1.0, vnoise(vec2(texCoord.x * {F(u(1, 4.0, 8.0))}, anim * 0.12)));",
            "vec2 off = vec2(",
            f"    sin(texCoord.y * {F(u(2, 65.0, 95.0))} + anim * 4.2),",
            "    0.0",
            f") * {F(u(3, 0.0018, 0.0028))} * ParamsA.y * animAmp * column;",
            "vec3 scene = sampleAt(texCoord + safeOffset(off));",
        ] + grade, {"vnoise", "safeOffset", "sampleAt"}
    # embers
    return [
        "// Gentle haze plus sparse embers drifting up the screen.",
        "vec2 off = vec2(",
        f"    sin(texCoord.y * {F(u(0, 60.0, 90.0))} + anim * 3.6),",
        f"    cos(texCoord.x * {F(u(1, 40.0, 65.0))} + anim * 2.4) * 0.4",
        f") * {F(u(2, 0.001, 0.0018))} * ParamsA.y * animAmp;",
        "vec3 scene = sampleAt(texCoord + safeOffset(off));",
        "// The ember cell's y id scrolls with time; wrap it at 256 so the hash",
        "// input stays small enough for fp32 over the whole GameTime day.",
        f"vec2 emberCell = floor(vec2(texCoord.x * {F(u(3, 40.0, 70.0))}, texCoord.y * {F(u(4, 25.0, 45.0))} + anim * {F(u(5, 1.5, 3.0))}));",
        f"float ember = step({F(u(6, 0.985, 0.995))}, hash21(vec2(emberCell.x, mod(emberCell.y, 256.0))));",
        "scene += Primary.rgb * ember * 0.35 * animAmp;",
    ] + grade, {"hash21", "safeOffset", "sampleAt"}


def fam_posterize(v, u):
    if v == "breathe":
        return [
            "// Quantize to a slowly breathing number of levels.",
            f"float levels = max(2.0, ParamsA.y + sin(anim * {F(u(0, 0.7, 1.2))}) * {F(u(1, 1.2, 1.8))});",
            "vec3 quantized = floor(base * levels + 0.5) / levels;",
            "vec3 outColor = mix(quantized, quantized * Primary.rgb, ParamsB.z);",
        ], set()
    if v == "dither":
        return [
            "// Hash-dithered posterize: banding broken up by per-pixel noise.",
            "// The dither frame wraps at 256 so the hash input stays fp32-friendly.",
            "float levels = max(2.0, ParamsA.y);",
            f"float dframe = mod(floor(anim * {F(u(0, 3.0, 6.0))}), 256.0);",
            "float dith = (hash21(floor(texCoord * safeInSize) + vec2(dframe, 0.0)) - 0.5) / levels;",
            "vec3 quantized = floor((base + dith) * levels + 0.5) / levels;",
            "vec3 outColor = mix(quantized, quantized * Primary.rgb, ParamsB.z);",
        ], {"hash21"}
    if v == "lumaonly":
        return [
            "// Quantize the luminance only; chroma stays smooth.",
            "float levels = max(2.0, ParamsA.y);",
            "float ql = floor(baseLuma * levels + 0.5) / levels;",
            "vec3 quantized = base * (ql / max(baseLuma, 0.001));",
            "vec3 outColor = mix(quantized, quantized * Primary.rgb, ParamsB.z);",
        ], {"luma"}
    # banded
    return [
        "// Posterize with glowing seams along the quantization bands.",
        "float levels = max(2.0, ParamsA.y);",
        "vec3 quantized = floor(base * levels + 0.5) / levels;",
        f"float seam = smoothstep({F(u(0, 0.42, 0.46))}, 0.5, abs(fract(baseLuma * levels) - 0.5));",
        f"vec3 outColor = mix(quantized, quantized * Primary.rgb, ParamsB.z) + Primary.rgb * seam * {F(u(1, 0.1, 0.2))} * animAmp;",
    ], {"luma"}


def fam_radialblur(v, u):
    taps = [6, 8, 10, 12][int(u(15, 0.0, 3.999))]
    if v == "zoom":
        return [
            f"// {taps}-tap zoom streak from the screen center outward.",
            f"float blur = {F(u(0, 0.03, 0.045))} * ParamsA.y * animAmp * smoothstep(0.05, 0.7, centerDist);",
            "vec3 accum = vec3(0.0);",
            f"for (int i = 0; i < {taps}; i++) {{",
            f"    float t = float(i) / {F(float(taps - 1))};",
            "    accum += sampleAt(texCoord + safeOffset(-centered * blur * t));",
            "}",
            f"vec3 streaked = accum / {F(float(taps))};",
            "vec3 outColor = mix(streaked, streaked * Primary.rgb, smoothstep(0.4, 0.8, centerDist) * ParamsB.z);",
        ], {"safeOffset", "sampleAt"}
    if v == "spin":
        return [
            f"// {taps}-tap tangential spin streak around the center.",
            f"float arc = {F(u(0, 0.035, 0.055))} * ParamsA.y * animAmp * smoothstep(0.1, 0.7, centerDist);",
            "vec3 accum = vec3(0.0);",
            f"for (int i = 0; i < {taps}; i++) {{",
            f"    float t = (float(i) / {F(float(taps - 1))} - 0.5) * arc;",
            "    float ca = cos(t);",
            "    float sa = sin(t);",
            "    vec2 rc = vec2(centered.x * ca - centered.y * sa, centered.x * sa + centered.y * ca);",
            "    accum += sampleAt(texCoord + safeOffset(vec2(0.5) + rc - texCoord));",
            "}",
            f"vec3 streaked = accum / {F(float(taps))};",
            "vec3 outColor = mix(streaked, streaked * Primary.rgb, smoothstep(0.35, 0.8, centerDist) * ParamsB.z);",
        ], {"safeOffset", "sampleAt"}
    if v == "zoomspin":
        return [
            f"// {taps}-tap combined zoom + spin streak: a slow vortex pull.",
            f"float blur = {F(u(0, 0.02, 0.032))} * ParamsA.y * animAmp * smoothstep(0.08, 0.7, centerDist);",
            f"float arc = {F(u(1, 0.02, 0.035))} * ParamsA.y * animAmp;",
            "vec3 accum = vec3(0.0);",
            f"for (int i = 0; i < {taps}; i++) {{",
            f"    float t = float(i) / {F(float(taps - 1))};",
            "    float a = (t - 0.5) * arc;",
            "    float ca = cos(a);",
            "    float sa = sin(a);",
            "    vec2 rc = vec2(centered.x * ca - centered.y * sa, centered.x * sa + centered.y * ca);",
            "    accum += sampleAt(texCoord + safeOffset(vec2(0.5) + rc * (1.0 - blur * t) - texCoord));",
            "}",
            f"vec3 streaked = accum / {F(float(taps))};",
            "vec3 outColor = mix(streaked, streaked * Primary.rgb, smoothstep(0.4, 0.8, centerDist) * ParamsB.z);",
        ], {"safeOffset", "sampleAt"}
    # pulsezoom
    return [
        f"// {taps}-tap zoom streak whose reach surges on GameTime.",
        f"float surgeK = {F(u(0, 0.55, 0.7))} + {F(u(1, 0.3, 0.45))} * sin(anim * {F(u(2, 1.0, 1.8))} + ParamsB.x * 6.2831);",
        f"float blur = {F(u(3, 0.03, 0.045))} * ParamsA.y * animAmp * surgeK * smoothstep(0.05, 0.7, centerDist);",
        "vec3 accum = vec3(0.0);",
        f"for (int i = 0; i < {taps}; i++) {{",
        f"    float t = float(i) / {F(float(taps - 1))};",
        "    accum += sampleAt(texCoord + safeOffset(-centered * blur * t));",
        "}",
        f"vec3 streaked = accum / {F(float(taps))};",
        "vec3 outColor = mix(streaked, streaked * Primary.rgb, smoothstep(0.4, 0.8, centerDist) * ParamsB.z);",
    ], {"safeOffset", "sampleAt"}


def fam_glitch(v, u):
    # The shear and the RGB split are summed FIRST and clamped once, so the
    # total scene displacement can never exceed the safeOffset bound (the old
    # baseCoord + second safeOffset stacked two clamps to up to 2x the limit).
    # Calibration: the tear/split amplitudes ride min(ParamsA.y, 1.0) (`glitchK`
    # below) so high-id strengths cannot over-drive them, and every flash tint
    # is scaled by (1 - baseLuma) so bright scenes are not blown out further.
    prelude = [
        "float glitchK = min(ParamsA.y, 1.0);",
        "float flashK = 1.0 - baseLuma;",
    ]
    split_tail = [
        "float red = sampleAt(texCoord + safeOffset(baseOff + vec2(split, 0.0))).r;",
        "float green = sampleAt(texCoord + safeOffset(baseOff)).g;",
        "float blue = sampleAt(texCoord + safeOffset(baseOff - vec2(split, 0.0))).b;",
        "vec3 torn = vec3(red, green, blue);",
    ]
    if v == "tear":
        return prelude + [
            "// Scanband tears: a few rows shear sideways each glitch frame.",
            "// The frame counter wraps at 1024 so the hash input stays small enough",
            "// for fp32 (an unbounded counter would freeze the glitch within minutes).",
            f"float frame = mod(floor(anim * {F(u(0, 5.0, 8.0))}), 1024.0);",
            f"float band = floor(texCoord.y * {F(u(1, 18.0, 30.0))});",
            "float tearRoll = hash11(band * 7.31 + frame * 13.7);",
            f"float tear = (tearRoll > 0.85 ? (tearRoll - 0.85) / 0.15 - 0.5 : 0.0) * {F(u(2, 0.05, 0.08))} * glitchK * animAmp;",
            "vec2 baseOff = vec2(tear, 0.0);",
            "float split = (0.004 + abs(tear) * 0.5) * glitchK;",
        ] + split_tail + [
            "float flash = abs(tear) > 0.0001 ? ParamsB.z * flashK : 0.0;",
            "vec3 outColor = mix(torn, torn * Primary.rgb, flash);",
        ], {"hash11", "safeOffset", "sampleAt"}
    if v == "blocks":
        return prelude + [
            "// Coarse block dropouts: cells occasionally displace as a chunk.",
            "// Frame counter wrapped at 1024: keeps the hash input fp32-friendly.",
            f"float frame = mod(floor(anim * {F(u(0, 4.0, 7.0))}), 1024.0);",
            f"vec2 cell = floor(texCoord * vec2({F(u(1, 6.0, 10.0))}, {F(u(2, 4.0, 8.0))}));",
            "float cellRoll = hash11(cell.x * 3.7 + cell.y * 11.9 + frame * 5.3);",
            f"vec2 jitter = cellRoll > {F(u(3, 0.88, 0.93))}",
            f"    ? (vec2(hash11(cellRoll * 91.7), hash11(cellRoll * 47.3)) - 0.5) * {F(u(4, 0.025, 0.04))} * glitchK",
            "    : vec2(0.0);",
            "vec2 baseOff = jitter;",
            "float split = 0.003 * glitchK;",
        ] + split_tail + [
            "float flash = length(jitter) > 0.0001 ? ParamsB.z * 1.2 * flashK : 0.0;",
            "vec3 outColor = mix(torn, mix(torn * Primary.rgb, torn * Secondary.rgb, hash11(cellRoll * 3.1)), clamp(flash, 0.0, 1.0));",
        ], {"hash11", "safeOffset", "sampleAt"}
    if v == "rgbdrift":
        return prelude + [
            "// The color channels wander apart and snap back on glitch frames.",
            "// Frame counter wrapped at 1024: keeps the hash input fp32-friendly.",
            f"float frame = mod(floor(anim * {F(u(0, 3.0, 5.0))}), 1024.0);",
            "float wander = hash11(frame * 17.3) - 0.5;",
            f"float split = (0.004 + abs(wander) * {F(u(1, 0.01, 0.018))}) * glitchK * animAmp;",
            "vec2 baseOff = vec2(0.0);",
        ] + split_tail + [
            f"float microTear = step({F(u(2, 0.96, 0.985))}, hash11(floor(texCoord.y * 90.0) + frame)) * glitchK;",
            "vec3 outColor = mix(torn, torn * Primary.rgb, clamp(microTear * ParamsB.z * 2.0 * flashK, 0.0, 1.0));",
        ], {"hash11", "safeOffset", "sampleAt"}
    # rowcol
    return prelude + [
        "// Row tears and column jitters interleave on alternating frames.",
        "// Frame counter wrapped at 1024: keeps the hash input fp32-friendly.",
        f"float frame = mod(floor(anim * {F(u(0, 5.0, 8.0))}), 1024.0);",
        f"float rowRoll = hash11(floor(texCoord.y * {F(u(1, 20.0, 32.0))}) * 7.31 + frame * 13.7);",
        f"float colRoll = hash11(floor(texCoord.x * {F(u(2, 14.0, 24.0))}) * 5.13 + frame * 7.9);",
        f"float tearX = (rowRoll > 0.88 ? rowRoll - 0.88 : 0.0) * {F(u(3, 0.3, 0.5))} * glitchK * animAmp;",
        f"float tearY = (colRoll > 0.9 ? colRoll - 0.9 : 0.0) * {F(u(4, 0.2, 0.4))} * glitchK * animAmp;",
        "vec2 baseOff = vec2(tearX, tearY);",
        "float split = (0.003 + (tearX + tearY) * 0.3) * glitchK;",
    ] + split_tail + [
        "float flash = (tearX + tearY) > 0.0001 ? ParamsB.z * flashK : 0.0;",
        "vec3 outColor = mix(torn, torn * Primary.rgb, flash);",
    ], {"hash11", "safeOffset", "sampleAt"}


def fam_duotone(v, u):
    if v == "soft":
        return [
            "// Two-tone remap with a soft-knee luminance ramp.",
            f"float ramp = smoothstep({F(u(0, 0.05, 0.12))}, {F(u(1, 0.88, 0.95))}, baseLuma);",
            f"vec3 duotone = mix(Secondary.rgb * {F(u(2, 0.5, 0.6))}, Primary.rgb * ({F(u(3, 0.7, 0.8))} + {F(u(4, 0.2, 0.3))} * ramp), ramp);",
            "vec3 outColor = mix(base, duotone, clamp(strength, 0.0, 1.0));",
        ], {"luma"}
    if v == "hard":
        return [
            "// Hard-knee duotone: an inky comic-print split.",
            f"float ramp = smoothstep({F(u(0, 0.38, 0.46))}, {F(u(1, 0.54, 0.62))}, baseLuma);",
            f"vec3 duotone = mix(Secondary.rgb * {F(u(2, 0.5, 0.6))}, Primary.rgb, ramp);",
            "vec3 outColor = mix(base, duotone, clamp(strength, 0.0, 1.0));",
        ], {"luma"}
    if v == "tritone":
        return [
            "// Three-tone remap: shadows, a blended mid, and highlights.",
            f"vec3 midTone = mix(Primary.rgb, Secondary.rgb, 0.5) * {F(u(0, 0.75, 0.9))};",
            f"float lo = smoothstep({F(u(1, 0.1, 0.2))}, {F(u(2, 0.42, 0.52))}, baseLuma);",
            f"float hi = smoothstep({F(u(3, 0.55, 0.65))}, {F(u(4, 0.85, 0.95))}, baseLuma);",
            f"vec3 duotone = mix(mix(Secondary.rgb * 0.55, midTone, lo), Primary.rgb, hi);",
            "vec3 outColor = mix(base, duotone, clamp(strength, 0.0, 1.0));",
        ], {"luma"}
    # shifting
    return [
        "// The duotone knee slides on GameTime, tides of shadow and light.",
        f"float knee = {F(u(0, 0.4, 0.5))} + {F(u(1, 0.12, 0.2))} * sin(anim * {F(u(2, 0.4, 0.8))} + ParamsB.x * 6.2831);",
        "float ramp = smoothstep(knee - 0.35, knee + 0.35, baseLuma);",
        f"vec3 duotone = mix(Secondary.rgb * {F(u(3, 0.5, 0.6))}, Primary.rgb * (0.75 + 0.25 * ramp), ramp);",
        "vec3 outColor = mix(base, duotone, clamp(strength, 0.0, 1.0));",
    ], {"luma"}


def fam_kaleido(v, u):
    if v == "mirror":
        return [
            "// Mirror refraction: the frame leans toward its own reflection across",
            "// a slowly gliding vertical axis; the seam glows.",
            f"float axisPos = 0.5 + {F(u(0, 0.1, 0.2))} * sin(anim * {F(u(1, 0.2, 0.45))});",
            "vec2 target = vec2(axisPos * 2.0 - texCoord.x, texCoord.y);",
            "vec3 scene = sampleAt(texCoord + safeOffset((target - texCoord) * strength));",
            f"float seam = invsmooth(0.0, {F(u(2, 0.03, 0.06))}, abs(texCoord.x - axisPos));",
            f"vec3 outColor = scene + Primary.rgb * seam * {F(u(3, 0.2, 0.35))} * strength;",
        ], {"invsmooth", "safeOffset", "sampleAt"}
    wedges = {"wedge4": 4, "wedge6": 6, "wedge8": 8}[v]
    return [
        f"// Kaleidoscopic refraction: {wedges} angular wedges; each pixel leans toward",
        "// its fold position (bounded), and the wedge seams glow.",
        "float angle = safeAtan(aspectCentered.y, aspectCentered.x);",
        f"float wedge = 6.2831853 / {F(float(wedges))};",
        f"float local = mod(angle + anim * {F(u(0, 0.08, 0.2))}, wedge) - wedge * 0.5;",
        "float folded = abs(local);",
        f"vec2 dir = vec2(cos(folded + anim * {F(u(1, 0.03, 0.08))}), sin(folded + anim * {F(u(2, 0.03, 0.08))}));",
        "vec2 invAspect = vec2(safeInSize.y / safeInSize.x, 1.0);",
        "vec2 target = vec2(0.5) + dir * centerDist * invAspect;",
        "vec3 scene = sampleAt(texCoord + safeOffset((target - texCoord) * strength));",
        f"float seam = invsmooth(0.0, {F(u(3, 0.05, 0.1))}, abs(local)) * smoothstep(0.05, 0.25, centerDist);",
        f"vec3 outColor = scene + mix(Primary.rgb, Secondary.rgb, texCoord.y) * seam * {F(u(4, 0.2, 0.35))} * strength;",
    ], {"invsmooth", "safeAtan", "safeOffset", "sampleAt"}


def fam_huedrift(v, u):
    tail = [
        "vec3 outColor = mix(shifted, shifted * Primary.rgb, ParamsB.z * 0.5);",
    ]
    if v == "global":
        return [
            "// The whole world's hue swings gently around the grey axis.",
            f"float hueAngle = sin(anim * {F(u(0, 0.25, 0.5))} + ParamsB.x * 6.2831) * {F(u(1, 0.7, 1.1))} * strength;",
            "vec3 shifted = hueRotate(base, hueAngle);",
        ] + tail, {"hueRotate"}
    if v == "radial":
        return [
            "// Hue drift grows with distance from the center; the middle stays true.",
            f"float reach = smoothstep({F(u(0, 0.08, 0.16))}, {F(u(1, 0.6, 0.8))}, centerDist);",
            f"float hueAngle = sin(anim * {F(u(2, 0.3, 0.55))}) * {F(u(3, 0.8, 1.2))} * strength * reach;",
            "vec3 shifted = hueRotate(base, hueAngle);",
        ] + tail, {"hueRotate"}
    if v == "waves":
        return [
            "// Bands of hue roll down the screen like slow chromatic weather.",
            f"float hueAngle = sin(anim * {F(u(0, 0.35, 0.6))} + texCoord.y * {F(u(1, 4.0, 9.0))}) * {F(u(2, 0.6, 1.0))} * strength;",
            "vec3 shifted = hueRotate(base, hueAngle);",
        ] + tail, {"hueRotate"}
    # split
    return [
        "// Shadows and highlights drift in opposite hue directions.",
        f"float sgn = baseLuma * 2.0 - 1.0;",
        f"float hueAngle = -sgn * sin(anim * {F(u(0, 0.3, 0.55))} + ParamsB.x * 6.2831) * {F(u(1, 0.6, 1.0))} * strength;",
        "vec3 shifted = hueRotate(base, hueAngle);",
    ] + tail, {"luma", "hueRotate"}


def fam_dreamblur(v, u):
    if _RATE_CAPPED:
        # 840-flip ids: the paramA-scaled anim would flash these motes at
        # 3-5+ Hz (photosensitivity), so the twinkle sine runs on an
        # INDEPENDENT unit-rate clock (GameTime only -- neither Speed nor the
        # drift boost can accelerate it); the baked rate stays under 2.4 Hz.
        sparkle = [
            "// Sparkles: rate-capped twinkle on an independent unit-rate clock",
            "// (photosensitivity: the paramA-scaled anim reaches 3-5+ Hz here).",
            f"vec2 cellUv = floor(texCoord * safeInSize / {F(u(10, 8.0, 16.0))});",
            "float tw = hash21(cellUv);",
            "float twClock = GameTime * 1200.0 + ParamsB.x * 61.8;",
            f"float twinkle = smoothstep({F(u(11, 0.75, 0.85))}, 1.0, sin(twClock * {F(u(12, 6.0, 15.0))} + tw * 6.2831) * 0.5 + 0.5) * step({F(u(13, 0.965, 0.985))}, tw);",
            f"vec3 outColor = dream + Primary.rgb * twinkle * {F(u(14, 0.3, 0.5))} * animAmp;",
        ]
    else:
        sparkle = [
            f"vec2 cellUv = floor(texCoord * safeInSize / {F(u(10, 8.0, 16.0))});",
            "float tw = hash21(cellUv);",
            f"float twinkle = smoothstep({F(u(11, 0.75, 0.85))}, 1.0, sin(anim * {F(u(12, 1.5, 3.0))} + tw * 6.2831) * 0.5 + 0.5) * step({F(u(13, 0.965, 0.985))}, tw);",
            f"vec3 outColor = dream + Primary.rgb * twinkle * {F(u(14, 0.3, 0.5))} * animAmp;",
        ]
    if v == "soft9":
        return [
            "// 9-tap soft blur veils the scene; hash-cell sparkles twinkle on top.",
            f"vec2 texel = {F(u(0, 2.0, 4.0))} / safeInSize;",
            "vec3 blurred = vec3(0.0);",
            "for (int i = 0; i < 3; i++) {",
            "    for (int j = 0; j < 3; j++) {",
            "        blurred += sampleAt(texCoord + safeOffset(vec2(float(i - 1), float(j - 1)) * texel));",
            "    }",
            "}",
            "blurred /= 9.0;",
            f"vec3 dream = mix(base, blurred * {F(u(1, 1.02, 1.1))}, clamp(strength, 0.0, 0.85));",
        ] + sparkle, {"hash21", "safeOffset", "sampleAt"}
    if v == "cross5":
        return [
            "// 5-tap cross blur haze with drifting sparkle dust.",
            f"vec2 texel = {F(u(0, 2.5, 4.5))} / safeInSize;",
            "vec3 blurred = sampleAt(texCoord) * 0.32",
            "    + sampleAt(texCoord + safeOffset(vec2(texel.x, 0.0))) * 0.17",
            "    + sampleAt(texCoord + safeOffset(vec2(-texel.x, 0.0))) * 0.17",
            "    + sampleAt(texCoord + safeOffset(vec2(0.0, texel.y))) * 0.17",
            "    + sampleAt(texCoord + safeOffset(vec2(0.0, -texel.y))) * 0.17;",
            f"vec3 dream = mix(base, blurred * {F(u(1, 1.0, 1.08))}, clamp(strength, 0.0, 0.85));",
        ] + sparkle, {"hash21", "safeOffset", "sampleAt"}
    if v == "glowdream":
        return [
            "// Bright-lifted dream veil: the blur adds a soft luminous bloom.",
            f"vec2 texel = {F(u(0, 2.0, 3.5))} / safeInSize;",
            "vec3 blurred = vec3(0.0);",
            "for (int i = 0; i < 3; i++) {",
            "    for (int j = 0; j < 3; j++) {",
            "        blurred += sampleAt(texCoord + safeOffset(vec2(float(i - 1), float(j - 1)) * texel));",
            "    }",
            "}",
            "blurred /= 9.0;",
            f"vec3 dream = base * {F(u(1, 0.55, 0.68))} + blurred * mix(vec3(1.0), Primary.rgb, ParamsB.z) * {F(u(2, 0.5, 0.62))};",
        ] + sparkle, {"hash21", "safeOffset", "sampleAt"}
    # edgehalo
    return [
        "// The veil thickens toward the edges: a dreamy porthole.",
        f"vec2 texel = {F(u(0, 2.5, 4.0))} / safeInSize;",
        "vec3 blurred = sampleAt(texCoord) * 0.32",
        "    + sampleAt(texCoord + safeOffset(texel)) * 0.17",
        "    + sampleAt(texCoord + safeOffset(-texel)) * 0.17",
        "    + sampleAt(texCoord + safeOffset(vec2(texel.x, -texel.y))) * 0.17",
        "    + sampleAt(texCoord + safeOffset(vec2(-texel.x, texel.y))) * 0.17;",
        f"float halo = smoothstep({F(u(1, 0.12, 0.22))}, {F(u(2, 0.6, 0.8))}, centerDist);",
        f"vec3 dream = mix(base, blurred * {F(u(3, 1.0, 1.08))}, clamp(strength * halo, 0.0, 0.9));",
    ] + sparkle, {"hash21", "safeOffset", "sampleAt"}


def fam_moire(v, u):
    tail = [
        "float inter = g1 * g2;",
        "float mask = smoothstep(-0.2, 1.0, inter);",
        f"vec3 outColor = base * (1.0 - {F(u(9, 0.25, 0.4))} * strength * (1.0 - mask));",
        "outColor = mix(outColor, outColor * Primary.rgb, ParamsB.z * strength * mask);",
    ]
    if v == "linlin":
        a1 = u(0, 0.1, 1.0)
        a2 = a1 + u(1, 0.06, 0.16)
        return [
            "// Two near-parallel line gratings beat into drifting moire fringes.",
            f"float g1 = sin((texCoord.x * {F(math.cos(a1))} + texCoord.y * {F(math.sin(a1))}) * ParamsA.z + anim * {F(u(2, 0.3, 0.7))});",
            f"float g2 = sin((texCoord.x * {F(math.cos(a2))} + texCoord.y * {F(math.sin(a2))}) * ParamsA.z * {F(u(3, 1.02, 1.09))} - anim * {F(u(4, 0.2, 0.5))});",
        ] + tail, set()
    if v == "ringring":
        return [
            "// Two off-center ring gratings interfere in curved bands.",
            f"float d1 = length(texCoord - vec2({F(u(0, 0.25, 0.45))}, {F(u(1, 0.3, 0.5))}));",
            f"float d2 = length(texCoord - vec2({F(u(2, 0.55, 0.75))}, {F(u(3, 0.5, 0.7))}));",
            f"float g1 = sin(d1 * ParamsA.z + anim * {F(u(4, 0.4, 0.8))});",
            f"float g2 = sin(d2 * ParamsA.z * {F(u(5, 1.02, 1.08))} - anim * {F(u(6, 0.3, 0.6))});",
        ] + tail, set()
    if v == "linring":
        a1 = u(0, 0.2, 1.2)
        return [
            "// A line grating against a ring grating: spiral-ish beat patterns.",
            f"float g1 = sin((texCoord.x * {F(math.cos(a1))} + texCoord.y * {F(math.sin(a1))}) * ParamsA.z + anim * {F(u(1, 0.3, 0.6))});",
            f"float g2 = sin(centerDist * ParamsA.z * {F(u(2, 1.0, 1.15))} - anim * {F(u(3, 0.3, 0.7))});",
        ] + tail, set()
    # rotmoire
    a1 = u(0, 0.1, 1.0)
    return [
        "// The second grating slowly rotates: the fringes wheel and breathe.",
        f"float rot = {F(a1)} + {F(u(1, 0.06, 0.12))} + sin(anim * {F(u(2, 0.1, 0.25))}) * {F(u(3, 0.05, 0.1))};",
        f"float g1 = sin((texCoord.x * {F(math.cos(a1))} + texCoord.y * {F(math.sin(a1))}) * ParamsA.z);",
        f"float g2 = sin((texCoord.x * cos(rot) + texCoord.y * sin(rot)) * ParamsA.z * {F(u(4, 1.02, 1.08))});",
    ] + tail, set()


# ---------------------------------------------------------------------------
# v5 family modules (spectral/aberration/underwater/thermal/sketch/starburst/
# vhs/gloom). DORMANT until the next catalogue flip adds EffectRegistry rows
# that reference them, so nothing below can perturb an existing sfx byte.
#
# Shared v5 conventions (same discipline as the v1-v4 modules above):
# * every displaced scene sample routes its TOTAL summed displacement through
#   ONE safeOffset() clamp (never two stacked half-clamps);
# * the new families cannot rely on a JSON-side SPEED_CLAMP entry existing for
#   them yet, so every hard-cut/strobe-capable term is rate-limited IN-SHADER:
#   quantized frame counters use floor(anim * m) with m <= 0.3 (a worst-case
#   drift-boosted id-419 anim advances ~9.8/s, so rerolls stay under ~3 Hz --
#   photosensitivity), and luminance-modulating sines keep rate constants low
#   enough for the same bound;
# * the flip may pack ParamsA.z/.w as 0.0 for these families, so none of them
#   divides by (or scales purely with) those knobs -- frequencies are baked
#   per-id via u(), and ParamsA.w is only used through a max() guard;
# * amplitude knobs ride min(strength, 1.0) / clamp(strength, 0.0, 1.0) so an
#   unexpected packing cannot over-drive an additive or displacement term;
# * flash-capable code that STRADDLES the flip (the shared sparkle/grain
#   overlays, dreamblur's twinkle) is additionally rate-capped for ids >=
#   V5_FLIP_START: it ticks on an independent unit-rate clock
#   (GameTime * 1200.0, never the paramA-scaled anim) with a per-id baked
#   rate that stays under ~2.5 Hz -- see overlay_lines / fam_dreamblur.
#   Thermal (a whole-screen false-color remap) additionally carries a
#   luma-band guarantee (see fam_thermal) so no id can white-out or
#   black-out the scene.
# ---------------------------------------------------------------------------


def fam_spectral(v, u):
    # 2-3 DECAYING ghost-offset resamples of InSampler + desaturation drift.
    # Echo weights are energy-preserving (base keeps 1 - sum(w)) and every
    # ghost tap is bounded by safeOffset.
    tail = [
        "// Desaturation drift: a slow spirit-world pallor swings on GameTime.",
        f"float pallor = {F(u(8, 0.25, 0.4))} + {F(u(9, 0.12, 0.2))} * sin(anim * {F(u(10, 0.2, 0.4))} + ParamsB.x * 6.2831);",
        "vec3 pale = mix(haunted, vec3(luma(haunted)), clamp(pallor * strength, 0.0, 0.8));",
        "vec3 outColor = mix(pale, pale * Primary.rgb, ParamsB.z);",
    ]
    if v == "twin":
        ang = u(0, 0.3, 1.2)
        return [
            "// Twin ghosts: two opposite decaying echoes along a fixed diagonal.",
            "float echoK = clamp(strength, 0.0, 1.0);",
            f"vec2 ghostAxis = vec2({F(math.cos(ang))}, {F(math.sin(ang))}) * {F(u(1, 0.008, 0.013))} * animAmp;",
            "vec3 g1 = sampleAt(texCoord + safeOffset(ghostAxis));",
            f"vec3 g2 = sampleAt(texCoord + safeOffset(-ghostAxis * {F(u(2, 1.6, 2.0))}));",
            f"float w1 = {F(u(3, 0.28, 0.38))} * echoK;",
            f"float w2 = {F(u(4, 0.13, 0.19))} * echoK;",
            "vec3 haunted = base * (1.0 - w1 - w2) + g1 * w1 + g2 * w2;",
        ] + tail, {"luma", "safeOffset", "sampleAt"}
    if v == "triple":
        return [
            "// Three ghosts at 120-degree spokes, each fainter and further out.",
            "float echoK = clamp(strength, 0.0, 1.0);",
            f"float spokeAng = {F(u(0, 0.0, 2.0))};",
            f"float ghostR = {F(u(1, 0.007, 0.012))} * animAmp;",
            "vec3 g1 = sampleAt(texCoord + safeOffset(vec2(cos(spokeAng), sin(spokeAng)) * ghostR));",
            f"vec3 g2 = sampleAt(texCoord + safeOffset(vec2(cos(spokeAng + 2.0944), sin(spokeAng + 2.0944)) * ghostR * {F(u(2, 1.4, 1.7))}));",
            f"vec3 g3 = sampleAt(texCoord + safeOffset(vec2(cos(spokeAng + 4.1888), sin(spokeAng + 4.1888)) * ghostR * {F(u(3, 1.9, 2.3))}));",
            f"float w1 = {F(u(4, 0.23, 0.3))} * echoK;",
            f"float w2 = {F(u(5, 0.12, 0.17))} * echoK;",
            f"float w3 = {F(u(6, 0.06, 0.1))} * echoK;",
            "vec3 haunted = base * (1.0 - w1 - w2 - w3) + g1 * w1 + g2 * w2 + g3 * w3;",
        ] + tail, {"luma", "safeOffset", "sampleAt"}
    if v == "orbit":
        return [
            "// The ghost pair slowly orbits the true image on GameTime.",
            "float echoK = clamp(strength, 0.0, 1.0);",
            f"float orbitAng = anim * {F(u(0, 0.1, 0.22))} + ParamsB.x * 6.2831;",
            f"vec2 orbitDir = vec2(cos(orbitAng), sin(orbitAng)) * {F(u(1, 0.008, 0.013))} * animAmp;",
            "vec3 g1 = sampleAt(texCoord + safeOffset(orbitDir));",
            f"vec3 g2 = sampleAt(texCoord + safeOffset(-orbitDir * {F(u(2, 1.5, 1.9))}));",
            f"float w1 = {F(u(3, 0.27, 0.36))} * echoK;",
            f"float w2 = {F(u(4, 0.12, 0.18))} * echoK;",
            "vec3 haunted = base * (1.0 - w1 - w2) + g1 * w1 + g2 * w2;",
        ] + tail, {"luma", "safeOffset", "sampleAt"}
    # fade
    ang = u(0, 0.4, 1.3)
    return [
        "// A single echo trail: two taps down one axis, breathing in reach.",
        "float echoK = clamp(strength, 0.0, 1.0);",
        f"float reach = 0.7 + 0.3 * sin(anim * {F(u(1, 0.25, 0.45))} + ParamsB.x * 6.2831);",
        f"vec2 trail = vec2({F(math.cos(ang))}, {F(math.sin(ang))}) * {F(u(2, 0.009, 0.015))} * reach * animAmp;",
        "vec3 g1 = sampleAt(texCoord + safeOffset(trail));",
        "vec3 g2 = sampleAt(texCoord + safeOffset(trail * 2.0));",
        f"float w1 = {F(u(3, 0.3, 0.38))} * echoK;",
        f"float w2 = {F(u(4, 0.12, 0.18))} * echoK;",
        "vec3 haunted = base * (1.0 - w1 - w2) + g1 * w1 + g2 * w2;",
    ] + tail, {"luma", "safeOffset", "sampleAt"}


def fam_aberration(v, u):
    # True lens fringing: a barrel/pincushion warp plus per-channel RADIAL
    # offsets. The warp and the fringe are SUMMED before the single safeOffset
    # clamp per channel, so the total displacement can never exceed the bound.
    tail = [
        "float red = sampleAt(texCoord + safeOffset(warp + fringe)).r;",
        "float green = sampleAt(texCoord + safeOffset(warp)).g;",
        "float blue = sampleAt(texCoord + safeOffset(warp - fringe)).b;",
        "vec3 fringed = vec3(red, green, blue);",
        "vec3 outColor = mix(fringed, fringed * Primary.rgb, ParamsB.z);",
    ]
    if v == "barrel":
        return [
            "// Barrel warp: the frame bulges outward; fringing grows with radius.",
            "float r2 = centerDist * centerDist;",
            f"vec2 warp = centered * r2 * {F(u(0, 0.05, 0.09))} * min(strength, 1.0);",
            f"vec2 fringe = centered * r2 * {F(u(1, 0.012, 0.02))} * min(strength, 1.0) * animAmp;",
        ] + tail, {"safeOffset", "sampleAt"}
    if v == "pincushion":
        return [
            "// Pincushion warp: the frame pinches inward toward the center.",
            "float r2 = centerDist * centerDist;",
            f"vec2 warp = -centered * r2 * {F(u(0, 0.05, 0.09))} * min(strength, 1.0);",
            f"vec2 fringe = centered * r2 * {F(u(1, 0.012, 0.02))} * min(strength, 1.0) * animAmp;",
        ] + tail, {"safeOffset", "sampleAt"}
    if v == "breathing":
        return [
            "// The lens breathes: the warp swings between barrel and pincushion",
            "// on a slow GameTime sine (well under any strobe-relevant rate).",
            "float r2 = centerDist * centerDist;",
            f"float lensK = sin(anim * {F(u(0, 0.2, 0.4))} + ParamsB.x * 6.2831) * {F(u(1, 0.04, 0.07))} * min(strength, 1.0);",
            "vec2 warp = centered * r2 * lensK;",
            f"vec2 fringe = centered * r2 * {F(u(2, 0.012, 0.02))} * min(strength, 1.0) * animAmp;",
        ] + tail, {"safeOffset", "sampleAt"}
    # cornered
    return [
        "// Corner-weighted fringe: an r^4 falloff keeps the center optically",
        "// clean while the corners smear like a cheap wide-angle element.",
        "float r2 = centerDist * centerDist;",
        f"vec2 warp = centered * r2 * {F(u(0, 0.03, 0.06))} * min(strength, 1.0);",
        f"vec2 fringe = centered * r2 * r2 * {F(u(1, 0.03, 0.05))} * min(strength, 1.0) * animAmp;",
    ] + tail, {"safeOffset", "sampleAt"}


def fam_underwater(v, u):
    # Caustic-modulated wavy refraction + blue-green depth grade + light
    # shafts. All motion is slow scene-space sway (no hard cuts anywhere).
    def caustic_sway(extra_amp):
        return [
            "// Caustic field (slow vnoise drift) modulates the sway amplitude.",
            f"float caustic = vnoise(texCoord * {F(u(0, 6.0, 10.0))} + vec2(anim * {F(u(1, 0.08, 0.16))}, anim * {F(u(2, 0.05, 0.1))}));",
            "vec2 sway = vec2(",
            f"    sin(texCoord.y * {F(u(3, 40.0, 65.0))} + anim * {F(u(4, 1.0, 1.8))}),",
            f"    cos(texCoord.x * {F(u(5, 30.0, 50.0))} - anim * {F(u(6, 0.8, 1.4))})",
            f") * {F(u(7, 0.004, 0.007))} * min(strength, 1.0) * animAmp * (0.35 + 0.65 * caustic){extra_amp};",
            "vec3 scene = sampleAt(texCoord + safeOffset(sway));",
        ]
    shaft_ang = u(8, 1.9, 2.4)
    shafts = [
        "// Light shafts: soft diagonal bands, brightest near the surface (top).",
        f"vec2 shaftDir = vec2({F(math.cos(shaft_ang))}, {F(math.sin(shaft_ang))});",
        f"float shaftBand = pow(0.5 + 0.5 * sin(dot(texCoord, shaftDir) * {F(u(9, 9.0, 16.0))} + anim * {F(u(10, 0.2, 0.45))}), {F(u(11, 3.0, 6.0))});",
        f"float shaft = shaftBand * smoothstep({F(u(12, 0.15, 0.3))}, {F(u(13, 0.75, 0.95))}, texCoord.y) * (0.4 + 0.6 * caustic);",
    ]
    grade = [
        "// Depth grade: the scene sinks toward the palette with screen depth.",
        f"float depthMix = invsmooth({F(u(14, 0.1, 0.2))}, {F(u(15, 0.8, 0.95))}, texCoord.y);",
        "vec3 deepTone = scene * mix(Primary.rgb, Secondary.rgb, depthMix);",
    ]
    if v == "shallows":
        return caustic_sway("") + shafts + grade + [
            f"vec3 graded = mix(scene, deepTone, {F(u(16, 0.3, 0.4))} * clamp(strength, 0.0, 1.0));",
            f"vec3 outColor = graded + Primary.rgb * shaft * {F(u(17, 0.15, 0.22))} * min(strength, 1.0);",
        ], {"invsmooth", "vnoise", "safeOffset", "sampleAt"}
    if v == "deep":
        return caustic_sway("") + shafts + grade + [
            f"vec3 graded = mix(scene, deepTone, {F(u(16, 0.55, 0.7))} * clamp(strength, 0.0, 1.0)) * {F(u(18, 0.84, 0.92))};",
            f"vec3 outColor = graded + Primary.rgb * shaft * {F(u(17, 0.06, 0.1))} * min(strength, 1.0);",
        ], {"invsmooth", "vnoise", "safeOffset", "sampleAt"}
    if v == "shafted":
        return caustic_sway("") + shafts + [
            "// A second, wider band set doubles up into proper god-rays.",
            f"float shaftBandB = pow(0.5 + 0.5 * sin(dot(texCoord, shaftDir) * {F(u(18, 4.0, 7.0))} - anim * {F(u(19, 0.15, 0.3))}), {F(u(20, 2.5, 4.5))});",
            f"shaft = clamp(shaft + shaftBandB * smoothstep({F(u(12, 0.15, 0.3))}, {F(u(13, 0.75, 0.95))}, texCoord.y) * 0.6, 0.0, 1.5);",
        ] + grade + [
            f"vec3 graded = mix(scene, deepTone, {F(u(16, 0.35, 0.45))} * clamp(strength, 0.0, 1.0));",
            f"vec3 outColor = graded + Primary.rgb * shaft * {F(u(17, 0.22, 0.3))} * min(strength, 1.0);",
        ], {"invsmooth", "vnoise", "safeOffset", "sampleAt"}
    # surgewash
    return [
        "// A slow swell breathes through the sway and the grade together.",
        f"float swell = 0.5 + 0.5 * sin(anim * {F(u(18, 0.15, 0.3))} + ParamsB.x * 6.2831);",
    ] + caustic_sway(" * (0.7 + 0.3 * swell)") + shafts + grade + [
        f"vec3 graded = mix(scene, deepTone, ({F(u(16, 0.3, 0.4))} + {F(u(19, 0.12, 0.2))} * swell) * clamp(strength, 0.0, 1.0));",
        f"vec3 outColor = graded + Primary.rgb * shaft * {F(u(17, 0.12, 0.2))} * min(strength, 1.0);",
    ], {"invsmooth", "vnoise", "safeOffset", "sampleAt"}


def fam_thermal(v, u):
    # Luma -> false-color ramp built from the FxConfig palette, biased by a
    # slowly DRIFTING hotspot field. Strictly a per-pixel remap: no hard cuts,
    # the only motion is the sub-0.1-rate noise drift (flicker-free).
    #
    # Legibility band (playability): thermal is a WHOLE-SCREEN remap, so it
    # gets belt-and-braces bounds on top of the shared ParamsB.w floor:
    # * the ramp's hot peak is capped below pure white (<= ~0.88);
    # * the blend always keeps a fixed share of the true scene -- the
    #   inverted variant reads dark scenes as ~fully hot (black -> near-
    #   white before this fix), so its blend caps at 0.55 (>= 45% of the
    #   real scene always survives); the other variants cap at 0.85;
    # * the composite is luma-banded: a hue-preserving ceiling at 0.75
    #   stops any palette/variant from washing the view toward white, and
    #   a 0.05 channel floor stops near-black palettes from crushing it --
    #   no thermal id can white-out or black-out the screen.
    ramp = [
        "// False-color ramp: cold Secondary depths through the palette to a",
        "// capped hot peak (never pure white -- legibility ceiling).",
        f"vec3 coldTone = Secondary.rgb * {F(u(8, 0.14, 0.22))};",
        f"vec3 ramped = mix(coldTone, Secondary.rgb, smoothstep(0.0, {F(u(9, 0.42, 0.5))}, heat));",
        f"ramped = mix(ramped, Primary.rgb, smoothstep({F(u(10, 0.36, 0.44))}, {F(u(11, 0.72, 0.82))}, heat));",
        f"ramped = mix(ramped, vec3({F(u(13, 0.80, 0.88))}), smoothstep({F(u(12, 0.84, 0.9))}, 1.0, heat));",
        "// Luma band: keep a fixed share of the real scene, ceiling the read",
        "// hue-preservingly at 0.75 luma and floor it at 0.05 per channel so",
        "// no palette/variant can white-out or black-out the screen.",
        "vec3 toned = mix(base, ramped, thermalMix);",
        "float tonedLuma = luma(toned);",
        "toned *= min(tonedLuma, 0.75) / max(tonedLuma, 0.001);",
        "vec3 outColor = max(toned, vec3(0.05));",
    ]
    blob = [
        "// Drifting hotspot field biases the reading before the ramp.",
        f"float blob = vnoise(texCoord * {F(u(0, 3.0, 5.5))} + vec2(anim * {F(u(1, 0.04, 0.08))}, anim * {F(u(2, 0.02, 0.05))}));",
    ]
    if v == "classic":
        return blob + [
            "float thermalMix = clamp(strength, 0.0, 0.85);",
            f"float heat = clamp(baseLuma + (blob - 0.5) * {F(u(3, 0.2, 0.3))} * min(strength, 1.0), 0.0, 1.0);",
        ] + ramp, {"luma", "vnoise"}
    if v == "inverted":
        return blob + [
            "// Inverted sensor: shadows read hot, highlights read cold. Dark",
            "// scenes therefore read ~fully hot, so the blend cap is tighter",
            "// (anti-whiteout: a black scene stays under ~0.5 output luma).",
            "float thermalMix = clamp(strength, 0.0, 0.55);",
            f"float heat = clamp(1.0 - baseLuma + (blob - 0.5) * {F(u(3, 0.2, 0.3))} * min(strength, 1.0), 0.0, 1.0);",
        ] + ramp, {"luma", "vnoise"}
    if v == "banded":
        levels = float(int(u(4, 5.0, 8.99)))
        return blob + [
            "// Quantized isotherm bands, like a cheap thermography readout.",
            "float thermalMix = clamp(strength, 0.0, 0.85);",
            f"float heat = clamp(baseLuma + (blob - 0.5) * {F(u(3, 0.18, 0.26))} * min(strength, 1.0), 0.0, 1.0);",
            f"heat = floor(heat * {F(levels)} + 0.5) / {F(levels)};",
        ] + ramp, {"luma", "vnoise"}
    # hotspots
    return blob + [
        "// A second, tighter octave makes distinct wandering hot blobs.",
        f"float blobB = vnoise(texCoord * {F(u(4, 7.0, 11.0))} - vec2(anim * {F(u(5, 0.03, 0.06))}, anim * {F(u(6, 0.05, 0.09))}));",
        "float thermalMix = clamp(strength, 0.0, 0.85);",
        f"float heat = clamp(baseLuma + (blob * 0.6 + blobB * 0.4 - 0.5) * {F(u(3, 0.3, 0.45))} * min(strength, 1.0), 0.0, 1.0);",
    ] + ramp, {"luma", "vnoise"}


def fam_sketch(v, u):
    # 4-tap Sobel (cross-difference) edge ink over a contrast-flattened,
    # paper-tinted base. The edge taps are pixel-scale (well inside the
    # safeOffset bound) and the look is static: day-wrap-trivially-safe.
    taps = [
        "// 4-tap cross-difference Sobel over scene luma.",
        f"vec2 texel = {F(u(0, 1.0, 1.6))} / safeInSize;",
        "float gl = lumaAt(texCoord + safeOffset(vec2(-texel.x, 0.0)));",
        "float gr = lumaAt(texCoord + safeOffset(vec2(texel.x, 0.0)));",
        "float gu = lumaAt(texCoord + safeOffset(vec2(0.0, -texel.y)));",
        "float gd = lumaAt(texCoord + safeOffset(vec2(0.0, texel.y)));",
        f"float edge = clamp(length(vec2(gr - gl, gd - gu)) * {F(u(1, 2.8, 4.2))}, 0.0, 1.0);",
        "// Paper base: contrast-flattened luma with a palette paper tint.",
        f"float flatTone = {F(u(2, 0.52, 0.62))} + {F(u(3, 0.28, 0.36))} * baseLuma;",
        "vec3 paper = vec3(flatTone) * mix(vec3(1.0), Secondary.rgb, ParamsB.z);",
    ]
    if v == "ink":
        return taps + [
            f"vec3 inked = mix(paper, Primary.rgb * {F(u(4, 0.1, 0.2))}, edge);",
            "vec3 outColor = mix(base, inked, clamp(strength, 0.0, 1.0));",
        ], {"luma", "lumaAt", "safeOffset"}
    if v == "crosshatch":
        ang = u(4, 0.5, 1.0)
        return taps + [
            "// Shadow crosshatch: two static gratings bite where the scene is dark.",
            f"float h1 = 0.5 + 0.5 * sin((texCoord.x * {F(math.cos(ang))} + texCoord.y * {F(math.sin(ang))}) * {F(u(5, 320.0, 420.0))});",
            f"float h2 = 0.5 + 0.5 * sin((texCoord.x * {F(-math.sin(ang))} + texCoord.y * {F(math.cos(ang))}) * {F(u(6, 300.0, 400.0))});",
            f"float shadowMask = smoothstep({F(u(7, 0.3, 0.4))}, {F(u(8, 0.75, 0.9))}, 1.0 - baseLuma);",
            f"float hatch = clamp(smoothstep(0.6, 1.0, h1) + smoothstep(0.65, 1.0, h2), 0.0, 1.0) * shadowMask;",
            f"vec3 inked = mix(paper, Primary.rgb * {F(u(9, 0.12, 0.2))}, clamp(edge + hatch * {F(u(10, 0.5, 0.7))}, 0.0, 1.0));",
            "vec3 outColor = mix(base, inked, clamp(strength, 0.0, 1.0));",
        ], {"luma", "lumaAt", "safeOffset"}
    if v == "charcoal":
        return taps + [
            "// Charcoal: soft wide strokes broken up by a static grain mask.",
            f"float soft = pow(edge, {F(u(4, 0.55, 0.7))});",
            f"float grain = vnoise(texCoord * safeInSize * {F(u(5, 0.04, 0.08))});",
            f"vec3 inked = mix(paper, Secondary.rgb * {F(u(6, 0.15, 0.25))}, clamp(soft * (0.55 + 0.45 * grain), 0.0, 1.0));",
            "vec3 outColor = mix(base, inked, clamp(strength, 0.0, 1.0));",
        ], {"luma", "lumaAt", "safeOffset", "vnoise"}
    # blueprint
    return taps + [
        "// Blueprint: glowing drafting lines over a deep palette paper.",
        f"vec3 paperDeep = Secondary.rgb * ({F(u(4, 0.2, 0.3))} + {F(u(5, 0.1, 0.18))} * baseLuma);",
        f"vec3 inked = paperDeep + Primary.rgb * edge * {F(u(6, 0.7, 0.9))};",
        "vec3 outColor = mix(base, inked, clamp(strength, 0.0, 1.0));",
    ], {"luma", "lumaAt", "safeOffset"}


def fam_starburst(v, u):
    # Bright-pass directional streaks: 4-6 arms, <= 12 decaying arm taps total
    # (the compile-safety streak-tap budget; the center bright term reuses the
    # prelude's undisplaced `base` fetch instead of a 13th texture tap).
    # Distinct from bloomglow's starburst variant (3 double-ended axes, single
    # tap per arm): these are one-sided arms with multiple decaying taps each.
    # ParamsA.w may be packed as 0.0 for this family, so the bright threshold
    # is floor-guarded via max().
    tail = [
        "// Additive calibration: capped strength, attenuated on bright scenes.",
        f"vec3 outColor = base + streak * mix(vec3(1.0), Primary.rgb, {F(u(9, 0.55, 0.75))}) * min(strength, 1.0) * (1.0 - {F(u(10, 0.3, 0.45))} * baseLuma);",
    ]
    thr = f"max(ParamsA.w, {F(u(1, 0.35, 0.5))})"
    center = "vec3 streak = base * max(baseLuma - thr, 0.0) * 0.3;"
    if v == "four":
        return [
            "// 4-arm starburst: 3 decaying bright-pass taps down each arm.",
            f"vec2 texel = {F(u(0, 4.0, 7.0))} / safeInSize;",
            f"float thr = {thr};",
            center,
            "for (int i = 0; i < 4; i++) {",
            f"    float armAng = float(i) * 1.5708 + {F(u(2, 0.0, 0.7))};",
            "    vec2 arm = vec2(cos(armAng), sin(armAng)) * texel;",
            "    streak += brightTap(texCoord + safeOffset(arm), thr) * 0.1;",
            f"    streak += brightTap(texCoord + safeOffset(arm * {F(u(3, 2.0, 2.5))}), thr) * 0.06;",
            f"    streak += brightTap(texCoord + safeOffset(arm * {F(u(4, 3.2, 3.9))}), thr) * 0.035;",
            "}",
        ] + tail, {"luma", "brightTap", "safeOffset"}
    if v == "five":
        return [
            "// 5-arm starburst: an off-kilter iris star, 2 taps per arm.",
            f"vec2 texel = {F(u(0, 4.0, 7.0))} / safeInSize;",
            f"float thr = {thr};",
            center,
            "for (int i = 0; i < 5; i++) {",
            f"    float armAng = float(i) * 1.2566 + {F(u(2, 0.0, 0.6))};",
            "    vec2 arm = vec2(cos(armAng), sin(armAng)) * texel;",
            "    streak += brightTap(texCoord + safeOffset(arm), thr) * 0.11;",
            f"    streak += brightTap(texCoord + safeOffset(arm * {F(u(3, 2.1, 2.6))}), thr) * 0.055;",
            "}",
        ] + tail, {"luma", "brightTap", "safeOffset"}
    if v == "six":
        return [
            "// 6-arm starburst: a dense snowflake star, 2 taps per arm.",
            f"vec2 texel = {F(u(0, 3.5, 6.0))} / safeInSize;",
            f"float thr = {thr};",
            center,
            "for (int i = 0; i < 6; i++) {",
            f"    float armAng = float(i) * 1.0472 + {F(u(2, 0.0, 0.5))};",
            "    vec2 arm = vec2(cos(armAng), sin(armAng)) * texel;",
            "    streak += brightTap(texCoord + safeOffset(arm), thr) * 0.1;",
            f"    streak += brightTap(texCoord + safeOffset(arm * {F(u(3, 2.1, 2.6))}), thr) * 0.05;",
            "}",
        ] + tail, {"luma", "brightTap", "safeOffset"}
    # spinning
    return [
        "// 4-arm burst that slowly wheels on GameTime (calm, sub-strobe rate).",
        f"vec2 texel = {F(u(0, 4.0, 7.0))} / safeInSize;",
        f"float thr = {thr};",
        f"float wheel = anim * {F(u(2, 0.06, 0.14))};",
        center,
        "for (int i = 0; i < 4; i++) {",
        "    float armAng = float(i) * 1.5708 + wheel;",
        "    vec2 arm = vec2(cos(armAng), sin(armAng)) * texel;",
        "    streak += brightTap(texCoord + safeOffset(arm), thr) * 0.11;",
        f"    streak += brightTap(texCoord + safeOffset(arm * {F(u(3, 2.1, 2.6))}), thr) * 0.06;",
        "}",
    ] + tail, {"luma", "brightTap", "safeOffset"}


def fam_vhs(v, u):
    # Tape-deck artifacts: scanline-phase wobble + intermittent line dropouts
    # + chroma bleed. Dropout/tracking rerolls are quantized SLOWLY: the
    # floor() multiplier is <= 0.3, so even a drift-boosted max-Speed anim
    # (~9.8/s at id 419) rerolls under ~3 Hz -- the same photosensitivity
    # discipline the glitch/scanlines families get from the JSON SPEED_CLAMP
    # (which does not cover these new families yet, hence the in-shader cap).
    # The frame counter wraps at 1024 to keep hash inputs fp32-friendly.
    prelude = [
        "float vhsK = min(ParamsA.y, 1.0);",
        f"float frame = mod(floor(anim * {F(u(0, 0.18, 0.3))}), 1024.0);",
        f"float row = floor(texCoord.y * {F(u(1, 90.0, 140.0))});",
        "// Scanline-phase wobble: every row leans on a slow sine phase.",
        f"float phaseWobble = sin(texCoord.y * {F(u(2, 240.0, 340.0))} + anim * {F(u(3, 0.8, 1.4))}) * {F(u(4, 0.0012, 0.002))} * vhsK * animAmp;",
    ]
    bleed_tail = [
        "// Chroma bleed: the color channels smear sideways off the luma. The",
        "// row shift and the bleed are summed before the single clamp.",
        f"float bleed = {F(u(5, 0.0025, 0.0042))} * vhsK;",
        "float red = sampleAt(texCoord + safeOffset(baseOff + vec2(bleed, 0.0))).r;",
        "float green = sampleAt(texCoord + safeOffset(baseOff)).g;",
        f"float blue = sampleAt(texCoord + safeOffset(baseOff - vec2(bleed * {F(u(6, 1.4, 1.9))}, 0.0))).b;",
        "vec3 taped = vec3(red, green, blue);",
    ]
    if v == "tracking":
        return prelude + [
            "// A tracking band crawls up the tape and shears the rows it crosses.",
            f"float bandPos = fract(anim * {F(u(7, 0.03, 0.06))});",
            f"float bandMask = invsmooth(0.0, {F(u(8, 0.05, 0.09))}, abs(texCoord.y - bandPos));",
            f"float shear = (hash11(row * 3.7 + frame * 17.3) - 0.5) * {F(u(9, 0.02, 0.035))} * vhsK * bandMask;",
            "vec2 baseOff = vec2(phaseWobble + shear, 0.0);",
        ] + bleed_tail + [
            f"taped = mix(taped, vec3(luma(taped)) * {F(u(10, 1.1, 1.3))}, bandMask * {F(u(11, 0.35, 0.5))} * vhsK);",
            "vec3 outColor = mix(taped, taped * Primary.rgb, ParamsB.z);",
        ], {"luma", "invsmooth", "hash11", "safeOffset", "sampleAt"}
    if v == "dropout":
        return prelude + [
            "// Intermittent line dropouts: rare rows lose signal to pale static.",
            "float dropRoll = hash11(row * 7.31 + frame * 13.7);",
            f"float drop = step({F(u(7, 0.94, 0.97))}, dropRoll);",
            "vec2 baseOff = vec2(phaseWobble, 0.0);",
        ] + bleed_tail + [
            f"float snowNoise = hash21(vec2(floor(texCoord.x * {F(u(8, 160.0, 240.0))}), row + frame));",
            f"taped = mix(taped, vec3({F(u(9, 0.75, 0.9))}) * (0.6 + 0.4 * snowNoise), drop * {F(u(10, 0.6, 0.8))} * vhsK);",
            "vec3 outColor = mix(taped, taped * Primary.rgb, ParamsB.z);",
        ], {"hash11", "hash21", "safeOffset", "sampleAt"}
    if v == "headswitch":
        return prelude + [
            "// Head-switch tear: the bottom edge of the frame always shears.",
            f"float switchZone = invsmooth(0.0, {F(u(7, 0.05, 0.09))}, texCoord.y);",
            f"float shear = switchZone * (sin(anim * {F(u(8, 0.5, 0.9))} + texCoord.y * {F(u(9, 60.0, 90.0))}) * 0.5 + 0.7) * {F(u(10, 0.008, 0.014))} * vhsK;",
            "vec2 baseOff = vec2(phaseWobble + shear, 0.0);",
        ] + bleed_tail + [
            f"taped = mix(taped, vec3(luma(taped)), switchZone * {F(u(11, 0.3, 0.5))});",
            "vec3 outColor = mix(taped, taped * Primary.rgb, ParamsB.z);",
        ], {"luma", "invsmooth", "safeOffset", "sampleAt"}
    # worn
    return prelude + [
        "// Worn tape: per-row micro-jitter plus an all-over grain film.",
        f"float rowJitter = (hash11(row + frame * 91.7) - 0.5) * {F(u(7, 0.0015, 0.0025))} * vhsK;",
        "vec2 baseOff = vec2(phaseWobble + rowJitter, 0.0);",
    ] + bleed_tail + [
        f"float film = hash21(floor(texCoord * safeInSize * {F(u(8, 0.4, 0.6))}) + vec2(frame, 0.0)) - 0.5;",
        f"taped += vec3(film) * {F(u(9, 0.035, 0.06))} * vhsK;",
        "vec3 outColor = mix(taped, taped * Primary.rgb, ParamsB.z);",
    ], {"hash11", "hash21", "safeOffset", "sampleAt"}


def fam_gloom(v, u):
    # Breathing dark vignette + cold desaturation lift + slow luminance pulse.
    # Every sine rate here is <= 0.45 on anim (well under 1 Hz even at max
    # Speed with drift boost), and the shared ParamsB.w luma floor still
    # guarantees the world never crushes below ~0.35x.
    cold = [
        "// Cold lift: the scene drains toward a Secondary-tinted grey.",
        "vec3 coldGrey = vec3(baseLuma) * mix(vec3(1.0), Secondary.rgb, ParamsB.z);",
        f"vec3 chilled = mix(base, coldGrey, {F(u(8, 0.4, 0.55))} * clamp(strength, 0.0, 1.0));",
    ]
    if v == "dusk":
        return cold + [
            f"float breathe = 0.5 + 0.5 * sin(anim * {F(u(0, 0.25, 0.45))} + ParamsB.x * 6.2831);",
            f"float reach = {F(u(1, 0.3, 0.38))} + {F(u(2, 0.08, 0.14))} * breathe;",
            f"float rim = smoothstep(reach, reach + {F(u(3, 0.35, 0.5))}, centerDist);",
            f"float swell = 1.0 - {F(u(4, 0.08, 0.14))} * (0.5 + 0.5 * sin(anim * {F(u(5, 0.12, 0.25))}));",
            "vec3 dimmed = chilled * swell;",
            f"vec3 outColor = mix(dimmed, Primary.rgb * {F(u(6, 0.08, 0.16))}, rim * clamp(strength, 0.0, 1.0) * {F(u(7, 0.6, 0.75))});",
        ], {"luma"}
    if v == "creeping":
        return cold + [
            "// The dark rim creeps inward along a noise-eaten front, then recedes.",
            f"float front = vnoise(texCoord * {F(u(0, 4.0, 8.0))} + vec2(anim * {F(u(1, 0.02, 0.05))}, 0.0));",
            f"float breathe = 0.5 + 0.5 * sin(anim * {F(u(2, 0.18, 0.35))} + ParamsB.x * 6.2831);",
            f"float reach = {F(u(3, 0.26, 0.34))} + {F(u(4, 0.1, 0.16))} * breathe;",
            f"float rim = smoothstep(reach, reach + {F(u(5, 0.3, 0.42))}, centerDist + (front - 0.5) * {F(u(6, 0.16, 0.26))});",
            f"float swell = 1.0 - {F(u(7, 0.07, 0.12))} * breathe;",
            "vec3 dimmed = chilled * swell;",
            f"vec3 outColor = mix(dimmed, Primary.rgb * {F(u(9, 0.06, 0.14))}, rim * clamp(strength, 0.0, 1.0) * {F(u(10, 0.6, 0.75))});",
        ], {"luma", "vnoise"}
    if v == "heartbeat":
        return cold + [
            "// Double-lobed slow pulse (a sub-1 Hz heartbeat, never a strobe).",
            f"float beatPhase = anim * {F(u(0, 0.3, 0.45))} + ParamsB.x * 6.2831;",
            "float lub = max(sin(beatPhase), 0.0);",
            f"float dub = max(sin(beatPhase + {F(u(1, 2.2, 2.6))}), 0.0);",
            f"float beat = lub * lub + {F(u(2, 0.4, 0.6))} * dub * dub;",
            f"float swell = 1.0 - {F(u(3, 0.1, 0.16))} * beat;",
            f"float rim = smoothstep({F(u(4, 0.3, 0.38))}, {F(u(5, 0.68, 0.8))}, centerDist + beat * {F(u(6, 0.05, 0.09))});",
            "vec3 dimmed = chilled * swell;",
            f"vec3 outColor = mix(dimmed, Primary.rgb * {F(u(7, 0.07, 0.14))}, rim * clamp(strength, 0.0, 1.0) * {F(u(9, 0.55, 0.7))});",
        ], {"luma"}
    # hollow
    return cold + [
        "// Hollow heart: color drains from the CENTER, the rim sinks to dusk.",
        f"float core = invsmooth({F(u(0, 0.1, 0.18))}, {F(u(1, 0.45, 0.6))}, centerDist);",
        f"vec3 drained = mix(chilled, vec3(baseLuma) * {F(u(2, 0.8, 0.92))}, core * clamp(strength, 0.0, 1.0) * {F(u(3, 0.5, 0.7))});",
        f"float rim = smoothstep({F(u(4, 0.34, 0.42))}, {F(u(5, 0.7, 0.85))}, centerDist);",
        f"float swell = 1.0 - {F(u(6, 0.06, 0.11))} * (0.5 + 0.5 * sin(anim * {F(u(7, 0.15, 0.3))} + ParamsB.x * 6.2831));",
        "vec3 dimmed = drained * swell;",
        f"vec3 outColor = mix(dimmed, Primary.rgb * {F(u(9, 0.06, 0.12))}, rim * clamp(strength, 0.0, 1.0) * {F(u(10, 0.55, 0.7))});",
    ], {"luma", "invsmooth"}


FAMILY_BODIES = {
    "tint": fam_tint,
    "wobble": fam_wobble,
    "vignette": fam_vignette,
    "chroma": fam_chroma,
    "pixelate": fam_pixelate,
    "desat": fam_desat,
    "bloomglow": fam_bloomglow,
    "ripple": fam_ripple,
    "scanlines": fam_scanlines,
    "edgeglow": fam_edgeglow,
    "frostlens": fam_frostlens,
    "heathaze": fam_heathaze,
    "posterize": fam_posterize,
    "radialblur": fam_radialblur,
    "glitch": fam_glitch,
    "duotone": fam_duotone,
    "kaleido": fam_kaleido,
    "huedrift": fam_huedrift,
    "dreamblur": fam_dreamblur,
    "moire": fam_moire,
    "spectral": fam_spectral,
    "aberration": fam_aberration,
    "underwater": fam_underwater,
    "thermal": fam_thermal,
    "sketch": fam_sketch,
    "starburst": fam_starburst,
    "vhs": fam_vhs,
    "gloom": fam_gloom,
}


def motion_lines(motion, u):
    """Animation prelude: defines `anim` (phase driver) and `animAmp` (strength mod)."""
    if motion == "steady":
        return [
            "// GameTime wraps once per day cycle (24000 ticks); scale to roughly seconds.",
            "float anim = GameTime * 1200.0 * ParamsA.x + ParamsB.x * 61.8;",
            "float animAmp = 1.0;",
        ]
    if motion == "pulse":
        return [
            "// GameTime wraps once per day cycle (24000 ticks); scale to roughly seconds.",
            "float anim = GameTime * 1200.0 * ParamsA.x + ParamsB.x * 61.8;",
            f"float animAmp = 0.8 + 0.2 * sin(anim * {F(u(60, 0.6, 1.2))} + ParamsB.x * 6.2831);",
        ]
    if motion == "drift":
        return [
            "// GameTime wraps once per day cycle (24000 ticks); scale to roughly seconds.",
            "float animRaw = GameTime * 1200.0 * ParamsA.x + ParamsB.x * 61.8;",
            f"float anim = animRaw + {F(u(60, 1.5, 3.0))} * sin(animRaw * {F(u(61, 0.1, 0.2))}) * ParamsB.y;",
            "float animAmp = 1.0;",
        ]
    # surge
    return [
        "// GameTime wraps once per day cycle (24000 ticks); scale to roughly seconds.",
        "float anim = GameTime * 1200.0 * ParamsA.x + ParamsB.x * 61.8;",
        f"float surge = 0.5 + 0.5 * sin(anim * {F(u(60, 0.35, 0.7))} + ParamsB.x * 3.1416);",
        "float animAmp = 0.7 + 0.3 * surge * surge * surge;",
    ]


def overlay_lines(overlay, u, rate_capped):
    """The shared overlays. rate_capped=True for the 840-flip ids (>= V5_FLIP_START):
    their paramA reaches ~10.4, so the paramA-scaled `anim` would tick the
    sparkle twinkle at 3-5 Hz and hard-refresh the grain at up to ~100 Hz --
    over the sub-3 Hz photosensitivity budget. Those ids instead run on an
    INDEPENDENT unit-rate clock (GameTime only, never anim, so neither Speed
    nor the drift boost can accelerate it) with a per-id baked rate whose
    worst case stays under ~2.5 Hz. Ids < V5_FLIP_START keep the frozen
    anim-driven form byte-for-byte."""
    if overlay == "none":
        return [], set()
    if overlay == "grain":
        if rate_capped:
            return [
                "// Overlay: living film grain. Photosensitivity: the refresh ticks on",
                "// an INDEPENDENT unit-rate clock (GameTime only, never the",
                "// paramA-scaled anim, which would hard-refresh at up to ~100 Hz",
                "// here); the baked per-id rate keeps every reroll under 2.5 Hz.",
                "// The frame counter wraps at 256 so the hash input stays",
                "// fp32-friendly across the whole GameTime day.",
                "float grainClock = GameTime * 1200.0 + ParamsB.x * 61.8;",
                f"float grainFrame = mod(floor(grainClock * {F(u(70, 1.5, 2.5))}), 256.0);",
                f"outColor += (hash21(floor(texCoord * safeInSize) + vec2(grainFrame, 0.0)) - 0.5) * {F(u(71, 0.02, 0.04))};",
            ], {"hash21"}
        return [
            "// Overlay: living film grain (frame counter wrapped at 256 so the",
            "// hash input stays fp32-friendly across the whole GameTime day).",
            f"float grainFrame = mod(floor(anim * {F(u(70, 5.0, 9.0))}), 256.0);",
            f"outColor += (hash21(floor(texCoord * safeInSize) + vec2(grainFrame, 0.0)) - 0.5) * {F(u(71, 0.02, 0.04))};",
        ], {"hash21"}
    if overlay == "sparkle":
        if rate_capped:
            return [
                "// Overlay: sparse twinkling motes. Photosensitivity: the twinkle",
                "// sine runs on an INDEPENDENT unit-rate clock (GameTime only, never",
                "// the paramA-scaled anim, which reaches ~3-5 Hz at these ids); the",
                "// baked per-id rate keeps every flash cycle under 2.4 Hz.",
                f"vec2 oCell = floor(texCoord * safeInSize / {F(u(70, 10.0, 18.0))});",
                "float oTw = hash21(oCell + vec2(37.0, 91.0));",
                "float oClock = GameTime * 1200.0 + ParamsB.x * 61.8;",
                f"float oTwinkle = smoothstep({F(u(71, 0.8, 0.88))}, 1.0, sin(oClock * {F(u(72, 6.0, 15.0))} + oTw * 6.2831) * 0.5 + 0.5) * step({F(u(73, 0.975, 0.99))}, oTw);",
                f"outColor += Secondary.rgb * oTwinkle * {F(u(74, 0.25, 0.4))};",
            ], {"hash21"}
        return [
            "// Overlay: sparse twinkling motes.",
            f"vec2 oCell = floor(texCoord * safeInSize / {F(u(70, 10.0, 18.0))});",
            "float oTw = hash21(oCell + vec2(37.0, 91.0));",
            f"float oTwinkle = smoothstep({F(u(71, 0.8, 0.88))}, 1.0, sin(anim * {F(u(72, 1.2, 2.4))} + oTw * 6.2831) * 0.5 + 0.5) * step({F(u(73, 0.975, 0.99))}, oTw);",
            f"outColor += Secondary.rgb * oTwinkle * {F(u(74, 0.25, 0.4))};",
        ], {"hash21"}
    # pulseglow
    return [
        "// Overlay: a faint breathing glow of the effect color at the rim.",
        f"float oBreath = 0.5 + 0.5 * sin(anim * {F(u(70, 0.5, 1.0))} + ParamsB.x * 6.2831);",
        f"float oRim = smoothstep({F(u(71, 0.35, 0.5))}, 1.0, centerDist);",
        f"outColor += Primary.rgb * oRim * oBreath * {F(u(72, 0.08, 0.16))};",
    ], set()


def emit_shader(asg: dict) -> str:
    global _RATE_CAPPED
    effect_id = asg["id"]
    rate_capped = effect_id >= V5_FLIP_START
    _RATE_CAPPED = rate_capped
    rng = Rng(asg["seed"])
    draws = [rng.unit() for _ in range(96)]

    def u(i, lo, hi):
        return lo + (hi - lo) * draws[i]

    body_lines, body_helpers = FAMILY_BODIES[asg["family"]](asg["variant"], u)
    ov_lines, ov_helpers = overlay_lines(asg["overlay"], u, rate_capped)
    helper_names = resolve_helpers({"luma"} | body_helpers | ov_helpers)

    lines = []
    lines.append("#version 330")
    lines.append("")
    lines.append("#moj_import <minecraft:globals.glsl>")
    lines.append("")
    lines.append(f"// GENERATED FILE -- do not edit by hand. Emitted by tools/gen_screen_shaders.py")
    lines.append(f"// for effect {effect_id:03d}. Edit the generator and regenerate instead")
    lines.append("// (byte-stable, fixed seed).")
    lines.append(f"// [screen:{asg['family']}:{asg['variant']}:{asg['motion']}:{asg['overlay']}]")
    lines.append("")
    lines.append("uniform sampler2D InSampler;")
    lines.append("")
    lines.append("in vec2 texCoord;")
    lines.append("")
    lines.append("layout(std140) uniform SamplerInfo {")
    lines.append("    vec2 OutSize;")
    lines.append("    vec2 InSize;")
    lines.append("};")
    lines.append("")
    lines.append("// Standardized per-effect config; member order is load-bearing (it must match")
    lines.append("// the uniform order in post_effect/effect_NN.json -- checked by the validator).")
    lines.append("// ParamsA = [Speed, Strength, Scale, Aux]; ParamsB = [Phase, Drift, TintMix, LumaFloor].")
    lines.append("layout(std140) uniform FxConfig {")
    lines.append("    vec4 Primary;")
    lines.append("    vec4 Secondary;")
    lines.append("    vec4 ParamsA;")
    lines.append("    vec4 ParamsB;")
    lines.append("};")
    lines.append("")
    lines.append("out vec4 fragColor;")
    lines.append("")
    for hn in helper_names:
        lines.append(HELPERS[hn])
        lines.append("")
    lines.append("void main() {")
    lines.append("    // Undisplaced scene sample: the gameplay-safety floor references this.")
    lines.append("    vec3 base = texture(InSampler, texCoord).rgb;")
    lines.append("    float baseLuma = luma(base);")
    lines.append("    // InSize is driver-fed; guard it so no divide below can hit zero.")
    lines.append("    vec2 safeInSize = max(InSize, vec2(1.0));")
    lines.append("    vec2 centered = texCoord - vec2(0.5);")
    lines.append("    vec2 aspectCentered = centered * vec2(safeInSize.x / safeInSize.y, 1.0);")
    lines.append("    float centerDist = length(aspectCentered);")
    for ln in motion_lines(asg["motion"], u):
        lines.append("    " + ln)
    lines.append("    float strength = ParamsA.y * animAmp;")
    lines.append("")
    for ln in body_lines:
        lines.append("    " + ln)
    if ov_lines:
        lines.append("")
        for ln in ov_lines:
            lines.append("    " + ln)
    lines.append("")
    lines.append("    // Richness pass (v3): a bounded soft-contrast curve plus a vibrance")
    lines.append("    // lift deepen the effect's read (anti-washout). Both are bounded and")
    lines.append("    // hue-preserving, and the luma floor below still guarantees the world")
    lines.append("    // stays readable.")
    lines.append("    vec3 curved = clamp(outColor, 0.0, 1.0);")
    lines.append(f"    outColor = mix(outColor, curved * curved * (3.0 - 2.0 * curved), {F(u(90, 0.10, 0.22))});")
    lines.append(f"    outColor = clamp(mix(vec3(luma(outColor)), outColor, {F(u(91, 1.06, 1.22))}), 0.0, 1.5);")
    lines.append("")
    lines.append("    // Gameplay-safety floor: never crush the world below ParamsB.w (~0.35x),")
    lines.append("    // and always output an opaque frame.")
    lines.append("    outColor = max(outColor, base * ParamsB.w);")
    lines.append("    fragColor = vec4(outColor, 1.0);")
    lines.append("}")

    source = "\n".join(lines) + "\n"
    n = source.count("\n")
    if not 40 <= n <= 200:
        sys.exit(f"sfx_{effect_id:03d}: emitted {n} lines, outside the 40..200 sanity bounds")
    return source


def manifest_entry(asg: dict, source: str) -> dict:
    return {
        "file": f"sfx_{asg['id']:03d}.fsh",
        "family": asg["family"],
        "variant": asg["variant"],
        "motion": asg["motion"],
        "overlay": asg["overlay"],
        "seed": f"{asg['seed']:016x}",
        "lines": source.count("\n"),
    }


def parse_only(spec: str) -> list:
    ids = set()
    for part in spec.split(","):
        part = part.strip()
        if "-" in part:
            lo, hi = part.split("-", 1)
            lo, hi = int(lo), int(hi)
            if lo > hi:
                sys.exit(f"--only range '{part}' is reversed (expected lo-hi with lo <= hi)")
            ids.update(range(lo, hi + 1))
        else:
            ids.add(int(part))
    bad = [i for i in ids if not 0 <= i < COUNT]
    if bad:
        sys.exit(f"--only ids out of range 0..{COUNT - 1}: {sorted(bad)}")
    if not ids:
        sys.exit("--only selected no ids")
    return sorted(ids)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--only", help="comma/range list of effect ids to emit (e.g. '0-15,42'); "
                                       "default: all 420. Emitted bytes are identical to a full run.")
    parser.add_argument("--out", help="output directory override (default: the repo screenfx dir); "
                                      "the manifest is then written next to the shaders instead of "
                                      "tools/ + the classpath copy.")
    args = parser.parse_args()

    ids = set(parse_only(args.only)) if args.only else set(range(COUNT))
    out_dir = Path(args.out) if args.out else SCREENFX_DIR
    out_dir.mkdir(parents=True, exist_ok=True)

    assignments = build_assignments()  # always the full table: bytes never depend on --only
    # The manifest is ALWAYS the full COUNT-entry table -- only the FILE writes
    # are restricted by --only. (Writing a subset manifest used to clobber the
    # committed full manifest AND its classpath copy on partial runs, which
    # then failed validation and the screenTemplateMatchesJson gametest.)
    manifest = {}
    written = 0
    # All writes are UTF-8 + LF explicitly: byte-stable output across platforms
    # (Windows' default newline translation would flip every file to CRLF and
    # break the byte-identical classpath-manifest check).
    for asg in assignments:
        source = emit_shader(asg)
        manifest[str(asg["id"])] = manifest_entry(asg, source)
        if asg["id"] in ids:
            (out_dir / f"sfx_{asg['id']:03d}.fsh").write_text(source, encoding="utf-8", newline="\n")
            written += 1

    manifest_text = json.dumps(manifest, indent=2, sort_keys=True) + "\n"
    if args.out:
        (out_dir / "screen_manifest.json").write_text(manifest_text, encoding="utf-8", newline="\n")
        manifest_paths = [out_dir / "screen_manifest.json"]
    else:
        DEFAULT_MANIFEST.write_text(manifest_text, encoding="utf-8", newline="\n")
        CLASSPATH_MANIFEST.write_text(manifest_text, encoding="utf-8", newline="\n")
        manifest_paths = [DEFAULT_MANIFEST, CLASSPATH_MANIFEST]

    line_counts = [e["lines"] for e in manifest.values()]
    print(f"wrote {written} shaders to {out_dir}")
    for p in manifest_paths:
        print(f"manifest: {p} ({len(manifest)} entries, always the full table)")
    print(f"line counts: min {min(line_counts)}, max {max(line_counts)}")
    if args.only:
        print(f"NOTE: partial run ({args.only}); only {written} files were (re)written, "
              f"but the manifest still covers all {COUNT} ids.")


if __name__ == "__main__":
    main()

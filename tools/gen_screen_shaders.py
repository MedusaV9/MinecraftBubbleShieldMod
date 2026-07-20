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

* 20 screen technique families -- the 16 original template looks refactored as
  parameterized modules (tint, wobble, vignette, chroma, pixelate, desat,
  bloomglow, ripple, scanlines, edgeglow, frostlens, heathaze, posterize,
  radialblur, glitch, duotone) + 4 new ones (kaleido refraction, huedrift,
  dreamblur + sparkle, moire interference). Each id's family comes from
  EffectRegistry.java (parsed at generation time as ground truth); the
  structural stack (family, variant, motion, overlay) is chosen by
  deterministic probing so all 420 stacks are pairwise distinct.

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

COUNT = 420
GLOBAL_SEED = 0x5C4EE7F1
MAX_UV_OFFSET = 0.02  # gameplay-safety bound for any scene-sample displacement

FAMILIES = [
    "tint", "wobble", "vignette", "chroma", "pixelate", "desat",
    "bloomglow", "ripple", "scanlines", "edgeglow", "frostlens", "heathaze",
    "posterize", "radialblur", "glitch", "duotone",
    "kaleido", "huedrift", "dreamblur", "moire",
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
    if v == "horizontal":
        return [
            "// CRT rows: per-row sync jitter plus rolling dark scanlines.",
            "// The frame counter wraps at 1024 so the hash input stays small enough",
            "// for fp32 (an unbounded counter would freeze the jitter within minutes).",
            "float row = floor(texCoord.y * ParamsA.z);",
            "float frame = mod(floor(anim * 8.0), 1024.0);",
            f"float jitter = (hash11(row + frame * 91.7) - 0.5) * {F(u(0, 0.002, 0.0035))} * ParamsA.y;",
            "vec3 scene = sampleAt(texCoord + safeOffset(vec2(jitter, 0.0)));",
            f"float scan = 0.5 + 0.5 * sin((texCoord.y * ParamsA.z - anim * {F(u(1, 0.4, 0.7))}) * 6.2831);",
            f"float darken = 1.0 - ParamsA.y * {F(u(2, 0.3, 0.4))} * scan * animAmp;",
            "vec3 outColor = mix(scene * darken, scene * darken * Primary.rgb, ParamsB.z);",
        ], {"hash11", "safeOffset", "sampleAt"}
    if v == "vertical":
        return [
            "// Vertical raster columns with per-column shimmer.",
            "// Frame counter wrapped at 1024: keeps the hash input fp32-friendly.",
            "float col = floor(texCoord.x * ParamsA.z);",
            "float frame = mod(floor(anim * 6.0), 1024.0);",
            f"float jitter = (hash11(col + frame * 47.3) - 0.5) * {F(u(0, 0.002, 0.0035))} * ParamsA.y;",
            "vec3 scene = sampleAt(texCoord + safeOffset(vec2(0.0, jitter)));",
            f"float scan = 0.5 + 0.5 * sin((texCoord.x * ParamsA.z + anim * {F(u(1, 0.3, 0.6))}) * 6.2831);",
            f"float darken = 1.0 - ParamsA.y * {F(u(2, 0.28, 0.38))} * scan * animAmp;",
            "vec3 outColor = mix(scene * darken, scene * darken * Primary.rgb, ParamsB.z);",
        ], {"hash11", "safeOffset", "sampleAt"}
    if v == "rolling":
        return [
            "// A bright readout band rolls down the raster.",
            f"float scan = 0.5 + 0.5 * sin(texCoord.y * ParamsA.z * 6.2831 - anim * {F(u(0, 2.0, 3.5))});",
            f"float bandPos = fract(anim * {F(u(1, 0.05, 0.11))});",
            f"float roll = invsmooth(0.0, {F(u(2, 0.06, 0.12))}, abs(texCoord.y - bandPos));",
            f"float darken = 1.0 - ParamsA.y * {F(u(3, 0.25, 0.35))} * scan * animAmp;",
            "vec3 lined = base * darken + Primary.rgb * roll * ParamsA.y * 0.25;",
            "vec3 outColor = mix(lined, lined * Primary.rgb, ParamsB.z);",
        ], {"invsmooth"}
    # grid
    return [
        "// Faint raster grid: both axes carry drifting line sets.",
        f"float scanY = 0.5 + 0.5 * sin((texCoord.y * ParamsA.z - anim * {F(u(0, 0.3, 0.6))}) * 6.2831);",
        f"float scanX = 0.5 + 0.5 * sin((texCoord.x * ParamsA.z * {F(u(1, 0.6, 0.9))} + anim * {F(u(2, 0.2, 0.5))}) * 6.2831);",
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
    ]
    if v == "sobel":
        return ["// 3x3 Sobel over scene luma; edges glow in the primary color."] + sobel + [
            "vec3 outColor = base + Primary.rgb * edge * strength;",
        ], {"lumaAt", "safeOffset"}
    if v == "pulse":
        return ["// Sobel edges whose glow breathes on GameTime."] + sobel + [
            f"float breath = {F(u(0, 0.55, 0.7))} + {F(u(1, 0.3, 0.45))} * sin(anim * {F(u(2, 1.2, 2.2))} + ParamsB.x * 6.2831);",
            "vec3 outColor = base + Primary.rgb * edge * strength * breath;",
        ], {"lumaAt", "safeOffset"}
    if v == "duo":
        return ["// Direction-split edges: horizontal gradients glow Primary, vertical Secondary."] + sobel + [
            "vec3 glowColor = mix(Secondary.rgb, Primary.rgb, clamp(0.5 + 0.5 * (abs(gx) - abs(gy)) * 2.0, 0.0, 1.0));",
            "vec3 outColor = base + glowColor * edge * strength;",
        ], {"lumaAt", "safeOffset"}
    # thick
    return ["// Wide-radius Sobel: thick painterly outlines."] + sobel + [
        f"vec3 outColor = base + Primary.rgb * pow(edge, {F(u(0, 0.6, 0.8))}) * strength * {F(u(1, 0.75, 0.9))};",
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
        "vec3 outColor = mix(scene, frosted, frost);",
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
            "// A thin full-pane rime sheet, heaviest in the corners.",
            f"float crystals = vnoise(texCoord * ParamsA.z) * 0.55 + vnoise(texCoord * ParamsA.z * 2.7) * 0.45;",
            "vec2 corner = abs(centered) * 2.0;",
            f"float frost = clamp({F(u(0, 0.18, 0.28))} + {F(u(1, 0.45, 0.6))} * max(corner.x, corner.y) * crystals, 0.0, 1.0) * strength;",
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
    split_tail = [
        "float red = sampleAt(texCoord + safeOffset(baseOff + vec2(split, 0.0))).r;",
        "float green = sampleAt(texCoord + safeOffset(baseOff)).g;",
        "float blue = sampleAt(texCoord + safeOffset(baseOff - vec2(split, 0.0))).b;",
        "vec3 torn = vec3(red, green, blue);",
    ]
    if v == "tear":
        return [
            "// Scanband tears: a few rows shear sideways each glitch frame.",
            "// The frame counter wraps at 1024 so the hash input stays small enough",
            "// for fp32 (an unbounded counter would freeze the glitch within minutes).",
            f"float frame = mod(floor(anim * {F(u(0, 5.0, 8.0))}), 1024.0);",
            f"float band = floor(texCoord.y * {F(u(1, 18.0, 30.0))});",
            "float tearRoll = hash11(band * 7.31 + frame * 13.7);",
            f"float tear = (tearRoll > 0.85 ? (tearRoll - 0.85) / 0.15 - 0.5 : 0.0) * {F(u(2, 0.05, 0.08))} * ParamsA.y * animAmp;",
            "vec2 baseOff = vec2(tear, 0.0);",
            "float split = (0.004 + abs(tear) * 0.5) * ParamsA.y;",
        ] + split_tail + [
            "float flash = abs(tear) > 0.0001 ? ParamsB.z : 0.0;",
            "vec3 outColor = mix(torn, torn * Primary.rgb, flash);",
        ], {"hash11", "safeOffset", "sampleAt"}
    if v == "blocks":
        return [
            "// Coarse block dropouts: cells occasionally displace as a chunk.",
            "// Frame counter wrapped at 1024: keeps the hash input fp32-friendly.",
            f"float frame = mod(floor(anim * {F(u(0, 4.0, 7.0))}), 1024.0);",
            f"vec2 cell = floor(texCoord * vec2({F(u(1, 6.0, 10.0))}, {F(u(2, 4.0, 8.0))}));",
            "float cellRoll = hash11(cell.x * 3.7 + cell.y * 11.9 + frame * 5.3);",
            f"vec2 jitter = cellRoll > {F(u(3, 0.88, 0.93))}",
            f"    ? (vec2(hash11(cellRoll * 91.7), hash11(cellRoll * 47.3)) - 0.5) * {F(u(4, 0.025, 0.04))} * ParamsA.y",
            "    : vec2(0.0);",
            "vec2 baseOff = jitter;",
            "float split = 0.003 * ParamsA.y;",
        ] + split_tail + [
            "float flash = length(jitter) > 0.0001 ? ParamsB.z * 1.2 : 0.0;",
            "vec3 outColor = mix(torn, mix(torn * Primary.rgb, torn * Secondary.rgb, hash11(cellRoll * 3.1)), clamp(flash, 0.0, 1.0));",
        ], {"hash11", "safeOffset", "sampleAt"}
    if v == "rgbdrift":
        return [
            "// The color channels wander apart and snap back on glitch frames.",
            "// Frame counter wrapped at 1024: keeps the hash input fp32-friendly.",
            f"float frame = mod(floor(anim * {F(u(0, 3.0, 5.0))}), 1024.0);",
            "float wander = hash11(frame * 17.3) - 0.5;",
            f"float split = (0.004 + abs(wander) * {F(u(1, 0.01, 0.018))}) * ParamsA.y * animAmp;",
            "vec2 baseOff = vec2(0.0);",
        ] + split_tail + [
            f"float microTear = step({F(u(2, 0.96, 0.985))}, hash11(floor(texCoord.y * 90.0) + frame)) * ParamsA.y;",
            "vec3 outColor = mix(torn, torn * Primary.rgb, microTear * ParamsB.z * 2.0);",
        ], {"hash11", "safeOffset", "sampleAt"}
    # rowcol
    return [
        "// Row tears and column jitters interleave on alternating frames.",
        "// Frame counter wrapped at 1024: keeps the hash input fp32-friendly.",
        f"float frame = mod(floor(anim * {F(u(0, 5.0, 8.0))}), 1024.0);",
        f"float rowRoll = hash11(floor(texCoord.y * {F(u(1, 20.0, 32.0))}) * 7.31 + frame * 13.7);",
        f"float colRoll = hash11(floor(texCoord.x * {F(u(2, 14.0, 24.0))}) * 5.13 + frame * 7.9);",
        f"float tearX = (rowRoll > 0.88 ? rowRoll - 0.88 : 0.0) * {F(u(3, 0.3, 0.5))} * ParamsA.y * animAmp;",
        f"float tearY = (colRoll > 0.9 ? colRoll - 0.9 : 0.0) * {F(u(4, 0.2, 0.4))} * ParamsA.y * animAmp;",
        "vec2 baseOff = vec2(tearX, tearY);",
        "float split = (0.003 + (tearX + tearY) * 0.3) * ParamsA.y;",
    ] + split_tail + [
        "float flash = (tearX + tearY) > 0.0001 ? ParamsB.z : 0.0;",
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


def overlay_lines(overlay, u):
    if overlay == "none":
        return [], set()
    if overlay == "grain":
        return [
            "// Overlay: living film grain (frame counter wrapped at 256 so the",
            "// hash input stays fp32-friendly across the whole GameTime day).",
            f"float grainFrame = mod(floor(anim * {F(u(70, 5.0, 9.0))}), 256.0);",
            f"outColor += (hash21(floor(texCoord * safeInSize) + vec2(grainFrame, 0.0)) - 0.5) * {F(u(71, 0.02, 0.04))};",
        ], {"hash21"}
    if overlay == "sparkle":
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
    effect_id = asg["id"]
    rng = Rng(asg["seed"])
    draws = [rng.unit() for _ in range(96)]

    def u(i, lo, hi):
        return lo + (hi - lo) * draws[i]

    body_lines, body_helpers = FAMILY_BODIES[asg["family"]](asg["variant"], u)
    ov_lines, ov_helpers = overlay_lines(asg["overlay"], u)
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
    for asg in assignments:
        source = emit_shader(asg)
        manifest[str(asg["id"])] = manifest_entry(asg, source)
        if asg["id"] in ids:
            (out_dir / f"sfx_{asg['id']:03d}.fsh").write_text(source)
            written += 1

    manifest_text = json.dumps(manifest, indent=2, sort_keys=True) + "\n"
    if args.out:
        (out_dir / "screen_manifest.json").write_text(manifest_text)
        manifest_paths = [out_dir / "screen_manifest.json"]
    else:
        DEFAULT_MANIFEST.write_text(manifest_text)
        CLASSPATH_MANIFEST.write_text(manifest_text)
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

#!/usr/bin/env python3
"""Deterministic generator for the per-effect bubble surface shaders (fx_000..fx_349).

Running `python3 tools/gen_surface_shaders.py` (re)writes ALL of
src/client/resources/assets/bubbleshield/shaders/bubble/fx_000.fsh .. fx_349.fsh
plus tools/surface_manifest.json. Regeneration is byte-stable: a fixed global
seed feeds a self-contained splitmix64 PRNG (no reliance on Python's `random`
module internals), iteration is in sorted id order, and floats are formatted
with a fixed precision -- so diffs stay reviewable.

Design (see /tmp/shader_plan.md sections 1, 2, 4.1 and AGENTS.md):

* Frozen fragment contract: `#version 330`; only `#moj_import <minecraft:fog.glsl>`
  and `<minecraft:globals.glsl>`; inputs texCoord0 (RAW [0,1] sphere UV),
  vertexColor (rgb = palette = dominant chroma, a = dissolve; the final alpha
  is vertexColor.a * clamp(a0 + a1 * pattern, 0, 1), so the dissolve always
  wins), sphericalVertexDistance, cylindricalVertexDistance; out fragColor;
  animation ONLY via `float time = GameTime * 1200.0;`; NO custom uniforms,
  NO textures; `discard` when alpha < 0.01; the last statement applies
  apply_fog exactly like the 16 original hand-written seed shaders did.

* Portability/robustness rules baked into every emitted file:
  - `invsmooth(lo, hi, x)` (== 1 - smoothstep(lo, hi, x)) replaces every
    reversed-edge smoothstep(hi, lo, x) call: edge0 >= edge1 is undefined by
    the GLSL spec, and invsmooth is numerically identical on conforming
    drivers.
  - `safeAtan(y, x)` guards the exact-origin case of two-argument atan
    (spiral/kaleido/petal polar frames).

* Seam-safe periodic sampling (the u = 0/1 longitude wrap): fract(texCoord0)
  alone does NOT make hash-noise fields periodic in u, so V1 files showed a
  vertical seam. Every noise/lattice field is now sampled on a wrapping
  lattice: the u axis is scaled by an INTEGER cell count and vnoise / voronoi /
  voronoise / cell hashes wrap their lattice ids with mod(id, period)
  (`cellHash`). fbm2 uses an exact x-lacunarity of 2 with a doubling period
  (per-octave rotation was dropped -- it would mix latitude into longitude and
  break the wrap; the y lacunarity stays a free float for variety). Center-
  based polar families (kaleido/petals/vortex spiral/interference/ringPulse)
  instead build their domain from sin/cos of whole longitude turns or a
  chordal x-distance, which is periodic by construction (noise fed with an
  already-periodic domain needs no lattice wrap and uses a large 64-cell
  no-op period). Sinusoidal gratings (moire/waves/vortex bands) use integer
  longitude harmonics (baseUV.x * 2pi * m). The "rotate" anim mode is emitted
  as a seam-safe shear sway (true domain rotation would break the wrap), and
  "pulse" breathes the y axis only.

* Day-wrap animation continuity: time wraps 1200 -> 0 once per Minecraft day,
  which used to snap every scroll/sin phase. All constant sin/cos speeds are
  quantized so k * 1200 is an integer multiple of 2*pi (quant_sin_speed);
  lattice scroll speeds are quantized so a full day shifts the (wrapping)
  pattern by an integer number of lattice periods (quant_drift); fract-phase
  speeds complete integer cycles per day (quant_fract_speed). Hash-gated
  flicker terms (per-cell twinkle phases, strobe gates, grain reseeds) simply
  re-roll at the wrap, which is indistinguishable from their normal behavior.

* Real depth (v3): the DEEP layer is 3..4 CORRELATED parallax PLANES of ONE
  deep field sampled on the 3D sphere direction. Each plane obeys the real
  parallax law -- farther planes show finer features (per-plane scale
  1 + i*g), move slower (strictly decreasing integer turns/day rotation
  speeds), shift along the silhouette slope (rimDir, from screen-space
  derivatives of the camera-distance varying -- seam-safe by construction),
  and recede toward the palette's dark stop (aerial-perspective per-plane
  tint accumulated into a vec3). The deep volume is then composited UNDER
  the MID signature (rgb = mix(deepCol, structure, midWeight)) instead of
  being a scalar folded into `pattern`, while a scalar copy still feeds the
  alpha path. The rim estimator normalizes fwidth(sphericalVertexDistance)
  by the distance itself (view-consistent width) and is split into TWO
  bands: a wide soft inner glow plus a thin hot line at the very edge, with
  a bounded two-band thin-film RGB dispersion multiplied into the
  palette-driven rgb (recolor-safe).

* 3D sphere-direction domain (v3): `sdir` is reconstructed from texCoord0
  (u -> longitude angle, v -> latitude angle), so any field sampled on it is
  periodic across the u = 0/1 seam AND uniform at the poles by construction
  -- no lattice wrap needed. hash31/hash33/vnoise3/fbm3/voro3/caustic3
  sample this domain. The whole DEEP volume lives there, as does the MID
  layer of the unstructured-noise families (FAMILIES_3D_MID: PLASMA, ARCS,
  LIGHTNING, CURLSMOKE, RIDGED, NEBULA), which also gain TRUE 3D rotation:
  rotA() spins the domain around a baked unit axis at an integer number of
  turns per day (day-wrap safe; linear lattice drift is NOT day-safe on an
  unwrapped 3D lattice, so 3D animation uses rotation + quantized sin sway
  only). Lattice/polar families keep their proven seam-safe 2D domains.

* Rich color (v3): the flat multiplicative composite (vertexColor.rgb *
  (b0 + b1 * pattern)) washed out to grey. Every shader now grades the
  pattern through a runtime 3-stop gradient derived from vertexColor.rgb:
  deepStop = base*base*dk (darker AND more saturated, stays in-hue),
  hotStop = screen-blend toward white of a hue-rotated base (baked angle
  within +-40 deg; Rodrigues rotation around the grey axis computed at
  runtime from vertexColor -- recolor-safe by construction), two-smoothstep
  3-stop mapping with a baked split point, then a luma-mix saturation lift
  (baked 1.05..1.35) and an additive hotStop highlight on the brightest
  pattern areas. Low-pattern areas fall to the dark stop + low alpha
  (darker/transparent, never pale grey). The gradient lookup is offset by
  rim * rk so silhouettes shift toward the hot stop (chromatic rim). The
  owner /color override replaces vertexColor wholesale, so the whole
  gradient re-derives from it: recolor safety is preserved. Every file
  carries the `// [palette:gradient3]` marker.

* Stronger presence (v3): final alpha = vertexColor.a * min(aBase + aPresence
  * smoothstep(0.02, 0.30, pattern) + aGain * pattern, aMax) -- a floor of
  ~0.28..0.45 wherever pattern is present rising toward ~0.85 on bright
  features, so an active bubble reads as a solid-but-translucent membrane.
  The vertexColor.a dissolve near whitelisted players still always wins,
  and discard stays at < 0.01.

* Helper snippet bank (inlined per file, ONLY the helpers a shader uses):
  invsmooth/safeAtan/hash11/hash21/hash22/cellHash, vnoise (quintic fade,
  wrapping), fbm2 (modes standard/ridged/turb, <=6 octaves), warp1/warp2 (iq
  domain warp), curl2, voro2 (F1/F2/exact-border/cell-id, wrapping),
  voronoise, hexDist/hexCoords, triGrid, truchet, polarFold, spiralWarp,
  caustic, thinFilm, accentPalette (iq cosine, baked consts), rimGraze,
  rimLat, sparkle, ringPulse, and the unrolled correlated parallax deep stack.

* 24 MID-layer technique composers keyed by the 24 SurfaceTemplate families:
  the 16 existing enum names (PLASMA..LIGHTNING) + THINFILM, CAUSTIC,
  CURLSMOKE, TRUCHET, RIDGED, MOIRE, TRIWEAVE, NEBULA.

* Per-id assignment table: ids 0..104 seed their family from the current
  EffectRegistry.java surface column (parsed at generation time as ground
  truth); ids 105..349 cycle all 24 families per 5-id color block. The
  accentPalette base color is sourced from the registry's argbPrimary for
  EVERY id the registry knows (not random), so accents always lean toward the
  effect's authored palette. Every id gets a distinct (family, warpMode,
  deepStack, rimStyle, animMode) tuple, guaranteed by deterministic probing
  and re-asserted here and by tools/validate_shaders.py.

* Structural depth rule: every file carries the three marker comments
  `// [layer:deep:<mod>]`, `// [layer:mid:<mod>]`, `// [layer:rim:<mod>]`
  (machine-checked by the validator) plus a small "flourish" accent layer and
  a micro-grain detail pass.

* Recolor safety: pattern layers multiply vertexColor.rgb; cosine-palette /
  thin-film accents contribute only through a bounded mix biased toward
  vertexColor.rgb (weight <= 0.45); the final alpha is
  vertexColor.a * clamp(a0 + a1 * pattern, 0, 1).

* Compile safety: conservative GLSL 330 subset only -- const-bounded for
  loops (fbm <= 6 octaves, parallax <= 4 taps, voronoi 3x3/5x5), no while, no
  switch, no texture(), no arrays-of-structs, no uniforms beyond the two
  vanilla imports, explicit float literals, every function defined before use.

Usage:
    python3 tools/gen_surface_shaders.py                  # all 350 + manifest
    python3 tools/gen_surface_shaders.py --only 0-15      # subset (same bytes)
    python3 tools/gen_surface_shaders.py --only 0-15 --out /tmp/probe
"""

import argparse
import json
import math
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
BUBBLE_DIR = REPO_ROOT / "src/client/resources/assets/bubbleshield/shaders/bubble"
REGISTRY_JAVA = REPO_ROOT / "src/main/java/com/bubbleshield/effect/EffectRegistry.java"
DEFAULT_MANIFEST = REPO_ROOT / "tools/surface_manifest.json"

COUNT = 350
GLOBAL_SEED = 0xB0BB7E5D

# The 16 existing SurfaceTemplate enum names (enum order) + the 8 planned ones.
FAMILIES = [
    "PLASMA", "HEX", "WAVES", "AURORA", "SPARKLE", "RINGS", "VORONOI", "ARCS",
    "SCALES", "STARFIELD", "VORTEX", "INTERFERENCE", "KALEIDO", "CIRCUIT",
    "PETALS", "LIGHTNING",
    "THINFILM", "CAUSTIC", "CURLSMOKE", "TRUCHET", "RIDGED", "MOIRE",
    "TRIWEAVE", "NEBULA",
]

WARPS = ["none", "warp1", "warp2", "curl"]
DEEPS = ["fbm", "ridge", "caustic", "voro"]
RIMS = ["graze", "lat", "graze_film", "graze_sparkle"]
ANIMS = ["scroll", "rotate", "pulse", "flicker"]
FBM_MODES = ["standard", "ridged", "turb"]
FLOURISHES = ["swirl", "glint", "echo", "shimmer"]

# Families whose MID signature is unstructured noise: their primary field is
# sampled on the 3D sphere direction (fbm3), which kills both the longitude
# seam (for non-periodic fields) and the pole pinch, and lets the pattern
# genuinely rotate in 3D. Lattice/polar/directional families (AURORA's
# curtain drape included) keep their proven seam-safe 2D domains; every
# family's DEEP volume goes 3D regardless.
FAMILIES_3D_MID = frozenset({
    "PLASMA", "ARCS", "LIGHTNING", "CURLSMOKE", "RIDGED", "NEBULA",
})

MASK64 = (1 << 64) - 1

TWO_PI = 2.0 * math.pi
# `float time = GameTime * 1200.0` wraps 1200 -> 0 once per Minecraft day.
DAY_SECONDS = 1200.0
# Lattice period passed to fbm2 when its input domain is ALREADY an exactly
# periodic function of u (polar/chordal frames): big enough that the wrap is
# never reached by the domain itself, small enough that drift offsets can be
# day-quantized to it without freezing.
NOWRAP_PERIOD = 64.0


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


def F6(x: float) -> str:
    """Six-decimal literal for day-quantized speeds (keeps the residual daily
    phase error far below anything perceptible)."""
    return f"{x:.6f}"


def quant_sin_speed(k: float) -> float:
    """Snaps a sin/cos angular speed so it completes an integer number of
    cycles per day (k * 1200 becomes an integer multiple of 2*pi), so the
    daily time wrap 1200 -> 0 causes no phase pop."""
    m = max(1, round(k * DAY_SECONDS / TWO_PI))
    return m * TWO_PI / DAY_SECONDS


def quant_drift(k: float, period: float) -> float:
    """Snaps a lattice scroll speed so a full day shifts the pattern by an
    integer number of lattice periods; on the wrapping lattice that makes the
    daily time wrap invisible."""
    m = round(k * DAY_SECONDS / period)
    return m * period / DAY_SECONDS


def quant_fract_speed(k: float) -> float:
    """Snaps a fract(t * k) phase speed so it completes integer cycles per day."""
    m = max(1, round(k * DAY_SECONDS))
    return m / DAY_SECONDS


def parse_registry() -> dict:
    """Parses EffectRegistry.java rows: id -> (surfaceName, rgbPrimary, rgbSecondary).

    Accepts any dense catalogue of at least the 105 V1 rows: ids 0..104 seed
    their family/palette from the registry (frozen), while ids >= 105 get their
    family computed by build_assignments and only cross-checked against the
    registry (the milestone-D expansion rows were authored FROM this
    generator's manifest, so a mismatch means the two have drifted). Palettes
    are taken from the registry for EVERY id present in it, so the baked
    accentPalette always leans toward the effect's authored primary color.
    """
    text = REGISTRY_JAVA.read_text(encoding="utf-8")
    pattern = re.compile(
        r"row\((\d+),\s*0x([0-9A-Fa-f]{6}),\s*0x([0-9A-Fa-f]{6}),\s*\"([a-z]+)\"")
    rows = {}
    for m in pattern.finditer(text):
        rows[int(m.group(1))] = (m.group(4).upper(), int(m.group(2), 16), int(m.group(3), 16))
    if len(rows) < 105 or sorted(rows) != list(range(len(rows))):
        sys.exit(f"EffectRegistry.java parse failed: found {len(rows)} rows, expected dense ids 0..N-1 with N >= 105")
    return rows


def build_assignments() -> list:
    """Builds the full 350-row assignment table (always computed over ALL ids so
    partial --only runs emit byte-identical files to a full run)."""
    registry = parse_registry()
    rows = []
    used = set()
    for effect_id in range(COUNT):
        # Palette: sourced from the registry for every id it has (the baked
        # accentPalette must lean toward the effect's AUTHORED primary color,
        # not a random hue). Only ids the registry does not know yet fall back
        # to seeded draws (bootstrap case, before the registry was expanded).
        prim = registry[effect_id][1] if effect_id in registry else None
        if effect_id < 105:
            family = registry[effect_id][0]
        else:
            block = effect_id // 5
            slot = effect_id % 5
            family = FAMILIES[(5 * (block + slot)) % len(FAMILIES)]
            # Drift guard: the milestone-D registry rows were authored from this
            # generator's manifest, so the registry (when it already has this id)
            # must agree with the computed family.
            if effect_id in registry and registry[effect_id][0] != family:
                sys.exit(f"id {effect_id}: EffectRegistry surface family {registry[effect_id][0]} "
                         f"!= generator family {family} (registry and generator have drifted)")
        rng = Rng(mix_seed(effect_id, 1))
        w0 = rng.randint(0, 3)
        d0 = rng.randint(0, 3)
        r0 = rng.randint(0, 3)
        a0 = rng.randint(0, 3)
        chosen = None
        for k in range(256):
            warp = WARPS[(w0 + k // 64) % 4]
            deep = DEEPS[(d0 + (k // 16) % 4) % 4]
            rim = RIMS[(r0 + (k // 4) % 4) % 4]
            anim = ANIMS[(a0 + k) % 4]
            tup = (family, warp, deep, rim, anim)
            if tup not in used:
                chosen = tup
                break
        if chosen is None:
            sys.exit(f"assignment probing exhausted for id {effect_id} (family {family})")
        used.add(chosen)
        rows.append({
            "id": effect_id,
            "family": chosen[0],
            "warp": chosen[1],
            "deep": chosen[2],
            "rim": chosen[3],
            "anim": chosen[4],
            "primary": prim,
            "seed": mix_seed(effect_id, 2),
        })
    assert len(used) == COUNT, "assignment tuples are not pairwise distinct"
    return rows


# ---------------------------------------------------------------------------
# Helper snippet bank. Each builder returns GLSL source for one helper; deps
# are resolved through HELPER_DEPS and emitted in CANONICAL_ORDER so every
# function is defined before use.
# ---------------------------------------------------------------------------

HELPER_DEPS = {
    "invsmooth": [],
    "safeAtan": [],
    "rotA": [],
    "hash11": [],
    "hash21": [],
    "hash22": [],
    "hash31": [],
    "hash33": [],
    "cellHash": ["hash21"],
    "vnoise": ["hash21"],
    "vnoise3": ["hash31"],
    "fbm2": ["vnoise"],
    "fbm3": ["vnoise3"],
    "warp1": ["fbm2"],
    "warp2": ["fbm2", "warp1"],
    "curl2": ["vnoise"],
    "voro2": ["hash22", "hash21"],
    "voro3": ["hash33"],
    "voronoise": ["hash22", "hash21"],
    "hexDist": [],
    "hexCoords": ["hexDist"],
    "triGrid": ["cellHash"],
    "truchet": ["cellHash", "invsmooth"],
    "polarFold": ["safeAtan"],
    "spiralWarp": ["safeAtan"],
    "caustic": ["vnoise"],
    "caustic3": ["vnoise3"],
    "thinFilm": [],
    "accentPalette": [],
    "gradient3": [],
    "satLift": [],
    "hueSpin": [],
    "rimGraze": [],
    "rimLat": ["invsmooth"],
    "sparkle": ["cellHash", "invsmooth"],
    "ringPulse": ["hash22", "invsmooth"],
    "deepField": [],  # deps filled per deep recipe at emission time
}

CANONICAL_ORDER = [
    "invsmooth", "safeAtan", "rotA", "hash11", "hash21", "hash22", "hash31",
    "hash33", "cellHash", "vnoise", "vnoise3", "fbm2", "fbm3", "warp1",
    "warp2", "curl2", "voro2", "voro3", "voronoise", "hexDist", "hexCoords",
    "triGrid", "truchet", "polarFold", "spiralWarp", "caustic", "caustic3",
    "thinFilm", "accentPalette", "gradient3", "satLift", "hueSpin",
    "rimGraze", "rimLat", "sparkle", "ringPulse", "deepField",
]


def helper_source(name: str, c: dict) -> str:
    """Returns the GLSL source of one helper, with per-file consts baked in."""
    if name == "invsmooth":
        return (
            "// 1 - smoothstep with ASCENDING edges. Replaces every reversed-edge\n"
            "// smoothstep(hi, lo, x) call: edge0 >= edge1 is undefined by the GLSL\n"
            "// spec; this form is numerically identical on conforming drivers.\n"
            "float invsmooth(float lo, float hi, float x) {\n"
            "    return 1.0 - smoothstep(lo, hi, x);\n"
            "}")
    if name == "safeAtan":
        return (
            "// two-argument atan is undefined at the exact origin; guard it\n"
            "float safeAtan(float y, float x) {\n"
            "    return (abs(x) < 1e-6 && abs(y) < 1e-6) ? 0.0 : atan(y, x);\n"
            "}")
    if name == "rotA":
        return (
            "// Rodrigues rotation matrix around a baked unit axis. Driven with an\n"
            "// angle that completes an INTEGER number of turns per day, the daily\n"
            "// time wrap 1200 -> 0 lands exactly on a full turn -- the catalogue's\n"
            "// first true (non-shear) rotation animation, only legal on the 3D\n"
            "// sphere-direction domain (a 2D UV rotation would break the u wrap).\n"
            "mat3 rotA(vec3 axis, float a) {\n"
            "    float s = sin(a);\n"
            "    float c = cos(a);\n"
            "    float oc = 1.0 - c;\n"
            "    return mat3(\n"
            "        oc * axis.x * axis.x + c, oc * axis.x * axis.y + axis.z * s, oc * axis.z * axis.x - axis.y * s,\n"
            "        oc * axis.x * axis.y - axis.z * s, oc * axis.y * axis.y + c, oc * axis.y * axis.z + axis.x * s,\n"
            "        oc * axis.z * axis.x + axis.y * s, oc * axis.y * axis.z - axis.x * s, oc * axis.z * axis.z + c);\n"
            "}")
    if name == "hash11":
        return (
            "float hash11(float p) {\n"
            "    p = fract(p * 0.1031);\n"
            "    p *= p + 33.33;\n"
            "    p *= p + p;\n"
            "    return fract(p);\n"
            "}")
    if name == "hash21":
        return (
            "float hash21(vec2 p) {\n"
            "    vec3 p3 = fract(vec3(p.xyx) * 0.1031);\n"
            "    p3 += dot(p3, p3.yzx + 33.33);\n"
            "    return fract((p3.x + p3.y) * p3.z);\n"
            "}")
    if name == "hash22":
        return (
            "vec2 hash22(vec2 p) {\n"
            "    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));\n"
            "    p3 += dot(p3, p3.yzx + 33.33);\n"
            "    return fract((p3.xx + p3.yz) * p3.zy);\n"
            "}")
    if name == "hash31":
        return (
            "// Hoskins-style 3D->1D hash; small multiplier keeps fp32 precision\n"
            "// alive over the whole sphere-direction domain\n"
            "float hash31(vec3 p3) {\n"
            "    p3 = fract(p3 * 0.1031);\n"
            "    p3 += dot(p3, p3.zyx + 31.32);\n"
            "    return fract((p3.x + p3.y) * p3.z);\n"
            "}")
    if name == "hash33":
        return (
            "vec3 hash33(vec3 p3) {\n"
            "    p3 = fract(p3 * vec3(0.1031, 0.1030, 0.0973));\n"
            "    p3 += dot(p3, p3.yxz + 33.33);\n"
            "    return fract((p3.xxy + p3.yxx) * p3.zyx);\n"
            "}")
    if name == "cellHash":
        return (
            "// lattice-cell hash that tiles across the longitude seam: the cell's\n"
            "// x id wraps every px cells, so u = 0 and u = 1 sample identical cells\n"
            "float cellHash(vec2 cellId, float px) {\n"
            "    return hash21(vec2(mod(cellId.x, px), cellId.y));\n"
            "}")
    if name == "vnoise":
        return (
            "// value noise on a wrapping lattice (quintic fade): 'per' tiles the\n"
            "// field so it is seamless across the u = 0/1 longitude wrap\n"
            "float vnoise(vec2 p, vec2 per) {\n"
            "    vec2 i = floor(p);\n"
            "    vec2 f = fract(p);\n"
            "    vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);\n"
            "    float a = hash21(mod(i, per));\n"
            "    float b = hash21(mod(i + vec2(1.0, 0.0), per));\n"
            "    float cc = hash21(mod(i + vec2(0.0, 1.0), per));\n"
            "    float d = hash21(mod(i + vec2(1.0, 1.0), per));\n"
            "    return mix(mix(a, b, u.x), mix(cc, d, u.x), u.y);\n"
            "}")
    if name == "vnoise3":
        return (
            "// 3D value noise (quintic fade). Sampled on the sphere direction the\n"
            "// domain is periodic across the u seam AND uniform at the poles by\n"
            "// construction, so unlike the 2D lattice it needs NO wrap period.\n"
            "float vnoise3(vec3 p) {\n"
            "    vec3 i = floor(p);\n"
            "    vec3 f = fract(p);\n"
            "    vec3 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);\n"
            "    float n000 = hash31(i);\n"
            "    float n100 = hash31(i + vec3(1.0, 0.0, 0.0));\n"
            "    float n010 = hash31(i + vec3(0.0, 1.0, 0.0));\n"
            "    float n110 = hash31(i + vec3(1.0, 1.0, 0.0));\n"
            "    float n001 = hash31(i + vec3(0.0, 0.0, 1.0));\n"
            "    float n101 = hash31(i + vec3(1.0, 0.0, 1.0));\n"
            "    float n011 = hash31(i + vec3(0.0, 1.0, 1.0));\n"
            "    float n111 = hash31(i + vec3(1.0, 1.0, 1.0));\n"
            "    return mix(mix(mix(n000, n100, u.x), mix(n010, n110, u.x), u.y),\n"
            "        mix(mix(n001, n101, u.x), mix(n011, n111, u.x), u.y), u.z);\n"
            "}")
    if name == "fbm2":
        mode = c["fbmMode"]
        if mode == "standard":
            tap = "value += amplitude * vnoise(p, per);"
        elif mode == "ridged":
            tap = "value += amplitude * (1.0 - abs(2.0 * vnoise(p, per) - 1.0));"
        else:  # turb
            tap = "value += amplitude * abs(2.0 * vnoise(p, per) - 1.0);"
        return (
            f"// fractal noise, {mode} mode, {c['octaves']} octaves. The x lacunarity\n"
            "// is exactly 2 and the lattice period doubles with it, so EVERY octave\n"
            "// tiles the longitude seam (a rotation here would break the wrap).\n"
            "float fbm2(vec2 p, vec2 per) {\n"
            "    float value = 0.0;\n"
            "    float amplitude = 0.5;\n"
            f"    for (int i = 0; i < {c['octaves']}; i++) {{\n"
            f"        {tap}\n"
            f"        p = vec2(p.x * 2.0, p.y * {c['lac']}) + vec2(17.7, 9.2);\n"
            f"        per = vec2(per.x * 2.0, per.y * {c['lac']});\n"
            "        amplitude *= 0.5;\n"
            "    }\n"
            "    return value;\n"
            "}")
    if name == "fbm3":
        mode = c["fbmMode"]
        if mode == "standard":
            tap3 = "value += amplitude * vnoise3(p);"
        elif mode == "ridged":
            tap3 = "value += amplitude * (1.0 - abs(2.0 * vnoise3(p) - 1.0));"
        else:  # turb
            tap3 = "value += amplitude * abs(2.0 * vnoise3(p) - 1.0);"
        return (
            f"// 3D fractal noise, {mode} mode, {c['oct3']} octaves (budget: <= 4 --\n"
            "// each octave costs 8 hashes). No wrap bookkeeping: the sphere-\n"
            "// direction domain is seam- and pole-free by construction.\n"
            "float fbm3(vec3 p) {\n"
            "    float value = 0.0;\n"
            "    float amplitude = 0.5;\n"
            f"    for (int i = 0; i < {c['oct3']}; i++) {{\n"
            f"        {tap3}\n"
            "        p = p * 2.0 + vec3(11.7, 5.3, 7.1);\n"
            "        amplitude *= 0.5;\n"
            "    }\n"
            "    return value;\n"
            "}")
    if name == "warp1":
        return (
            "// iq single-stage domain warp: f(p + h(p)). The offset field is seam-\n"
            "// periodic, so warping preserves the u wrap; drifts are day-quantized.\n"
            "vec2 warp1(vec2 p, vec2 per, float t) {\n"
            f"    return p + {c['warpAmp']} * (vec2(fbm2(p + vec2(0.0, t * {c['warpDriftY']}), per),\n"
            f"        fbm2(p + vec2(5.2, 1.3) - vec2(t * {c['warpDriftX']}, 0.0), per)) - 0.5);\n"
            "}")
    if name == "warp2":
        return (
            "// iq two-stage nested domain warp: f(p + h(p + g(p)))\n"
            "vec2 warp2(vec2 p, vec2 per, float t) {\n"
            "    vec2 q = warp1(p, per, t);\n"
            f"    return p + {c['warpAmp2']} * (vec2(fbm2(q + vec2(1.7, 9.2), per),\n"
            f"        fbm2(q + vec2(8.3, 2.8) + vec2(0.0, t * {c['warp2DriftY']}), per)) - 0.5);\n"
            "}")
    if name == "curl2":
        return (
            "// divergence-free curl of a wrapping value-noise potential\n"
            "vec2 curl2(vec2 p, vec2 per) {\n"
            "    float e = 0.02;\n"
            "    float n1 = vnoise(p + vec2(e, 0.0), per);\n"
            "    float n2 = vnoise(p - vec2(e, 0.0), per);\n"
            "    float n3 = vnoise(p + vec2(0.0, e), per);\n"
            "    float n4 = vnoise(p - vec2(0.0, e), per);\n"
            "    return vec2(n3 - n4, -(n1 - n2)) / (2.0 * e);\n"
            "}")
    if name == "voro2":
        jit = c["voroJit"]
        return (
            "// iq two-pass voronoi on a wrapping lattice: x = exact border distance,\n"
            "// y = F1, z = cell hash. 'per' tiles the cells across the u seam.\n"
            "vec3 voro2(vec2 p, vec2 per, float t) {\n"
            "    vec2 n = floor(p);\n"
            "    vec2 f = fract(p);\n"
            "    vec2 mg = vec2(0.0);\n"
            "    vec2 mr = vec2(0.0);\n"
            "    float md = 8.0;\n"
            "    for (int j = -1; j <= 1; j++) {\n"
            "        for (int i = -1; i <= 1; i++) {\n"
            "            vec2 g = vec2(float(i), float(j));\n"
            "            vec2 o = hash22(mod(n + g, per));\n"
            f"            o = 0.5 + {jit} * sin(t + 6.2831853 * o);\n"
            "            vec2 r = g + o - f;\n"
            "            float d = dot(r, r);\n"
            "            if (d < md) {\n"
            "                md = d;\n"
            "                mr = r;\n"
            "                mg = g;\n"
            "            }\n"
            "        }\n"
            "    }\n"
            "    float mbd = 8.0;\n"
            "    for (int j = -2; j <= 2; j++) {\n"
            "        for (int i = -2; i <= 2; i++) {\n"
            "            vec2 g = mg + vec2(float(i), float(j));\n"
            "            vec2 o = hash22(mod(n + g, per));\n"
            f"            o = 0.5 + {jit} * sin(t + 6.2831853 * o);\n"
            "            vec2 r = g + o - f;\n"
            "            if (dot(mr - r, mr - r) > 0.00001) {\n"
            "                mbd = min(mbd, dot(0.5 * (mr + r), normalize(r - mr)));\n"
            "            }\n"
            "        }\n"
            "    }\n"
            "    return vec3(mbd, sqrt(md), hash21(mod(n + mg, per)));\n"
            "}")
    if name == "voro3":
        return (
            "// 3D voronoi F1 + cell hash on the sphere-direction domain (seam- and\n"
            "// pole-free, so no lattice wrap); 27 cells, const-bounded\n"
            "vec2 voro3(vec3 p, float t) {\n"
            "    vec3 n = floor(p);\n"
            "    vec3 f = fract(p);\n"
            "    float md = 8.0;\n"
            "    float mh = 0.0;\n"
            "    for (int k = -1; k <= 1; k++) {\n"
            "        for (int j = -1; j <= 1; j++) {\n"
            "            for (int i = -1; i <= 1; i++) {\n"
            "                vec3 g = vec3(float(i), float(j), float(k));\n"
            "                vec3 oh = hash33(n + g);\n"
            f"                vec3 o = 0.5 + {c['voroJit']} * sin(t + 6.2831853 * oh);\n"
            "                vec3 r = g + o - f;\n"
            "                float d = dot(r, r);\n"
            "                if (d < md) {\n"
            "                    md = d;\n"
            "                    mh = oh.x;\n"
            "                }\n"
            "            }\n"
            "        }\n"
            "    }\n"
            "    return vec2(sqrt(md), mh);\n"
            "}")
    if name == "voronoise":
        return (
            "// iq voronoise on a wrapping lattice: blends cellular and value noise\n"
            "float voronoise(vec2 p, vec2 per, float u, float v) {\n"
            "    float k = 1.0 + 63.0 * pow(1.0 - v, 6.0);\n"
            "    vec2 i = floor(p);\n"
            "    vec2 f = fract(p);\n"
            "    vec2 a = vec2(0.0, 0.0);\n"
            "    for (int y = -2; y <= 2; y++) {\n"
            "        for (int x = -2; x <= 2; x++) {\n"
            "            vec2 g = vec2(float(x), float(y));\n"
            "            vec2 o = hash22(mod(i + g, per));\n"
            "            vec2 d = g - f + o * u;\n"
            "            float w = pow(1.0 - smoothstep(0.0, 1.414, length(d)), k);\n"
            "            a += vec2(hash21(mod(i + g, per)) * w, w);\n"
            "        }\n"
            "    }\n"
            "    return a.x / max(a.y, 0.001);\n"
            "}")
    if name == "hexDist":
        return (
            "float hexDist(vec2 p) {\n"
            "    p = abs(p);\n"
            "    return max(dot(p, normalize(vec2(1.0, 1.7320508))), p.x);\n"
            "}")
    if name == "hexCoords":
        return (
            "// hex lattice: x = local x, y = distance to cell edge, zw = cell id\n"
            "// (the cell x-period is 1.0, so an integer u scale tiles the seam;\n"
            "// hash the id through cellHash to wrap it)\n"
            "vec4 hexCoords(vec2 uv) {\n"
            "    vec2 r = vec2(1.0, 1.7320508);\n"
            "    vec2 h = r * 0.5;\n"
            "    vec2 a = mod(uv, r) - h;\n"
            "    vec2 b = mod(uv - h, r) - h;\n"
            "    vec2 gv = (dot(a, a) < dot(b, b)) ? a : b;\n"
            "    vec2 id = uv - gv;\n"
            "    return vec4(gv.x, 0.5 - hexDist(gv), id.x, id.y);\n"
            "}")
    if name == "triGrid":
        return (
            "// triangle lattice: x = edge distance, y = cell hash, z = parity;\n"
            "// the cell hash wraps every px cells in x so the weave tiles the seam\n"
            "vec3 triGrid(vec2 p, float px) {\n"
            "    vec2 s = vec2(p.x - p.y * 0.57735, p.y * 1.1547);\n"
            "    vec2 f = fract(s);\n"
            "    vec2 cellIdx = floor(s);\n"
            "    float upper = step(1.0, f.x + f.y);\n"
            "    float d = mix(min(min(f.x, f.y), abs(1.0 - f.x - f.y)),\n"
            "        min(min(1.0 - f.x, 1.0 - f.y), abs(f.x + f.y - 1.0)), upper);\n"
            "    float ch = cellHash(cellIdx + vec2(upper * 0.5, upper * 0.25), px);\n"
            "    return vec3(d, ch, upper);\n"
            "}")
    if name == "truchet":
        return (
            "// hash-flipped quarter-circle truchet arcs on a wrapping lattice\n"
            "float truchet(vec2 p, float width, float px) {\n"
            "    vec2 id = floor(p);\n"
            "    vec2 f = fract(p) - 0.5;\n"
            "    float flip = step(0.5, cellHash(id, px));\n"
            "    f.x *= mix(1.0, -1.0, flip);\n"
            "    vec2 c = (f.x + f.y > 0.0) ? vec2(0.5, 0.5) : vec2(-0.5, -0.5);\n"
            "    float d = abs(length(f - c) - 0.5);\n"
            "    return invsmooth(width * 0.35, width, d);\n"
            "}")
    if name == "polarFold":
        return (
            f"// seam-safe kaleidoscope fold into {c['folds']} mirrored sectors around\n"
            "// the bubble's forward point: longitude enters only through sin/cos of\n"
            "// whole turns, so the mandala tiles across the u = 0/1 seam\n"
            "vec2 polarFold(vec2 uv, float t) {\n"
            "    float du = uv.x - 0.5;\n"
            "    float dv = uv.y - 0.5;\n"
            "    float r = length(vec2(sin(du * 3.1415927), dv));\n"
            "    float a = safeAtan(dv, sin(du * 6.2831853) * 0.5) + t;\n"
            f"    float sector = {c['sector']};\n"
            "    a = mod(a, sector);\n"
            "    a = abs(a - sector * 0.5);\n"
            "    return vec2(cos(a), sin(a)) * r;\n"
            "}")
    if name == "spiralWarp":
        return (
            "// seam-safe spiral rotation around the bubble's forward point\n"
            "// (longitude wrapped through sin/cos so the swirl tiles the u seam)\n"
            "vec2 spiralWarp(vec2 uv, float t) {\n"
            "    float du = uv.x - 0.5;\n"
            "    float dv = uv.y - 0.5;\n"
            "    float r = length(vec2(sin(du * 3.1415927), dv));\n"
            f"    float a = safeAtan(dv, sin(du * 6.2831853) * 0.5) + {c['spiralK']} * r - t;\n"
            "    return vec2(cos(a), sin(a)) * r + vec2(0.5, 0.5);\n"
            "}")
    if name == "caustic":
        return (
            "// jaybird-style iterated caustic: noise-displaced resampling + exp\n"
            "// sharpen. The wrapping lattice keeps it seamless across the u seam;\n"
            "// the caller passes a day-quantized drift.\n"
            "float caustic(vec2 p, vec2 drift, vec2 per) {\n"
            "    vec2 k = p;\n"
            "    float acc = 0.0;\n"
            "    for (int i = 0; i < 3; i++) {\n"
            "        float fi = float(i);\n"
            "        float w = vnoise(k + drift + vec2(fi * 3.7, fi * 1.3), per);\n"
            "        k += 0.35 * vec2(cos(w * 6.2831853), sin(w * 6.2831853));\n"
            "        acc += w;\n"
            "    }\n"
            "    acc = acc / 3.0;\n"
            "    return clamp(exp(acc * 3.0 - 1.9) - 0.35, 0.0, 1.4);\n"
            "}")
    if name == "caustic3":
        return (
            "// iterated caustic on the 3D sphere direction: noise-displaced\n"
            "// resampling + exp sharpen, seam- and pole-free by construction.\n"
            "// Motion comes from the caller rotating the domain (day-safe).\n"
            "float caustic3(vec3 p, float t) {\n"
            "    vec3 k = p;\n"
            "    float acc = 0.0;\n"
            "    for (int i = 0; i < 3; i++) {\n"
            "        float fi = float(i);\n"
            "        float w = vnoise3(k + vec3(fi * 3.7, fi * 1.3, fi * 2.1));\n"
            "        k += 0.35 * vec3(cos(w * 6.2831853), sin(w * 6.2831853), cos(w * 4.1887902 + 1.7));\n"
            "        acc += w;\n"
            "    }\n"
            "    acc = acc / 3.0;\n"
            "    return clamp(exp(acc * 3.0 - 1.9) - 0.35, 0.0, 1.4);\n"
            "}")
    if name == "thinFilm":
        return (
            "// soap-film interference: per-RGB cosine over a pseudo optical path length\n"
            "vec3 thinFilm(float thickness) {\n"
            "    vec3 invLambda = vec3(1.0, 0.8065, 0.6452);\n"
            "    return 0.5 + 0.5 * cos(6.2831853 * thickness * invLambda + vec3(0.0, 0.6, 1.2));\n"
            "}")
    if name == "accentPalette":
        cx, cy, cz = c["palC"]
        dx, dy, dz = c["palD"]
        return (
            "// iq cosine palette, baked per effect; used ONLY for bounded accents\n"
            "vec3 accentPalette(float t) {\n"
            f"    return vec3(0.5) + vec3(0.5) * cos(6.2831853 * (vec3({F(cx)}, {F(cy)}, {F(cz)}) * t\n"
            f"        + vec3({F(dx)}, {F(dy)}, {F(dz)})));\n"
            "}")
    if name == "gradient3":
        return (
            "// 3-stop shadow/body/highlight grade (two smoothstep segments): every\n"
            "// stop is derived from vertexColor.rgb at RUNTIME, so the owner /color\n"
            "// override recolors shadow, body and highlight together (recolor-safe\n"
            "// by construction).\n"
            "vec3 gradient3(vec3 deepStop, vec3 body, vec3 hotStop, float x) {\n"
            f"    vec3 g = mix(deepStop, body, smoothstep(0.0, {c['gSplit']}, x));\n"
            f"    return mix(g, hotStop, smoothstep({c['gSplit']}, 1.0, x));\n"
            "}")
    if name == "satLift":
        return (
            "// saturation lift: mixes AWAY from luma (s > 1), deepening the chroma\n"
            "// without changing the hue -- the anti-washout pass\n"
            "vec3 satLift(vec3 col, float s) {\n"
            "    float l = dot(col, vec3(0.299, 0.587, 0.114));\n"
            "    return clamp(mix(vec3(l), col, s), 0.0, 1.0);\n"
            "}")
    if name == "hueSpin":
        return (
            "// Rodrigues rotation of the color vector around the grey axis; nudges\n"
            "// the highlight stop's hue away from the body color. The input is\n"
            "// always derived from vertexColor.rgb at runtime, so recolor-safe.\n"
            "vec3 hueSpin(vec3 col, float a) {\n"
            "    const vec3 k = vec3(0.57735027);\n"
            "    float ca = cos(a);\n"
            "    float sa = sin(a);\n"
            "    return col * ca + cross(k, col) * sa + k * dot(k, col) * (1.0 - ca);\n"
            "}")
    if name == "rimGraze":
        return (
            "// silhouette estimator: the camera-distance varying changes fastest per\n"
            "// pixel at grazing angles, so fwidth() peaks at the bubble's rim.\n"
            "// Normalizing by the distance itself keeps the rim width roughly\n"
            "// view-consistent near and far.\n"
            "float rimGraze() {\n"
            "    float g = fwidth(sphericalVertexDistance) / max(sphericalVertexDistance, 1.0);\n"
            f"    return smoothstep({c['rg0']}, {c['rg1']}, g);\n"
            "}")
    if name == "rimLat":
        return (
            "// latitude band rim: pole caps plus a soft equator belt\n"
            "float rimLat(vec2 uv) {\n"
            "    float lat = abs(uv.y * 2.0 - 1.0);\n"
            f"    float poles = smoothstep({c['rl0']}, 1.0, lat);\n"
            f"    float belt = invsmooth(0.0, {c['rlEq']}, lat);\n"
            f"    return clamp(poles + {c['rlEqW']} * belt, 0.0, 1.0);\n"
            "}")
    if name == "sparkle":
        return (
            "// hash-cell twinkle: sparse offset star points with per-cell phase;\n"
            "// cells wrap every px in x so the field tiles the u seam\n"
            "float sparkle(vec2 p, float t, float px) {\n"
            "    vec2 cellId = floor(p);\n"
            "    vec2 f = fract(p) - 0.5;\n"
            "    float h = cellHash(cellId, px);\n"
            "    vec2 off = vec2(cellHash(cellId + 11.3, px), cellHash(cellId + 27.9, px)) - 0.5;\n"
            "    float d = length(f - off * 0.55);\n"
            "    float tw = pow(0.5 + 0.5 * sin(t * (2.0 + 5.0 * h) + h * 39.0), 6.0);\n"
            f"    return step({c['sparkTh']}, h) * invsmooth(0.02, 0.22, d) * tw;\n"
            "}")
    if name == "ringPulse":
        return (
            "// GameTime-phased expanding rings from hash-seeded surface points; the\n"
            "// chordal x-distance (sin of the longitude difference) keeps the rings\n"
            "// round across the u = 0/1 seam, and the phase speed is day-quantized\n"
            "float ringPulse(vec2 uv, float t) {\n"
            "    float acc = 0.0;\n"
            "    for (int i = 0; i < 3; i++) {\n"
            "        float fi = float(i);\n"
            f"        vec2 c = hash22(vec2(fi * 7.31 + {c['rpSeed']}, fi * 2.97));\n"
            f"        float phase = fract(t * {c['rpSpeed']} + fi * 0.37);\n"
            "        float d = length(vec2(sin((uv.x - c.x) * 3.1415927) * 0.3183, uv.y - c.y));\n"
            "        acc += invsmooth(0.0, 0.05, abs(d - phase * 0.7)) * (1.0 - phase);\n"
            "    }\n"
            "    return acc;\n"
            "}")
    if name == "deepField":
        recipe = c["deep"]
        if recipe == "fbm":
            body = "    return fbm3(p);"
            note = "3D fbm volume"
        elif recipe == "ridge":
            body = (
                "    float n = fbm3(p);\n"
                f"    return pow(1.0 - abs(2.0 * n - 1.0), {c['dRidgePow']});")
            note = "3D ridged crest volume"
        elif recipe == "caustic":
            body = "    return caustic3(p, t);"
            note = "3D refracted caustic volume"
        else:  # voro
            body = (
                f"    vec2 v = voro3(p, t * {c['dSpeed']});\n"
                "    return invsmooth(0.15, 0.95, v.x);")
            note = "3D voronoi blob volume"
        return (
            f"// DEEP-layer field ({note}), sampled on the 3D sphere direction by\n"
            "// the correlated parallax planes -- seam- and pole-free\n"
            "float deepField(vec3 p, float t) {\n"
            f"{body}\n"
            "}")
    raise KeyError(name)


def deep_field_deps(recipe: str) -> list:
    return {"fbm": ["fbm3"], "ridge": ["fbm3"], "caustic": ["caustic3"],
            "voro": ["voro3", "invsmooth"]}[recipe]


# ---------------------------------------------------------------------------
# MID-layer composers, one per SurfaceTemplate family. Each returns
# (needs, lines, post_lines); lines compute `float mid` from `wuv`
# (warped+animated UV), `baseUV`, `midPer` (the wrapping-lattice period of
# wuv) and `time`; post_lines run in the composite after the accent mix.
# ---------------------------------------------------------------------------

def mid_composer(family: str, c: dict):
    u = c["u"]
    qs = c["qs"]
    qsc = c["qsc"]
    qpx = c["qpx"]
    qpy = c["qpy"]
    sx = c["sx"]
    syp = c["syp"]
    px = c["PX"]
    if family == "PLASMA":
        # 3D: warped fbm on the rotating sphere direction (seam- + pole-free);
        # time enters only via the day-safe mdir rotation and quantized sin.
        return (["fbm3"], [
            f"vec3 pw = mdir + {F(u(40, 0.6, 1.2))} * (vec3(fbm3(mdir + vec3(7.31, 1.77, 3.91)), fbm3(mdir + vec3(2.11, 8.43, 5.67)), fbm3(mdir + vec3(4.87, 3.29, 9.13))) - 0.5);",
            "float pn = fbm3(pw);",
            f"float mid = 0.5 + 0.5 * sin(6.2831853 * pn * {F(u(42, 1.2, 2.4))} + time * {qs(43, 0.5, 1.1)});",
            f"mid = pow(clamp(mid, 0.0, 1.0), {F(u(44, 1.4, 2.6))});",
        ], [])
    if family == "HEX":
        return (["hexCoords", "cellHash", "invsmooth"], [
            "vec4 hc = hexCoords(wuv);",
            f"float hexEdge = invsmooth(0.015, {F(u(40, 0.10, 0.20))}, hc.y);",
            f"float cellPulse = 0.5 + 0.5 * sin(time * {qs(41, 0.8, 1.8)} + cellHash(hc.zw, {px}) * 6.2831853);",
            f"float mid = hexEdge * (0.55 + 0.45 * cellPulse) + {F(u(42, 0.15, 0.35))} * cellPulse * smoothstep(0.08, 0.32, hc.y);",
        ], [])
    if family == "WAVES":
        harmonic = 1 + int(u(43, 0.0, 2.999))  # integer cycles per u wrap
        return (["fbm2"], [
            f"float w1 = sin(wuv.y * {F(u(40, 2.2, 4.5))} + time * {qs(41, 0.6, 1.4)} + fbm2(wuv, midPer) * {F(u(42, 1.5, 3.2))});",
            "// the cross wave rides an integer longitude harmonic: seam-aligned",
            f"float w2 = sin(baseUV.x * 6.2831853 * {F(float(harmonic))} + wuv.y * {F(u(44, 0.6, 1.6))} - time * {qs(46, 0.4, 1.0)});",
            "float crest = 0.5 + 0.5 * (w1 * 0.6 + w2 * 0.4);",
            f"float mid = pow(clamp(crest, 0.0, 1.0), {F(u(47, 1.4, 2.8))});",
        ], [])
    if family == "AURORA":
        kxi = 1 + int(u(40, 0.0, 1.999))  # integer curtain x stretch
        ky = u(42, 0.25, 0.6)
        qdx = F6(quant_drift(u(41, 0.16, 0.34), float(sx * kxi)))
        qdy = F6(quant_drift(u(43, 0.04, 0.10), float(syp) * ky))
        return (["fbm2", "invsmooth"], [
            f"float curtain = fbm2(vec2(wuv.x * {F(float(kxi))} + time * {qdx}, wuv.y * {F(ky)} - time * {qdy}), vec2({F(float(sx * kxi))}, {F(float(syp) * ky)}));",
            f"float rays = pow(clamp(curtain * {F(u(44, 1.4, 1.9))} - {F(u(45, 0.18, 0.32))}, 0.0, 1.0), 2.0);",
            "float drape = smoothstep(0.0, 0.35, baseUV.y) * invsmooth(0.55, 1.0, baseUV.y);",
            f"float shimmer = 0.5 + 0.5 * sin(time * {qs(46, 0.8, 1.5)} + baseUV.x * 12.5663706);",
            "float mid = rays * drape * (0.7 + 0.3 * shimmer);",
        ], [])
    if family == "SPARKLE":
        return (["sparkle", "voronoise"], [
            "float s1 = sparkle(wuv, time, midPer.x);",
            f"float s2 = sparkle(wuv * 2.0 + 31.7, time * {F(u(40, 1.1, 1.6))}, midPer.x * 2.0);",
            f"float haze = voronoise(wuv + vec2(time * {qpx(45, 0.03, 0.08)}, 0.0), midPer, {F(u(42, 0.6, 1.0))}, {F(u(43, 0.4, 0.9))});",
            f"float mid = clamp(s1 + 0.6 * s2 + {F(u(44, 0.20, 0.40))} * haze, 0.0, 1.2);",
        ], [])
    if family == "RINGS":
        return (["fbm2", "ringPulse"], [
            f"float band = 0.5 + 0.5 * sin(baseUV.y * {F(u(40, 18.0, 34.0))} + time * {qs(41, 0.7, 1.5)} + fbm2(wuv, midPer) * {F(u(42, 1.2, 2.6))});",
            "float rp = ringPulse(baseUV, time);",
            f"float mid = pow(clamp(band, 0.0, 1.0), {F(u(43, 1.6, 3.0))}) * 0.8 + rp * {F(u(44, 0.5, 0.9))};",
        ], [])
    if family == "VORONOI":
        return (["voro2", "invsmooth"], [
            f"vec3 v = voro2(wuv, midPer, time * {qs(40, 0.4, 0.9)});",
            f"float border = invsmooth(0.005, {F(u(41, 0.06, 0.12))}, v.x);",
            f"float cellGlow = 0.5 + 0.5 * sin(time * {qs(42, 0.8, 1.6)} + v.z * 6.2831853);",
            f"float mid = border * (0.7 + 0.3 * cellGlow) + {F(u(43, 0.20, 0.40))} * invsmooth(0.2, 0.9, v.y) * cellGlow;",
        ], [])
    if family == "ARCS":
        # 3D: ridged fbm arcs crawl on the rotating sphere direction; the
        # sway is a quantized sin (linear drift is NOT day-safe on the
        # unwrapped 3D lattice).
        return (["fbm3"], [
            f"vec3 aw3 = mdir + vec3(0.0, {F(u(40, 0.25, 0.55))} * sin(time * {qs(46, 0.10, 0.25)}), 0.0);",
            "float ridge = 1.0 - abs(2.0 * fbm3(aw3) - 1.0);",
            f"float bolt = pow(clamp(ridge, 0.0, 1.0), {F(u(41, 7.0, 13.0))});",
            f"float flash = 0.6 + 0.4 * sin(time * {qs(42, 2.0, 4.5)} + fbm3(mdir + vec3(4.1, 1.3, 7.9)) * 9.0);",
            "float mid = bolt * flash;",
        ], [])
    if family == "SCALES":
        return (["cellHash", "invsmooth"], [
            "vec2 g = wuv;",
            "g.x += 0.5 * step(1.0, mod(floor(g.y), 2.0));",
            "vec2 cf = fract(g);",
            "float d = length(cf - vec2(0.5, 1.1));",
            f"float lip = invsmooth({F(u(41, 0.015, 0.035))}, {F(u(40, 0.05, 0.10))}, abs(d - 0.62));",
            "float shade = invsmooth(0.35, 1.15, d);",
            f"float glint = 0.5 + 0.5 * sin(time * {qs(42, 0.9, 1.9)} + cellHash(floor(g), {px}) * 6.2831853);",
            f"float mid = lip * (0.6 + 0.4 * glint) + {F(u(43, 0.18, 0.36))} * shade;",
        ], [])
    if family == "STARFIELD":
        return (["cellHash", "invsmooth"], [
            "float stars = 0.0;",
            "for (int i = 0; i < 3; i++) {",
            "    float fi = float(i);",
            "    // integer per-layer scale keeps each star lattice seam-aligned",
            "    float layerPx = midPer.x * (fi + 1.0);",
            f"    vec2 su = wuv * (fi + 1.0) + vec2(fi * 17.3, fi * 9.1) + vec2(time * {qpx(40, 0.008, 0.02)} * (fi + 1.0), 0.0);",
            "    vec2 sc = floor(su);",
            "    vec2 lf = fract(su) - 0.5;",
            "    float h = cellHash(sc + vec2(fi * 47.0, 0.0), layerPx);",
            "    float tw = 0.5 + 0.5 * sin(time * (1.0 + 3.0 * h) + h * 40.0);",
            f"    stars += step({F(u(41, 0.80, 0.90))}, h) * invsmooth(0.02, 0.18, length(lf - (vec2(cellHash(sc + 3.1, layerPx), cellHash(sc + 7.7, layerPx)) - 0.5) * 0.6)) * tw;",
            "}",
            "float mid = clamp(stars, 0.0, 1.3);",
        ], [])
    if family == "VORTEX":
        band_harm = 2 + int(u(42, 0.0, 2.999))  # integer: seam-aligned bands
        return (["fbm2", "spiralWarp"], [
            "float latDist = 1.0 - abs(baseUV.y * 2.0 - 1.0);",
            f"float twist = baseUV.x * 6.2831853 + latDist * {F(u(40, 5.0, 9.0))} - time * {qs(41, 0.6, 1.2)};",
            f"float band = smoothstep(0.15, 0.9, sin(twist * {F(float(band_harm))}));",
            f"float arms = smoothstep(0.45, 0.95, sin(baseUV.x * 6.2831853 - latDist * {F(u(43, 3.5, 6.0))} + time * {qs(44, 0.4, 0.9)}));",
            f"float eye = clamp(pow(clamp(1.0 - latDist, 0.0, 1.0), 3.0) * (0.6 + 0.4 * sin(time * {qsc(1.7)})), 0.0, 1.0);",
            f"vec2 sv = spiralWarp(baseUV, time * {qs(45, 0.06, 0.14)});",
            "// sv is periodic in u by construction, so the noise needs no wrap",
            f"float mid = clamp(band * 0.8 + arms * 0.5 + eye + fbm2(sv * {F(u(46, 3.0, 6.0))}, vec2({F(NOWRAP_PERIOD)}, {F(NOWRAP_PERIOD)})) * 0.18, 0.0, 1.3);",
        ], [])
    if family == "INTERFERENCE":
        return (["fbm2"], [
            "// seam-safe chordal distances: sin() wraps the longitude difference,",
            "// so both ripple sources stay point-like across the u = 0/1 seam",
            f"vec2 sc1 = vec2({F(u(40, 0.1, 0.9))}, {F(u(41, 0.2, 0.8))});",
            f"vec2 sc2 = vec2({F(u(43, 0.1, 0.9))}, {F(u(44, 0.2, 0.8))});",
            f"float r1 = length(vec2(sin((baseUV.x - sc1.x) * 3.1415927) * 0.6366, baseUV.y - sc1.y)) * {F(u(55, 2.0, 3.5))} + fbm2(wuv, midPer) * {F(u(42, 0.05, 0.16))};",
            f"float r2 = length(vec2(sin((baseUV.x - sc2.x) * 3.1415927) * 0.6366, baseUV.y - sc2.y)) * {F(u(56, 2.0, 3.5))};",
            f"float inter = 0.5 + 0.5 * sin(r1 * {F(u(45, 8.0, 15.0))} - time * {qs(46, 0.8, 1.7)}) * sin(r2 * {F(u(47, 7.0, 13.0))} + time * {qs(48, 0.6, 1.3)});",
            f"float mid = pow(clamp(inter, 0.0, 1.0), {F(u(49, 1.6, 3.0))});",
        ], [])
    if family == "KALEIDO":
        qdrift = F6(quant_drift(u(47, 0.05, 0.09), NOWRAP_PERIOD))
        return (["polarFold", "fbm2"], [
            f"vec2 kv = polarFold(baseUV, time * {qs(41, 0.05, 0.14)}) * {F(u(42, 5.0, 9.0))};",
            "// kv is periodic in u by construction, so the noise needs no wrap",
            f"float kp = fbm2(kv + vec2(time * {qdrift}, 0.0), vec2({F(NOWRAP_PERIOD)}, {F(NOWRAP_PERIOD)}));",
            f"float mandala = pow(clamp(0.5 + 0.5 * sin(kp * 9.42 + time * {qs(43, 0.5, 1.1)}), 0.0, 1.0), {F(u(44, 1.8, 3.2))});",
            f"float spokes = 0.5 + 0.5 * sin(kv.x * {F(u(45, 4.0, 8.0))} - time * {qs(46, 0.8, 1.5)});",
            "float mid = clamp(mandala * 0.75 + spokes * 0.4, 0.0, 1.2);",
        ], [])
    if family == "CIRCUIT":
        return (["cellHash", "invsmooth"], [
            "vec2 cellId = floor(wuv);",
            "vec2 cf = fract(wuv) - 0.5;",
            f"float h = cellHash(cellId, {px});",
            f"float lineH = invsmooth({F(u(41, 0.015, 0.03))}, {F(u(40, 0.05, 0.09))}, abs(cf.y)) * step(h, 0.55);",
            f"float lineV = invsmooth({F(u(41, 0.015, 0.03))}, {F(u(40, 0.05, 0.09))}, abs(cf.x)) * step(0.45, h);",
            f"float node = invsmooth(0.05, 0.16, length(cf)) * step(0.8, cellHash(cellId + 13.1, {px}));",
            f"float traffic = 0.5 + 0.5 * sin(time * {qs(42, 1.5, 3.0)} + h * 6.2831853 + (cf.x + cf.y) * 3.0);",
            "float mid = clamp((lineH + lineV) * (0.5 + 0.5 * traffic) + node, 0.0, 1.2);",
        ], [])
    if family == "PETALS":
        petals = 3 + int(u(41, 0.0, 1.999))  # integer petal count: day-safe spin
        return (["fbm2", "safeAtan", "invsmooth"], [
            "// seam-safe polar frame around the bubble's forward point: longitude",
            "// enters through sin() of whole turns, so petals tile the u seam",
            "float du = baseUV.x - 0.5;",
            f"float ang = safeAtan(baseUV.y - 0.5, sin(du * 6.2831853) * 0.5) + time * {qs(40, 0.15, 0.40)};",
            "float rad = length(vec2(sin(du * 3.1415927), baseUV.y - 0.5)) * 2.0;",
            f"float petal = pow(abs(cos(ang * {F(float(petals))})), {F(u(42, 0.6, 1.4))});",
            "float bloom = invsmooth(petal * 0.9 - 0.12, petal * 0.9 + 0.08, rad);",
            f"float veins = 0.5 + 0.5 * sin(rad * {F(u(43, 10.0, 20.0))} - time * {qs(44, 0.6, 1.3)});",
            "float mid = clamp(bloom * (0.6 + 0.4 * veins) + fbm2(wuv, midPer) * 0.15, 0.0, 1.2);",
        ], [])
    if family == "LIGHTNING":
        # 3D: bolts live on the rotating sphere direction with a quantized
        # sin sway (day-safe); the strobe gate reseeds at the wrap as before.
        return (["fbm3", "hash11"], [
            f"vec3 lw3 = mdir + vec3(0.0, {F(u(46, 0.30, 0.60))} * sin(time * {qs(47, 0.15, 0.35)}), 0.0);",
            "float n = fbm3(lw3);",
            f"float bolt = pow(clamp(1.0 - abs(2.0 * n - 1.0) * {F(u(41, 1.1, 1.5))}, 0.0, 1.0), {F(u(42, 9.0, 15.0))});",
            f"float gate = step({F(u(43, 0.35, 0.55))}, hash11(floor(time * {F(u(44, 3.0, 6.0))})));",
            f"float strobe = 0.35 + 0.65 * gate * (0.5 + 0.5 * sin(time * {qsc(40.0)}));",
            f"float mid = bolt * strobe + {F(u(45, 0.15, 0.30))} * bolt;",
        ], [])
    if family == "THINFILM":
        mtw = F(u(46, 0.25, 0.40))
        return (["fbm2", "thinFilm"], [
            f"float thick = fbm2(wuv + vec2(time * {qpx(45, 0.03, 0.08)}, 0.0), midPer) * {F(u(40, 1.5, 3.0))} + baseUV.y * {F(u(41, 0.8, 2.0))} + {F(u(42, 0.2, 0.9))};",
            "vec3 filmTint = thinFilm(thick);",
            f"float mid = clamp(dot(filmTint, vec3(0.3333)) * {F(u(43, 0.9, 1.3))}, 0.0, 1.2);",
        ], [
            f"rgb = mix(rgb, rgb * (0.55 + 0.9 * filmTint), {mtw});",
        ])
    if family == "CAUSTIC":
        d1x = F6(quant_drift(0.23, float(2 * sx)))
        d1y = F6(-quant_drift(0.17, float(2 * syp)))
        d2x = F6(quant_drift(u(40, 0.24, 0.31), float(3 * sx)))
        d2y = F6(-quant_drift(0.20, float(3 * syp)))
        return (["caustic"], [
            f"float c1 = caustic(wuv * 2.0, vec2({d1x}, {d1y}) * time, midPer * 2.0);",
            f"float c2 = caustic(wuv * 3.0 + vec2(13.1, 4.7), vec2({d2x}, {d2y}) * time, midPer * 3.0);",
            f"float mid = clamp(c1 * {F(u(41, 0.6, 0.9))} + c2 * {F(u(42, 0.3, 0.55))}, 0.0, 1.4);",
        ], [])
    if family == "CURLSMOKE":
        # 3D: pseudo-curl advection (vector of three fbm3 fields) on the
        # rotating sphere direction; the swirl motion comes from the day-safe
        # mdir rotation plus a quantized sin sway.
        return (["fbm3"], [
            f"vec3 adv = mdir + {F(u(40, 0.35, 0.75))} * (vec3(fbm3(mdir + vec3(1.7, 9.2, 4.1)), fbm3(mdir + vec3(8.3, 2.8, 6.9)), fbm3(mdir + vec3(3.4, 5.1, 0.8))) - 0.5);",
            f"float smoke = fbm3(adv + vec3({F(u(42, 0.20, 0.50))} * sin(time * {qs(43, 0.08, 0.20)}), 0.0, 0.0));",
            f"float wisp = pow(clamp(smoke * 1.5 - 0.25, 0.0, 1.0), {F(u(44, 1.4, 2.6))});",
            "float mid = wisp;",
        ], [])
    if family == "TRUCHET":
        return (["truchet"], [
            f"float t1 = truchet(wuv, {F(u(40, 0.07, 0.13))}, midPer.x);",
            f"float t2 = truchet(wuv * 2.0 + vec2(7.3, 3.1), {F(u(41, 0.05, 0.10))}, midPer.x * 2.0) * 0.5;",
            "// the flow phase rides one full longitude turn: seam-aligned",
            f"float flow = 0.5 + 0.5 * sin(baseUV.x * 6.2831853 + wuv.y * 1.7 + time * {qs(42, 0.9, 1.8)});",
            "float mid = clamp(t1 * (0.6 + 0.4 * flow) + t2, 0.0, 1.2);",
        ], [])
    if family == "RIDGED":
        # 3D: ridged crests on the rotating sphere direction (day-safe sway).
        return (["fbm3"], [
            f"float rn = fbm3(mdir + vec3(0.0, 0.0, {F(u(40, 0.20, 0.50))} * sin(time * {qs(41, 0.10, 0.25)})));",
            f"float crest = pow(clamp(1.0 - abs(2.0 * rn - 1.0), 0.0, 1.0), {F(u(42, 2.2, 4.5))});",
            f"float strata = 0.5 + 0.5 * sin(crest * {F(u(43, 6.0, 12.0))} + time * {qsc(0.8)});",
            "float mid = clamp(crest * (0.7 + 0.3 * strata), 0.0, 1.2);",
        ], [])
    if family == "MOIRE":
        m1 = 9 + int(u(40, 0.0, 6.999))  # integer longitude harmonics
        return ([], [
            "// two gratings in integer longitude harmonics (m and m+1 cycles per",
            "// wrap): always seam-aligned, and their beat wheels once per turn",
            f"float g1 = sin(baseUV.x * 6.2831853 * {F(float(m1))} + wuv.y * {F(u(42, 1.5, 4.0))} + time * {qs(43, 0.5, 1.1)});",
            f"float g2 = sin(baseUV.x * 6.2831853 * {F(float(m1 + 1))} + wuv.y * {F(u(44, 1.5, 4.0))} - time * {qs(45, 0.4, 0.9)});",
            "float beat = g1 * g2;",
            f"float mid = pow(clamp(0.5 + 0.5 * beat, 0.0, 1.0), {F(u(46, 1.6, 3.0))});",
        ], [])
    if family == "TRIWEAVE":
        return (["triGrid", "invsmooth"], [
            "vec3 tg = triGrid(wuv, midPer.x);",
            f"float triEdge = invsmooth({F(u(41, 0.012, 0.03))}, {F(u(40, 0.05, 0.10))}, tg.x);",
            f"float fill = 0.5 + 0.5 * sin(time * {qs(42, 0.8, 1.7)} + tg.y * 6.2831853 + tg.z * 3.1416);",
            f"float mid = clamp(triEdge * (0.6 + 0.4 * fill) + {F(u(43, 0.15, 0.32))} * fill * smoothstep(0.05, 0.25, tg.x), 0.0, 1.2);",
        ], [])
    if family == "NEBULA":
        s2i = 5 + int(u(43, 0.0, 4.999))  # integer star-lattice scale
        star_px = F(float(sx * s2i))
        # 3D: cloud + dust lanes ride the rotating sphere direction; the star
        # twinkle layer stays on the (already seam-aligned) 2D cell lattice.
        return (["fbm3", "cellHash"], [
            f"float neb = fbm3(mdir + {F(u(40, 0.5, 1.1))} * (vec3(fbm3(mdir + vec3(3.1, 7.7, 1.9)), fbm3(mdir + vec3(9.2, 4.4, 6.3)), fbm3(mdir + vec3(5.8, 2.6, 8.1))) - 0.5));",
            f"float lanes = 1.0 - pow(clamp(1.0 - abs(2.0 * fbm3(mdir * 2.0 + vec3(4.2, 1.1, 7.6)) - 1.0), 0.0, 1.0), 3.0) * {F(u(41, 0.35, 0.6))};",
            f"float cloud = pow(clamp(neb * 1.4 - 0.2, 0.0, 1.0), {F(u(42, 1.3, 2.2))}) * lanes;",
            f"vec2 scell = floor(wuv * {F(float(s2i))});",
            f"float star = step(0.92, cellHash(scell, {star_px})) * (0.5 + 0.5 * sin(time * {qsc(3.0)} + cellHash(scell + 5.0, {star_px}) * 20.0));",
            "float mid = clamp(cloud + star * 0.8, 0.0, 1.3);",
        ], [])
    raise KeyError(family)


# Family-tuned MID pattern scale ranges (lattice families need larger scales).
# The u-axis scale is rounded to an INTEGER cell count (the wrap period).
MID_SCALE_RANGES = {
    "PLASMA": (4.0, 7.0), "HEX": (6.0, 11.0), "WAVES": (3.0, 6.0),
    "AURORA": (5.0, 9.0), "SPARKLE": (9.0, 16.0), "RINGS": (3.0, 6.0),
    "VORONOI": (6.0, 10.0), "ARCS": (3.5, 6.5), "SCALES": (7.0, 12.0),
    "STARFIELD": (8.0, 14.0), "VORTEX": (3.0, 6.0), "INTERFERENCE": (3.0, 5.5),
    "KALEIDO": (4.0, 8.0), "CIRCUIT": (7.0, 12.0), "PETALS": (4.0, 8.0),
    "LIGHTNING": (3.0, 6.0), "THINFILM": (2.5, 5.0), "CAUSTIC": (4.0, 7.5),
    "CURLSMOKE": (3.0, 6.0), "TRUCHET": (6.0, 11.0), "RIDGED": (3.5, 6.5),
    "MOIRE": (3.0, 6.0), "TRIWEAVE": (6.0, 11.0), "NEBULA": (3.0, 5.5),
}


def emit_shader(asg: dict) -> str:
    """Emits one standalone .fsh for the given assignment row.

    Draw-index reservations (the PRNG stream is 128 unit draws; the first 96
    keep their v2 meanings so regenerated files stay maximally diff-stable):
      0..39   shared helper knobs, 40..49 family knobs, 50..59 rim knobs,
      60..61 flourish, 62..83 composite/deep v2 knobs (some now unused but
      still drawn for stream stability), 84..95 gradient3/alpha knobs,
      96..127 v3 depth/3D-domain knobs.
    """
    effect_id = asg["id"]
    family = asg["family"]
    rng = Rng(asg["seed"])
    draws = [rng.unit() for _ in range(128)]

    def u(i, lo, hi):
        return lo + (hi - lo) * draws[i]

    c = {"u": u, "deep": asg["deep"]}
    # Baked helper consts (indices < 40 are reserved for shared knobs).
    c["octaves"] = 3 + int(u(0, 0.0, 3.999))
    c["fbmMode"] = FBM_MODES[int(u(1, 0.0, 2.999))]
    c["lac"] = F(u(2, 1.9, 2.2))
    c["warpAmp"] = F(u(3, 0.45, 1.0))
    c["warpAmp2"] = F(u(4, 0.35, 0.8))
    c["voroJit"] = F(u(5, 0.28, 0.40))
    folds = 4 + 2 * int(u(6, 0.0, 2.999))  # 4, 6 or 8 mirrored sectors
    c["folds"] = folds
    c["sector"] = F(6.2831853 / folds)
    c["spiralK"] = F(u(7, 3.0, 7.0))
    if asg["primary"] is not None:
        prim = asg["primary"]
        base_d = ((prim >> 16 & 0xFF) / 255.0, (prim >> 8 & 0xFF) / 255.0, (prim & 0xFF) / 255.0)
    else:
        base_d = (u(9, 0.0, 1.0), u(10, 0.0, 1.0), u(11, 0.0, 1.0))
    pal_c = (u(12, 0.4, 1.3), u(13, 0.4, 1.3), u(14, 0.4, 1.3))
    pal_d = tuple((0.5 * bd + 0.5 * u(15 + k, 0.0, 1.0)) % 1.0 for k, bd in enumerate(base_d))
    c["palC"] = pal_c
    c["palD"] = pal_d
    # rimGraze thresholds are tuned for the distance-normalized fwidth slope.
    c["rg0"] = F(u(18, 0.003, 0.007))
    c["rg1"] = F(u(19, 0.030, 0.060))
    c["rl0"] = F(u(20, 0.55, 0.75))
    c["rlEq"] = F(u(21, 0.20, 0.40))
    c["rlEqW"] = F(u(22, 0.25, 0.55))
    c["sparkTh"] = F(u(23, 0.68, 0.80))
    c["rpSeed"] = F(u(24, 1.0, 40.0))
    c["rpSpeed"] = F6(quant_fract_speed(u(25, 0.10, 0.24)))
    c["dRidgePow"] = F(u(29, 1.6, 3.0))
    c["dSpeed"] = F6(quant_sin_speed(u(28, 0.3, 0.7)))
    c["oct3"] = min(4, c["octaves"])  # fbm3 budget: 8 hashes per octave
    c["gSplit"] = F(u(87, 0.40, 0.60))  # gradient3 shadow/highlight split

    taps = 3 + int(u(30, 0.0, 1.999))  # 3..4 correlated parallax depth planes
    mid_scale = u(31, *MID_SCALE_RANGES[family])
    flourish = FLOURISHES[int(u(33, 0.0, 3.999))]
    use3d_mid = family in FAMILIES_3D_MID

    # Integer lattice periods: sx cells per u wrap for the MID domain (the y
    # period gets a +2 margin so pulse breathing and warp offsets never expose
    # a repeat), and a small integer scale for the DEEP domain.
    sx = max(2, round(mid_scale))
    syp = sx + 2
    _deep_sx = max(1, round(u(32, 1.2, 2.6)))  # v2 deep lattice: superseded
    c["sx"] = sx                               # by the 3D sphere-dir domain
    c["syp"] = syp
    c["PX"] = F(float(sx))

    def qs(i, lo, hi):
        return F6(quant_sin_speed(u(i, lo, hi)))

    def qsc(k):
        return F6(quant_sin_speed(k))

    def qpx(i, lo, hi):
        return F6(quant_drift(u(i, lo, hi), float(sx)))

    def qpy(i, lo, hi):
        return F6(quant_drift(u(i, lo, hi), float(syp)))

    c["qs"] = qs
    c["qsc"] = qsc
    c["qpx"] = qpx
    c["qpy"] = qpy
    # warp1/warp2 internal drifts, day-quantized against the MID period.
    c["warpDriftY"] = F6(quant_drift(0.31, float(syp)))
    c["warpDriftX"] = F6(quant_drift(0.27, float(sx)))
    c["warp2DriftY"] = F6(quant_drift(0.21, float(syp)))

    mid_needs, mid_lines, post_lines = mid_composer(family, c)

    needs = set(mid_needs)
    needs.add("invsmooth")      # emitted into every shader (portability rule)
    needs.add("cellHash")       # micro grain
    needs.add("accentPalette")  # bounded accent in the composite
    needs.add("rotA")           # day-safe 3D rotation of the deep volume
    needs.add("gradient3")      # 3-stop runtime palette grade
    needs.add("satLift")        # anti-washout saturation lift
    needs.add("hueSpin")        # hue-nudged highlight stop
    needs.add("deepField")
    needs.update(deep_field_deps(asg["deep"]))

    # Anim mode -> `vec2 auv` (scaled, animated UV). The u axis is always
    # baseUV.x * sx (+ seam-safe shear/drift terms only), so one u wrap spans
    # exactly the integer lattice period and scroll speeds day-quantize.
    anim = asg["anim"]
    anim_lines = []
    sx_lit = F(float(sx))
    sy_lit = F(float(sx))
    if anim == "scroll":
        anim_lines.append(
            f"vec2 auv = vec2(baseUV.x * {sx_lit}, baseUV.y * {sy_lit}) + vec2({F6(quant_drift(u(34, -0.5, 0.5), float(sx)))}, {F6(quant_drift(u(35, -0.5, 0.5), float(syp)))}) * time;")
    elif anim == "rotate":
        anim_lines += [
            "// seam-safe stand-in for domain rotation: true rotation would mix",
            "// latitude into longitude and break the u wrap, so the pattern",
            "// leans (shears) back and forth instead.",
            f"float sway = {F(u(34, 0.5, 1.5))} * sin(time * {qs(35, 0.10, 0.30)});",
            f"vec2 auv = vec2(baseUV.x * {sx_lit} + (baseUV.y - 0.5) * sway + time * {F6(quant_drift(u(36, -0.15, 0.15), float(sx)))}, baseUV.y * {sy_lit});",
        ]
    elif anim == "pulse":
        anim_lines += [
            f"float breathe = 1.0 + {F(u(34, 0.04, 0.12))} * sin(time * {qs(35, 0.6, 1.6)});",
            "// only the y axis breathes; the x scale stays fixed so the u wrap",
            "// stays exact (the y period carries a margin for the stretch)",
            f"vec2 auv = vec2(baseUV.x * {sx_lit} + time * {F6(quant_drift(u(36, -0.3, 0.3), float(sx)))}, (baseUV.y - 0.5) * {sy_lit} * breathe + {F(sx * 0.5)} + time * {F6(quant_drift(u(37, -0.3, 0.3), float(syp)))});",
        ]
    else:  # flicker
        needs.add("hash11")
        anim_lines += [
            f"float jump = hash11(floor(time * {F(u(34, 2.0, 5.0))}));",
            f"float ft = time + {F(u(35, 0.15, 0.45))} * jump;",
            f"vec2 auv = vec2(baseUV.x * {sx_lit}, baseUV.y * {sy_lit}) + vec2({F6(quant_drift(u(36, -0.5, 0.5), float(sx)))}, {F6(quant_drift(u(37, -0.5, 0.5), float(syp)))}) * ft;",
        ]

    # Warp mode -> `vec2 wuv`. Warp offsets are seam-periodic fields, so the
    # warped domain keeps the exact integer x period.
    warp = asg["warp"]
    if warp == "none":
        warp_lines = ["vec2 wuv = auv;"]
    elif warp == "warp1":
        needs.add("warp1")
        warp_lines = ["vec2 wuv = warp1(auv, midPer, time);"]
    elif warp == "warp2":
        needs.update(("warp1", "warp2"))
        warp_lines = ["vec2 wuv = warp2(auv, midPer, time);"]
    else:  # curl
        needs.add("curl2")
        warp_lines = [
            f"vec2 wuv = auv + {F(u(38, 0.10, 0.28))} * curl2(auv + vec2(0.0, time * {F6(quant_drift(0.07, float(syp)))}), midPer);"]

    # Rim style -> `float rim` (+ optional composite post lines).
    rim = asg["rim"]
    rim_post = []
    rim_k = F(u(50, 0.6, 1.0))
    if rim == "graze":
        needs.add("rimGraze")
        rim_lines = [f"float rim = rimGraze() * {rim_k};"]
    elif rim == "lat":
        needs.add("rimLat")
        rim_lines = [f"float rim = rimLat(baseUV) * {rim_k};"]
    elif rim == "graze_film":
        needs.update(("rimGraze", "thinFilm"))
        rim_lines = [f"float rim = rimGraze() * {rim_k};"]
        rim_post = [
            f"vec3 rimFilm = thinFilm({F(u(51, 0.3, 1.2))} + pattern * {F(u(52, 0.8, 1.8))} + baseUV.y * {F(u(53, 0.5, 1.4))});",
            f"rgb = mix(rgb, rgb * (0.6 + 0.8 * rimFilm), clamp(rim, 0.0, 1.0) * {F(u(54, 0.25, 0.40))});",
        ]
    else:  # graze_sparkle
        needs.update(("rimGraze", "sparkle"))
        glint_px = 10 + int(u(51, 0.0, 8.999))  # integer: seam-aligned glints
        rim_lines = [
            f"float rimGlint = sparkle(baseUV * {F(float(glint_px))}, time, {F(float(glint_px))});",
            f"float rim = rimGraze() * {rim_k} * (0.7 + 0.6 * rimGlint) + {F(u(52, 0.3, 0.6))} * rimGlint;",
        ]

    # Flourish accent micro-layer (adds signature detail, not part of the tuple).
    if flourish == "swirl":
        needs.add("fbm2")
        flourish_lines = [
            f"float flourish = {F(u(60, 0.10, 0.28))} * pow(clamp(fbm2(wuv + vec2(-time * {F6(quant_drift(u(61, 0.07, 0.15), float(sx)))}, time * {F6(quant_drift(0.07, float(syp)))}), midPer), 0.0, 1.0), 2.0);"]
    elif flourish == "glint":
        needs.add("sparkle")
        flourish_lines = [
            f"float flourish = {F(u(60, 0.15, 0.35))} * sparkle(wuv * 2.0 + 7.7, time * 1.4, midPer.x * 2.0);"]
    elif flourish == "echo":
        needs.add("ringPulse")
        flourish_lines = [
            f"float flourish = {F(u(60, 0.12, 0.30))} * ringPulse(baseUV + vec2(0.13, 0.31), time);"]
    else:  # shimmer
        needs.add("caustic")
        flourish_lines = [
            f"float flourish = {F(u(60, 0.08, 0.20))} * caustic(wuv + vec2(3.1, 8.7), vec2({F6(quant_drift(u(61, 0.10, 0.20), float(sx)))}, {F6(-quant_drift(0.12, float(syp)))}) * time, midPer);"]

    # Resolve helper closure over deps (deepField's deps depend on the recipe).
    closed = set()
    stack = sorted(needs)
    while stack:
        n = stack.pop()
        if n in closed:
            continue
        closed.add(n)
        deps = deep_field_deps(asg["deep"]) if n == "deepField" else HELPER_DEPS[n]
        for d in deps:
            if d not in closed:
                stack.append(d)
    helper_names = [n for n in CANONICAL_ORDER if n in closed]

    dw = F(u(62, 0.30, 0.50))
    mw = F(u(63, 0.70, 1.00))
    rw = F(u(64, 0.50, 0.90))
    ap0 = F(u(65, 0.0, 1.0))
    ap1 = F(u(66, 0.30, 0.70))
    _b0 = u(67, 0.35, 0.55)   # v2 composite knobs: drawn for stream stability
    _b1 = u(68, 0.55, 0.95)   # (indices 67/68/70/71 are no longer emitted)
    aw = F(u(69, 0.15, 0.45))
    _a0 = u(70, 0.10, 0.25)
    _a1 = u(71, 0.60, 0.85)
    gk = F(u(72, 0.04, 0.10))
    grain_scale = 24 + int(u(73, 0.0, 40.999))  # integer: seam-aligned grain
    d_fall = u(75, 0.55, 0.95)
    d_pow = F(u(76, 1.0, 1.8))
    d_step = u(77, 0.03, 0.08)
    _ddx = u(78, -0.05, 0.05)  # v2 lattice drifts: drawn for stream stability
    _ddy = u(79, -0.05, 0.05)  # (3D-domain animation is rotation-only)

    # gradient3 palette + presence-alpha knobs (indices 84..95).
    dk = F(u(84, 0.55, 0.85))          # dark-stop depth (base*base*dk)
    hk = F(u(85, 0.55, 0.85))          # hot-stop screen-blend strength
    hue_ang = F(u(86, -0.6981, 0.6981))  # highlight hue nudge, +-40 deg
    sat = F(u(88, 1.08, 1.35))         # saturation lift
    hlw = F(u(89, 0.15, 0.35))         # additive hot highlight weight
    pk = F(u(90, 0.70, 0.95))          # pattern -> gradient position scale
    rk = F(u(91, 0.12, 0.30))          # chromatic rim gradient offset
    a_base = F(u(92, 0.04, 0.08))      # alpha where no pattern (dark, thin)
    a_floor = F(u(93, 0.24, 0.37))     # presence floor: solid membrane read
    a_gain = F(u(94, 0.32, 0.50))      # alpha rise on bright features
    a_max = F(u(95, 0.78, 0.86))       # translucency ceiling

    # v3 3D-depth knobs (indices 96..127).
    def unit3(i0):
        v = (u(i0, -1.0, 1.0), u(i0 + 1, -1.0, 1.0), u(i0 + 2, -1.0, 1.0))
        n = math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if n < 0.25:  # nearly-zero draw: fall back to a fixed oblique axis
            return (0.4851, 0.7276, 0.4851)
        return (v[0] / n, v[1] / n, v[2] / n)

    spin_axis = unit3(96)
    plane_g = u(99, 0.30, 0.60)        # per-plane perspective scale growth
    deep_s0 = u(100, 1.6, 3.0)         # front-plane domain scale
    base_turns = max(taps + 2, round(u(101, 0.05, 0.16) * DAY_SECONDS / TWO_PI))
    aer = u(102, 0.55, 0.90)           # aerial-perspective tint reach
    par1 = unit3(103)
    par2 = unit3(106)
    mwc = F(u(110, 0.85, 1.15))        # mid coverage over the deep volume
    rwc = F(u(111, 0.40, 0.70))        # rim coverage over the deep volume
    mid3_scale = F(mid_scale * u(112, 0.18, 0.30))
    mid3_speed = F6(quant_sin_speed(u(113, 0.02, 0.06)))
    d_bright = u(116, 0.95, 1.30)      # deep volume brightness
    line_l0 = u(117, 0.045, 0.075)     # thin hot rim line band
    line_l1 = line_l0 + u(118, 0.020, 0.040)
    line_w = F(u(119, 0.50, 0.90))     # hot line lift into rim
    line_disp_w = F(u(120, 0.18, 0.32))  # hot-line dispersion mix (bounded)
    line_disp_f = F(u(121, 0.5, 1.2))
    line_disp_p = F(u(122, 0.0, 1.0))

    # Correlated multi-plane parallax (v3): ONE deep field sampled at `taps`
    # depth planes on the 3D sphere direction. Real parallax law per plane:
    # farther planes show finer features (scale * (1 + i*g)), rotate slower
    # (strictly decreasing integer turns/day), shift along the silhouette
    # slope (rimDir), and recede toward the dark stop (aerial perspective),
    # with front-to-back exponential weights.
    tap_lines = []
    norm = 0.0
    prev_turns = None
    for i in range(taps):
        w = math.exp(-i * d_fall)
        norm += w
        turns = max(1, round(base_turns / (1.0 + i * plane_g)))
        if prev_turns is not None and turns >= prev_turns:
            turns = max(1, prev_turns - 1)  # strictly slower with depth
        prev_turns = turns
        speed = F6(turns * TWO_PI / DAY_SECONDS)
        scale = F(deep_s0 * (1.0 + i * plane_g))
        tint = F(aer * i / (taps - 1.0))
        sample = f"deepField(rotA(spinAxis, time * {speed}) * (sdir * {scale})"
        if i > 0:
            sample += f" + par * {F(i * d_step)}"
        sample += ", time)"
        if i == 0:
            tap_lines.append(f"float dp = {sample};")
            tap_lines.append(f"vec3 deepCol = {F(w)} * dp * baseCol;")
            tap_lines.append(f"float deepPat = {F(w)} * dp;")
        else:
            tap_lines.append(f"dp = {sample};")
            tap_lines.append(f"deepCol += {F(w)} * dp * mix(baseCol, deepStop, {tint});")
            tap_lines.append(f"deepPat += {F(w)} * dp;")
    tap_lines.append(f"deepCol *= {F(d_bright / norm)};")
    tap_lines.append(f"deepPat = pow(clamp(deepPat * {F(1.0 / norm)}, 0.0, 1.0), {d_pow});")

    deep_marker = f"parallax3d_{asg['deep']}_x{taps}"
    mid_marker = f"{family.lower()}_{warp}_{anim}"

    lines = []
    lines.append("#version 330")
    lines.append("")
    lines.append("#moj_import <minecraft:fog.glsl>")
    lines.append("#moj_import <minecraft:globals.glsl>")
    lines.append("")
    lines.append(f"// Bubble surface shader fx_{effect_id:03d} -- family {family}")
    lines.append(f"// stack: deep={asg['deep']} x{taps} taps | mid={family.lower()}+{warp}+{anim} | rim={rim} | flourish={flourish}")
    lines.append(f"// fbm: {c['fbmMode']} x{c['octaves']} octaves | seed {asg['seed']:016x}")
    lines.append("// GENERATED by tools/gen_surface_shaders.py -- do not hand-edit; edit the")
    lines.append("// generator and regenerate instead (byte-stable, fixed seed).")
    lines.append("")
    lines.append("in vec2 texCoord0;")
    lines.append("in vec4 vertexColor;")
    lines.append("in float sphericalVertexDistance;")
    lines.append("in float cylindricalVertexDistance;")
    lines.append("")
    lines.append("out vec4 fragColor;")
    lines.append("")
    for hn in helper_names:
        lines.append(helper_source(hn, c))
        lines.append("")
    lines.append("void main() {")
    lines.append("    // GameTime spans one day cycle in [0, 1); scale to roughly seconds.")
    lines.append("    // All constant speeds below are day-quantized (integer cycles or")
    lines.append("    // integer lattice periods per day) so the daily wrap does not pop.")
    lines.append("    float time = GameTime * 1200.0;")
    lines.append("    // texCoord0 is the raw sphere UV, already in [0,1]; fract() is only a")
    lines.append("    // defensive wrap. Seam-freedom in u comes from the wrapping-lattice /")
    lines.append("    // periodic-domain sampling below, NOT from this fract().")
    lines.append("    vec2 baseUV = fract(texCoord0);")
    lines.append(f"    vec2 midPer = vec2({F(float(sx))}, {F(float(syp))});")
    lines.append("    // 3D sphere direction reconstructed from the UV (u -> longitude,")
    lines.append("    // v -> latitude): any field sampled on it is periodic across the")
    lines.append("    // u = 0/1 seam AND uniform at the poles by construction.")
    lines.append("    vec3 sdir = vec3(sin(3.1415927 * baseUV.y) * cos(6.2831853 * baseUV.x),")
    lines.append("        cos(3.1415927 * baseUV.y),")
    lines.append("        sin(3.1415927 * baseUV.y) * sin(6.2831853 * baseUV.x));")
    lines.append("")
    lines.append("    // [palette:gradient3]")
    lines.append("    // Runtime 3-stop palette derived from vertexColor.rgb: the dark stop")
    lines.append("    // darkens AND saturates (base*base stays in-hue instead of greying),")
    lines.append("    // the hot stop screen-blends a hue-nudged base toward white. The")
    lines.append("    // owner /color override replaces vertexColor wholesale, so the whole")
    lines.append("    // ramp re-derives from it -- recolor-safe by construction.")
    lines.append("    vec3 baseCol = vertexColor.rgb;")
    lines.append(f"    vec3 deepStop = baseCol * baseCol * {dk};")
    lines.append(f"    vec3 spun = clamp(hueSpin(baseCol, {hue_ang}), 0.0, 1.0);")
    lines.append(f"    vec3 hotStop = clamp(1.0 - (1.0 - spun) * (1.0 - spun) * {hk}, 0.0, 1.0);")
    lines.append("")
    lines.append(f"    // [layer:deep:{deep_marker}]")
    lines.append("    // Interior volume: correlated parallax PLANES of ONE deep field on")
    lines.append("    // the 3D sphere direction. Farther planes show finer features, spin")
    lines.append("    // slower (integer turns/day: the daily wrap lands on a full turn),")
    lines.append("    // slide along the silhouette slope (rimDir is screen-space, so it is")
    lines.append("    // seam-safe) and recede toward the dark stop (aerial perspective).")
    lines.append("    vec2 rimDirRaw = vec2(dFdx(sphericalVertexDistance), dFdy(sphericalVertexDistance));")
    lines.append("    vec2 rimDir = rimDirRaw / (length(rimDirRaw) + 0.0001);")
    lines.append(f"    vec3 spinAxis = vec3({F(spin_axis[0])}, {F(spin_axis[1])}, {F(spin_axis[2])});")
    lines.append(f"    vec3 par = rimDir.x * vec3({F(par1[0])}, {F(par1[1])}, {F(par1[2])}) + rimDir.y * vec3({F(par2[0])}, {F(par2[1])}, {F(par2[2])});")
    for ln in tap_lines:
        lines.append("    " + ln)
    lines.append("")
    lines.append(f"    // [layer:mid:{mid_marker}]")
    lines.append("    // Signature structure of this effect, domain-warped and animated.")
    if use3d_mid:
        lines.append("    // This family's signature field also lives on the sphere direction")
        lines.append("    // (seam- and pole-free) and TRULY rotates, day-safely.")
        lines.append(f"    vec3 mdir = rotA(spinAxis, time * {mid3_speed}) * (sdir * {mid3_scale});")
    for ln in anim_lines:
        lines.append("    " + ln)
    for ln in warp_lines:
        lines.append("    " + ln)
    for ln in mid_lines:
        lines.append("    " + ln)
    lines.append("")
    lines.append(f"    // [layer:rim:{rim}]")
    lines.append("    // Silhouette / band lift so the membrane reads as a curved shell:")
    lines.append("    // a wide soft inner glow (style-specific) plus a thin hot line at")
    lines.append("    // the very edge. The slope is normalized by the camera distance so")
    lines.append("    // the rim width stays view-consistent near and far.")
    lines.append("    float rimSlope = fwidth(sphericalVertexDistance) / max(sphericalVertexDistance, 1.0);")
    lines.append(f"    float rimLine = smoothstep({F(line_l0)}, {F(line_l1)}, rimSlope);")
    for ln in rim_lines:
        lines.append("    " + ln)
    lines.append(f"    rim = clamp(rim + {line_w} * rimLine, 0.0, 1.4);")
    lines.append("")
    lines.append("    // Flourish accent + micro grain keep large areas alive up close.")
    for ln in flourish_lines:
        lines.append("    " + ln)
    lines.append(f"    float grain = {gk} * (cellHash(floor(wuv * {F(float(grain_scale))}) + vec2(floor(time * 6.0), 0.0), {F(float(sx * grain_scale))}) - 0.5);")
    lines.append("")
    lines.append("    // Recolor-safe composite v3: the whole pattern is graded through the")
    lines.append("    // vertexColor-derived 3-stop ramp (low pattern falls to the DARK stop")
    lines.append("    // and low alpha -- never pale grey), the deep volume sits BEHIND the")
    lines.append("    // signature structure, and the gradient position leans toward the hot")
    lines.append("    // stop at the rim (chromatic rim). The vertexColor.a dissolve near")
    lines.append("    // whitelisted players always wins the final alpha.")
    lines.append(f"    float pattern = clamp({dw} * deepPat + {mw} * mid + {rw} * rim + flourish + grain, 0.0, 1.5);")
    lines.append(f"    float gpos = clamp(pattern * {pk} + rim * {rk}, 0.0, 1.0);")
    lines.append("    vec3 rgb = gradient3(deepStop, baseCol, hotStop, gpos);")
    lines.append(f"    float midCover = clamp({mwc} * mid + {rwc} * rim, 0.0, 1.0);")
    lines.append("    rgb = mix(deepCol, rgb, midCover);")
    lines.append(f"    rgb = satLift(rgb, {sat});")
    lines.append(f"    rgb += hotStop * {hlw} * smoothstep(0.72, 1.0, pattern);")
    lines.append(f"    vec3 accent = accentPalette({ap0} + pattern * {ap1});")
    lines.append(f"    rgb = mix(rgb, rgb * (0.55 + 0.9 * accent), {aw});")
    for ln in post_lines + rim_post:
        lines.append("    " + ln)
    lines.append("    // Two-band chromatic dispersion on the rim (thin-film-like), biased")
    lines.append("    // to vertexColor.rgb: band 1 multiplies the wide glow into the")
    lines.append("    // palette-driven rgb, band 2 pulls the thin hot line toward the (also")
    lines.append("    // vertexColor-derived) hot stop. Both bounded -- the owner recolor")
    lines.append("    // override stays authoritative on every rim style.")
    lines.append(f"    vec3 rimDisp = 0.5 + 0.5 * cos(vec3(1.0, 0.8065, 0.6452) * (rim * {F(u(80, 0.6, 1.2))} + baseUV.y * {F(u(81, 0.3, 0.8))} + {F(u(82, 0.0, 1.0))}) * 6.2831853);")
    lines.append(f"    rgb = mix(rgb, rgb * (0.72 + 0.56 * rimDisp), clamp(rim, 0.0, 1.0) * {F(u(83, 0.10, 0.22))});")
    lines.append(f"    vec3 lineDisp = 0.5 + 0.5 * cos(vec3(0.6452, 0.8065, 1.0) * (rimLine * {line_disp_f} + baseUV.y * 0.31 + {line_disp_p}) * 6.2831853);")
    lines.append(f"    rgb = mix(rgb, hotStop * (0.62 + 0.50 * lineDisp), clamp(rimLine, 0.0, 1.0) * {line_disp_w});")
    lines.append("    // Presence alpha: a solid-but-translucent membrane floor wherever the")
    lines.append("    // pattern is present, rising toward the ceiling on bright features;")
    lines.append("    // pattern-free areas stay dark AND thin (anti-washout).")
    lines.append(f"    float presence = smoothstep(0.02, 0.30, pattern);")
    lines.append(f"    float alpha = vertexColor.a * min({a_base} + {a_floor} * presence + {a_gain} * pattern, {a_max});")
    lines.append("    vec4 color = vec4(clamp(rgb, 0.0, 1.0), alpha);")
    lines.append("    if (color.a < 0.01) {")
    lines.append("        discard;")
    lines.append("    }")
    lines.append("    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, "
                 "FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);")
    lines.append("}")

    source = "\n".join(lines) + "\n"
    n = source.count("\n")
    if not 110 <= n <= 380:
        sys.exit(f"fx_{effect_id:03d}: emitted {n} lines, outside the 110..380 sanity bounds")
    return source


def manifest_entry(asg: dict, source: str) -> dict:
    rng = Rng(asg["seed"])
    draws = [rng.unit() for _ in range(128)]

    def u(i, lo, hi):
        return lo + (hi - lo) * draws[i]

    return {
        "file": f"fx_{asg['id']:03d}.fsh",
        "family": asg["family"],
        "warp": asg["warp"],
        "deep": asg["deep"],
        "rim": asg["rim"],
        "anim": asg["anim"],
        "taps": 3 + int(u(30, 0.0, 1.999)),
        "fbmMode": FBM_MODES[int(u(1, 0.0, 2.999))],
        "octaves": 3 + int(u(0, 0.0, 3.999)),
        "flourish": FLOURISHES[int(u(33, 0.0, 3.999))],
        "paletteMode": "gradient3",
        "mid3d": asg["family"] in FAMILIES_3D_MID,
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
                                       "default: all 350. Emitted bytes are identical to a full run.")
    parser.add_argument("--out", help="output directory override (default: the repo bubble shader dir); "
                                      "the manifest is then written next to the shaders instead of tools/.")
    args = parser.parse_args()

    ids = set(parse_only(args.only)) if args.only else set(range(COUNT))
    out_dir = Path(args.out) if args.out else BUBBLE_DIR
    manifest_path = (out_dir / "surface_manifest.json") if args.out else DEFAULT_MANIFEST
    out_dir.mkdir(parents=True, exist_ok=True)

    assignments = build_assignments()  # always the full table: bytes never depend on --only
    # The manifest is ALWAYS the full COUNT-entry table -- only the FILE writes
    # are restricted by --only. (Writing a subset manifest used to clobber the
    # committed full manifest on partial runs, which then failed validation.)
    manifest = {}
    written = 0
    for asg in assignments:
        source = emit_shader(asg)
        manifest[str(asg["id"])] = manifest_entry(asg, source)
        if asg["id"] in ids:
            (out_dir / f"fx_{asg['id']:03d}.fsh").write_text(source, encoding="utf-8", newline="\n")
            written += 1

    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n",
                             encoding="utf-8", newline="\n")
    line_counts = [e["lines"] for e in manifest.values()]
    print(f"wrote {written} shaders to {out_dir}")
    print(f"manifest: {manifest_path} ({len(manifest)} entries, always the full table)")
    print(f"line counts: min {min(line_counts)}, max {max(line_counts)}")
    if args.only:
        print(f"NOTE: partial run ({args.only}); only {written} files were (re)written, "
              f"but the manifest still covers all {COUNT} ids.")


if __name__ == "__main__":
    main()

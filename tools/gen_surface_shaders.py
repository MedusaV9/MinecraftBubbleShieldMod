#!/usr/bin/env python3
"""Deterministic generator for the per-effect bubble surface shaders (fx_000..fx_419).

Running `python3 tools/gen_surface_shaders.py` (re)writes ALL of
src/client/resources/assets/bubbleshield/shaders/bubble/fx_000.fsh .. fx_419.fsh
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

* Real depth (v4): the DEEP layer is 3..4 CORRELATED parallax PLANES of ONE
  deep field sampled on the 3D sphere direction. Each plane obeys the real
  parallax law -- farther planes show finer features (per-plane scale
  1 + i*g), move slower (strictly decreasing integer turns/day rotation
  speeds), shift along the silhouette slope (rimDir, from screen-space
  derivatives of the camera-distance varying -- seam-safe by construction),
  and recede toward the palette's dark stop (aerial-perspective per-plane
  tint accumulated into a vec3). v4 composites the planes FRONT-TO-BACK
  with Beer-Lambert transmittance: each plane's density occludes the planes
  behind it (deepTrans *= 1 - absorb * dp), its light lands under the
  REMAINING transmittance into BOTH deepCol (per-plane color: baseCol in
  front receding through secCol to deepStop) and the deep OPACITY term
  (1 - deepTrans), which feeds the alpha path -- a real volume, not one
  averaged scalar. The deep volume is composited UNDER the MID signature
  (rgb = mix(deepCol, structure, midWeight)). The rim estimator normalizes fwidth(sphericalVertexDistance)
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

* Rich color (v4): the flat multiplicative composite (vertexColor.rgb *
  (b0 + b1 * pattern)) washed out to grey, and the v3 white-screen hotStop
  still bled toward pastel. Every shader now grades the pattern through a
  runtime 3-stop gradient derived from vertexColor.rgb:
  deepStop = base*base*dk (darker AND more saturated, stays in-hue),
  hotStop = a LUMA-CAPPED CHROMATIC highlight -- the base is hue-rotated
  (baked angle within +-40 deg), brightened and saturation-lifted, then its
  luma is capped by a baked ceiling so the highlight can NEVER lerp toward
  vec3(1.0). The old additive white highlight is gone; instead hot areas
  run through a hue-preserving SOFT-CLIP (1 - exp(-k * hotStop * x)) so
  crests saturate toward the palette's bright tint, not white. Then a
  FAMILY-TUNED saturation lift (see FAMILY_PRESENCE). Low-pattern areas
  fall to the dark stop + low alpha (darker/transparent, never pale grey).
  The gradient lookup is offset by rim * rk so silhouettes shift toward the
  hot stop (chromatic rim). The owner /color override replaces vertexColor
  wholesale, so the whole gradient re-derives from it: recolor safety is
  preserved. Every file carries the `// [palette:gradient3]` marker.

* Secondary palette (v4): the vertex format is POSITION_TEX_COLOR -- there
  is no spare attribute for a second color, and packing it into UV1 or a
  format change would touch every pipeline. Instead each shader derives
  `secCol` IN-SHADER from vertexColor.rgb via baked constants (hue-rotation
  angle + saturation/value ratios) computed at generation time from the
  registry's authored argbPrimary -> argbSecondary relation. Both colors
  therefore re-derive from the LIVE palette (owner /color override
  included): recolor-safe by construction. secCol tints the far parallax
  planes in every file and gives material roles to LAVAFLOW (crust),
  EMBERSTORM (smoke) and PORTALVOID (void interior).

* Family-tuned presence (v4): final alpha = vertexColor.a * min(aBase +
  aPresence * smoothstep(0.02, 0.30, pattern) + aGain * pattern + aVol *
  (1 - deepTrans), aMax), with aPresence/aGain/aMax (and the saturation
  lift + mid coverage) drawn from PER-FAMILY ranges (FAMILY_PRESENCE):
  pale/airy families (VOLUMECLOUD, CHROME, KALEIDO, NEBULA) get a solid
  floor + raised saturation so the sky never washes them out, while
  sparse-dark families (BIOLUME, STARFIELD, PORTALVOID, ...) keep low
  floors so their darkness reads. The vertexColor.a dissolve near
  whitelisted players still always wins, and discard stays at < 0.01.

* Helper snippet bank (inlined per file, ONLY the helpers a shader uses):
  invsmooth/safeAtan/hash11/hash21/hash22/cellHash, vnoise (quintic fade,
  wrapping), fbm2 (modes standard/ridged/turb, <=6 octaves), warp1/warp2 (iq
  domain warp), curl2, voro2 (F1/F2/exact-border/cell-id, wrapping),
  voronoise, hexDist/hexCoords, triGrid, truchet, polarFold, spiralWarp,
  caustic, thinFilm, accentPalette (iq cosine, baked consts), rimGraze,
  rimLat, sparkle, ringPulse, and the unrolled correlated parallax deep stack.

* 60 MID-layer technique composers keyed by the SurfaceTemplate families:
  the 16 original enum names (PLASMA..LIGHTNING) + THINFILM, CAUSTIC,
  CURLSMOKE, TRUCHET, RIDGED, MOIRE, TRIWEAVE, NEBULA (350 milestone) +
  KALISET, VOLUMECLOUD, CHROME, LAVAFLOW, TENDRILNET, GALAXYSWIRL,
  RIBBONAURORA, FROSTFERN, BIOLUME, HOLOGRID, PORTALVOID, EMBERSTORM,
  SHARDTESS, SACREDGEO, VOIDTENDRIL, CRYSTALREFRACT (420 milestone) +
  the 20 v5 technique families (SPECTRALVEIL..VOIDRIFT, FAMILIES_V5) staged
  for the NEXT catalogue flip: no EffectRegistry row references them yet, so
  they emit no fx_* file today and every pre-v5 file stays byte-identical.

* v5 quality layer (FAMILIES_V5 ONLY -- the gate is what keeps the 420
  existing files byte-stable): gl_FrontFacing back-face densify/dim (the far
  shell dims toward the dark stop and gains alpha), a 3-tap slope-parallax
  resample of the deep field along the silhouette slope, luminance-weighted
  ghost alpha (dark areas thin out, bright features hold), blue-noise-style
  (interleaved gradient noise) alpha dithering scaled by the dissolve, and a
  soft-knee highlight rolloff. Only builtins (gl_FrontFacing, gl_FragCoord)
  -- no new uniforms, no textures; the vertexColor.a dissolve still wins.

* Per-id assignment table: EVERY id reads its family from the current
  EffectRegistry.java surface column (parsed at generation time as ground
  truth) -- the registry, not any modulo cycle, decides which family an id
  renders, so adding families can never reassign existing ids. The
  accentPalette base color is likewise sourced from the registry's argbPrimary
  (not random), so accents always lean toward the effect's authored palette.
  Every id gets a distinct (family, warpMode, deepStack, rimStyle, animMode)
  tuple, guaranteed by deterministic probing and re-asserted here and by
  tools/validate_shaders.py.

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
    python3 tools/gen_surface_shaders.py                  # all 420 + manifest
    python3 tools/gen_surface_shaders.py --only 0-15      # subset (same bytes)
    python3 tools/gen_surface_shaders.py --only 0-15 --out /tmp/probe
"""

import argparse
import colorsys
import json
import math
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
BUBBLE_DIR = REPO_ROOT / "src/client/resources/assets/bubbleshield/shaders/bubble"
REGISTRY_JAVA = REPO_ROOT / "src/main/java/com/bubbleshield/effect/EffectRegistry.java"
DEFAULT_MANIFEST = REPO_ROOT / "tools/surface_manifest.json"

COUNT = 420
GLOBAL_SEED = 0xB0BB7E5D

# The 40 SurfaceTemplate enum names (enum order): the 16 originals, the 8 added
# for the 350 milestone and the 16 added for the 420 milestone.
FAMILIES = [
    "PLASMA", "HEX", "WAVES", "AURORA", "SPARKLE", "RINGS", "VORONOI", "ARCS",
    "SCALES", "STARFIELD", "VORTEX", "INTERFERENCE", "KALEIDO", "CIRCUIT",
    "PETALS", "LIGHTNING",
    "THINFILM", "CAUSTIC", "CURLSMOKE", "TRUCHET", "RIDGED", "MOIRE",
    "TRIWEAVE", "NEBULA",
    "KALISET", "VOLUMECLOUD", "CHROME", "LAVAFLOW", "TENDRILNET",
    "GALAXYSWIRL", "RIBBONAURORA", "FROSTFERN", "BIOLUME", "HOLOGRID",
    "PORTALVOID", "EMBERSTORM", "SHARDTESS", "SACREDGEO", "VOIDTENDRIL",
    "CRYSTALREFRACT",
    # 20 v5 technique families (append-only; no registry row references them
    # yet, so regenerating leaves fx_000..fx_419 byte-identical -- the
    # assignment table reads each id's family from EffectRegistry.java).
    "SPECTRALVEIL", "RAYMARCHFOG", "PRISMDISPERSE", "HOLOPARALLAX",
    "ORBITTRAP", "CRYSTALSDF", "FLUIDINK", "IRISFILM", "AETHERSMOKE",
    "STAINEDGLASS", "PHANTOMECHO", "GRAVLENS", "MYCELIA", "SOLARFLARE",
    "DEEPICE", "RUNECIRCUIT", "OILSLICK", "PLASMAGLOBE", "ECTOPLASM",
    "VOIDRIFT",
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
# family's DEEP volume goes 3D regardless. Of the 420-milestone families,
# the volumetric/field techniques (KALISET's fractal fold, VOLUMECLOUD's
# transmittance march, CHROME's gradient normals, VOIDTENDRIL's anisotropic
# ridges) live on the sphere direction too.
FAMILIES_3D_MID = frozenset({
    "PLASMA", "ARCS", "LIGHTNING", "CURLSMOKE", "RIDGED", "NEBULA",
    "KALISET", "VOLUMECLOUD", "CHROME", "VOIDTENDRIL",
    # v5 families whose signature field lives on the sphere direction: the
    # veils/fog/smoke/fractal/facet/refraction techniques sample mdir (and so
    # gain the true day-safe 3D rotation); GRAVLENS/PHANTOMECHO build their
    # own rotations from raw sdir and the polar/lattice v5 families keep the
    # proven seam-safe 2D domains.
    "SPECTRALVEIL", "RAYMARCHFOG", "PRISMDISPERSE", "ORBITTRAP",
    "CRYSTALSDF", "AETHERSMOKE", "VOIDRIFT",
})

# The 20 v5 technique families (appended to FAMILIES for the next catalogue
# flip). GATED: only these families receive the v5 quality layer (backface
# densify/dim, slope-parallax taps, luminance-weighted ghost alpha, blue-noise
# dither, soft-knee rolloff) -- keying the layer off this set is what keeps
# every pre-v5 file byte-identical under regeneration.
FAMILIES_V5 = frozenset(FAMILIES[40:])
assert len(FAMILIES_V5) == 20 and "CRYSTALREFRACT" not in FAMILIES_V5

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

    The registry is the GROUND TRUTH for every id's surface family: requiring
    the full dense 0..COUNT-1 table here (and reading the family from it in
    build_assignments) means adding new families can never reassign an
    existing id -- the old modulo cycle for ids >= 105 silently re-dealt EVERY
    expansion id whenever len(FAMILIES) changed. Palettes are taken from the
    registry for every id too, so the baked accentPalette always leans toward
    the effect's authored primary color.
    """
    text = REGISTRY_JAVA.read_text(encoding="utf-8")
    pattern = re.compile(
        r"row\((\d+),\s*0x([0-9A-Fa-f]{6}),\s*0x([0-9A-Fa-f]{6}),\s*\"([a-z]+)\"")
    rows = {}
    for m in pattern.finditer(text):
        rows[int(m.group(1))] = (m.group(4).upper(), int(m.group(2), 16), int(m.group(3), 16))
    if sorted(rows) != list(range(COUNT)):
        sys.exit(f"EffectRegistry.java parse failed: found {len(rows)} rows, expected dense ids 0..{COUNT - 1}")
    return rows


def build_assignments() -> list:
    """Builds the full COUNT-row assignment table (always computed over ALL ids
    so partial --only runs emit byte-identical files to a full run)."""
    registry = parse_registry()
    rows = []
    used = set()
    for effect_id in range(COUNT):
        # Family AND palette come straight from the registry row: the surface
        # column is authored there (ground truth for every id), and the baked
        # accentPalette must lean toward the effect's AUTHORED primary color,
        # not a random hue.
        family = registry[effect_id][0]
        prim = registry[effect_id][1]
        sec = registry[effect_id][2]
        if family not in FAMILIES:
            sys.exit(f"id {effect_id}: EffectRegistry surface family {family} "
                     f"has no MID composer in this generator (FAMILIES is out of date)")
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
            "secondary": sec,
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
    "sdSeg": [],
    "deepField": [],  # deps filled per deep recipe at emission time
}

CANONICAL_ORDER = [
    "invsmooth", "safeAtan", "rotA", "hash11", "hash21", "hash22", "hash31",
    "hash33", "cellHash", "vnoise", "vnoise3", "fbm2", "fbm3", "warp1",
    "warp2", "curl2", "voro2", "voro3", "voronoise", "hexDist", "hexCoords",
    "triGrid", "truchet", "polarFold", "spiralWarp", "caustic", "caustic3",
    "thinFilm", "accentPalette", "gradient3", "satLift", "hueSpin",
    "rimGraze", "rimLat", "sparkle", "ringPulse", "sdSeg", "deepField",
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
    if name == "sdSeg":
        return (
            "// distance from p to the line segment a-b (classic iq segment SDF);\n"
            "// used by the branch/filament/glyph v5 families\n"
            "float sdSeg(vec2 p, vec2 a, vec2 b) {\n"
            "    vec2 pa = p - a;\n"
            "    vec2 ba = b - a;\n"
            "    float h = clamp(dot(pa, ba) / max(dot(ba, ba), 1e-6), 0.0, 1.0);\n"
            "    return length(pa - ba * h);\n"
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
    if family == "KALISET":
        # 3D: the classic Kaliset fractal fold p = |p|/dot(p,p) - c iterated on
        # the rotating sphere direction; the per-iteration orbit-length deltas
        # accumulate into star-nest filaments. v4: an ORBIT-TRAP (closest
        # approach to the origin) drives concentric banding along the
        # filaments, the interstitials fall harder to the dark stop (bigger
        # bias + steeper pow), and the deep volume is down-weighted
        # (DEEP_WEIGHT) so the fractal signature reads. The dot() is floored
        # so the fold can never blow up near the origin.
        return ([], [
            f"vec3 kp = mdir * {F(u(40, 0.8, 1.4))};",
            f"vec3 kc = vec3({F(u(41, 0.45, 0.75))}, {F(u(42, 0.55, 0.85))}, {F(u(43, 0.35, 0.65))});",
            "float kacc = 0.0;",
            "float kprev = 0.0;",
            "float ktrap = 100.0;",
            "for (int i = 0; i < 5; i++) {",
            "    kp = abs(kp) / max(dot(kp, kp), 0.18) - kc;",
            "    float km = length(kp);",
            "    kacc += abs(km - kprev);",
            "    kprev = km;",
            "    ktrap = min(ktrap, km);",
            "}",
            f"float nest = pow(clamp(kacc * {F(u(44, 0.16, 0.26))} - {F(u(45, 0.16, 0.30))}, 0.0, 1.0), {F(u(46, 2.0, 3.2))});",
            f"float kband = pow(0.5 + 0.5 * sin(ktrap * {F(u(55, 9.0, 16.0))} - time * {qs(47, 0.4, 0.9)}), {F(u(56, 2.0, 4.0))});",
            f"float mid = clamp(nest * (0.55 + 0.45 * kband) + kband * {F(u(48, 0.18, 0.30))} * smoothstep(0.10, 0.55, nest), 0.0, 1.25);",
        ], [])
    if family == "VOLUMECLOUD":
        # 3D: a 4-step front-to-back transmittance march INTO the shell along
        # a baked pseudo view ray -- every step samples the same fbm3 density
        # deeper and finer, light accumulates under the REMAINING transmittance.
        # v4: HIGHER density threshold + Beer-Lambert (exp) absorption so
        # distinct puffs read against genuinely darker gaps instead of one
        # pale wash, and a lower coverage ceiling.
        return (["fbm3"], [
            f"vec3 marchDir = normalize(vec3({F(u(40, -0.8, 0.8))}, {F(u(41, 0.35, 0.9))}, {F(u(42, -0.8, 0.8))}));",
            "float trans = 1.0;",
            "float lightAcc = 0.0;",
            "for (int i = 0; i < 4; i++) {",
            "    float fi = float(i);",
            f"    float dens = clamp(fbm3(mdir * (1.0 + fi * {F(u(43, 0.22, 0.40))}) + marchDir * (fi * {F(u(44, 0.28, 0.55))})) * {F(u(45, 1.8, 2.6))} - {F(u(46, 0.85, 1.10))}, 0.0, 1.0);",
            "    lightAcc += trans * dens * (1.0 - fi * 0.16);",
            f"    trans *= exp(-dens * {F(u(47, 1.1, 1.7))});",
            "}",
            f"float mid = clamp(lightAcc * {F(u(48, 1.0, 1.4))} + (1.0 - trans) * {F(u(49, 0.12, 0.22))}, 0.0, 1.1);",
        ], [])
    if family == "CHROME":
        eps = F(u(40, 0.10, 0.18))
        # 3D: liquid metal -- a pseudo-normal from central-difference fbm3
        # gradients (biased by sdir so normalize() can never see a zero
        # vector) reflects a baked environment. v4: the environment is
        # ALTERNATING dark/bright horizon bands (bright band lifts, dark
        # band actively SUBTRACTS toward the dark stop) -- real mirror
        # contrast instead of a uniform sheen.
        return (["fbm3"], [
            "float chromeBase = fbm3(mdir);",
            f"float chromeX = fbm3(mdir + vec3({eps}, 0.0, 0.0));",
            f"float chromeY = fbm3(mdir + vec3(0.0, {eps}, 0.0));",
            f"float chromeZ = fbm3(mdir + vec3(0.0, 0.0, {eps}));",
            f"vec3 chromeN = normalize(vec3(chromeX - chromeBase, chromeY - chromeBase, chromeZ - chromeBase) * {F(u(41, 2.5, 4.5))} + sdir);",
            f"float envBand = sin(dot(chromeN, vec3({F(u(42, -0.9, 0.9))}, {F(u(43, 0.4, 1.0))}, {F(u(44, -0.9, 0.9))})) * {F(u(45, 5.0, 9.0))} + time * {qs(46, 0.3, 0.7)});",
            f"float envBright = pow(clamp(envBand, 0.0, 1.0), {F(u(56, 2.0, 3.2))});",
            f"float envDark = pow(clamp(-envBand, 0.0, 1.0), {F(u(57, 1.6, 2.6))});",
            f"float envCross = pow(0.5 + 0.5 * sin(dot(chromeN, vec3({F(u(47, 0.4, 1.0))}, {F(u(48, -0.9, 0.9))}, {F(u(49, -0.9, 0.9))})) * {F(u(55, 3.0, 6.0))}), 3.0);",
            f"float mid = clamp({F(u(58, 0.34, 0.46))} + envBright * {F(u(59, 0.75, 1.00))} + envCross * 0.40 - envDark * {F(u(39, 0.70, 0.95))}, 0.0, 1.25);",
        ], [])
    if family == "LAVAFLOW":
        qdown = F6(quant_drift(u(40, 0.10, 0.22), float(syp)))
        # 2D: crusted lava -- a slow molten current (day-quantized v drift on
        # the wrapping lattice) shows through the gaps of a darker crust
        # coverage field, with hot veins where the crust cracks (turbulence
        # ridges) and a soft molten throb. v4 MATERIAL ROLES via the derived
        # secondary color (post lines): crust plates = secCol sunk toward the
        # dark stop, molten body = the baseCol grade, veins = hotStop pushed
        # through the hue-preserving soft clip.
        return (["fbm2"], [
            f"vec2 lavaUV = wuv + vec2(0.0, time * {qdown});",
            "float molten = fbm2(lavaUV, midPer);",
            f"float crust = smoothstep({F(u(41, 0.35, 0.45))}, {F(u(42, 0.62, 0.75))}, fbm2(lavaUV * 2.0 + vec2(9.1, 3.7), midPer * 2.0));",
            f"float vein = pow(clamp(1.0 - abs(2.0 * fbm2(lavaUV + vec2(4.3, 7.9), midPer) - 1.0), 0.0, 1.0), {F(u(43, 6.0, 11.0))});",
            f"float throb = 0.8 + 0.2 * sin(time * {qs(44, 0.4, 0.9)} + molten * {F(u(45, 2.0, 4.0))});",
            f"float mid = clamp(molten * (1.0 - crust * {F(u(46, 0.55, 0.75))}) * throb + vein * {F(u(47, 0.6, 1.0))} * (1.0 - crust * 0.5), 0.0, 1.3);",
        ], [
            "// Material roles: crust plates take the secondary-derived color",
            "// (sunk toward the dark stop), the cracks between them stay the",
            "// molten baseCol grade, and the veins burn at the hot stop.",
            f"vec3 crustCol = mix(secCol, deepStop, {F(u(48, 0.40, 0.60))});",
            f"rgb = mix(rgb, crustCol * (0.60 + 0.40 * molten), clamp(crust, 0.0, 1.0) * {F(u(49, 0.55, 0.75))});",
            f"rgb = mix(rgb, hotStop, clamp(vein * (1.0 - crust * 0.6) * {F(u(55, 0.70, 0.95))}, 0.0, 1.0));",
        ])
    if family == "TENDRILNET":
        qwig = F6(quant_drift(u(41, 0.06, 0.12), NOWRAP_PERIOD))
        # 2D polar: plasma-globe arcs. v4 rescue: THREE wide filaments (was 4
        # thin ones), gentler falloff with exponent ~3..5, a minimum filament
        # intensity so no arc ever flickers to invisible, and the anchor
        # point pulled OFF-CENTER (baked offset) so the globe reads as a
        # living discharge, not a symmetric star. abs(sin(dAng/2)) stays
        # continuous across the atan branch cut.
        return (["fbm2", "safeAtan", "invsmooth"], [
            f"float du = baseUV.x - {F(u(57, 0.30, 0.70))};",
            f"float dv = baseUV.y - {F(u(58, 0.38, 0.62))};",
            "float tang = safeAtan(dv, sin(du * 6.2831853) * 0.5);",
            "float trad = length(vec2(sin(du * 3.1415927), dv)) * 2.0;",
            "float net = 0.0;",
            "for (int i = 0; i < 3; i++) {",
            "    float fi = float(i);",
            f"    float fa = fi * 2.0943951 + {F(u(42, 0.5, 1.2))} * sin(time * {qs(43, 0.10, 0.24)} + fi * 1.9);",
            f"    float wig = (fbm2(vec2(trad * {F(u(40, 2.5, 5.0))} + fi * 7.31, time * {qwig}), vec2({F(NOWRAP_PERIOD)}, {F(NOWRAP_PERIOD)})) - 0.5) * {F(u(44, 0.5, 1.1))} * trad;",
            "    float dAng = abs(sin((tang - fa) * 0.5 + wig));",
            f"    float fil = pow(clamp(1.0 - dAng * {F(u(45, 1.5, 2.2))}, 0.0, 1.0), {F(u(46, 3.0, 5.0))});",
            f"    net += fil * smoothstep(0.06, 0.25, trad) * (0.72 + 0.28 * sin(time * {qs(47, 1.5, 3.2)} + fi * 2.3));",
            "}",
            f"float core = invsmooth(0.02, {F(u(48, 0.15, 0.30))}, trad);",
            "float mid = clamp(net + core * 0.8, 0.0, 1.3);",
        ], [])
    if family == "GALAXYSWIRL":
        arm_n = 3 + int(u(40, 0.0, 1.999))  # 3..4 arms: seam-safe integer
        # 2D polar: logarithmic spiral arms (angle + twist * log r -- a true
        # log-spiral, unlike VORTEX's latitude twist bands). v4 rescue: 3..4
        # NARROW arms (steeper pow), a hot two-tier core that reads at any
        # distance, and INDEPENDENT twinkling stars scattered off the arms
        # (sparkle cells, not dust multiplied into the arm mask). The arm
        # phase turns at a quantized speed so the daily wrap lands on a
        # whole turn.
        return (["safeAtan", "invsmooth", "sparkle"], [
            "float du = baseUV.x - 0.5;",
            "float gang = safeAtan(baseUV.y - 0.5, sin(du * 6.2831853) * 0.5);",
            "float grad = length(vec2(sin(du * 3.1415927), baseUV.y - 0.5)) * 2.0;",
            f"float arm = 0.5 + 0.5 * cos(gang * {F(float(arm_n))} + log(max(grad, 0.05)) * {F(u(41, 2.5, 5.0))} - time * {qs(42, 0.15, 0.40)});",
            f"float arms = pow(clamp(arm, 0.0, 1.0), {F(u(43, 5.0, 9.0))}) * smoothstep(0.08, 0.45, grad) * invsmooth(0.55, 1.15, grad);",
            f"float core = invsmooth(0.02, {F(u(44, 0.18, 0.34))}, grad) + 0.6 * invsmooth(0.01, {F(u(45, 0.08, 0.14))}, grad);",
            f"float stars = sparkle(wuv * 2.0 + 17.3, time, midPer.x * 2.0);",
            f"float mid = clamp(arms * {F(u(47, 1.0, 1.3))} + core + stars * {F(u(48, 0.35, 0.55))}, 0.0, 1.3);",
        ], [])
    if family == "RIBBONAURORA":
        m1 = 1 + int(u(40, 0.0, 1.999))  # integer curtain harmonics
        # 2D: folded ribbons -- the v DISTANCE to wandering integer-harmonic
        # curves cuts sharp-edged bands (a curve-distance technique, unlike
        # AURORA's fbm curtain rays). v4 rescue: THREE vertically-layered
        # ribbons stacked at distinct heights (low / middle / high), each
        # with its own harmonic, phase and width, instead of one arch; the
        # brightest ribbon still catches the light where its slope folds.
        return (["fbm2", "invsmooth"], [
            f"float curve1 = {F(u(49, 0.24, 0.32))} + {F(u(41, 0.06, 0.11))} * sin(baseUV.x * 6.2831853 * {F(float(m1))} + time * {qs(42, 0.25, 0.55)}) + {F(u(43, 0.03, 0.07))} * sin(baseUV.x * 6.2831853 * {F(float(m1 * 3))} - time * {qs(44, 0.15, 0.35)});",
            f"float slope1 = cos(baseUV.x * 6.2831853 * {F(float(m1))} + time * {qs(42, 0.25, 0.55)});",
            f"float band1 = invsmooth(0.005, {F(u(45, 0.05, 0.09))}, abs(baseUV.y - curve1));",
            f"float curve2 = {F(u(57, 0.46, 0.54))} + {F(u(46, 0.07, 0.12))} * sin(baseUV.x * 6.2831853 * {F(float(m1 + 1))} - time * {qs(47, 0.20, 0.45)} + 2.1);",
            f"float band2 = invsmooth(0.005, {F(u(48, 0.04, 0.07))}, abs(baseUV.y - curve2));",
            f"float curve3 = {F(u(58, 0.68, 0.76))} + {F(u(59, 0.05, 0.09))} * sin(baseUV.x * 6.2831853 * {F(float(m1 + 2))} + time * {qs(39, 0.18, 0.40)} + 4.4);",
            f"float band3 = invsmooth(0.005, {F(u(26, 0.03, 0.06))}, abs(baseUV.y - curve3));",
            "float foldGlow = 0.6 + 0.4 * slope1 * slope1;",
            f"float shimmer = fbm2(wuv, midPer) * {F(u(55, 0.12, 0.22))};",
            "float mid = clamp(band1 * foldGlow + band2 * 0.75 + band3 * 0.55 + shimmer, 0.0, 1.25);",
        ], [])
    if family == "FROSTFERN":
        aniso = 2 + int(u(40, 0.0, 1.999))  # integer x stretch keeps the wrap
        # 2D: dendritic frost. v4 rescue: BRANCHING dendrites via a domain
        # FORK -- besides the two nested axis-aligned ridge sets, a third
        # ridge set on a diagonally SHEARED copy of the domain (shear by v
        # keeps the u wrap exact, like the rotate anim's shear sway) crosses
        # them; the vein product survives along the main stems AND along the
        # fork directions, giving true side-branches instead of one feathery
        # blur. Frost dust sparkles on the fronds.
        return (["fbm2", "sparkle"], [
            f"vec2 fernUV = vec2(wuv.x * {F(float(aniso))}, wuv.y * {F(u(41, 0.35, 0.6))});",
            f"float vein1 = 1.0 - abs(2.0 * fbm2(fernUV, vec2(midPer.x * {F(float(aniso))}, midPer.y)) - 1.0);",
            f"float vein2 = 1.0 - abs(2.0 * fbm2(fernUV * 2.0 + vec2(5.3, 8.1), vec2(midPer.x * {F(float(2 * aniso))}, midPer.y * 2.0)) - 1.0);",
            f"vec2 forkUV = vec2(fernUV.x + fernUV.y * {F(u(57, 0.45, 0.75))}, fernUV.y * 1.35) + vec2(2.7, 6.2);",
            f"float vein3 = 1.0 - abs(2.0 * fbm2(forkUV, vec2(midPer.x * {F(float(aniso))}, midPer.y * 1.35)) - 1.0);",
            f"float stem = pow(clamp(vein1 * vein2, 0.0, 1.0), {F(u(42, 2.5, 4.5))});",
            f"float branch = pow(clamp(vein2 * vein3, 0.0, 1.0), {F(u(58, 3.0, 5.0))});",
            f"float growth = 0.62 + 0.38 * sin(time * {qs(43, 0.10, 0.25)} + baseUV.y * {F(u(44, 2.0, 4.0))});",
            "float dust = sparkle(wuv * 2.0 + 13.7, time, midPer.x * 2.0);",
            f"float fern = clamp(stem + branch * {F(u(59, 0.55, 0.80))}, 0.0, 1.0);",
            f"float mid = clamp(fern * growth * {F(u(45, 1.0, 1.3))} + dust * {F(u(46, 0.25, 0.45))} * fern, 0.0, 1.25);",
        ], [])
    if family == "BIOLUME":
        # 2D: bioluminescent plankton. v4 rescue: SPARSE isolated bright
        # blobs on near-black water -- only a hash-gated minority of voronoi
        # cells glow at all (bio.z gate), each as a TIGHT hot core with a
        # small halo, answering the travelling wavefront at its own phase;
        # the background haze is almost gone so the darkness reads.
        return (["voro2", "invsmooth", "fbm2"], [
            f"vec3 bio = voro2(wuv, midPer, time * {qs(40, 0.15, 0.35)});",
            f"float lit = step({F(u(41, 0.55, 0.70))}, bio.z);",
            f"float blobCore = invsmooth(0.02, {F(u(57, 0.16, 0.26))}, bio.y);",
            f"float blobHalo = invsmooth(0.05, {F(u(58, 0.40, 0.55))}, bio.y);",
            f"float wavePhase = baseUV.x * 6.2831853 + baseUV.y * {F(u(42, 2.0, 5.0))} - time * {qs(43, 0.5, 1.1)};",
            f"float waveGlow = pow(0.5 + 0.5 * sin(wavePhase + bio.z * 6.2831853), {F(u(44, 2.0, 4.0))});",
            f"float haze = fbm2(wuv + vec2(3.1, 6.7), midPer) * {F(u(45, 0.03, 0.08))};",
            f"float mid = clamp(lit * (blobCore * ({F(u(46, 0.55, 0.75))} + {F(u(47, 0.45, 0.65))} * waveGlow) + blobHalo * 0.22 * waveGlow) + haze, 0.0, 1.2);",
        ], [])
    if family == "HOLOGRID":
        lon_n = 8 + 2 * int(u(40, 0.0, 3.999))  # integer graticule counts
        lat_n = 6 + 2 * int(u(41, 0.0, 2.999))
        # 2D: holographic graticule. v4 rescue: BRIGHTER continuous lines
        # (lower pow + higher weight so the grid never dissolves into dots)
        # with FEWER, DIMMER node glints (tighter hash gate, smaller lift),
        # plus the v-sweeping scan band under a hash flicker gate.
        return (["invsmooth", "cellHash", "hash11"], [
            f"float lonLine = pow(abs(sin(baseUV.x * 3.1415927 * {F(float(lon_n))})), {F(u(42, 7.0, 12.0))});",
            f"float latLine = pow(abs(sin(baseUV.y * 3.1415927 * {F(float(lat_n))})), {F(u(43, 7.0, 12.0))});",
            f"float scanPos = fract(time * {F6(quant_fract_speed(u(44, 0.04, 0.10)))});",
            f"float scan = invsmooth(0.0, {F(u(45, 0.10, 0.20))}, abs(baseUV.y - scanPos));",
            f"float glint = step(0.82, cellHash(floor(vec2(baseUV.x * {F(float(lon_n))}, baseUV.y * {F(float(lat_n))})), {F(float(lon_n))})) * lonLine * latLine;",
            f"float holoFlicker = 0.90 + 0.10 * step(0.35, hash11(floor(time * {F(u(46, 6.0, 11.0))})));",
            f"float mid = clamp((lonLine + latLine) * {F(u(47, 0.62, 0.85))} * holoFlicker + scan * {F(u(48, 0.35, 0.55))} + glint * 0.9, 0.0, 1.25);",
        ], [])
    if family == "PORTALVOID":
        suck_n = 3 + int(u(42, 0.0, 2.999))  # integer streak symmetry
        r1 = u(48, 0.26, 0.34)
        r2 = r1 + u(57, 0.12, 0.18)
        r3 = r2 + u(58, 0.12, 0.18)
        # 2D polar: a collapsing portal. v4 rescue: an EXPLICIT dark center
        # (the post line sinks the core all the way to the dark stop -- the
        # void is a hole, not a dim patch) ringed by THREE concentric
        # accretion rings of falling brightness, plus the infall bands and
        # spiral streaks outside. Low mid in the core also thins the alpha.
        return (["safeAtan", "invsmooth"], [
            "float du = baseUV.x - 0.5;",
            "float pang = safeAtan(baseUV.y - 0.5, sin(du * 6.2831853) * 0.5);",
            "float prad = length(vec2(sin(du * 3.1415927), baseUV.y - 0.5)) * 2.0;",
            f"float infall = 0.5 + 0.5 * sin(prad * {F(u(40, 12.0, 22.0))} + time * {qs(41, 0.8, 1.6)});",
            f"float suck = pow(clamp(0.5 + 0.5 * sin(pang * {F(float(suck_n))} + prad * {F(u(43, 4.0, 8.0))} + time * {qs(44, 0.4, 0.9)}), 0.0, 1.0), {F(u(45, 3.0, 6.0))});",
            f"float voidCore = invsmooth({F(u(46, 0.12, 0.22))}, {F(u(47, 0.30, 0.45))}, prad);",
            f"float ringThrob = 0.8 + 0.2 * sin(time * {qsc(2.0)});",
            f"float accretion = invsmooth(0.0, {F(u(49, 0.030, 0.055))}, abs(prad - {F(r1)})) * ringThrob",
            f"    + invsmooth(0.0, {F(u(59, 0.030, 0.055))}, abs(prad - {F(r2)})) * 0.65 * ringThrob",
            f"    + invsmooth(0.0, {F(u(39, 0.030, 0.055))}, abs(prad - {F(r3)})) * 0.40;",
            f"float mid = clamp((infall * 0.45 + suck * 0.65) * (1.0 - voidCore) * smoothstep(0.10, 0.55, prad) + accretion, 0.0, 1.3);",
        ], [
            "// The void interior takes the secondary-derived color sunk to the",
            "// dark stop -- an explicitly DARK center, not a dim patch.",
            f"rgb = mix(rgb, mix(secCol * secCol, deepStop, 0.6) * {F(u(26, 0.25, 0.40))}, clamp(voidCore, 0.0, 1.0) * {F(u(27, 0.80, 0.95))});",
        ])
    if family == "EMBERSTORM":
        qup = F6(quant_drift(u(40, 0.15, 0.30), float(syp)))
        # 2D: a storm of rising embers -- three parallax cell layers advect
        # UPWARD (the day-quantized v drift shifts whole cells at the wrap,
        # so the per-cell embers re-roll there like any hash flicker), each
        # ember an elongated streak with per-cell heat flicker, over a faint
        # heat-shimmer background on the wrapping lattice.
        return (["cellHash", "invsmooth", "fbm2"], [
            "float embers = 0.0;",
            "for (int i = 0; i < 3; i++) {",
            "    float fi = float(i);",
            "    // integer per-layer scale keeps each ember lattice seam-aligned",
            "    float layerPx = midPer.x * (fi + 1.0);",
            f"    vec2 eu = vec2(wuv.x * (fi + 1.0), wuv.y * (fi + 1.0) - time * {qup} * (fi + 1.0)) + vec2(fi * 13.7, fi * 5.3);",
            "    vec2 ecell = floor(eu);",
            "    vec2 ef = fract(eu) - 0.5;",
            "    float eh = cellHash(ecell + vec2(fi * 31.0, 0.0), layerPx);",
            "    vec2 eoff = vec2(cellHash(ecell + 7.1, layerPx), cellHash(ecell + 19.3, layerPx)) - 0.5;",
            "    vec2 ed = ef - eoff * 0.5;",
            f"    float streak = invsmooth(0.01, {F(u(41, 0.05, 0.09))}, abs(ed.x)) * invsmooth(0.04, {F(u(42, 0.22, 0.38))}, abs(ed.y));",
            "    float heat = 0.5 + 0.5 * sin(time * (3.0 + 4.0 * eh) + eh * 47.0);",
            f"    embers += step({F(u(43, 0.55, 0.72))}, eh) * streak * (0.4 + 0.6 * heat) * (1.0 - fi * 0.22);",
            "}",
            f"float shimmer = fbm2(wuv + vec2(0.0, -time * {qup}), midPer) * {F(u(44, 0.15, 0.28))};",
            "float mid = clamp(embers + shimmer, 0.0, 1.3);",
        ], [
            "// Material roles: the smoke between the embers takes the derived",
            "// secondary color (dark, cool cast); the ember streaks keep the",
            "// primary-driven hot grade.",
            f"rgb = mix(rgb, mix(secCol, deepStop, 0.45) * (0.7 + 2.2 * shimmer), clamp(1.0 - embers * 2.5, 0.0, 1.0) * {F(u(45, 0.25, 0.40))});",
        ])
    if family == "SHARDTESS":
        facet_lvl = 3 + int(u(42, 0.0, 1.999))
        # 2D: stained-glass tessellation -- STATIC nested voronoi shards (big
        # panes cut by finer fractures), POSTERIZED per-pane brightness (flat
        # facets, not VORONOI's cell glow), bright leadwork borders and a
        # glint that lights whole panes as its phase sweeps their hashes.
        return (["voro2", "invsmooth"], [
            "vec3 pane = voro2(wuv, midPer, 0.0);",
            "vec3 crack = voro2(wuv * 2.0 + vec2(17.3, 8.9), midPer * 2.0, 0.0);",
            f"float lead = invsmooth(0.004, {F(u(40, 0.045, 0.075))}, pane.x) + invsmooth(0.003, {F(u(41, 0.025, 0.045))}, crack.x) * 0.55;",
            f"float facet = floor(pane.z * {F(float(facet_lvl))} + 0.5) / {F(float(facet_lvl))};",
            f"float glintPhase = 0.5 + 0.5 * sin(time * {qs(43, 0.5, 1.0)} + pane.z * 6.2831853);",
            f"float paneGlint = pow(glintPhase, {F(u(44, 5.0, 9.0))});",
            f"float mid = clamp(lead * (0.55 + 0.45 * glintPhase) + facet * {F(u(45, 0.28, 0.45))} + paneGlint * {F(u(46, 0.5, 0.8))} * (1.0 - clamp(lead, 0.0, 1.0)), 0.0, 1.3);",
        ], [])
    if family == "SACREDGEO":
        spoke_n = 3 + int(u(40, 0.0, 2.999))  # integer mandala symmetry
        # 2D: sacred geometry -- a flower-of-life ring lattice (thin circles
        # on a brick-offset grid at two interleaved radii, so neighbors
        # overlap) under a slowly turning spoked mandala with integer
        # symmetry and a fixed halo ring; all constructions are seam-safe.
        return (["invsmooth", "safeAtan"], [
            "vec2 gg = wuv;",
            "gg.x += 0.5 * step(1.0, mod(floor(gg.y), 2.0));",
            "vec2 gf = fract(gg) - 0.5;",
            f"float ring1 = invsmooth(0.0, {F(u(41, 0.025, 0.045))}, abs(length(gf) - {F(u(42, 0.44, 0.52))}));",
            "vec2 gf2 = fract(gg + vec2(0.5, 0.5)) - 0.5;",
            f"float ring2 = invsmooth(0.0, {F(u(43, 0.02, 0.04))}, abs(length(gf2) - {F(u(44, 0.30, 0.40))}));",
            "float du = baseUV.x - 0.5;",
            f"float sang = safeAtan(baseUV.y - 0.5, sin(du * 6.2831853) * 0.5) + time * {qs(45, 0.06, 0.15)};",
            "float srad = length(vec2(sin(du * 3.1415927), baseUV.y - 0.5)) * 2.0;",
            f"float spokes = pow(abs(cos(sang * {F(float(spoke_n))})), {F(u(46, 8.0, 16.0))}) * invsmooth(0.15, 0.9, srad);",
            f"float halo = invsmooth(0.0, {F(u(47, 0.05, 0.09))}, abs(srad - {F(u(48, 0.55, 0.75))}));",
            f"float mid = clamp((ring1 + ring2 * 0.7) * {F(u(49, 0.55, 0.75))} + spokes * 0.6 + halo * 0.5, 0.0, 1.25);",
        ], [])
    if family == "VOIDTENDRIL":
        # 3D: void tendrils -- ridged fbm3 on an anisotropically SQUASHED
        # sphere direction gives long meridional filaments. v4 rescue: the
        # breathing reach mask starts near the EQUATOR (tendrils cover most
        # of the shell, not just the poles), each filament is a DARK TRENCH
        # with a thin BRIGHT EDGE (wide band subtracts, narrow crest adds),
        # the murk floor is halved and the deep volume is down-weighted.
        return (["fbm3"], [
            f"vec3 tdir = vec3(mdir.x, mdir.y * {F(u(40, 0.28, 0.45))}, mdir.z);",
            f"float tnoise = fbm3(tdir + vec3(0.0, {F(u(41, 0.15, 0.35))} * sin(time * {qs(42, 0.08, 0.18)}), 0.0));",
            "float tridge = clamp(1.0 - abs(2.0 * tnoise - 1.0), 0.0, 1.0);",
            f"float edgeBright = pow(tridge, {F(u(43, 7.0, 12.0))});",
            f"float trench = smoothstep({F(u(57, 0.30, 0.45))}, {F(u(58, 0.70, 0.85))}, tridge);",
            "float lat = abs(baseUV.y * 2.0 - 1.0);",
            f"float reach = smoothstep({F(u(44, 0.00, 0.08))}, {F(u(45, 0.45, 0.65))}, lat + {F(u(46, 0.10, 0.22))} * sin(time * {qs(47, 0.10, 0.22)}));",
            f"float murk = fbm3(mdir * 0.6 + vec3(7.7, 2.2, 5.5)) * {F(u(48, 0.06, 0.12))};",
            f"float mid = clamp((edgeBright * {F(u(49, 1.0, 1.3))} - trench * {F(u(59, 0.25, 0.40))} + {F(u(39, 0.16, 0.26))}) * reach + murk, 0.0, 1.2);",
        ], [])
    if family == "CRYSTALREFRACT":
        harm = 3 + int(u(40, 0.0, 3.999))  # integer backdrop harmonic
        cfw = F(u(55, 0.22, 0.35))
        # 2D: refractive crystal panes -- each STATIC voronoi facet offsets
        # the sampling of an integer-harmonic backdrop grating by its own
        # hash (the refraction JUMPS at pane borders, which is the look),
        # with thin-film dispersion on the panes and dark border grooves.
        return (["voro2", "hash22", "invsmooth", "thinFilm"], [
            "vec3 cry = voro2(wuv, midPer, 0.0);",
            f"vec2 refr = (hash22(vec2(cry.z * 91.7, cry.z * 33.1)) - 0.5) * {F(u(41, 0.35, 0.65))};",
            f"float back = 0.5 + 0.5 * sin((baseUV.x + refr.x) * 6.2831853 * {F(float(harm))} + (baseUV.y + refr.y) * {F(u(42, 3.0, 7.0))} + time * {qs(43, 0.3, 0.7)});",
            f"float paneLit = pow(clamp(back, 0.0, 1.0), {F(u(44, 2.0, 3.5))});",
            f"float groove = invsmooth(0.004, {F(u(45, 0.05, 0.09))}, cry.x);",
            f"vec3 crystalFilm = thinFilm(cry.z * {F(u(46, 1.5, 3.0))} + paneLit * {F(u(47, 0.6, 1.2))});",
            f"float mid = clamp(paneLit * {F(u(48, 0.75, 1.0))} + groove * {F(u(49, 0.35, 0.55))}, 0.0, 1.2);",
        ], [
            f"rgb = mix(rgb, rgb * (0.6 + 0.85 * crystalFilm), {cfw});",
        ])
    # ------------------------------------------------------------------
    # v5 technique families (FAMILIES_V5). Staged for the next catalogue
    # flip: no EffectRegistry row references them yet, so none of these
    # branches runs for ids 0..419 and every pre-v5 file stays byte-stable.
    # ------------------------------------------------------------------
    if family == "SPECTRALVEIL":
        # 3D: fresnel-band ghost veil -- the grazing estimator itself is the
        # banding coordinate (bands of the VIEW angle, not of the surface),
        # modulated by a slow body field; plus a time-offset AFTERIMAGE:
        # the same field resampled at an earlier domain rotation (the
        # rotation IS the motion, so the offset is a true trailing ghost).
        eqs = qs(46, 0.05, 0.12)
        return (["fbm3", "rimGraze"], [
            "float svGraze = rimGraze();",
            f"float svBody = fbm3(mdir + vec3(0.0, {F(u(40, 0.20, 0.50))} * sin(time * {qs(41, 0.10, 0.24)}), 0.0));",
            f"float svVeil = pow(0.5 + 0.5 * sin(svGraze * {F(u(42, 6.0, 11.0))} + svBody * {F(u(43, 3.0, 6.0))} - time * {qs(44, 0.4, 0.9)}), {F(u(45, 1.6, 2.8))});",
            "// time-offset afterimage: the SAME field at 1x and 2x earlier",
            "// rotation phases, thresholded into decaying ghost sheets",
            f"float svEcho1 = fbm3(rotA(spinAxis, -time * {eqs}) * (mdir * {F(u(47, 1.05, 1.25))}) + vec3(2.7, 1.3, 5.1));",
            f"float svEcho2 = fbm3(rotA(spinAxis, -time * {eqs} * 2.0) * (mdir * {F(u(48, 1.25, 1.50))}) + vec3(7.9, 4.2, 0.6));",
            f"float svGhost = pow(clamp(svEcho1 * 1.5 - 0.40, 0.0, 1.0), 2.0) * {F(u(49, 0.45, 0.65))} + pow(clamp(svEcho2 * 1.5 - 0.45, 0.0, 1.0), 2.0) * {F(u(55, 0.22, 0.40))};",
            "float mid = clamp(svVeil * (0.55 + 0.45 * svBody) + svGhost, 0.0, 1.2);",
        ], [])
    if family == "RAYMARCHFOG":
        # 3D: a REAL 6..8-step density march INTO the shell along a baked
        # pseudo view ray with front-to-back accumulation -- deeper steps
        # sample the same fbm3 field farther in and land under the remaining
        # transmittance (twice the depth resolution of VOLUMECLOUD's 4-step
        # march, with a per-step depth fade for light attenuation).
        steps = 6 + int(u(39, 0.0, 2.999))
        return (["fbm3"], [
            f"vec3 rfDir = normalize(vec3({F(u(40, -0.7, 0.7))}, {F(u(41, 0.30, 0.90))}, {F(u(42, -0.7, 0.7))}));",
            "float rfTrans = 1.0;",
            "float rfLight = 0.0;",
            f"for (int i = 0; i < {steps}; i++) {{",
            "    float fi = float(i);",
            f"    vec3 rfP = mdir * (1.0 + fi * {F(u(43, 0.10, 0.20))}) + rfDir * (fi * {F(u(44, 0.16, 0.30))});",
            f"    float rfD = clamp(fbm3(rfP) * {F(u(45, 1.7, 2.4))} - {F(u(46, 0.55, 0.85))}, 0.0, 1.0);",
            f"    rfLight += rfTrans * rfD * (1.0 - fi * {F(0.85 / steps)});",
            f"    rfTrans *= 1.0 - rfD * {F(u(47, 0.30, 0.45))};",
            "}",
            f"float mid = clamp(rfLight * {F(u(48, 0.9, 1.3))} + (1.0 - rfTrans) * {F(u(49, 0.15, 0.25))}, 0.0, 1.15);",
        ], [])
    if family == "PRISMDISPERSE":
        # 3D: gradient pseudo-normal (central-difference fbm3, sdir-biased so
        # normalize never sees zero) refracts the DEEP field three times with
        # per-channel index offsets -- a true chromatic split of one image,
        # not a palette tint; plus a normal-aligned specular.
        eps = F(u(40, 0.10, 0.18))
        base = u(43, 0.10, 0.20)
        ldir = (u(44, -1.0, 1.0), u(45, 0.3, 1.0), u(46, -1.0, 1.0))
        ln = math.sqrt(ldir[0] ** 2 + ldir[1] ** 2 + ldir[2] ** 2)
        ldir = (ldir[0] / ln, ldir[1] / ln, ldir[2] / ln)
        return (["fbm3"], [
            "float pdC = fbm3(mdir);",
            f"float pdX = fbm3(mdir + vec3({eps}, 0.0, 0.0));",
            f"float pdY = fbm3(mdir + vec3(0.0, {eps}, 0.0));",
            f"float pdZ = fbm3(mdir + vec3(0.0, 0.0, {eps}));",
            f"vec3 pdN = normalize(vec3(pdX - pdC, pdY - pdC, pdZ - pdC) * {F(u(41, 2.0, 4.0))} + sdir);",
            "// 3-channel chromatic split: each channel refracts the SAME deep",
            "// field with its own index-of-refraction offset along the normal",
            f"float pdSc = {F(u(42, 1.8, 2.8))};",
            f"vec3 pdSplit = vec3(deepField(sdir * pdSc + pdN * {F(base)}, time),",
            f"    deepField(sdir * pdSc + pdN * {F(base * 1.35)}, time),",
            f"    deepField(sdir * pdSc + pdN * {F(base * 1.75)}, time));",
            f"float pdSpec = pow(clamp(dot(pdN, vec3({F(ldir[0])}, {F(ldir[1])}, {F(ldir[2])})), 0.0, 1.0), {F(u(47, 6.0, 12.0))});",
            f"float mid = clamp(dot(pdSplit, vec3(0.3333)) * {F(u(48, 0.75, 1.05))} + pdSpec * {F(u(49, 0.45, 0.70))}, 0.0, 1.25);",
        ], [
            "// the split lands per-channel into the palette-driven rgb: a",
            "// bounded multiplicative mix, so the owner recolor stays in charge",
            f"rgb = mix(rgb, rgb * (0.45 + {F(u(55, 1.0, 1.5))} * pdSplit), {F(u(56, 0.30, 0.44))});",
        ])
    if family == "HOLOPARALLAX":
        # 2D: 3..4 grid/scanline layers, each parallax-shifted along the
        # screen-space silhouette slope (rimDir -- seam-safe by construction,
        # the same trick as the deep planes) with integer per-layer scales so
        # every layer tiles the u wrap; plus a v-sweeping horizontal sync
        # band and a hash flicker gate.
        hp_layers = 3 + int(u(39, 0.0, 1.999))
        gw = u(42, 0.06, 0.12)
        return (["invsmooth", "hash11"], [
            "float hpAcc = 0.0;",
            f"for (int i = 0; i < {hp_layers}; i++) {{",
            "    float fi = float(i);",
            "    // integer per-layer scale keeps the grid seam-aligned; the",
            "    // rimDir shift is the hologram's depth parallax",
            f"    vec2 hpUV = wuv * (fi + 1.0) + rimDir * (fi * {F(u(41, 0.05, 0.12))});",
            "    vec2 hpD = abs(fract(hpUV) - 0.5);",
            f"    float hpGrid = smoothstep({F(0.5 - gw)}, {F(0.5 - gw * 0.35)}, max(hpD.x, hpD.y));",
            f"    float hpScan = 0.5 + 0.5 * sin(hpUV.y * {F(u(43, 9.0, 16.0))} + time * {qs(44, 0.5, 1.1)} - fi * 1.7);",
            f"    hpAcc += (hpGrid * {F(u(45, 0.50, 0.70))} + hpScan * {F(u(46, 0.10, 0.20))}) * (1.0 - fi * {F(u(47, 0.16, 0.24))});",
            "}",
            f"float hpSync = invsmooth(0.0, {F(u(48, 0.04, 0.08))}, abs(baseUV.y - fract(time * {F6(quant_fract_speed(u(40, 0.05, 0.12)))})));",
            f"float hpFlick = 0.85 + 0.15 * step(0.4, hash11(floor(time * {F(u(49, 8.0, 14.0))})));",
            f"float mid = clamp(hpAcc * hpFlick + hpSync * {F(u(55, 0.35, 0.55))}, 0.0, 1.25);",
        ], [])
    if family == "ORBITTRAP":
        # 3D-fed 2D fractal: a Julia iteration on the (rotated) sphere-
        # direction plane -- seam- and pole-safe by construction -- with TWO
        # orbit traps (closest approach to a baked point AND to a line
        # through the origin) and a slowly drifting c (quantized sin, so the
        # daily wrap cannot pop the set shape). z is clamped each iteration
        # so divergence can never reach inf on any driver.
        return ([], [
            f"vec2 otZ = mdir.xy * {F(u(40, 1.2, 1.8))} + vec2(mdir.z * {F(u(41, 0.3, 0.7))}, 0.0);",
            f"vec2 otC = vec2({F(u(42, -0.85, -0.55))}, {F(u(43, 0.35, 0.65))}) + {F(u(44, 0.03, 0.08))} * vec2(sin(time * {qs(45, 0.03, 0.08)}), cos(time * {qs(46, 0.02, 0.06)}));",
            "float otPt = 100.0;",
            "float otLn = 100.0;",
            "for (int i = 0; i < 7; i++) {",
            "    otZ = vec2(otZ.x * otZ.x - otZ.y * otZ.y, 2.0 * otZ.x * otZ.y) + otC;",
            "    otZ = clamp(otZ, -8.0, 8.0);",
            f"    otPt = min(otPt, length(otZ - vec2({F(u(47, -0.4, 0.4))}, {F(u(48, -0.4, 0.4))})));",
            f"    otLn = min(otLn, abs(otZ.y - otZ.x * {F(u(49, -0.5, 0.5))}));",
            "}",
            f"float otGlow = pow(clamp(1.0 - otPt * {F(u(55, 0.7, 1.1))}, 0.0, 1.0), {F(u(56, 2.0, 3.5))});",
            f"float otWire = pow(clamp(1.0 - otLn * {F(u(57, 1.2, 2.0))}, 0.0, 1.0), {F(u(58, 3.0, 6.0))});",
            f"float otBand = 0.5 + 0.5 * sin(otPt * {F(u(59, 5.0, 10.0))} - time * {qs(26, 0.3, 0.7)});",
            "float mid = clamp(otGlow * (0.55 + 0.45 * otBand) + otWire * 0.6, 0.0, 1.25);",
        ], [])
    if family == "CRYSTALSDF":
        # 3D: nearest hashed-plane facet cells -- 6 baked unit normals cut
        # the sphere into spherical-voronoi facets (nearest plane wins), the
        # edge is the margin between the two best dots, and each facet
        # glints AS ONE PLANE when its normal sweeps past a rotating light
        # (facet-aligned specular, unlike any per-pixel sparkle).
        def _cs_unit(ia, ib, ic):
            v = (u(ia, -1.0, 1.0), u(ib, -1.0, 1.0), u(ic, -1.0, 1.0))
            n = math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            if n < 0.25:
                return (0.4851, 0.7276, 0.4851)
            return (v[0] / n, v[1] / n, v[2] / n)
        cs_planes = [_cs_unit(40, 41, 42), _cs_unit(43, 44, 45), _cs_unit(46, 47, 48),
                     _cs_unit(49, 55, 56), _cs_unit(57, 58, 59), _cs_unit(26, 27, 28)]
        cs_light = _cs_unit(29, 39, 19)
        cs_lines = [
            "vec3 csDir = normalize(mdir);",
            "float csBest = -2.0;",
            "float csSecond = -2.0;",
            "vec3 csN = vec3(0.0, 1.0, 0.0);",
            "float csId = 0.0;",
        ]
        for i, pn in enumerate(cs_planes):
            cs_lines.append(f"vec3 csP{i} = vec3({F(pn[0])}, {F(pn[1])}, {F(pn[2])});")
            cs_lines.append(f"float csD{i} = dot(csDir, csP{i});")
            cs_lines.append(
                f"if (csD{i} > csBest) {{ csSecond = csBest; csBest = csD{i}; csN = csP{i}; csId = {F(i * 0.1618 + 0.07)}; }}"
                f" else if (csD{i} > csSecond) {{ csSecond = csD{i}; }}")
        cs_lines += [
            f"float csEdge = invsmooth(0.015, {F(u(20, 0.10, 0.20))}, csBest - csSecond);",
            f"vec3 csL = rotA(spinAxis, time * {qs(21, 0.05, 0.12)}) * vec3({F(cs_light[0])}, {F(cs_light[1])}, {F(cs_light[2])});",
            f"float csSpec = pow(clamp(dot(csN, csL), 0.0, 1.0), {F(u(22, 8.0, 16.0))});",
            f"float csTw = 0.5 + 0.5 * sin(time * {qs(23, 0.7, 1.5)} + csId * 39.0);",
            f"float csFill = 0.5 + 0.5 * sin(csId * 6.2831853 + dot(csDir, csN) * {F(u(24, 3.0, 6.0))});",
            f"float mid = clamp(csEdge * {F(u(25, 0.45, 0.70))} + csSpec * ({F(u(17, 0.55, 0.85))} + 0.3 * csTw) + csFill * {F(u(16, 0.15, 0.28))}, 0.0, 1.3);",
        ]
        return (["invsmooth"], cs_lines, [])
    if family == "FLUIDINK":
        # 2D: marbled ink -- an EXPLICIT double domain-warp (q then r, both
        # seam-periodic fbm2 offset fields, so the wrap survives) whose warp
        # vectors also ADVECT the accent-palette position in the post pass:
        # the color bands follow the marbling folds, not the raw UV.
        fi_amp = F(u(41, 1.6, 2.6))
        return (["fbm2"], [
            f"vec2 fiQ = vec2(fbm2(wuv + vec2(0.0, time * {qpy(40, 0.05, 0.12)}), midPer), fbm2(wuv + vec2(5.2, 1.3), midPer));",
            f"vec2 fiR = vec2(fbm2(wuv + fiQ * {fi_amp} + vec2(1.7, 9.2) + vec2(time * {qpx(42, 0.03, 0.08)}, 0.0), midPer), fbm2(wuv + fiQ * {fi_amp} + vec2(8.3, 2.8), midPer));",
            f"float fiInk = fbm2(wuv + fiR * {F(u(43, 2.0, 3.2))}, midPer);",
            f"float fiVein = pow(clamp(1.0 - abs(2.0 * fiR.x - 1.0), 0.0, 1.0), {F(u(44, 3.0, 6.0))});",
            f"float mid = clamp(fiInk * {F(u(45, 0.9, 1.2))} + fiVein * {F(u(46, 0.30, 0.50))}, 0.0, 1.2);",
        ], [
            "// advected palette: the warp field itself steers the accent",
            "// position, so the hue bands ride the marbling (bounded mix)",
            f"rgb = mix(rgb, rgb * (0.55 + 0.9 * accentPalette(fiQ.x * {F(u(47, 0.5, 0.9))} + fiR.y * {F(u(48, 0.4, 0.8))})), {F(u(49, 0.25, 0.40))});",
        ])
    if family == "IRISFILM":
        # 2D: full-hue thin film -- a MULTI-HARMONIC spectral phase ramp
        # (three incommensurate per-channel cosine harmonics beat into a much
        # richer rainbow than the single-cosine thinFilm helper) multiplied
        # by a grazing-angle term, so the iridescence lives at the silhouette
        # like a real soap membrane.
        return (["fbm2", "rimGraze"], [
            f"float ifThick = fbm2(wuv + vec2(time * {qpx(40, 0.02, 0.06)}, 0.0), midPer) * {F(u(41, 1.2, 2.2))} + baseUV.y * {F(u(42, 0.6, 1.4))} + {F(u(43, 0.0, 0.8))};",
            "float ifGraze = 0.35 + 0.65 * rimGraze();",
            "vec3 ifPhase = 6.2831853 * ifThick * vec3(1.0, 0.8065, 0.6452);",
            f"vec3 ifFilm = vec3(0.34) + 0.22 * cos(ifPhase) + 0.22 * cos(ifPhase * 2.0 + vec3(0.7, 1.9, 3.1)) + 0.22 * cos(ifPhase * 3.0 + vec3(2.3, 4.1, 0.9));",
            f"float mid = clamp(dot(clamp(ifFilm, 0.0, 1.0), vec3(0.3333)) * {F(u(44, 1.0, 1.4))} * ifGraze + {F(u(45, 0.10, 0.22))}, 0.0, 1.2);",
        ], [
            f"rgb = mix(rgb, rgb * (0.5 + {F(u(46, 1.1, 1.5))} * clamp(ifFilm, 0.0, 1.0)), {F(u(47, 0.30, 0.45))} * ifGraze);",
        ])
    if family == "AETHERSMOKE":
        # 3D: volumetric smoke -- ONE curl-style advection vector field
        # (three offset fbm3 taps) displaces three depth taps of the density
        # field, composited BACK-TO-FRONT: the deepest tap lays down a dark
        # base and each nearer, brighter tap alpha-composites OVER it, so
        # dense smoke visibly hides the layers behind.
        as_g = u(41, 0.20, 0.38)
        as_th = u(45, 0.50, 0.80)
        as_gain = F(u(44, 1.5, 2.1))
        as_lines = [
            f"vec3 asW = {F(u(40, 0.4, 0.8))} * (vec3(fbm3(mdir + vec3(9.1, 2.3, 6.7)), fbm3(mdir + vec3(4.5, 8.2, 1.1)), fbm3(mdir + vec3(2.9, 5.6, 7.4))) - 0.5);",
            "float asAcc = 0.0;",
        ]
        for k, i in enumerate((2, 1, 0)):  # deepest first: back-to-front
            bright = F(0.40 + 0.30 * (2 - i))
            as_lines += [
                f"float asD{i} = clamp(fbm3(mdir * {F(1.0 + i * as_g)} + asW * {F(1.0 - i * 0.28)} + vec3({F(i * 4.7)}, {F(i * 2.9)}, {F(i * 7.3)})) * {as_gain} - {F(as_th)}, 0.0, 1.0);",
                f"asAcc = mix(asAcc, {bright}, asD{i} * {F(u(46, 0.70, 0.90))});",
            ]
        as_lines += [
            f"float asSway = 0.85 + 0.15 * sin(time * {qs(47, 0.10, 0.25)} + asW.x * 4.0);",
            f"float mid = clamp(asAcc * asSway * {F(u(48, 1.0, 1.3))}, 0.0, 1.2);",
        ]
        return (["fbm3"], as_lines, [])
    if family == "STAINEDGLASS":
        # 2D: stained glass -- static voronoi panes whose lead came is DARK
        # (sunk to the dark stop in the post pass, unlike SHARDTESS's bright
        # leadwork), each pane transmitting light through its OWN hue
        # (per-cell accent-palette position) with a slow sun pulse.
        return (["voro2", "invsmooth"], [
            "vec3 sgV = voro2(wuv, midPer, 0.0);",
            f"float sgLead = invsmooth(0.008, {F(u(40, 0.05, 0.09))}, sgV.x);",
            f"float sgLight = 0.55 + 0.45 * sin(time * {qs(41, 0.3, 0.7)} + sgV.z * 6.2831853);",
            f"float sgBevel = smoothstep(0.0, {F(u(42, 0.25, 0.45))}, sgV.x);",
            f"float mid = clamp((1.0 - sgLead) * ({F(u(43, 0.45, 0.65))} + {F(u(44, 0.30, 0.45))} * sgLight) * (0.75 + 0.25 * sgBevel), 0.0, 1.2);",
        ], [
            "// per-pane hue: each cell tints through its own palette position;",
            "// the lead lines sink to the dark stop (bounded, recolor-safe)",
            f"rgb = mix(rgb, rgb * (0.45 + 1.1 * accentPalette(sgV.z * {F(u(45, 0.6, 1.0))} + {F(u(46, 0.0, 1.0))})), {F(u(47, 0.30, 0.45))});",
            f"rgb = mix(rgb, deepStop, clamp(sgLead, 0.0, 1.0) * {F(u(48, 0.55, 0.75))});",
        ])
    if family == "PHANTOMECHO":
        # 2D: spectral afterimage -- FOUR time-shifted evaluations of the
        # same moving blob field (chordal metaballs on quantized Lissajous
        # paths), each echo sampled at an earlier time with geometrically
        # decaying weight: a motion trail baked from pure re-evaluation.
        return (["invsmooth"], [
            "float peAcc = 0.0;",
            "float peW = 1.0;",
            "for (int e = 0; e < 4; e++) {",
            f"    float peT = time - float(e) * {F(u(40, 0.35, 0.70))};",
            "    float peField = 0.0;",
            "    for (int b = 0; b < 3; b++) {",
            "        float fb = float(b);",
            f"        vec2 peC = vec2(0.5) + vec2({F(u(41, 0.22, 0.34))} * sin(peT * {qs(42, 0.10, 0.22)} + fb * 2.4), {F(u(43, 0.16, 0.28))} * cos(peT * {qs(44, 0.08, 0.18)} + fb * 4.1));",
            "        // chordal x-distance keeps every blob round across the seam",
            "        float peD = length(vec2(sin((baseUV.x - peC.x) * 3.1415927) * 0.6366, baseUV.y - peC.y));",
            f"        peField += invsmooth({F(u(45, 0.04, 0.08))}, {F(u(46, 0.20, 0.34))}, peD);",
            "    }",
            "    peAcc += peField * peW;",
            f"    peW *= {F(u(47, 0.50, 0.65))};",
            "}",
            f"float mid = clamp(peAcc * {F(u(48, 0.40, 0.60))}, 0.0, 1.25);",
        ], [])
    if family == "GRAVLENS":
        # 2D: gravitational lens -- a drifting mass point radially warps the
        # star lattice UV (deflection ~ 1/r, computed in the seam-safe
        # chordal frame so the pull field tiles the wrap) and an Einstein
        # ring brightens both the ring itself and any star that drifts near
        # it (magnification).
        return (["cellHash", "invsmooth"], [
            f"vec2 glC = vec2({F(u(40, 0.2, 0.8))} + {F(u(41, 0.08, 0.16))} * sin(time * {qs(42, 0.04, 0.10)}), {F(u(43, 0.3, 0.7))} + {F(u(44, 0.06, 0.12))} * cos(time * {qs(45, 0.03, 0.09)}));",
            "vec2 glD = vec2(sin((baseUV.x - glC.x) * 6.2831853) * 0.1592, baseUV.y - glC.y);",
            "float glR = length(glD) + 0.02;",
            f"float glPull = {F(u(46, 0.010, 0.022))} / glR;",
            "// the deflection is periodic in u by construction, so the warped",
            "// lattice still tiles the seam",
            "vec2 glUV = wuv - glD / glR * glPull * midPer.x;",
            "float glStars = 0.0;",
            "for (int i = 0; i < 2; i++) {",
            "    float fi = float(i);",
            "    float layerPx = midPer.x * (fi + 1.0);",
            "    vec2 su = glUV * (fi + 1.0) + vec2(fi * 23.7, fi * 11.9);",
            "    vec2 sc = floor(su);",
            "    vec2 sf = fract(su) - 0.5;",
            "    float sh = cellHash(sc + vec2(fi * 53.0, 0.0), layerPx);",
            "    float tw = 0.6 + 0.4 * sin(time * (1.0 + 2.5 * sh) + sh * 44.0);",
            f"    glStars += step({F(u(47, 0.78, 0.88))}, sh) * invsmooth(0.03, 0.16, length(sf - (vec2(cellHash(sc + 4.7, layerPx), cellHash(sc + 9.3, layerPx)) - 0.5) * 0.55)) * tw;",
            "}",
            f"float glRing = invsmooth(0.0, {F(u(48, 0.030, 0.055))}, abs(glR - {F(u(49, 0.10, 0.18))}));",
            f"float mid = clamp(glStars * (1.0 + glRing * {F(u(55, 1.2, 2.0))}) + glRing * {F(u(56, 0.35, 0.55))}, 0.0, 1.3);",
        ], [])
    if family == "MYCELIA":
        # 2D: fungal threads -- a per-cell node network whose branches are
        # REAL segment SDFs (node to the four neighbor nodes, hash-jittered
        # on the wrapping lattice), with glowing node tips and a traveling
        # growth pulse that sweeps the network by cell phase.
        return (["sdSeg", "cellHash", "invsmooth"], [
            "vec2 myI = floor(wuv);",
            "vec2 myF = fract(wuv);",
            "vec2 myN0 = vec2(cellHash(myI, midPer.x), cellHash(myI + 71.3, midPer.x)) * 0.6 + 0.2;",
            "float myD = 8.0;",
            "float myPh = 0.0;",
            "for (int k = 0; k < 4; k++) {",
            "    vec2 og = (k == 0) ? vec2(1.0, 0.0) : ((k == 1) ? vec2(-1.0, 0.0) : ((k == 2) ? vec2(0.0, 1.0) : vec2(0.0, -1.0)));",
            "    vec2 nc = myI + og;",
            "    vec2 nn = og + vec2(cellHash(nc, midPer.x), cellHash(nc + 71.3, midPer.x)) * 0.6 + 0.2;",
            "    float sd = sdSeg(myF, myN0, nn);",
            "    if (sd < myD) { myD = sd; myPh = cellHash(nc + 13.9, midPer.x); }",
            "}",
            f"float myFil = invsmooth({F(u(40, 0.015, 0.030))}, {F(u(41, 0.06, 0.11))}, myD);",
            f"float myGlow = invsmooth(0.05, {F(u(42, 0.22, 0.38))}, myD);",
            "float myNode = invsmooth(0.02, 0.07, length(myF - myN0));",
            "// traveling growth pulse: a wavefront sweeps the network by phase",
            f"float myPulse = pow(0.5 + 0.5 * sin(time * {qs(43, 0.5, 1.1)} - (myI.y + myPh * {F(u(44, 2.0, 5.0))}) * {F(u(45, 0.8, 1.6))}), {F(u(46, 3.0, 6.0))});",
            f"float mid = clamp(myFil * ({F(u(47, 0.45, 0.65))} + {F(u(48, 0.45, 0.65))} * myPulse) + myNode * 0.5 * myPulse + myGlow * 0.15 * myPulse, 0.0, 1.2);",
        ], [])
    if family == "SOLARFLARE":
        # 2D polar: solar prominences -- three arcing filament LOOPS anchored
        # on the chordal polar frame (each loop's radius falls off with the
        # square of the angular distance from its meridian: a real arch, not
        # a radial spoke), over limb brightening and low granulation. The
        # loop tips burn at the hot stop in the post pass.
        return (["safeAtan", "fbm2", "rimGraze", "invsmooth"], [
            "float du = baseUV.x - 0.5;",
            "float sfAng = safeAtan(baseUV.y - 0.5, sin(du * 6.2831853) * 0.5);",
            "float sfRad = length(vec2(sin(du * 3.1415927), baseUV.y - 0.5)) * 2.0;",
            "float sfArcs = 0.0;",
            "for (int i = 0; i < 3; i++) {",
            "    float fi = float(i);",
            f"    float fa = fi * 2.0943951 + {F(u(40, 0.0, 2.0))} + {F(u(41, 0.2, 0.5))} * sin(time * {qs(42, 0.06, 0.16)} + fi * 1.7);",
            "    float dAng = abs(sin((sfAng - fa) * 0.5));",
            f"    float loopR = {F(u(43, 0.45, 0.62))} + {F(u(44, 0.10, 0.20))} * sin(time * {qs(45, 0.15, 0.35)} + fi * 2.9) - dAng * dAng * {F(u(46, 1.2, 2.2))};",
            f"    float arc = invsmooth(0.005, {F(u(47, 0.035, 0.060))}, abs(sfRad - loopR)) * invsmooth(0.35, 0.75, dAng);",
            f"    sfArcs += arc * (0.6 + 0.4 * sin(time * {qs(48, 1.2, 2.6)} + fi * 2.1));",
            "}",
            f"float sfLimb = rimGraze() * {F(u(49, 0.5, 0.8))};",
            "float sfGran = fbm2(wuv, midPer) * 0.18;",
            "float mid = clamp(sfArcs + sfLimb + sfGran, 0.0, 1.35);",
        ], [
            "// flare tips burn at the (luma-capped) hot stop",
            f"rgb = mix(rgb, hotStop, clamp(sfArcs, 0.0, 1.0) * {F(u(55, 0.35, 0.55))});",
        ])
    if family == "DEEPICE":
        # 2D: cracked ice depth -- TWO crack-voronoi sheets; the deeper sheet
        # is offset along the silhouette slope and SCALED by the grazing term
        # (slope-scaled parallax: the sheets visibly separate where the shell
        # curves away) and recedes to a colder secondary cast in the post.
        return (["voro2", "invsmooth", "fbm2", "rimGraze"], [
            "vec3 iceA = voro2(wuv, midPer, 0.0);",
            "// slope-scaled parallax: the deeper sheet slides along rimDir,",
            "// farther where the view grazes (screen-space: seam-safe)",
            f"vec2 iceOff = rimDir * ({F(u(40, 0.20, 0.40))} + {F(u(41, 0.5, 1.0))} * rimGraze());",
            "vec3 iceB = voro2(wuv * 2.0 + iceOff + vec2(13.7, 5.9), midPer * 2.0, 0.0);",
            f"float crackA = invsmooth(0.004, {F(u(42, 0.035, 0.060))}, iceA.x);",
            f"float crackB = invsmooth(0.004, {F(u(43, 0.030, 0.050))}, iceB.x);",
            f"float sheen = fbm2(wuv + vec2(time * {qpx(44, 0.01, 0.04)}, 0.0), midPer);",
            f"float glint = pow(0.5 + 0.5 * sin(time * {qs(45, 0.4, 0.9)} + iceA.z * 6.2831853), {F(u(46, 6.0, 10.0))});",
            f"float mid = clamp(crackA * {F(u(47, 0.70, 0.95))} + crackB * {F(u(48, 0.35, 0.50))} + sheen * {F(u(49, 0.15, 0.30))} + glint * 0.35, 0.0, 1.25);",
        ], [
            "// the deeper crack sheet reads colder and darker (secondary",
            "// cast): depth through color separation, not just brightness",
            f"rgb = mix(rgb, mix(secCol, deepStop, 0.5), clamp(crackB * (1.0 - crackA), 0.0, 1.0) * {F(u(55, 0.35, 0.55))});",
        ])
    if family == "RUNECIRCUIT":
        # 2D: polar glyph bands -- integer cells per band (seam-aligned by
        # construction) each drawing a 3-stroke segment-SDF rune picked by
        # its wrap-safe hash, IGNITING SEQUENTIALLY as a phase front sweeps
        # each band (integer cycles/day, offset per row), between thin band
        # rails.
        rc_n = 10 + 2 * int(u(40, 0.0, 3.999))
        rc_bands = 4 + int(u(41, 0.0, 2.999))
        return (["sdSeg", "cellHash", "invsmooth"], [
            f"vec2 rcUV = vec2(baseUV.x * {F(float(rc_n))}, baseUV.y * {F(float(rc_bands))});",
            "vec2 rcI = floor(rcUV);",
            "vec2 rcF = fract(rcUV);",
            f"float rcH = cellHash(rcI, {F(float(rc_n))});",
            "// per-cell glyph: three strokes picked from a fixed anchor set",
            "vec2 rcA = vec2(0.25 + 0.5 * step(0.5, fract(rcH * 7.0)), 0.22);",
            "vec2 rcB = vec2(0.75 - 0.5 * step(0.5, fract(rcH * 13.0)), 0.5);",
            "vec2 rcC = vec2(0.25 + 0.5 * step(0.5, fract(rcH * 29.0)), 0.78);",
            "float rcD = min(sdSeg(rcF, rcA, rcB), min(sdSeg(rcF, rcB, rcC), sdSeg(rcF, vec2(0.5, 0.34), vec2(0.5, 0.66))));",
            f"float rcGlyph = invsmooth({F(u(42, 0.020, 0.035))}, {F(u(43, 0.07, 0.11))}, rcD) * invsmooth(0.36, 0.48, abs(rcF.y - 0.5));",
            "// sequential ignition: the front advances one cell at a time and",
            "// wraps exactly (rcI.x/n differs by 1 across the seam)",
            f"float rcPhase = fract(time * {F6(quant_fract_speed(u(44, 0.06, 0.14)))} - rcI.x / {F(float(rc_n))} + rcI.y * 0.37);",
            f"float rcLit = pow(clamp(1.0 - rcPhase * {F(u(45, 1.15, 1.60))}, 0.0, 1.0), {F(u(46, 1.5, 2.8))});",
            f"float rcRail = invsmooth(0.004, {F(u(47, 0.020, 0.035))}, abs(abs(rcF.y - 0.5) - 0.46));",
            f"float mid = clamp(rcGlyph * ({F(u(48, 0.15, 0.30))} + {F(u(49, 0.75, 1.00))} * rcLit) + rcRail * {F(u(55, 0.20, 0.35))}, 0.0, 1.25);",
        ], [])
    if family == "OILSLICK":
        # 2D: oil-on-water -- a curl-warped thickness field whose value
        # ROTATES THE HUE of the live palette (hueSpin with a thickness-
        # driven angle, bounded mix): a continuous hue sweep across the
        # slick, not a fixed accent ramp.
        return (["curl2", "fbm2"], [
            f"vec2 osW = wuv + {F(u(40, 0.35, 0.70))} * curl2(wuv + vec2(0.0, time * {F6(quant_drift(0.06, float(syp)))}), midPer);",
            f"float osTh = fbm2(osW, midPer) * {F(u(41, 1.5, 2.5))} + baseUV.y * {F(u(42, 0.4, 1.0))};",
            f"float osBand = 0.5 + 0.5 * sin(osTh * {F(u(43, 7.0, 12.0))} - time * {qs(44, 0.2, 0.5)});",
            f"float osSheen = pow(clamp(osBand, 0.0, 1.0), {F(u(45, 1.4, 2.4))});",
            f"float mid = clamp(osSheen * {F(u(46, 0.8, 1.1))} + fbm2(osW * 2.0 + vec2(3.9, 8.4), midPer * 2.0) * {F(u(47, 0.15, 0.30))}, 0.0, 1.2);",
        ], [
            "// hue-rotation iridescence: the film thickness spins the palette",
            "// hue itself (bounded, so the owner recolor stays authoritative)",
            f"vec3 osHue = clamp(hueSpin(baseCol, osTh * {F(u(48, 1.2, 2.2))} - {F(u(49, 0.8, 1.6))}), 0.0, 1.0);",
            f"rgb = mix(rgb, rgb * (0.45 + 1.05 * osHue), {F(u(55, 0.28, 0.42))} * osBand);",
        ])
    if family == "PLASMAGLOBE":
        # 2D polar: a tesla globe -- FIVE thin filaments anchored at the
        # exact center (unlike TENDRILNET's three wide off-center arcs),
        # slowly precessing as a whole, wiggling with radius-fed noise, with
        # a two-tier bright core and rim-contact flares where a filament
        # reaches the shell; the space between bolts stays near-transparent
        # (low presence floor).
        return (["safeAtan", "fbm2", "invsmooth"], [
            "float du = baseUV.x - 0.5;",
            "float pgAng = safeAtan(baseUV.y - 0.5, sin(du * 6.2831853) * 0.5);",
            "float pgRad = length(vec2(sin(du * 3.1415927), baseUV.y - 0.5)) * 2.0;",
            "float pgBolts = 0.0;",
            "for (int i = 0; i < 5; i++) {",
            "    float fi = float(i);",
            f"    float fa = fi * 1.2566371 + time * {qs(40, 0.05, 0.12)} + {F(u(41, 0.4, 1.0))} * sin(time * {qs(42, 0.15, 0.35)} + fi * 2.6);",
            f"    float wig = (fbm2(vec2(pgRad * {F(u(43, 3.0, 6.0))} + fi * 9.17, time * {F6(quant_drift(u(44, 0.10, 0.20), NOWRAP_PERIOD))}), vec2({F(NOWRAP_PERIOD)}, {F(NOWRAP_PERIOD)})) - 0.5) * {F(u(45, 0.4, 0.9))} * pgRad;",
            "    float dAng = abs(sin((pgAng - fa) * 0.5 + wig));",
            f"    float fil = pow(clamp(1.0 - dAng * {F(u(46, 2.2, 3.2))}, 0.0, 1.0), {F(u(47, 8.0, 14.0))});",
            f"    pgBolts += fil * (0.55 + 0.45 * sin(time * {qs(48, 2.0, 4.0)} + fi * 1.8));",
            "}",
            f"float pgCore = invsmooth(0.01, {F(u(49, 0.10, 0.18))}, pgRad) * 1.2 + invsmooth(0.0, 0.05, pgRad) * 0.8;",
            f"float pgTip = pgBolts * smoothstep({F(u(55, 0.55, 0.75))}, 1.0, pgRad) * 0.8;",
            "float mid = clamp(pgBolts * smoothstep(0.03, 0.15, pgRad) + pgCore + pgTip, 0.0, 1.4);",
        ], [])
    if family == "ECTOPLASM":
        # 2D: ghost goo -- a v-stretched, downward-advected warped fbm body
        # (the drip domain is anisotropic like AURORA's curtain but flows
        # DOWN) thresholded into a hanging mask that thickens toward the
        # top, with the drip BOUNDARY itself catching a hot rim highlight.
        ec_ky = u(40, 0.35, 0.55)
        ec_qdown = F6(quant_drift(u(41, 0.06, 0.14), float(syp) * ec_ky))
        return (["fbm2"], [
            f"vec2 ecUV = vec2(wuv.x, baseUV.y * {F(float(syp) * ec_ky)} + time * {ec_qdown});",
            f"float ecBody = fbm2(ecUV, vec2(midPer.x, {F(float(syp) * ec_ky)}));",
            f"float ecDrip = fbm2(vec2(ecUV.x * 2.0, ecUV.y * 0.6) + vec2(7.7, 2.3), vec2(midPer.x * 2.0, {F(float(syp) * ec_ky * 0.6)}));",
            f"float ecMask = smoothstep({F(u(43, 0.34, 0.44))}, {F(u(44, 0.60, 0.74))}, ecBody * 0.55 + ecDrip * 0.45 + (1.0 - baseUV.y) * {F(u(45, 0.12, 0.28))});",
            "float ecEdge = ecMask * (1.0 - ecMask) * 4.0;",
            f"float ecSheen = pow(0.5 + 0.5 * sin(ecBody * {F(u(46, 6.0, 11.0))} - time * {qs(47, 0.3, 0.7)}), {F(u(48, 2.0, 4.0))});",
            f"float mid = clamp(ecMask * ({F(u(49, 0.50, 0.70))} + 0.3 * ecSheen) + ecEdge * {F(u(55, 0.50, 0.80))}, 0.0, 1.2);",
        ], [
            "// drip rims catch the light: the goo boundary lifts to the hot stop",
            f"rgb = mix(rgb, hotStop, clamp(ecEdge, 0.0, 1.0) * {F(u(56, 0.25, 0.40))});",
        ])
    if family == "VOIDRIFT":
        # 3D: a dark shell torn by crack SDFs (ridged fbm3 pushed to a very
        # thin crest) that reveal a BRIGHT starry deep layer through the
        # rifts; the un-cracked body sinks to a near-black in-hue dark stop
        # in the post pass, so the interior only reads through the cracks.
        return (["fbm3", "hash31", "invsmooth"], [
            f"float vrN = fbm3(mdir + vec3(0.0, {F(u(40, 0.10, 0.30))} * sin(time * {qs(41, 0.05, 0.12)}), 0.0));",
            f"float vrCrack = pow(clamp(1.0 - abs(2.0 * vrN - 1.0) * {F(u(42, 1.05, 1.30))}, 0.0, 1.0), {F(u(43, 10.0, 18.0))});",
            f"float vrWide = pow(clamp(1.0 - abs(2.0 * vrN - 1.0), 0.0, 1.0), {F(u(44, 3.0, 5.0))});",
            "// the bright deep layer shows only THROUGH the cracks: a starry",
            "// lattice on the (day-safely rotated) sphere direction",
            f"vec3 vrS = rotA(spinAxis, time * {qs(45, 0.02, 0.06)}) * (sdir * {F(u(46, 9.0, 16.0))});",
            "vec3 vrCell = floor(vrS);",
            "float vrH = hash31(vrCell);",
            f"float vrStar = step({F(u(47, 0.75, 0.85))}, vrH) * invsmooth(0.08, 0.42, length(fract(vrS) - 0.5)) * (0.6 + 0.4 * sin(time * (1.5 + 2.0 * vrH) + vrH * 31.0));",
            f"float vrGlow = vrCrack * ({F(u(48, 0.70, 1.00))} + {F(u(49, 0.50, 0.80))} * vrStar) + vrWide * vrStar * 0.35;",
            f"float mid = clamp(vrGlow + vrWide * {F(u(55, 0.08, 0.16))}, 0.0, 1.3);",
        ], [
            "// the un-cracked body sinks to near-black (in-hue): the void is",
            "// a dark shell, and the starry interior reads only in the rifts",
            f"rgb = mix(rgb, deepStop * {F(u(56, 0.40, 0.60))}, clamp(1.0 - vrWide * {F(u(57, 1.4, 2.0))}, 0.0, 1.0) * {F(u(58, 0.60, 0.80))});",
        ])
    raise KeyError(family)


# Family-tuned presence + grade (v4): per-family ranges for the presence
# floor / gain / alpha ceiling, the saturation lift and the mid coverage over
# the deep volume. Pale/airy families (VOLUMECLOUD, CHROME, KALEIDO, NEBULA)
# get a SOLID floor + raised saturation so the sky never washes them out;
# sparse-dark families (BIOLUME, STARFIELD, PORTALVOID, ...) keep LOW floors
# so their darkness is allowed to read. Missing keys fall back to _default;
# the vertexColor.a dissolve still always wins the final alpha.
FAMILY_PRESENCE = {
    "_default":     {"floor": (0.24, 0.37), "gain": (0.32, 0.50), "amax": (0.78, 0.86), "sat": (1.08, 1.35), "cover": (0.85, 1.15)},
    "VOLUMECLOUD":  {"floor": (0.32, 0.42), "gain": (0.26, 0.38), "amax": (0.80, 0.88), "sat": (1.30, 1.55), "cover": (0.70, 0.95)},
    "CHROME":       {"floor": (0.34, 0.44), "gain": (0.34, 0.48), "amax": (0.84, 0.90), "sat": (1.25, 1.50), "cover": (0.95, 1.20)},
    "KALEIDO":      {"floor": (0.32, 0.42), "gain": (0.34, 0.48), "amax": (0.82, 0.90), "sat": (1.25, 1.50), "cover": (0.90, 1.20)},
    "NEBULA":       {"floor": (0.30, 0.40), "gain": (0.32, 0.46), "amax": (0.82, 0.90), "sat": (1.30, 1.55)},
    "THINFILM":     {"sat": (1.20, 1.45)},
    "LAVAFLOW":     {"floor": (0.34, 0.46), "amax": (0.86, 0.92), "sat": (1.12, 1.35)},
    "BIOLUME":      {"floor": (0.10, 0.18), "gain": (0.46, 0.60), "amax": (0.80, 0.88), "sat": (1.20, 1.45)},
    "STARFIELD":    {"floor": (0.10, 0.18), "gain": (0.45, 0.60)},
    "PORTALVOID":   {"floor": (0.14, 0.22), "gain": (0.42, 0.58), "amax": (0.82, 0.90)},
    "VOIDTENDRIL":  {"floor": (0.16, 0.26), "gain": (0.40, 0.55)},
    "KALISET":      {"floor": (0.14, 0.22), "gain": (0.42, 0.58), "sat": (1.20, 1.45)},
    "TENDRILNET":   {"floor": (0.12, 0.20), "gain": (0.46, 0.62)},
    "GALAXYSWIRL":  {"floor": (0.12, 0.20), "gain": (0.46, 0.62)},
    "HOLOGRID":     {"floor": (0.10, 0.18), "gain": (0.50, 0.65)},
    "RIBBONAURORA": {"floor": (0.12, 0.20), "gain": (0.44, 0.60), "sat": (1.15, 1.40)},
    "EMBERSTORM":   {"floor": (0.14, 0.22), "gain": (0.45, 0.60)},
    "FROSTFERN":    {"sat": (1.15, 1.40)},
    # v5 families (only ever read once a registry row references them, so
    # adding these entries cannot perturb any existing id's draws or bytes).
    "SPECTRALVEIL": {"floor": (0.10, 0.18), "gain": (0.38, 0.52), "amax": (0.72, 0.82), "sat": (1.10, 1.35)},
    "RAYMARCHFOG":  {"floor": (0.30, 0.40), "gain": (0.28, 0.40), "amax": (0.80, 0.88), "sat": (1.25, 1.50), "cover": (0.70, 0.95)},
    "PRISMDISPERSE": {"floor": (0.24, 0.34), "gain": (0.34, 0.50), "sat": (1.20, 1.45)},
    "HOLOPARALLAX": {"floor": (0.10, 0.18), "gain": (0.48, 0.62), "amax": (0.78, 0.86)},
    "ORBITTRAP":    {"floor": (0.12, 0.20), "gain": (0.44, 0.58), "sat": (1.18, 1.42)},
    "CRYSTALSDF":   {"floor": (0.26, 0.38), "gain": (0.36, 0.52), "sat": (1.12, 1.38)},
    "FLUIDINK":     {"floor": (0.28, 0.38), "gain": (0.32, 0.46), "sat": (1.18, 1.42)},
    "IRISFILM":     {"floor": (0.16, 0.26), "gain": (0.38, 0.54), "amax": (0.74, 0.84), "sat": (1.25, 1.50)},
    "AETHERSMOKE":  {"floor": (0.24, 0.34), "gain": (0.30, 0.44), "sat": (1.15, 1.40), "cover": (0.75, 1.00)},
    "STAINEDGLASS": {"floor": (0.30, 0.42), "gain": (0.34, 0.48), "amax": (0.84, 0.90), "sat": (1.20, 1.45)},
    "PHANTOMECHO":  {"floor": (0.08, 0.16), "gain": (0.46, 0.62), "amax": (0.74, 0.84)},
    "GRAVLENS":     {"floor": (0.08, 0.14), "gain": (0.48, 0.62)},
    "MYCELIA":      {"floor": (0.12, 0.20), "gain": (0.44, 0.60)},
    "SOLARFLARE":   {"floor": (0.12, 0.20), "gain": (0.46, 0.62), "sat": (1.15, 1.40)},
    "DEEPICE":      {"floor": (0.28, 0.40), "gain": (0.32, 0.46), "amax": (0.82, 0.90), "sat": (1.12, 1.35)},
    "RUNECIRCUIT":  {"floor": (0.10, 0.18), "gain": (0.48, 0.64)},
    "OILSLICK":     {"floor": (0.26, 0.36), "gain": (0.32, 0.46), "sat": (1.25, 1.50)},
    "PLASMAGLOBE":  {"floor": (0.06, 0.12), "gain": (0.50, 0.66), "amax": (0.80, 0.88)},
    "ECTOPLASM":    {"floor": (0.18, 0.28), "gain": (0.40, 0.56), "sat": (1.12, 1.38)},
    "VOIDRIFT":     {"floor": (0.14, 0.24), "gain": (0.42, 0.58)},
}

# Families whose DEEP volume must stay subordinate to the MID signature (the
# 10-way review found their deep weight muddied the read): multiplies the
# baked deep weight `dw` in the composite.
DEEP_WEIGHT = {
    "KALISET": 0.5, "VOIDTENDRIL": 0.55, "VOLUMECLOUD": 0.75,
    "TENDRILNET": 0.7, "GALAXYSWIRL": 0.7, "PORTALVOID": 0.7, "BIOLUME": 0.65,
    # v5: the sparse/dark signatures must not be muddied by the deep volume
    # (and the march/composite families already carry their own depth).
    "ORBITTRAP": 0.5, "GRAVLENS": 0.5, "PLASMAGLOBE": 0.45, "VOIDRIFT": 0.5,
    "PHANTOMECHO": 0.6, "RUNECIRCUIT": 0.6, "SOLARFLARE": 0.6, "MYCELIA": 0.65,
    "HOLOPARALLAX": 0.6, "SPECTRALVEIL": 0.7, "RAYMARCHFOG": 0.65,
    "AETHERSMOKE": 0.65, "STAINEDGLASS": 0.7,
}


def secondary_consts(prim: int, sec: int) -> tuple:
    """Bakes the primary -> secondary palette relation into three shader
    constants (hue-rotation angle, saturation ratio, value ratio).

    The vertex format is POSITION_TEX_COLOR: there is NO spare attribute for a
    second color, so instead of changing every pipeline the fragment shader
    derives `secCol` from vertexColor.rgb at runtime through these constants.
    Both colors therefore re-derive from the LIVE palette -- the owner /color
    override recolors primary and secondary together (recolor-safe)."""
    pr, pg, pb = ((prim >> 16 & 0xFF) / 255.0, (prim >> 8 & 0xFF) / 255.0, (prim & 0xFF) / 255.0)
    sr, sg, sb = ((sec >> 16 & 0xFF) / 255.0, (sec >> 8 & 0xFF) / 255.0, (sec & 0xFF) / 255.0)
    ph, ps, pv = colorsys.rgb_to_hsv(pr, pg, pb)
    sh, ss, sv = colorsys.rgb_to_hsv(sr, sg, sb)
    dh = sh - ph
    if dh > 0.5:
        dh -= 1.0
    elif dh < -0.5:
        dh += 1.0
    ang = max(-2.6, min(2.6, dh * TWO_PI))
    sat_ratio = max(0.35, min(2.2, ss / max(ps, 0.05)))
    val_ratio = max(0.30, min(1.9, sv / max(pv, 0.05)))
    return ang, sat_ratio, val_ratio


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
    "KALISET": (3.0, 6.0), "VOLUMECLOUD": (2.5, 5.0), "CHROME": (3.0, 6.0),
    "LAVAFLOW": (4.0, 7.0), "TENDRILNET": (4.0, 8.0), "GALAXYSWIRL": (3.0, 6.0),
    "RIBBONAURORA": (5.0, 9.0), "FROSTFERN": (6.0, 10.0), "BIOLUME": (6.0, 10.0),
    "HOLOGRID": (7.0, 12.0), "PORTALVOID": (3.0, 6.0), "EMBERSTORM": (8.0, 14.0),
    "SHARDTESS": (6.0, 10.0), "SACREDGEO": (5.0, 9.0), "VOIDTENDRIL": (3.0, 6.0),
    "CRYSTALREFRACT": (6.0, 10.0),
    "SPECTRALVEIL": (3.0, 6.0), "RAYMARCHFOG": (2.5, 5.0),
    "PRISMDISPERSE": (3.0, 6.0), "HOLOPARALLAX": (6.0, 10.0),
    "ORBITTRAP": (3.0, 6.0), "CRYSTALSDF": (3.0, 6.0), "FLUIDINK": (3.0, 6.0),
    "IRISFILM": (3.0, 5.5), "AETHERSMOKE": (3.0, 6.0),
    "STAINEDGLASS": (5.0, 9.0), "PHANTOMECHO": (3.0, 6.0),
    "GRAVLENS": (8.0, 14.0), "MYCELIA": (6.0, 11.0), "SOLARFLARE": (3.0, 6.0),
    "DEEPICE": (5.0, 9.0), "RUNECIRCUIT": (5.0, 9.0), "OILSLICK": (4.0, 7.0),
    "PLASMAGLOBE": (3.0, 6.0), "ECTOPLASM": (5.0, 9.0), "VOIDRIFT": (3.0, 6.0),
}


def emit_shader(asg: dict) -> str:
    """Emits one standalone .fsh for the given assignment row.

    Draw-index reservations (the PRNG stream is 128 unit draws; the first 96
    keep their v2 meanings so regenerated files stay maximally diff-stable):
      0..39   shared helper knobs, 40..49 family knobs, 50..59 rim knobs,
      60..61 flourish, 62..83 composite/deep v2 knobs (some now unused but
      still drawn for stream stability), 84..95 gradient3/alpha knobs,
      96..127 v3 depth/3D-domain knobs (v4 adds 123..127 for the
      Beer-Lambert / soft-clip knobs and repurposes 85 for the hot-stop
      saturation lift). The draws are POSITIONAL lookups into a fixed
      128-entry table, so a composer reusing a spare index only correlates
      two knobs -- it can never shift any other draw.
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

    dw = F(u(62, 0.30, 0.50) * DEEP_WEIGHT.get(family, 1.0))
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
    _d_fall = u(75, 0.55, 0.95)  # v3 exponential plane weights: superseded by
    d_pow = F(u(76, 1.0, 1.8))   # the v4 Beer-Lambert transmittance
    d_step = u(77, 0.03, 0.08)
    _ddx = u(78, -0.05, 0.05)  # v2 lattice drifts: drawn for stream stability
    _ddy = u(79, -0.05, 0.05)  # (3D-domain animation is rotation-only)

    # gradient3 palette + presence-alpha knobs (indices 84..95). The presence
    # and grade ranges are FAMILY-TUNED (v4): pale/airy families get a solid
    # floor + raised saturation, sparse-dark families keep low floors.
    pres = {**FAMILY_PRESENCE["_default"], **FAMILY_PRESENCE.get(family, {})}
    dk = F(u(84, 0.55, 0.85))          # dark-stop depth (base*base*dk)
    hue_ang = F(u(86, -0.6981, 0.6981))  # highlight hue nudge, +-40 deg
    sat = F(u(88, *pres["sat"]))       # family-tuned saturation lift
    hlw = F(u(89, 0.20, 0.40))         # soft-clip mix weight on hot crests
    pk = F(u(90, 0.70, 0.95))          # pattern -> gradient position scale
    rk = F(u(91, 0.12, 0.30))          # chromatic rim gradient offset
    a_base = F(u(92, 0.04, 0.08))      # alpha where no pattern (dark, thin)
    a_floor = F(u(93, *pres["floor"])) # family-tuned presence floor
    a_gain = F(u(94, *pres["gain"]))   # family-tuned alpha rise on features
    a_max = F(u(95, *pres["amax"]))    # family-tuned translucency ceiling

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
    mwc = F(u(110, *pres["cover"]))    # family-tuned mid coverage over the deep volume
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

    # v4 color/depth knobs (indices 123..127 + repurposed 85/89).
    absorb = u(123, 0.35, 0.60)        # Beer-Lambert per-plane absorption
    a_vol = F(u(124, 0.08, 0.16))      # deep-opacity alpha contribution
    soft_k = F(u(125, 2.2, 3.4))       # hue-preserving soft-clip steepness
    cap_gain = F(u(126, 1.45, 1.85))   # hot-stop luma cap vs base luma
    cap_lo = F(u(127, 0.45, 0.60))     # hot-stop luma cap floor
    hot_sat = F(u(85, 1.10, 1.35))     # hot-stop saturation lift (repurposed draw)

    # v5 quality-layer knobs, read ONLY for the 20 v5 families. Byte-safety:
    # the 128 draws are materialized up front and u() is a positional lookup,
    # so conditionally reading spare indices here can never shift any other
    # knob -- and no registry id maps to a v5 family yet, so no existing file
    # gains these lines.
    is_v5 = family in FAMILIES_V5
    if is_v5:
        v5_bf_dim = F(u(8, 0.25, 0.45))     # back-face recede toward the dark stop
        v5_bf_dens = F(u(74, 0.15, 0.35))   # back-face alpha densify
        v5_sp_w = F(u(114, 0.10, 0.22))     # slope-parallax pattern weight
        v5_sp_step = u(115, 0.05, 0.11)     # slope-parallax per-tap offset
        v5_sp_scale = u(67, 1.4, 2.4)       # slope-parallax domain scale
        v5_ghost_lo = u(68, 0.38, 0.55)     # ghost-alpha floor at zero luminance
        v5_knee = u(70, 0.62, 0.78)         # soft-knee highlight start
        v5_knee_k = F(u(71, 1.6, 2.6))      # soft-knee compression steepness
        v5_dither = F(u(75, 0.06, 0.14))    # blue-noise alpha dither amplitude
    sec_ang, sec_sat, sec_val = secondary_consts(
        asg["primary"], asg["secondary"] if asg.get("secondary") is not None else asg["primary"])

    # Correlated multi-plane parallax (v4): ONE deep field sampled at `taps`
    # depth planes on the 3D sphere direction. Real parallax law per plane:
    # farther planes show finer features (scale * (1 + i*g)), rotate slower
    # (strictly decreasing integer turns/day), shift along the silhouette
    # slope (rimDir), and recede toward the dark stop (aerial perspective).
    # v4 composites FRONT-TO-BACK with Beer-Lambert transmittance: each
    # plane's density occludes the planes behind it, its light lands under
    # the REMAINING transmittance into BOTH the color accumulator (per-plane
    # color: baseCol receding through secCol to deepStop) and the opacity
    # term (1 - deepTrans) that feeds the alpha path -- a real volume, not
    # one averaged scalar.
    tap_lines = ["float deepTrans = 1.0;", "vec3 deepCol = vec3(0.0);"]
    norm = 0.0
    trans_est = 1.0
    prev_turns = None
    for i in range(taps):
        turns = max(1, round(base_turns / (1.0 + i * plane_g)))
        if prev_turns is not None and turns >= prev_turns:
            turns = max(1, prev_turns - 1)  # strictly slower with depth
        prev_turns = turns
        speed = F6(turns * TWO_PI / DAY_SECONDS)
        scale = F(deep_s0 * (1.0 + i * plane_g))
        t = aer * i / (taps - 1.0)
        if i == 0:
            plane_col = "baseCol"
        else:
            plane_col = (f"mix(mix(baseCol, secCol, {F(min(1.0, 1.5 * t))}), "
                         f"deepStop, {F(0.55 * t)})")
        sample = f"deepField(rotA(spinAxis, time * {speed}) * (sdir * {scale})"
        if i > 0:
            sample += f" + par * {F(i * d_step)}"
        sample += ", time)"
        if i == 0:
            tap_lines.append(f"float dp = {sample};")
        else:
            tap_lines.append(f"dp = {sample};")
        tap_lines.append(f"deepCol += deepTrans * dp * {plane_col};")
        tap_lines.append(f"deepTrans *= 1.0 - {F(absorb)} * clamp(dp, 0.0, 1.0);")
        norm += trans_est * 0.6  # expected light with mean density ~0.6
        trans_est *= 1.0 - absorb * 0.6
    full_op = 1.0 - (1.0 - absorb) ** taps  # opacity at full density
    tap_lines.append(f"deepCol *= {F(d_bright / max(norm, 0.5))};")
    tap_lines.append(f"float deepPat = pow(clamp((1.0 - deepTrans) * {F(1.0 / full_op)}, 0.0, 1.0), {d_pow});")

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
    lines.append("    // darkens AND saturates (base*base stays in-hue instead of greying);")
    lines.append("    // the hot stop is a LUMA-CAPPED CHROMATIC highlight -- a hue-nudged,")
    lines.append("    // saturation-lifted, brightened base whose luma is capped relative to")
    lines.append("    // the base luma, so the highlight can NEVER wash out toward white.")
    lines.append("    // The owner /color override replaces vertexColor wholesale, so the")
    lines.append("    // whole ramp re-derives from it -- recolor-safe by construction.")
    lines.append("    vec3 baseCol = vertexColor.rgb;")
    lines.append(f"    vec3 deepStop = baseCol * baseCol * {dk};")
    lines.append(f"    vec3 spun = clamp(hueSpin(baseCol, {hue_ang}), 0.0, 1.0);")
    lines.append(f"    vec3 hotStop = satLift(spun, {hot_sat}) * 1.45;")
    lines.append("    float hotLuma = dot(hotStop, vec3(0.299, 0.587, 0.114));")
    lines.append(f"    float lumaCap = max({cap_lo}, dot(baseCol, vec3(0.299, 0.587, 0.114)) * {cap_gain});")
    lines.append("    hotStop = clamp(hotStop * min(1.0, lumaCap / max(hotLuma, 0.001)), 0.0, 1.0);")
    lines.append("    // Secondary palette color, derived IN-SHADER from vertexColor via the")
    lines.append("    // baked primary->secondary relation (hue angle + sat/value ratios) --")
    lines.append("    // the vertex format has no second color attribute, and deriving both")
    lines.append("    // from the live vertexColor keeps the owner recolor authoritative.")
    lines.append(f"    vec3 secCol = clamp(satLift(clamp(hueSpin(baseCol, {F(sec_ang)}), 0.0, 1.0), {F(sec_sat)}) * {F(sec_val)}, 0.0, 1.0);")
    lines.append("")
    lines.append(f"    // [layer:deep:{deep_marker}]")
    lines.append("    // Interior volume: correlated parallax PLANES of ONE deep field on")
    lines.append("    // the 3D sphere direction, composited FRONT-TO-BACK under Beer-")
    lines.append("    // Lambert transmittance -- each plane occludes the ones behind it,")
    lines.append("    // and its light lands in BOTH the color accumulator (per-plane color")
    lines.append("    // receding baseCol -> secCol -> dark stop) and the opacity term that")
    lines.append("    // feeds the alpha. Farther planes show finer features, spin slower")
    lines.append("    // (integer turns/day: the daily wrap lands on a full turn) and slide")
    lines.append("    // along the silhouette slope (rimDir is screen-space: seam-safe).")
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
    lines.append("    // Recolor-safe composite v4: the whole pattern is graded through the")
    lines.append("    // vertexColor-derived 3-stop ramp (low pattern falls to the DARK stop")
    lines.append("    // and low alpha -- never pale grey), the deep volume sits BEHIND the")
    lines.append("    // signature structure, and the gradient position leans toward the hot")
    lines.append("    // stop at the rim (chromatic rim). The vertexColor.a dissolve near")
    lines.append("    // whitelisted players always wins the final alpha.")
    if is_v5:
        lines.append("    // [layer:v5:slopeparallax]")
        lines.append("    // v5 slope-parallax multi-tap: three EXTRA taps of the deep field,")
        lines.append("    // stepped along the silhouette slope (par, screen-space: seam-safe)")
        lines.append("    // at growing offset and scale -- the membrane texture itself gains")
        lines.append("    // depth where the shell curves away from the view.")
        lines.append(f"    float v5Par = deepField(sdir * {F(v5_sp_scale)} + par * {F(v5_sp_step)}, time) * 0.5;")
        lines.append(f"    v5Par += deepField(sdir * {F(v5_sp_scale * 1.35)} + par * {F(v5_sp_step * 2.0)}, time) * 0.3;")
        lines.append(f"    v5Par += deepField(sdir * {F(v5_sp_scale * 1.75)} + par * {F(v5_sp_step * 3.0)}, time) * 0.2;")
        lines.append(f"    float pattern = clamp({dw} * deepPat + {mw} * mid + {rw} * rim + flourish + grain + {v5_sp_w} * v5Par, 0.0, 1.5);")
    else:
        lines.append(f"    float pattern = clamp({dw} * deepPat + {mw} * mid + {rw} * rim + flourish + grain, 0.0, 1.5);")
    lines.append(f"    float gpos = clamp(pattern * {pk} + rim * {rk}, 0.0, 1.0);")
    lines.append("    vec3 rgb = gradient3(deepStop, baseCol, hotStop, gpos);")
    lines.append(f"    float midCover = clamp({mwc} * mid + {rwc} * rim, 0.0, 1.0);")
    lines.append("    rgb = mix(deepCol, rgb, midCover);")
    lines.append(f"    rgb = satLift(rgb, {sat});")
    lines.append("    // Hue-preserving soft-clip on the hot crests: brightness saturates")
    lines.append("    // toward the palette's own bright tint (1 - exp(-k * hotStop * x)),")
    lines.append("    // never toward additive white -- rich color instead of pastel.")
    lines.append(f"    vec3 softHot = 1.0 - exp(-{soft_k} * hotStop * (pattern + 0.35 * rim));")
    lines.append(f"    rgb = mix(rgb, softHot, {hlw} * smoothstep(0.55, 1.10, pattern));")
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
    if is_v5:
        lines.append("    // [layer:v5:softknee]")
        lines.append("    // v5 soft-knee highlight rolloff: channels over the knee compress")
        lines.append("    // exponentially instead of clipping (continuous at the knee since")
        lines.append("    // exp(0) = 1), so hot crests keep hue separation right up to white.")
        lines.append(f"    vec3 v5Over = max(rgb - vec3({F(v5_knee)}), vec3(0.0));")
        lines.append(f"    rgb = min(rgb, vec3({F(v5_knee)})) + {F(1.0 - v5_knee)} * (1.0 - exp(-v5Over * {v5_knee_k}));")
    lines.append("    // Presence alpha (family-tuned): a solid-but-translucent membrane")
    lines.append("    // floor wherever the pattern is present, rising toward the ceiling on")
    lines.append("    // bright features, plus the deep volume's own Beer-Lambert opacity;")
    lines.append("    // pattern-free areas stay dark AND thin (anti-washout).")
    lines.append(f"    float presence = smoothstep(0.02, 0.30, pattern);")
    lines.append(f"    float alpha = vertexColor.a * min({a_base} + {a_floor} * presence + {a_gain} * pattern + {a_vol} * (1.0 - deepTrans), {a_max});")
    if is_v5:
        lines.append("    // [layer:v5:backface]")
        lines.append("    // v5 back-face densify/dim (gl_FrontFacing is a builtin, no uniform")
        lines.append("    // needed): the INSIDE of the far shell recedes toward the dark stop")
        lines.append("    // and gains alpha, so the bubble reads as a filled volume from within.")
        lines.append("    float v5Back = gl_FrontFacing ? 0.0 : 1.0;")
        lines.append(f"    rgb = mix(rgb, deepStop, v5Back * {v5_bf_dim});")
        lines.append(f"    alpha = min(alpha * (1.0 + v5Back * {v5_bf_dens}), {a_max});")
        lines.append("    // [layer:v5:ghostalpha]")
        lines.append("    // v5 luminance-weighted ghost translucency: dark areas thin out while")
        lines.append("    // bright features hold. Multiplicative on the existing alpha, so the")
        lines.append("    // vertexColor.a dissolve near whitelisted players still always wins.")
        lines.append("    float v5Luma = dot(clamp(rgb, 0.0, 1.0), vec3(0.299, 0.587, 0.114));")
        lines.append(f"    alpha *= {F(v5_ghost_lo)} + {F(1.0 - v5_ghost_lo)} * smoothstep(0.03, 0.42, v5Luma);")
        lines.append("    // [layer:v5:dither]")
        lines.append("    // v5 blue-noise-style alpha dither (interleaved gradient noise on")
        lines.append("    // gl_FragCoord) breaks translucency banding; multiplicative, so it is")
        lines.append("    // scaled by the dissolve and alpha = 0 stays exactly 0.")
        lines.append("    float v5Dither = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));")
        lines.append(f"    alpha = clamp(alpha * (1.0 + (v5Dither - 0.5) * {v5_dither}), 0.0, 1.0);")
    lines.append("    vec4 color = vec4(clamp(rgb, 0.0, 1.0), alpha);")
    lines.append("    if (color.a < 0.01) {")
    lines.append("        discard;")
    lines.append("    }")
    lines.append("    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, "
                 "FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);")
    lines.append("}")

    source = "\n".join(lines) + "\n"
    n = source.count("\n")
    if not 110 <= n <= 420:
        sys.exit(f"fx_{effect_id:03d}: emitted {n} lines, outside the 110..420 sanity bounds")
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
                                       "default: all 420. Emitted bytes are identical to a full run.")
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

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
  and `<minecraft:globals.glsl>`; inputs texCoord0 (RAW [0,1] sphere UV --
  fract() before periodic use), vertexColor (rgb = palette = dominant chroma,
  a = dissolve; final alpha is ALWAYS vertexColor.a * pattern),
  sphericalVertexDistance, cylindricalVertexDistance; out fragColor; animation
  ONLY via `float time = GameTime * 1200.0;`; NO custom uniforms, NO textures;
  `discard` when alpha < 0.01; the last statement applies apply_fog exactly
  like the 16 original hand-written seed shaders did (deleted in the final
  cleanup milestone; git history preserves them as reference points).

* Helper snippet bank (inlined per file, ONLY the helpers a shader uses):
  hash11/hash21/hash22, vnoise (quintic fade), fbm2 (modes standard/ridged/turb,
  inter-octave rotation, <=6 octaves), warp1/warp2 (iq domain warp), curl2,
  voro2 (F1/F2/exact-border/cell-id, 3x3 first pass + 5x5 border pass),
  voronoise, hexDist/hexCoords, triGrid, truchet, polarFold, spiralWarp,
  caustic, thinFilm, accentPalette (iq cosine, baked consts), rimGraze
  (fwidth(sphericalVertexDistance) silhouette estimator), rimLat, sparkle,
  ringPulse, and an unrolled-const parallax deep stack (<=4 taps).

* 24 MID-layer technique composers keyed by the 24 SurfaceTemplate families:
  the 16 existing enum names (PLASMA..LIGHTNING) + THINFILM, CAUSTIC,
  CURLSMOKE, TRUCHET, RIDGED, MOIRE, TRIWEAVE, NEBULA.

* Per-id assignment table: ids 0..104 seed their family from the current
  EffectRegistry.java surface column (parsed at generation time as ground
  truth); ids 105..349 cycle all 24 families per 5-id color block. Every id
  gets a distinct (family, warpMode, deepStack, rimStyle, animMode) tuple,
  guaranteed by deterministic probing and re-asserted here and by
  tools/validate_shaders.py.

* Structural depth rule: every file carries the three marker comments
  `// [layer:deep:<mod>]`, `// [layer:mid:<mod>]`, `// [layer:rim:<mod>]`
  (machine-checked by the validator) plus a small "flourish" accent layer and
  a micro-grain detail pass.

* Recolor safety: pattern layers multiply vertexColor.rgb; cosine-palette /
  thin-film accents contribute only through a bounded mix biased toward
  vertexColor.rgb (weight <= 0.45); alpha = vertexColor.a * pattern.

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


def parse_registry() -> dict:
    """Parses EffectRegistry.java rows: id -> (surfaceName, rgbPrimary, rgbSecondary).

    Accepts any dense catalogue of at least the 105 V1 rows: ids 0..104 seed
    their family/palette from the registry (frozen), while ids >= 105 are
    computed by build_assignments and only cross-checked against the registry
    (the milestone-D expansion rows were authored FROM this generator's
    manifest, so a mismatch means the two have drifted).
    """
    text = REGISTRY_JAVA.read_text()
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
        if effect_id < 105:
            family = registry[effect_id][0]
            prim = registry[effect_id][1]
        else:
            block = effect_id // 5
            slot = effect_id % 5
            family = FAMILIES[(5 * (block + slot)) % len(FAMILIES)]
            prim = None
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
    "hash11": [],
    "hash21": [],
    "hash22": [],
    "vnoise": ["hash21"],
    "fbm2": ["vnoise"],
    "warp1": ["fbm2"],
    "warp2": ["fbm2", "warp1"],
    "curl2": ["vnoise"],
    "voro2": ["hash22", "hash21"],
    "voronoise": ["hash22", "hash21"],
    "hexDist": [],
    "hexCoords": ["hexDist"],
    "triGrid": ["hash21"],
    "truchet": ["hash21"],
    "polarFold": [],
    "spiralWarp": [],
    "caustic": ["vnoise"],
    "thinFilm": [],
    "accentPalette": [],
    "rimGraze": [],
    "rimLat": [],
    "sparkle": ["hash21"],
    "ringPulse": ["hash22"],
    "deepField": [],  # deps filled per deep recipe at emission time
}

CANONICAL_ORDER = [
    "hash11", "hash21", "hash22", "vnoise", "fbm2", "warp1", "warp2", "curl2",
    "voro2", "voronoise", "hexDist", "hexCoords", "triGrid", "truchet",
    "polarFold", "spiralWarp", "caustic", "thinFilm", "accentPalette",
    "rimGraze", "rimLat", "sparkle", "ringPulse", "deepField",
]


def helper_source(name: str, c: dict) -> str:
    """Returns the GLSL source of one helper, with per-file consts baked in."""
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
    if name == "vnoise":
        return (
            "float vnoise(vec2 p) {\n"
            "    vec2 i = floor(p);\n"
            "    vec2 f = fract(p);\n"
            "    vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);\n"
            "    float a = hash21(i);\n"
            "    float b = hash21(i + vec2(1.0, 0.0));\n"
            "    float cc = hash21(i + vec2(0.0, 1.0));\n"
            "    float d = hash21(i + vec2(1.0, 1.0));\n"
            "    return mix(mix(a, b, u.x), mix(cc, d, u.x), u.y);\n"
            "}")
    if name == "fbm2":
        mode = c["fbmMode"]
        if mode == "standard":
            tap = "value += amplitude * vnoise(p);"
        elif mode == "ridged":
            tap = "value += amplitude * (1.0 - abs(2.0 * vnoise(p) - 1.0));"
        else:  # turb
            tap = "value += amplitude * abs(2.0 * vnoise(p) - 1.0);"
        return (
            f"// fractal noise, {mode} mode, {c['octaves']} octaves, inter-octave rotation\n"
            "float fbm2(vec2 p) {\n"
            "    float value = 0.0;\n"
            "    float amplitude = 0.5;\n"
            "    mat2 rot = mat2(0.8, 0.6, -0.6, 0.8);\n"
            f"    for (int i = 0; i < {c['octaves']}; i++) {{\n"
            f"        {tap}\n"
            f"        p = rot * p * {c['lac']} + vec2(17.7, 9.2);\n"
            "        amplitude *= 0.5;\n"
            "    }\n"
            "    return value;\n"
            "}")
    if name == "warp1":
        return (
            "// iq single-stage domain warp: f(p + h(p))\n"
            "vec2 warp1(vec2 p, float t) {\n"
            f"    return p + {c['warpAmp']} * (vec2(fbm2(p + vec2(0.0, t * 0.31)),\n"
            "        fbm2(p + vec2(5.2, 1.3) - vec2(t * 0.27, 0.0))) - 0.5);\n"
            "}")
    if name == "warp2":
        return (
            "// iq two-stage nested domain warp: f(p + h(p + g(p)))\n"
            "vec2 warp2(vec2 p, float t) {\n"
            "    vec2 q = warp1(p, t);\n"
            f"    return p + {c['warpAmp2']} * (vec2(fbm2(q + vec2(1.7, 9.2)),\n"
            "        fbm2(q + vec2(8.3, 2.8) + vec2(0.0, t * 0.21))) - 0.5);\n"
            "}")
    if name == "curl2":
        return (
            "// divergence-free curl of a value-noise potential (gradient rotated 90 deg)\n"
            "vec2 curl2(vec2 p) {\n"
            "    float e = 0.02;\n"
            "    float n1 = vnoise(p + vec2(e, 0.0));\n"
            "    float n2 = vnoise(p - vec2(e, 0.0));\n"
            "    float n3 = vnoise(p + vec2(0.0, e));\n"
            "    float n4 = vnoise(p - vec2(0.0, e));\n"
            "    return vec2(n3 - n4, -(n1 - n2)) / (2.0 * e);\n"
            "}")
    if name == "voro2":
        jit = c["voroJit"]
        return (
            "// iq two-pass voronoi: x = exact border distance, y = F1, z = cell hash\n"
            "vec3 voro2(vec2 p, float t) {\n"
            "    vec2 n = floor(p);\n"
            "    vec2 f = fract(p);\n"
            "    vec2 mg = vec2(0.0);\n"
            "    vec2 mr = vec2(0.0);\n"
            "    float md = 8.0;\n"
            "    for (int j = -1; j <= 1; j++) {\n"
            "        for (int i = -1; i <= 1; i++) {\n"
            "            vec2 g = vec2(float(i), float(j));\n"
            "            vec2 o = hash22(n + g);\n"
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
            "            vec2 o = hash22(n + g);\n"
            f"            o = 0.5 + {jit} * sin(t + 6.2831853 * o);\n"
            "            vec2 r = g + o - f;\n"
            "            if (dot(mr - r, mr - r) > 0.00001) {\n"
            "                mbd = min(mbd, dot(0.5 * (mr + r), normalize(r - mr)));\n"
            "            }\n"
            "        }\n"
            "    }\n"
            "    return vec3(mbd, sqrt(md), hash21(n + mg));\n"
            "}")
    if name == "voronoise":
        return (
            "// iq voronoise: blends cellular and value noise via jitter u / smoothness v\n"
            "float voronoise(vec2 p, float u, float v) {\n"
            "    float k = 1.0 + 63.0 * pow(1.0 - v, 6.0);\n"
            "    vec2 i = floor(p);\n"
            "    vec2 f = fract(p);\n"
            "    vec2 a = vec2(0.0, 0.0);\n"
            "    for (int y = -2; y <= 2; y++) {\n"
            "        for (int x = -2; x <= 2; x++) {\n"
            "            vec2 g = vec2(float(x), float(y));\n"
            "            vec2 o = hash22(i + g);\n"
            "            vec2 d = g - f + o * u;\n"
            "            float w = pow(1.0 - smoothstep(0.0, 1.414, length(d)), k);\n"
            "            a += vec2(hash21(i + g) * w, w);\n"
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
            "// triangle lattice: x = edge distance, y = cell hash, z = triangle parity\n"
            "vec3 triGrid(vec2 p) {\n"
            "    vec2 s = vec2(p.x - p.y * 0.57735, p.y * 1.1547);\n"
            "    vec2 f = fract(s);\n"
            "    vec2 cellIdx = floor(s);\n"
            "    float upper = step(1.0, f.x + f.y);\n"
            "    float d = mix(min(min(f.x, f.y), abs(1.0 - f.x - f.y)),\n"
            "        min(min(1.0 - f.x, 1.0 - f.y), abs(f.x + f.y - 1.0)), upper);\n"
            "    float cellHash = hash21(cellIdx + vec2(upper * 0.5, upper * 0.25));\n"
            "    return vec3(d, cellHash, upper);\n"
            "}")
    if name == "truchet":
        return (
            "// hash-flipped quarter-circle truchet arcs\n"
            "float truchet(vec2 p, float width) {\n"
            "    vec2 id = floor(p);\n"
            "    vec2 f = fract(p) - 0.5;\n"
            "    float flip = step(0.5, hash21(id));\n"
            "    f.x *= mix(1.0, -1.0, flip);\n"
            "    vec2 c = (f.x + f.y > 0.0) ? vec2(0.5, 0.5) : vec2(-0.5, -0.5);\n"
            "    float d = abs(length(f - c) - 0.5);\n"
            "    return smoothstep(width, width * 0.35, d);\n"
            "}")
    if name == "polarFold":
        return (
            f"// kaleidoscope fold into {c['folds']} mirrored sectors\n"
            "vec2 polarFold(vec2 uv, float t) {\n"
            "    vec2 c = uv - vec2(0.5, 0.5);\n"
            "    float r = length(c);\n"
            "    float a = atan(c.y, c.x) + t;\n"
            f"    float sector = {c['sector']};\n"
            "    a = mod(a, sector);\n"
            "    a = abs(a - sector * 0.5);\n"
            "    return vec2(cos(a), sin(a)) * r;\n"
            "}")
    if name == "spiralWarp":
        return (
            "// logarithmic-ish spiral rotation around the pattern center\n"
            "vec2 spiralWarp(vec2 uv, float t) {\n"
            "    vec2 c = uv - vec2(0.5, 0.5);\n"
            "    float r = length(c);\n"
            f"    float a = atan(c.y, c.x) + {c['spiralK']} * r - t;\n"
            "    return vec2(cos(a), sin(a)) * r + vec2(0.5, 0.5);\n"
            "}")
    if name == "caustic":
        return (
            "// jaybird-style iterated caustic: noise-displaced resampling + exp sharpen\n"
            "float caustic(vec2 p, float t) {\n"
            "    vec2 k = p;\n"
            "    float acc = 0.0;\n"
            "    for (int i = 0; i < 3; i++) {\n"
            "        float fi = float(i);\n"
            f"        float w = vnoise(k * {c['causticFreq']} + vec2(t * 0.23, -t * 0.17) + vec2(fi * 3.7, fi * 1.3));\n"
            "        k += 0.35 * vec2(cos(w * 6.2831853), sin(w * 6.2831853));\n"
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
    if name == "rimGraze":
        return (
            "// silhouette estimator: the camera-distance varying changes fastest per\n"
            "// pixel at grazing angles, so fwidth() peaks at the bubble's rim\n"
            "float rimGraze() {\n"
            "    float g = fwidth(sphericalVertexDistance);\n"
            f"    return smoothstep({c['rg0']}, {c['rg1']}, g);\n"
            "}")
    if name == "rimLat":
        return (
            "// latitude band rim: pole caps plus a soft equator belt\n"
            "float rimLat(vec2 uv) {\n"
            "    float lat = abs(uv.y * 2.0 - 1.0);\n"
            f"    float poles = smoothstep({c['rl0']}, 1.0, lat);\n"
            f"    float belt = 1.0 - smoothstep(0.0, {c['rlEq']}, lat);\n"
            f"    return clamp(poles + {c['rlEqW']} * belt, 0.0, 1.0);\n"
            "}")
    if name == "sparkle":
        return (
            "// hash-cell twinkle: sparse offset star points with per-cell phase\n"
            "float sparkle(vec2 p, float t) {\n"
            "    vec2 cellId = floor(p);\n"
            "    vec2 f = fract(p) - 0.5;\n"
            "    float h = hash21(cellId);\n"
            "    vec2 off = vec2(hash21(cellId + 11.3), hash21(cellId + 27.9)) - 0.5;\n"
            "    float d = length(f - off * 0.55);\n"
            "    float tw = pow(0.5 + 0.5 * sin(t * (2.0 + 5.0 * h) + h * 39.0), 6.0);\n"
            f"    return step({c['sparkTh']}, h) * smoothstep(0.22, 0.02, d) * tw;\n"
            "}")
    if name == "ringPulse":
        return (
            "// GameTime-phased expanding rings from hash-seeded surface points\n"
            "float ringPulse(vec2 uv, float t) {\n"
            "    float acc = 0.0;\n"
            "    for (int i = 0; i < 3; i++) {\n"
            "        float fi = float(i);\n"
            f"        vec2 c = hash22(vec2(fi * 7.31 + {c['rpSeed']}, fi * 2.97));\n"
            f"        float phase = fract(t * {c['rpSpeed']} + fi * 0.37);\n"
            "        float d = distance(uv, c);\n"
            "        acc += smoothstep(0.05, 0.0, abs(d - phase * 0.7)) * (1.0 - phase);\n"
            "    }\n"
            "    return acc;\n"
            "}")
    if name == "deepField":
        recipe = c["deep"]
        if recipe == "fbm":
            body = (f"    return fbm2(p * {c['dFreq']} + vec2(0.0, t * {c['dDrift']}));")
            note = "warped-fbm volume"
        elif recipe == "ridge":
            body = (
                f"    float n = fbm2(p * {c['dFreq']} - vec2(t * {c['dDrift']}, 0.0));\n"
                f"    return pow(1.0 - abs(2.0 * n - 1.0), {c['dRidgePow']});")
            note = "ridged crest volume"
        elif recipe == "caustic":
            body = (f"    return caustic(p * {c['dFreq']}, t * {c['dSpeed']});")
            note = "refracted caustic volume"
        else:  # voro
            body = (
                f"    vec3 v = voro2(p * {c['dFreq']}, t * {c['dSpeed']});\n"
                "    return smoothstep(0.95, 0.15, v.y);")
            note = "smooth-voronoi blob volume"
        return (
            f"// DEEP-layer field ({note}), sampled by the parallax stack below\n"
            "float deepField(vec2 p, float t) {\n"
            f"{body}\n"
            "}")
    raise KeyError(name)


def deep_field_deps(recipe: str) -> list:
    return {"fbm": ["fbm2"], "ridge": ["fbm2"], "caustic": ["caustic"], "voro": ["voro2"]}[recipe]


# ---------------------------------------------------------------------------
# MID-layer composers, one per SurfaceTemplate family. Each returns
# (needs, lines, post_lines); lines compute `float mid` from `wuv`
# (warped+animated UV), `baseUV` and `time`; post_lines run in the composite
# after the accent mix (used by THINFILM's bounded tint).
# ---------------------------------------------------------------------------

def mid_composer(family: str, c: dict):
    u = c["u"]
    if family == "PLASMA":
        return (["fbm2"], [
            f"float pn = fbm2(wuv + vec2(fbm2(wuv + vec2(time * {F(u(40, 0.08, 0.18))}, 0.0)), fbm2(wuv - vec2(0.0, time * {F(u(41, 0.07, 0.16))}))));",
            f"float mid = 0.5 + 0.5 * sin(6.2831853 * pn * {F(u(42, 1.2, 2.4))} + time * {F(u(43, 0.5, 1.1))});",
            f"mid = pow(clamp(mid, 0.0, 1.0), {F(u(44, 1.4, 2.6))});",
        ], [])
    if family == "HEX":
        return (["hexCoords", "hash21"], [
            "vec4 hc = hexCoords(wuv);",
            f"float hexEdge = smoothstep({F(u(40, 0.10, 0.20))}, 0.015, hc.y);",
            "float cellPulse = 0.5 + 0.5 * sin(time * " + F(u(41, 0.8, 1.8)) + " + hash21(hc.zw) * 6.2831853);",
            f"float mid = hexEdge * (0.55 + 0.45 * cellPulse) + {F(u(42, 0.15, 0.35))} * cellPulse * smoothstep(0.08, 0.32, hc.y);",
        ], [])
    if family == "WAVES":
        return (["fbm2"], [
            f"float w1 = sin(wuv.y * {F(u(40, 2.2, 4.5))} + time * {F(u(41, 0.6, 1.4))} + fbm2(wuv * 0.9) * {F(u(42, 1.5, 3.2))});",
            f"float w2 = sin(dot(wuv, vec2({F(u(43, 0.4, 1.2))}, {F(u(44, 0.6, 1.6))})) * {F(u(45, 1.8, 3.6))} - time * {F(u(46, 0.4, 1.0))});",
            "float crest = 0.5 + 0.5 * (w1 * 0.6 + w2 * 0.4);",
            f"float mid = pow(clamp(crest, 0.0, 1.0), {F(u(47, 1.4, 2.8))});",
        ], [])
    if family == "AURORA":
        return (["fbm2"], [
            f"float curtain = fbm2(vec2(wuv.x * {F(u(40, 1.2, 2.2))} + time * {F(u(41, 0.16, 0.34))}, wuv.y * {F(u(42, 0.25, 0.6))} - time * {F(u(43, 0.04, 0.10))}));",
            f"float rays = pow(clamp(curtain * {F(u(44, 1.4, 1.9))} - {F(u(45, 0.18, 0.32))}, 0.0, 1.0), 2.0);",
            "float drape = smoothstep(0.0, 0.35, baseUV.y) * smoothstep(1.0, 0.55, baseUV.y);",
            f"float shimmer = 0.5 + 0.5 * sin(time * {F(u(46, 0.8, 1.5))} + baseUV.x * 12.566);",
            "float mid = rays * drape * (0.7 + 0.3 * shimmer);",
        ], [])
    if family == "SPARKLE":
        return (["sparkle", "voronoise"], [
            "float s1 = sparkle(wuv, time);",
            f"float s2 = sparkle(wuv * 2.17 + 31.7, time * {F(u(40, 1.1, 1.6))});",
            f"float haze = voronoise(wuv * {F(u(41, 0.5, 0.9))} + vec2(time * 0.05, 0.0), {F(u(42, 0.6, 1.0))}, {F(u(43, 0.4, 0.9))});",
            f"float mid = clamp(s1 + 0.6 * s2 + {F(u(44, 0.20, 0.40))} * haze, 0.0, 1.2);",
        ], [])
    if family == "RINGS":
        return (["fbm2", "ringPulse"], [
            f"float band = 0.5 + 0.5 * sin(baseUV.y * {F(u(40, 18.0, 34.0))} + time * {F(u(41, 0.7, 1.5))} + fbm2(wuv * 0.8) * {F(u(42, 1.2, 2.6))});",
            "float rp = ringPulse(baseUV, time);",
            f"float mid = pow(clamp(band, 0.0, 1.0), {F(u(43, 1.6, 3.0))}) * 0.8 + rp * {F(u(44, 0.5, 0.9))};",
        ], [])
    if family == "VORONOI":
        return (["voro2"], [
            f"vec3 v = voro2(wuv, time * {F(u(40, 0.4, 0.9))});",
            f"float border = smoothstep({F(u(41, 0.06, 0.12))}, 0.005, v.x);",
            f"float cellGlow = 0.5 + 0.5 * sin(time * {F(u(42, 0.8, 1.6))} + v.z * 6.2831853);",
            f"float mid = border * (0.7 + 0.3 * cellGlow) + {F(u(43, 0.20, 0.40))} * smoothstep(0.9, 0.2, v.y) * cellGlow;",
        ], [])
    if family == "ARCS":
        return (["fbm2"], [
            f"float ridge = 1.0 - abs(2.0 * fbm2(wuv + vec2(0.0, time * {F(u(40, 0.25, 0.55))})) - 1.0);",
            f"float bolt = pow(clamp(ridge, 0.0, 1.0), {F(u(41, 7.0, 13.0))});",
            f"float flash = 0.6 + 0.4 * sin(time * {F(u(42, 2.0, 4.5))} + fbm2(wuv * 0.37) * 9.0);",
            "float mid = bolt * flash;",
        ], [])
    if family == "SCALES":
        return (["hash21"], [
            "vec2 g = wuv;",
            "g.x += 0.5 * step(1.0, mod(floor(g.y), 2.0));",
            "vec2 cf = fract(g);",
            "float d = length(cf - vec2(0.5, 1.1));",
            f"float lip = smoothstep({F(u(40, 0.05, 0.10))}, {F(u(41, 0.015, 0.035))}, abs(d - 0.62));",
            "float shade = smoothstep(1.15, 0.35, d);",
            f"float glint = 0.5 + 0.5 * sin(time * {F(u(42, 0.9, 1.9))} + hash21(floor(g)) * 6.2831853);",
            f"float mid = lip * (0.6 + 0.4 * glint) + {F(u(43, 0.18, 0.36))} * shade;",
        ], [])
    if family == "STARFIELD":
        return (["hash21"], [
            "float stars = 0.0;",
            "for (int i = 0; i < 3; i++) {",
            "    float fi = float(i);",
            f"    vec2 su = wuv * (1.0 + fi * 0.8) + vec2(fi * 17.3, fi * 9.1) + vec2(time * ({F(u(40, 0.008, 0.02))} + fi * 0.008), 0.0);",
            "    vec2 sc = floor(su);",
            "    vec2 lf = fract(su) - 0.5;",
            "    float h = hash21(sc + vec2(fi * 47.0, 0.0));",
            "    float tw = 0.5 + 0.5 * sin(time * (1.0 + 3.0 * h) + h * 40.0);",
            f"    stars += step({F(u(41, 0.80, 0.90))}, h) * smoothstep(0.18, 0.02, length(lf - (vec2(hash21(sc + 3.1), hash21(sc + 7.7)) - 0.5) * 0.6)) * tw;",
            "}",
            "float mid = clamp(stars, 0.0, 1.3);",
        ], [])
    if family == "VORTEX":
        return (["fbm2", "spiralWarp"], [
            "float latDist = 1.0 - abs(baseUV.y * 2.0 - 1.0);",
            f"float twist = baseUV.x * 6.2831853 + latDist * {F(u(40, 5.0, 9.0))} - time * {F(u(41, 0.6, 1.2))};",
            f"float band = smoothstep(0.15, 0.9, sin(twist * {F(u(42, 2.0, 4.0))}));",
            f"float arms = smoothstep(0.45, 0.95, sin(baseUV.x * 6.2831853 - latDist * {F(u(43, 3.5, 6.0))} + time * {F(u(44, 0.4, 0.9))}));",
            "float eye = clamp(pow(clamp(1.0 - latDist, 0.0, 1.0), 3.0) * (0.6 + 0.4 * sin(time * 1.7)), 0.0, 1.0);",
            f"vec2 sv = spiralWarp(baseUV, time * {F(u(45, 0.06, 0.14))});",
            f"float mid = clamp(band * 0.8 + arms * 0.5 + eye + fbm2(sv * {F(u(46, 3.0, 6.0))}) * 0.18, 0.0, 1.3);",
        ], [])
    if family == "INTERFERENCE":
        return (["fbm2"], [
            f"float r1 = distance(wuv, vec2({F(u(40, 0.5, 2.5))}, {F(u(41, 0.5, 2.5))})) + fbm2(wuv * 0.6) * {F(u(42, 0.05, 0.16))};",
            f"float r2 = distance(wuv, vec2({F(u(43, 2.5, 4.5))}, {F(u(44, 2.5, 4.5))}));",
            f"float inter = 0.5 + 0.5 * sin(r1 * {F(u(45, 8.0, 15.0))} - time * {F(u(46, 0.8, 1.7))}) * sin(r2 * {F(u(47, 7.0, 13.0))} + time * {F(u(48, 0.6, 1.3))});",
            f"float mid = pow(clamp(inter, 0.0, 1.0), {F(u(49, 1.6, 3.0))});",
        ], [])
    if family == "KALEIDO":
        return (["polarFold", "fbm2"], [
            f"vec2 kv = polarFold(fract(wuv * {F(u(40, 0.10, 0.22))}), time * {F(u(41, 0.05, 0.14))}) * {F(u(42, 5.0, 9.0))};",
            f"float kp = fbm2(kv + vec2(time * 0.07, 0.0));",
            f"float mandala = pow(clamp(0.5 + 0.5 * sin(kp * 9.42 + time * {F(u(43, 0.5, 1.1))}), 0.0, 1.0), {F(u(44, 1.8, 3.2))});",
            f"float spokes = 0.5 + 0.5 * sin(kv.x * {F(u(45, 4.0, 8.0))} - time * {F(u(46, 0.8, 1.5))});",
            "float mid = clamp(mandala * 0.75 + spokes * 0.4, 0.0, 1.2);",
        ], [])
    if family == "CIRCUIT":
        return (["hash21"], [
            "vec2 cellId = floor(wuv);",
            "vec2 cf = fract(wuv) - 0.5;",
            "float h = hash21(cellId);",
            f"float lineH = smoothstep({F(u(40, 0.05, 0.09))}, {F(u(41, 0.015, 0.03))}, abs(cf.y)) * step(h, 0.55);",
            f"float lineV = smoothstep({F(u(40, 0.05, 0.09))}, {F(u(41, 0.015, 0.03))}, abs(cf.x)) * step(0.45, h);",
            "float node = smoothstep(0.16, 0.05, length(cf)) * step(0.8, hash21(cellId + 13.1));",
            f"float traffic = 0.5 + 0.5 * sin(time * {F(u(42, 1.5, 3.0))} + h * 6.2831853 + (cf.x + cf.y) * 3.0);",
            "float mid = clamp((lineH + lineV) * (0.5 + 0.5 * traffic) + node, 0.0, 1.2);",
        ], [])
    if family == "PETALS":
        return (["fbm2"], [
            "vec2 pc = baseUV - vec2(0.5, 0.5);",
            f"float ang = atan(pc.y, pc.x) + time * {F(u(40, 0.15, 0.40))};",
            "float rad = length(pc) * 2.0;",
            f"float petal = pow(abs(cos(ang * {F(u(41, 2.5, 4.5))})), {F(u(42, 0.6, 1.4))});",
            "float bloom = smoothstep(petal * 0.9 + 0.08, petal * 0.9 - 0.12, rad);",
            f"float veins = 0.5 + 0.5 * sin(rad * {F(u(43, 10.0, 20.0))} - time * {F(u(44, 0.6, 1.3))});",
            f"float mid = clamp(bloom * (0.6 + 0.4 * veins) + fbm2(wuv) * 0.15, 0.0, 1.2);",
        ], [])
    if family == "LIGHTNING":
        return (["fbm2", "hash11"], [
            f"float n = fbm2(wuv + vec2(0.0, time * {F(u(40, 0.35, 0.7))}));",
            f"float bolt = pow(clamp(1.0 - abs(2.0 * n - 1.0) * {F(u(41, 1.1, 1.5))}, 0.0, 1.0), {F(u(42, 9.0, 15.0))});",
            f"float gate = step({F(u(43, 0.35, 0.55))}, hash11(floor(time * {F(u(44, 3.0, 6.0))})));",
            "float strobe = 0.35 + 0.65 * gate * (0.5 + 0.5 * sin(time * 40.0));",
            f"float mid = bolt * strobe + {F(u(45, 0.15, 0.30))} * bolt;",
        ], [])
    if family == "THINFILM":
        mtw = F(u(46, 0.25, 0.40))
        return (["fbm2", "thinFilm"], [
            f"float thick = fbm2(wuv * 0.9 + vec2(time * 0.05, 0.0)) * {F(u(40, 1.5, 3.0))} + baseUV.y * {F(u(41, 0.8, 2.0))} + {F(u(42, 0.2, 0.9))};",
            "vec3 filmTint = thinFilm(thick);",
            f"float mid = clamp(dot(filmTint, vec3(0.3333)) * {F(u(43, 0.9, 1.3))}, 0.0, 1.2);",
        ], [
            f"rgb = mix(rgb, rgb * (0.55 + 0.9 * filmTint), {mtw});",
        ])
    if family == "CAUSTIC":
        return (["caustic"], [
            "float c1 = caustic(wuv, time);",
            f"float c2 = caustic(wuv * 1.7 + vec2(13.1, 4.7), time * {F(u(40, 1.05, 1.35))});",
            f"float mid = clamp(c1 * {F(u(41, 0.6, 0.9))} + c2 * {F(u(42, 0.3, 0.55))}, 0.0, 1.4);",
        ], [])
    if family == "CURLSMOKE":
        return (["curl2", "fbm2"], [
            f"vec2 adv = wuv + {F(u(40, 0.10, 0.24))} * curl2(wuv * {F(u(41, 0.6, 1.3))} + vec2(0.0, time * 0.06));",
            f"float smoke = fbm2(adv + vec2(time * {F(u(42, 0.03, 0.07))}, -time * {F(u(43, 0.06, 0.12))}));",
            f"float wisp = pow(clamp(smoke * 1.5 - 0.25, 0.0, 1.0), {F(u(44, 1.4, 2.6))});",
            "float mid = wisp;",
        ], [])
    if family == "TRUCHET":
        return (["truchet"], [
            f"float t1 = truchet(wuv, {F(u(40, 0.07, 0.13))});",
            f"float t2 = truchet(wuv * 2.0 + vec2(7.3, 3.1), {F(u(41, 0.05, 0.10))}) * 0.5;",
            f"float flow = 0.5 + 0.5 * sin(time * {F(u(42, 0.9, 1.8))} + (wuv.x + wuv.y) * 1.7);",
            "float mid = clamp(t1 * (0.6 + 0.4 * flow) + t2, 0.0, 1.2);",
        ], [])
    if family == "RIDGED":
        return (["fbm2"], [
            f"float rn = fbm2(wuv + vec2(time * {F(u(40, 0.05, 0.12))}, time * {F(u(41, 0.03, 0.08))}));",
            f"float crest = pow(clamp(1.0 - abs(2.0 * rn - 1.0), 0.0, 1.0), {F(u(42, 2.2, 4.5))});",
            f"float strata = 0.5 + 0.5 * sin(crest * {F(u(43, 6.0, 12.0))} + time * 0.8);",
            "float mid = clamp(crest * (0.7 + 0.3 * strata), 0.0, 1.2);",
        ], [])
    if family == "MOIRE":
        a1 = u(40, 0.0, 3.1416)
        a2 = a1 + u(41, 0.15, 0.6)
        return ([], [
            f"float g1 = sin(dot(wuv, vec2({F(math.cos(a1))}, {F(math.sin(a1))})) * {F(u(42, 9.0, 16.0))} + time * {F(u(43, 0.5, 1.1))});",
            f"float g2 = sin(dot(wuv, vec2({F(math.cos(a2))}, {F(math.sin(a2))})) * {F(u(44, 8.0, 15.0))} - time * {F(u(45, 0.4, 0.9))});",
            "float beat = g1 * g2;",
            f"float mid = pow(clamp(0.5 + 0.5 * beat, 0.0, 1.0), {F(u(46, 1.6, 3.0))});",
        ], [])
    if family == "TRIWEAVE":
        return (["triGrid"], [
            "vec3 tg = triGrid(wuv);",
            f"float triEdge = smoothstep({F(u(40, 0.05, 0.10))}, {F(u(41, 0.012, 0.03))}, tg.x);",
            f"float fill = 0.5 + 0.5 * sin(time * {F(u(42, 0.8, 1.7))} + tg.y * 6.2831853 + tg.z * 3.1416);",
            f"float mid = clamp(triEdge * (0.6 + 0.4 * fill) + {F(u(43, 0.15, 0.32))} * fill * smoothstep(0.05, 0.25, tg.x), 0.0, 1.2);",
        ], [])
    if family == "NEBULA":
        return (["fbm2", "hash21"], [
            f"float neb = fbm2(wuv + {F(u(40, 0.5, 1.1))} * vec2(fbm2(wuv * 0.7 + vec2(time * 0.03, 0.0)), fbm2(wuv * 0.7 - vec2(0.0, time * 0.025))));",
            f"float lanes = 1.0 - pow(clamp(1.0 - abs(2.0 * fbm2(wuv * 1.6 + vec2(4.2, 1.1)) - 1.0), 0.0, 1.0), 3.0) * {F(u(41, 0.35, 0.6))};",
            f"float cloud = pow(clamp(neb * 1.4 - 0.2, 0.0, 1.0), {F(u(42, 1.3, 2.2))}) * lanes;",
            f"vec2 scell = floor(wuv * {F(u(43, 5.0, 9.0))});",
            f"float star = step(0.92, hash21(scell)) * (0.5 + 0.5 * sin(time * 3.0 + hash21(scell + 5.0) * 20.0));",
            "float mid = clamp(cloud + star * 0.8, 0.0, 1.3);",
        ], [])
    raise KeyError(family)


# Family-tuned MID pattern scale ranges (lattice families need larger scales).
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
    """Emits one standalone .fsh for the given assignment row."""
    effect_id = asg["id"]
    family = asg["family"]
    rng = Rng(asg["seed"])
    draws = [rng.unit() for _ in range(96)]

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
    c["causticFreq"] = F(u(8, 1.4, 2.4))
    if asg["primary"] is not None:
        prim = asg["primary"]
        base_d = ((prim >> 16 & 0xFF) / 255.0, (prim >> 8 & 0xFF) / 255.0, (prim & 0xFF) / 255.0)
    else:
        base_d = (u(9, 0.0, 1.0), u(10, 0.0, 1.0), u(11, 0.0, 1.0))
    pal_c = (u(12, 0.4, 1.3), u(13, 0.4, 1.3), u(14, 0.4, 1.3))
    pal_d = tuple((0.5 * bd + 0.5 * u(15 + k, 0.0, 1.0)) % 1.0 for k, bd in enumerate(base_d))
    c["palC"] = pal_c
    c["palD"] = pal_d
    c["rg0"] = F(u(18, 0.04, 0.10))
    c["rg1"] = F(u(19, 0.45, 0.85))
    c["rl0"] = F(u(20, 0.55, 0.75))
    c["rlEq"] = F(u(21, 0.20, 0.40))
    c["rlEqW"] = F(u(22, 0.25, 0.55))
    c["sparkTh"] = F(u(23, 0.68, 0.80))
    c["rpSeed"] = F(u(24, 1.0, 40.0))
    c["rpSpeed"] = F(u(25, 0.10, 0.24))
    c["dFreq"] = F(u(26, 0.7, 1.4))
    c["dDrift"] = F(u(27, 0.02, 0.06))
    c["dSpeed"] = F(u(28, 0.3, 0.7))
    c["dRidgePow"] = F(u(29, 1.6, 3.0))

    taps = 2 + int(u(30, 0.0, 2.999))  # 2..4 parallax depth taps
    mid_scale = u(31, *MID_SCALE_RANGES[family])
    deep_scale = u(32, 1.2, 2.6)
    flourish = FLOURISHES[int(u(33, 0.0, 3.999))]

    mid_needs, mid_lines, post_lines = mid_composer(family, c)

    needs = set(mid_needs)
    needs.add("hash21")          # micro grain
    needs.add("accentPalette")   # bounded accent in the composite
    needs.add("deepField")
    needs.update(deep_field_deps(asg["deep"]))

    # Anim mode -> `vec2 auv` (scaled, animated UV).
    anim = asg["anim"]
    anim_lines = []
    if anim == "scroll":
        anim_lines.append(
            f"vec2 auv = baseUV * {F(mid_scale)} + vec2({F(u(34, -0.5, 0.5))}, {F(u(35, -0.5, 0.5))}) * time;")
    elif anim == "rotate":
        anim_lines += [
            f"float spinAng = time * {F(u(34, 0.05, 0.20))};",
            "mat2 spin = mat2(cos(spinAng), sin(spinAng), -sin(spinAng), cos(spinAng));",
            f"vec2 auv = spin * (baseUV - 0.5) * {F(mid_scale)} + vec2({F(mid_scale * 0.5)}, {F(mid_scale * 0.5)});",
        ]
    elif anim == "pulse":
        anim_lines += [
            f"float breathe = 1.0 + {F(u(34, 0.04, 0.12))} * sin(time * {F(u(35, 0.6, 1.6))});",
            f"vec2 auv = (baseUV - 0.5) * {F(mid_scale)} * breathe + vec2({F(mid_scale * 0.5)}, {F(mid_scale * 0.5)}) + vec2({F(u(36, -0.3, 0.3))}, {F(u(37, -0.3, 0.3))}) * time;",
        ]
    else:  # flicker
        needs.add("hash11")
        anim_lines += [
            f"float jump = hash11(floor(time * {F(u(34, 2.0, 5.0))}));",
            f"float ft = time + {F(u(35, 0.15, 0.45))} * jump;",
            f"vec2 auv = baseUV * {F(mid_scale)} + vec2({F(u(36, -0.5, 0.5))}, {F(u(37, -0.5, 0.5))}) * ft;",
        ]

    # Warp mode -> `vec2 wuv`.
    warp = asg["warp"]
    if warp == "none":
        warp_lines = ["vec2 wuv = auv;"]
    elif warp == "warp1":
        needs.add("warp1")
        warp_lines = ["vec2 wuv = warp1(auv, time);"]
    elif warp == "warp2":
        needs.update(("warp1", "warp2"))
        warp_lines = ["vec2 wuv = warp2(auv, time);"]
    else:  # curl
        needs.add("curl2")
        warp_lines = [
            f"vec2 wuv = auv + {F(u(38, 0.10, 0.28))} * curl2(auv * {F(u(39, 0.5, 1.1))} + vec2(0.0, time * 0.07));"]

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
        rim_lines = [
            f"float rimGlint = sparkle(baseUV * {F(u(51, 10.0, 18.0))}, time);",
            f"float rim = rimGraze() * {rim_k} * (0.7 + 0.6 * rimGlint) + {F(u(52, 0.3, 0.6))} * rimGlint;",
        ]

    # Flourish accent micro-layer (adds signature detail, not part of the tuple).
    if flourish == "swirl":
        needs.add("fbm2")
        flourish_lines = [
            f"float flourish = {F(u(60, 0.10, 0.28))} * pow(clamp(fbm2(wuv * {F(u(61, 0.5, 1.2))} + vec2(-time * 0.11, time * 0.07)), 0.0, 1.0), 2.0);"]
    elif flourish == "glint":
        needs.add("sparkle")
        flourish_lines = [
            f"float flourish = {F(u(60, 0.15, 0.35))} * sparkle(wuv * {F(u(61, 1.2, 2.2))} + 7.7, time * 1.4);"]
    elif flourish == "echo":
        needs.add("ringPulse")
        flourish_lines = [
            f"float flourish = {F(u(60, 0.12, 0.30))} * ringPulse(baseUV + vec2(0.13, 0.31), time * 0.8);"]
    else:  # shimmer
        needs.add("caustic")
        flourish_lines = [
            f"float flourish = {F(u(60, 0.08, 0.20))} * caustic(wuv * {F(u(61, 0.4, 0.9))} + vec2(3.1, 8.7), time * 0.7);"]

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
    b0 = F(u(67, 0.35, 0.55))
    b1 = F(u(68, 0.55, 0.95))
    aw = F(u(69, 0.15, 0.45))
    a0 = F(u(70, 0.10, 0.25))
    a1 = F(u(71, 0.60, 0.85))
    gk = F(u(72, 0.04, 0.10))
    gs = F(u(73, 24.0, 64.0))
    d_lift = F(u(74, 0.25, 0.45))
    d_fall = F(u(75, 0.55, 0.95))
    d_pow = F(u(76, 1.0, 1.8))
    d_step = F(u(77, 0.03, 0.08))
    ddx = F(u(78, -0.05, 0.05))
    ddy = F(u(79, -0.05, 0.05))

    deep_marker = f"parallax_{asg['deep']}_x{taps}"
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
    lines.append("    float time = GameTime * 1200.0;")
    lines.append("    // RAW sphere UV in [0,1]: wrap before any periodic sampling.")
    lines.append("    vec2 baseUV = fract(texCoord0);")
    lines.append("")
    lines.append(f"    // [layer:deep:{deep_marker}]")
    lines.append("    // Interior volume: parallax stack of the deep field, taps sliding against")
    lines.append("    // the surface near the silhouette (rimDir from the camera-distance slope).")
    lines.append("    vec2 rimDirRaw = vec2(dFdx(sphericalVertexDistance), dFdy(sphericalVertexDistance));")
    lines.append("    vec2 rimDir = rimDirRaw / (length(rimDirRaw) + 0.0001);")
    lines.append("    float deep = 0.0;")
    lines.append("    float deepNorm = 0.0;")
    lines.append(f"    for (int i = 0; i < {taps}; i++) {{")
    lines.append("        float fi = float(i);")
    lines.append(f"        vec2 duv = baseUV * {F(deep_scale)} * (1.0 + fi * {d_lift}) + vec2({ddx}, {ddy}) * time * (1.0 + fi * 0.5)")
    lines.append(f"            + rimDir * fi * {d_step} + vec2(fi * 13.7, fi * 7.9);")
    lines.append(f"        float w = exp(-fi * {d_fall});")
    lines.append("        deep += w * deepField(duv, time);")
    lines.append("        deepNorm += w;")
    lines.append("    }")
    lines.append(f"    deep = pow(clamp(deep / deepNorm, 0.0, 1.0), {d_pow});")
    lines.append("")
    lines.append(f"    // [layer:mid:{mid_marker}]")
    lines.append("    // Signature structure of this effect, domain-warped and animated.")
    for ln in anim_lines:
        lines.append("    " + ln)
    for ln in warp_lines:
        lines.append("    " + ln)
    for ln in mid_lines:
        lines.append("    " + ln)
    lines.append("")
    lines.append(f"    // [layer:rim:{rim}]")
    lines.append("    // Silhouette / band lift so the membrane reads as a curved shell.")
    for ln in rim_lines:
        lines.append("    " + ln)
    lines.append("")
    lines.append("    // Flourish accent + micro grain keep large areas alive up close.")
    for ln in flourish_lines:
        lines.append("    " + ln)
    lines.append(f"    float grain = {gk} * (hash21(floor(wuv * {gs}) + vec2(floor(time * 6.0), 0.0)) - 0.5);")
    lines.append("")
    lines.append("    // Recolor-safe composite: vertexColor.rgb stays the dominant chroma and")
    lines.append("    // alpha = vertexColor.a * pattern (dissolve near whitelisted players works).")
    lines.append(f"    float pattern = clamp({dw} * deep + {mw} * mid + {rw} * rim + flourish + grain, 0.0, 1.5);")
    lines.append(f"    vec3 accent = accentPalette({ap0} + pattern * {ap1});")
    lines.append(f"    vec3 rgb = vertexColor.rgb * ({b0} + {b1} * pattern);")
    lines.append(f"    rgb = mix(rgb, rgb * (0.55 + 0.9 * accent), {aw});")
    for ln in post_lines + rim_post:
        lines.append("    " + ln)
    lines.append(f"    float alpha = vertexColor.a * clamp({a0} + {a1} * pattern, 0.0, 1.0);")
    lines.append("    vec4 color = vec4(rgb, alpha);")
    lines.append("    if (color.a < 0.01) {")
    lines.append("        discard;")
    lines.append("    }")
    lines.append("    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, "
                 "FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);")
    lines.append("}")

    source = "\n".join(lines) + "\n"
    n = source.count("\n")
    if not 110 <= n <= 280:
        sys.exit(f"fx_{effect_id:03d}: emitted {n} lines, outside the 110..280 sanity bounds")
    return source


def manifest_entry(asg: dict, source: str) -> dict:
    rng = Rng(asg["seed"])
    draws = [rng.unit() for _ in range(96)]

    def u(i, lo, hi):
        return lo + (hi - lo) * draws[i]

    return {
        "file": f"fx_{asg['id']:03d}.fsh",
        "family": asg["family"],
        "warp": asg["warp"],
        "deep": asg["deep"],
        "rim": asg["rim"],
        "anim": asg["anim"],
        "taps": 2 + int(u(30, 0.0, 2.999)),
        "fbmMode": FBM_MODES[int(u(1, 0.0, 2.999))],
        "octaves": 3 + int(u(0, 0.0, 3.999)),
        "flourish": FLOURISHES[int(u(33, 0.0, 3.999))],
        "seed": f"{asg['seed']:016x}",
        "lines": source.count("\n"),
    }


def parse_only(spec: str) -> list:
    ids = set()
    for part in spec.split(","):
        part = part.strip()
        if "-" in part:
            lo, hi = part.split("-", 1)
            ids.update(range(int(lo), int(hi) + 1))
        else:
            ids.add(int(part))
    bad = [i for i in ids if not 0 <= i < COUNT]
    if bad:
        sys.exit(f"--only ids out of range 0..{COUNT - 1}: {sorted(bad)}")
    return sorted(ids)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--only", help="comma/range list of effect ids to emit (e.g. '0-15,42'); "
                                       "default: all 350. Emitted bytes are identical to a full run.")
    parser.add_argument("--out", help="output directory override (default: the repo bubble shader dir); "
                                      "the manifest is then written next to the shaders instead of tools/.")
    args = parser.parse_args()

    ids = parse_only(args.only) if args.only else list(range(COUNT))
    out_dir = Path(args.out) if args.out else BUBBLE_DIR
    manifest_path = (out_dir / "surface_manifest.json") if args.out else DEFAULT_MANIFEST
    out_dir.mkdir(parents=True, exist_ok=True)

    assignments = build_assignments()  # always the full table: bytes never depend on --only
    manifest = {}
    for asg in assignments:
        if asg["id"] not in ids:
            continue
        source = emit_shader(asg)
        (out_dir / f"fx_{asg['id']:03d}.fsh").write_text(source)
        manifest[str(asg["id"])] = manifest_entry(asg, source)

    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    line_counts = [e["lines"] for e in manifest.values()]
    print(f"wrote {len(manifest)} shaders to {out_dir}")
    print(f"manifest: {manifest_path}")
    print(f"line counts: min {min(line_counts)}, max {max(line_counts)}")
    if args.only:
        print(f"NOTE: partial run ({args.only}); rerun without --only for the full 350-file set.")


if __name__ == "__main__":
    main()

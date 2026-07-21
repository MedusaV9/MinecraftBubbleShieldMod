#!/usr/bin/env python3
"""Deterministic generator for the shared bubble SURFACE texture atlas.

Running `python3 tools/gen_textures.py` (re)writes
`src/main/resources/assets/bubbleshield/textures/effect/surface_atlas.png`
(+ its `.png.mcmeta`). The atlas is an 8x4 grid of 32 SEAMLESS 512x512 tiles
(4096x2048 total, RGBA). Every tile is tileable (wraps with no seam) so the
bubble surface shaders can scroll/repeat it freely.

Channel packing per texel (so one `texture()` fetch gives the shader multi-scale
depth + a glow mask):
  * R = coarse structural layer of the tile's technique
  * G = mid-scale detail layer
  * B = fine grain layer
  * A = EMISSION mask (where this technique should glow / leuchten)

The RGB layers are neutral GRAYSCALE data (never baked hues) so the surface
shaders stay recolor-safe: they tint the sampled structure with the per-effect
palette, and add `A * brightColor` as the emissive term. Tile index -> technique
mapping is documented in TILES below and mirrored by the surface shader
generator (tools/gen_surface_shaders.py).

Seamlessness: all noise is built in the Fourier domain (FFT of white noise,
radially band-limited, inverse-FFT) which is periodic by construction, all
cellular/lattice fields use wrapped (toroidal) distances, and every polar /
medallion construction is fully contained inside cell-aligned sub-cells, so
every tile tiles perfectly. Output is byte-stable (fixed seeds, fixed
quantization).

PNG size control: all channels are POSTERIZED (R to 128 levels, G to 64,
B to 32, A to 24) before packing -- zlib then compresses the doubled tile
count back into the same ballpark as the old 16-tile atlas. The steps stay
invisible after shader tinting (multi-layer blends dither them out).
"""

from __future__ import annotations

import struct
import zlib
from pathlib import Path

import numpy as np

REPO_ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = REPO_ROOT / "src/main/resources/assets/bubbleshield/textures/effect"
ATLAS_PATH = OUT_DIR / "surface_atlas.png"
MCMETA_PATH = OUT_DIR / "surface_atlas.png.mcmeta"

TILE = 512
GRID_W = 8  # columns
GRID_H = 4  # rows -> 8x4 = 32 tiles
ATLAS_W = TILE * GRID_W
ATLAS_H = TILE * GRID_H

# Tile index -> technique name (mirrored in gen_surface_shaders.py ATLAS_TILES).
# Indices 0..15 keep the original 16 techniques (byte-compatible content, new
# atlas POSITIONS); 16..31 are the expansion set, each a genuinely different
# construction so no two SurfaceTemplate families need to share a look.
TILES = [
    "fbm_turbulence",     # 0
    "worley_cells",       # 1
    "cracked_glass",      # 2
    "hex_lattice",        # 3
    "caustic_web",        # 4
    "filaments",          # 5
    "scales",             # 6
    "circuit",            # 7
    "starfield",          # 8
    "chrome",             # 9
    "marble_ink",         # 10
    "honeycomb",          # 11
    "runes",              # 12
    "ridged",             # 13
    "foam",               # 14
    "nebula",             # 15
    "lightning_web",      # 16 jagged branching bolt web, hot cores
    "plasma_globules",    # 17 big soft blobs with glowing nuclei
    "feather_barbs",      # 18 diagonal barb strokes off vertical shafts
    "coral",              # 19 reaction-diffusion labyrinth lobes
    "basalt_columns",     # 20 large flat polygonal columns, dark seams
    "knit_weave",         # 21 over-under woven yarn rows
    "damascus_folds",     # 22 folded-metal flowing bands
    "mandala",            # 23 radial petal medallions (2x2 per tile)
    "glyph_ring",         # 24 annular tick-glyph rings (4x4 per tile)
    "solar_granulation",  # 25 convection granules + bright faculae specks
    "ice_dendrites",      # 26 thin sharp branching frost spines
    "smoke_wisps",        # 27 soft curling wisp strands
    "riveted_plates",     # 28 brick-offset metal plates + rivet dots
    "iris_eye",           # 29 radial-fiber iris rings (2x2 per tile)
    "topo_contours",      # 30 nested thin contour lines of a height field
    "dune_ripples",       # 31 long parallel wavy sand crests
]
assert len(TILES) == GRID_W * GRID_H


def _rng(seed: int) -> np.random.Generator:
    return np.random.default_rng(seed)


def _norm(a: np.ndarray) -> np.ndarray:
    lo = float(a.min())
    hi = float(a.max())
    if hi - lo < 1e-9:
        return np.zeros_like(a)
    return (a - lo) / (hi - lo)


def fft_noise(size: int, freq: float, seed: int) -> np.ndarray:
    """Periodic band-limited value noise via FFT-filtered white noise.

    `freq` ~ number of features across the tile. Result in [0, 1], seamless.
    """
    rng = _rng(seed)
    white = rng.standard_normal((size, size))
    f = np.fft.fft2(white)
    fy = np.fft.fftfreq(size) * size
    fx = np.fft.fftfreq(size) * size
    ry = fy[:, None]
    rx = fx[None, :]
    radius = np.sqrt(rx * rx + ry * ry)
    # Gaussian band-pass centred on `freq` so features are ~size/freq px wide.
    sigma = max(freq * 0.5, 1.0)
    band = np.exp(-((radius - freq) ** 2) / (2.0 * sigma * sigma))
    band[0, 0] = 0.0
    filtered = np.fft.ifft2(f * band).real
    return _norm(filtered)


def fft_noise_aniso(size: int, fx_count: float, fy_count: float, seed: int) -> np.ndarray:
    """Anisotropic periodic noise: an elliptical Fourier band with ~fx_count
    features across x and ~fy_count across y. Seamless by construction."""
    rng = _rng(seed)
    white = rng.standard_normal((size, size))
    f = np.fft.fft2(white)
    fy = np.fft.fftfreq(size) * size
    fx = np.fft.fftfreq(size) * size
    ry = fy[:, None] / max(fy_count, 1e-6)
    rx = fx[None, :] / max(fx_count, 1e-6)
    radius = np.sqrt(rx * rx + ry * ry)
    band = np.exp(-((radius - 1.0) ** 2) / (2.0 * 0.35 * 0.35))
    band[0, 0] = 0.0
    return _norm(np.fft.ifft2(f * band).real)


def fbm(size: int, base_freq: float, octaves: int, seed: int, ridged: bool = False) -> np.ndarray:
    total = np.zeros((size, size))
    amp = 1.0
    amp_sum = 0.0
    freq = base_freq
    for o in range(octaves):
        n = fft_noise(size, freq, seed + o * 101)
        if ridged:
            n = 1.0 - np.abs(2.0 * n - 1.0)
        total += amp * n
        amp_sum += amp
        amp *= 0.5
        freq *= 2.0
    return _norm(total / max(amp_sum, 1e-9))


def _wrapped_points(size: int, count: int, seed: int):
    rng = _rng(seed)
    pts = rng.random((count, 2)) * size
    return pts


def worley(size: int, count: int, seed: int, which: str = "f1"):
    """Toroidal (wrapping) Worley/cellular field. which in {f1, f2, f2mf1, id}."""
    pts = _wrapped_points(size, count, seed)
    ys, xs = np.mgrid[0:size, 0:size]
    f1 = np.full((size, size), 1e9)
    f2 = np.full((size, size), 1e9)
    idx = np.zeros((size, size), dtype=np.int32)
    for i, (py, px) in enumerate(pts):
        dy = np.abs(ys - py)
        dx = np.abs(xs - px)
        dy = np.minimum(dy, size - dy)
        dx = np.minimum(dx, size - dx)
        d = np.sqrt(dx * dx + dy * dy)
        closer = d < f1
        f2 = np.where(closer, f1, np.minimum(f2, d))
        idx = np.where(closer, i, idx)
        f1 = np.where(closer, d, f1)
    if which == "f1":
        return _norm(f1)
    if which == "f2":
        return _norm(f2)
    if which == "f2mf1":
        return _norm(f2 - f1)
    if which == "id":
        rng = _rng(seed + 7)
        vals = rng.random(count)
        return vals[idx]
    return _norm(f1)


def _posterize(a: np.ndarray, levels: int) -> np.ndarray:
    """Quantizes a [0,1] field to `levels` values -- big zlib wins on the fine
    grain and emission channels with no visible banding after shader tinting."""
    return np.round(np.clip(a, 0.0, 1.0) * (levels - 1)) / (levels - 1)


def _pack(coarse, mid, fine, emis) -> np.ndarray:
    """Stack 4 [0,1] fields into an HxWx4 uint8 tile (G/B/A posterized)."""
    out = np.zeros((TILE, TILE, 4), dtype=np.float64)
    out[..., 0] = _posterize(coarse, 128)
    out[..., 1] = _posterize(mid, 64)
    out[..., 2] = _posterize(fine, 32)
    out[..., 3] = _posterize(emis, 24)
    return np.clip(out * 255.0 + 0.5, 0, 255).astype(np.uint8)


def _thin(edge: np.ndarray, width: float) -> np.ndarray:
    """Emission mask emphasising thin bright lines from an edge-distance field."""
    return np.clip(1.0 - edge / max(width, 1e-6), 0.0, 1.0)


def build_tile(name: str, seed: int) -> np.ndarray:
    s = TILE
    if name == "fbm_turbulence":
        coarse = fbm(s, 3, 5, seed)
        mid = fbm(s, 6, 5, seed + 1)
        fine = fbm(s, 14, 4, seed + 2)
        emis = np.clip((coarse - 0.6) * 2.5, 0, 1) ** 1.5
    elif name == "worley_cells":
        f1 = worley(s, 40, seed, "f1")
        edge = worley(s, 40, seed, "f2mf1")
        coarse = 1.0 - f1
        mid = worley(s, 90, seed + 3, "f1")
        fine = fbm(s, 18, 3, seed + 4)
        emis = _thin(edge, 0.10)
    elif name == "cracked_glass":
        edge = worley(s, 60, seed, "f2mf1")
        cracks = _thin(edge, 0.06)
        coarse = 1.0 - worley(s, 24, seed + 5, "f1")
        mid = cracks
        fine = fbm(s, 24, 3, seed + 6) * 0.5
        emis = cracks ** 0.7
    elif name == "hex_lattice":
        coarse, edge = _hex(s, 10)
        mid, _ = _hex(s, 20)
        fine = fbm(s, 20, 3, seed + 7) * 0.4
        emis = _thin(edge, 0.08)
    elif name == "caustic_web":
        c = _caustic(s, seed)
        coarse = c
        mid = _caustic(s, seed + 11) * 0.8
        fine = fbm(s, 22, 3, seed + 8) * 0.3
        emis = np.clip((c - 0.7) * 3.0, 0, 1)
    elif name == "filaments":
        r = fbm(s, 4, 6, seed, ridged=True)
        fil = np.clip((r - 0.72) * 4.0, 0, 1)
        coarse = r
        mid = fbm(s, 9, 5, seed + 9, ridged=True)
        fine = fbm(s, 20, 3, seed + 10)
        emis = fil ** 0.8
    elif name == "scales":
        coarse, edge = _scales(s, 14)
        mid, _ = _scales(s, 26)
        fine = fbm(s, 24, 2, seed + 12) * 0.3
        emis = _thin(edge, 0.09) * 0.8
    elif name == "circuit":
        traces, pads = _circuit(s, seed)
        coarse = traces * 0.6 + pads
        mid = traces
        fine = fbm(s, 26, 2, seed + 13) * 0.2
        emis = np.clip(traces * 0.7 + pads, 0, 1)
    elif name == "starfield":
        stars, glow = _starfield(s, seed)
        coarse = glow
        mid = fbm(s, 8, 4, seed + 14) * 0.4
        fine = stars
        emis = stars
    elif name == "chrome":
        base = fbm(s, 3, 4, seed)
        bands = np.abs(np.sin(base * np.pi * 4.0))
        coarse = base
        mid = bands
        fine = fbm(s, 16, 3, seed + 15) * 0.4
        emis = np.clip((bands - 0.75) * 4.0, 0, 1)
    elif name == "marble_ink":
        warp = fbm(s, 3, 4, seed + 16)
        base = fbm(s, 4, 5, seed)
        marbled = _norm(np.abs(np.sin((base + warp) * np.pi * 3.0)))
        coarse = marbled
        mid = fbm(s, 8, 4, seed + 17)
        fine = fbm(s, 20, 3, seed + 18) * 0.4
        emis = np.clip((marbled - 0.8) * 3.0, 0, 1) * 0.6
    elif name == "honeycomb":
        cells, edge = _hex(s, 8)
        coarse = cells
        mid, _ = _hex(s, 16)
        fine = fbm(s, 22, 2, seed + 19) * 0.25
        emis = (1.0 - _thin(edge, 0.18)) * 0.5
    elif name == "runes":
        r = _runes(s, seed)
        coarse = fbm(s, 6, 3, seed + 20) * 0.3
        mid = r
        fine = fbm(s, 24, 2, seed + 21) * 0.2
        emis = r ** 0.8
    elif name == "ridged":
        r = fbm(s, 3, 6, seed, ridged=True)
        coarse = r
        mid = fbm(s, 7, 5, seed + 22, ridged=True)
        fine = fbm(s, 18, 3, seed + 23)
        emis = np.clip((r - 0.8) * 4.0, 0, 1)
    elif name == "foam":
        f = worley(s, 55, seed, "f2mf1")
        coarse = 1.0 - worley(s, 30, seed + 24, "f1")
        mid = f
        fine = fbm(s, 20, 3, seed + 25) * 0.3
        emis = _thin(f, 0.12)
    elif name == "nebula":
        a = fbm(s, 3, 6, seed)
        b = fbm(s, 5, 5, seed + 26)
        coarse = a
        mid = b
        fine = fbm(s, 16, 4, seed + 27)
        emis = np.clip((a * b - 0.35) * 3.0, 0, 1) ** 1.2
    # --- expansion tiles (16..31) ---
    elif name == "lightning_web":
        r = fbm(s, 7, 4, seed, ridged=True)
        jag = fbm(s, 42, 2, seed + 30)
        web = np.clip(((r + 0.18 * (jag - 0.5)) - 0.78) * 9.0, 0, 1)
        r2 = fbm(s, 14, 3, seed + 31, ridged=True)
        web2 = np.clip((r2 - 0.80) * 8.0, 0, 1)
        coarse = _norm(r * 0.75 + 0.25 * fbm(s, 3, 3, seed + 32))
        mid = np.clip(web + web2 * 0.6, 0, 1)
        fine = web2
        emis = np.clip(web ** 0.7 + 0.5 * web2, 0, 1)
    elif name == "plasma_globules":
        f1 = worley(s, 14, seed, "f1")
        blobs = (1.0 - f1) ** 2.2
        small = (1.0 - worley(s, 34, seed + 33, "f1")) ** 2.6
        cores = _blob_cores(s, 14, 16.0, seed)
        coarse = _norm(blobs)
        mid = _norm(small)
        fine = fbm(s, 12, 3, seed + 34) * 0.6
        emis = np.clip(cores + 0.35 * np.clip(blobs - 0.55, 0, 1) * 2.0, 0, 1)
    elif name == "feather_barbs":
        ys, xs = np.mgrid[0:s, 0:s].astype(np.float64)
        u = xs / s
        v = ys / s
        wob = fbm(s, 4, 3, seed + 35)
        shaft = np.abs(np.sin(np.pi * (u * 6 + 0.15 * (wob - 0.5)))) ** 14
        barb1 = np.abs(np.sin(2 * np.pi * (u * 10 + v * 24) + 4.0 * wob))
        barb2 = np.abs(np.sin(2 * np.pi * (u * 10 - v * 24) + 4.0 * wob + 1.7))
        barbs = _norm(np.maximum(barb1, barb2) ** 3)
        coarse = _norm(0.6 * wob + 0.4 * barbs)
        mid = barbs
        fine = fbm(s, 26, 2, seed + 36) * 0.4
        emis = np.clip(shaft + np.clip(barbs - 0.85, 0, 1) * 2.0, 0, 1) * 0.8
    elif name == "coral":
        n = fft_noise(s, 22, seed)
        lab = _smooth01((n - 0.5) / 0.06)
        lobes = fbm(s, 5, 3, seed + 37)
        edge = 4.0 * lab * (1.0 - lab)
        coarse = _norm(lobes)
        mid = lab
        fine = fbm(s, 30, 2, seed + 38) * 0.4
        emis = _norm(edge) ** 1.4 * 0.85
    elif name == "basalt_columns":
        fill = worley(s, 18, seed, "id")
        edge = worley(s, 18, seed, "f2mf1")
        seam = _thin(edge, 0.05)
        coarse = _norm(fill)
        mid = 1.0 - _thin(edge, 0.20)
        fine = fbm(s, 20, 3, seed + 39) * 0.35
        emis = seam * 0.75
    elif name == "knit_weave":
        ys, xs = np.mgrid[0:s, 0:s].astype(np.float64)
        u = xs / s
        v = ys / s
        row = 0.5 + 0.5 * np.sin(2 * np.pi * (v * 14 + 0.30 * np.sin(2 * np.pi * u * 14)))
        col = 0.5 + 0.5 * np.sin(2 * np.pi * (u * 14 + 0.30 * np.sin(2 * np.pi * v * 14)))
        checker = 0.5 + 0.5 * np.sin(2 * np.pi * u * 7) * np.sin(2 * np.pi * v * 7)
        weave = np.where(checker > 0.5, row, col)
        coarse = _norm(weave)
        mid = _norm(row * 0.5 + col * 0.5)
        fine = fbm(s, 32, 2, seed + 40) * 0.5
        emis = np.clip(weave - 0.82, 0, 1) * 4.0 * 0.7
    elif name == "damascus_folds":
        base = fbm(s, 3, 4, seed)
        ys = np.mgrid[0:s, 0:s][0].astype(np.float64) / s
        folds = np.abs(np.sin(2 * np.pi * (ys * 7 + 3.5 * base)))
        folds2 = np.abs(np.sin(2 * np.pi * (ys * 15 + 5.0 * base) + 1.3))
        coarse = _norm(folds)
        mid = _norm(folds2)
        fine = fft_noise_aniso(s, 60, 8, seed + 41) * 0.5
        emis = np.clip(folds - 0.86, 0, 1) * 6.0 * 0.8
    elif name == "mandala":
        med, rings, glow = _medallions(s, 2, seed, petals=True)
        coarse = med
        mid = rings
        fine = fbm(s, 24, 2, seed + 42) * 0.3
        emis = glow
    elif name == "glyph_ring":
        med, rings, glow = _glyph_rings(s, 4, seed)
        coarse = fbm(s, 5, 3, seed + 43) * 0.35
        mid = med
        fine = rings
        emis = glow
    elif name == "solar_granulation":
        f1 = worley(s, 380, seed, "f1")
        lanes = worley(s, 380, seed, "f2mf1")
        gran = (1.0 - f1) ** 1.6
        fac = np.clip(gran - 0.90, 0, 1) * 10.0
        coarse = _norm((1.0 - worley(s, 60, seed + 44, "f1")) ** 1.4)
        mid = _norm(gran)
        fine = _thin(lanes, 0.25)
        emis = np.clip(fac + 0.25 * np.clip(gran - 0.75, 0, 1), 0, 1)
    elif name == "ice_dendrites":
        r = fbm(s, 8, 5, seed, ridged=True)
        den = np.clip((r - 0.74) * 8.0, 0, 1)
        r2 = fbm(s, 16, 4, seed + 45, ridged=True).T  # transpose: crossing set
        den2 = np.clip((r2 - 0.78) * 8.0, 0, 1)
        coarse = _norm(fbm(s, 4, 4, seed + 46) * 0.5 + 0.3 * r)
        mid = np.clip(den + 0.7 * den2, 0, 1)
        fine = den2
        emis = np.clip(den ** 0.8 + 0.5 * den2, 0, 1) * 0.9
    elif name == "smoke_wisps":
        base = fbm(s, 3, 5, seed)
        ys = np.mgrid[0:s, 0:s][0].astype(np.float64) / s
        wisp = 0.5 + 0.5 * np.sin(2 * np.pi * (ys * 3 + 3.2 * base))
        strand = _norm(wisp * fft_noise_aniso(s, 5, 26, seed + 47))
        coarse = _norm(base)
        mid = _norm(wisp) * 0.85
        fine = strand
        emis = np.clip(strand - 0.72, 0, 1) * 2.2 * 0.5
    elif name == "riveted_plates":
        plates, bevel, rivets = _plates(s, seed)
        coarse = plates
        mid = bevel
        fine = fft_noise_aniso(s, 70, 6, seed + 48) * 0.5
        emis = np.clip(rivets + 0.3 * np.clip(bevel - 0.8, 0, 1) * 3.0, 0, 1)
    elif name == "iris_eye":
        fib, rings, glow = _medallions(s, 2, seed + 49, petals=False)
        coarse = rings
        mid = fib
        fine = fbm(s, 30, 2, seed + 50) * 0.4
        emis = glow
    elif name == "topo_contours":
        h = fbm(s, 4, 4, seed)
        c1 = 1.0 - np.clip(np.abs(np.mod(h * 12.0, 1.0) - 0.5) / 0.07, 0, 1)
        c2 = 1.0 - np.clip(np.abs(np.mod(h * 36.0, 1.0) - 0.5) / 0.10, 0, 1)
        coarse = h
        mid = _norm(c1)
        fine = _norm(c2) * 0.7
        emis = _norm(c1) ** 1.3 * 0.85
    elif name == "dune_ripples":
        base = fbm(s, 3, 3, seed)
        ys = np.mgrid[0:s, 0:s][0].astype(np.float64) / s
        d = 0.5 + 0.5 * np.sin(2 * np.pi * (ys * 12 + 2.0 * base))
        crest = d ** 3.5
        coarse = _norm(fft_noise_aniso(s, 3, 9, seed + 51))
        mid = _norm(crest)
        fine = fbm(s, 40, 2, seed + 52) * 0.5
        emis = np.clip(crest - 0.72, 0, 1) * 2.5 * 0.6
    else:
        raise ValueError(name)
    return _pack(_norm(coarse), _norm(mid), _norm(fine), np.clip(emis, 0, 1))


# --- structured helpers (all toroidal / seamless) ---

def _smooth01(x: np.ndarray) -> np.ndarray:
    """smoothstep(-1, 1, x) style soft threshold; input any range."""
    t = np.clip(0.5 + 0.5 * x, 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def _hex(size: int, cells: float):
    """Hex lattice: returns (cell-fill, edge-distance). Seamless via integer freq."""
    ys, xs = np.mgrid[0:size, 0:size].astype(np.float64)
    u = xs / size
    v = ys / size
    # three sine gratings 60deg apart -> hexagonal interference (periodic).
    k = cells * 2.0 * np.pi
    a = np.cos(k * u)
    b = np.cos(k * (u * 0.5 + v * 0.8660254))
    c = np.cos(k * (-u * 0.5 + v * 0.8660254))
    field = _norm(a + b + c)
    edge = _norm(np.abs(a) + np.abs(b) + np.abs(c))
    return field, 1.0 - edge


def _scales(size: int, cells: float):
    ys, xs = np.mgrid[0:size, 0:size].astype(np.float64)
    u = (xs / size) * cells
    v = (ys / size) * cells
    # brick-offset rows for a scale look
    row = np.floor(v)
    u = u + 0.5 * (row % 2)
    fu = u - np.floor(u) - 0.5
    fv = v - np.floor(v) - 0.5
    d = np.sqrt(fu * fu + fv * fv)
    fill = _norm(1.0 - d)
    edge = _norm(np.abs(d - 0.42))
    return fill, edge


def _caustic(size: int, seed: int):
    ys, xs = np.mgrid[0:size, 0:size].astype(np.float64)
    u = xs / size
    v = ys / size
    rng = _rng(seed)
    acc = np.zeros((size, size))
    for _ in range(5):
        kx = rng.integers(2, 7)
        ky = rng.integers(2, 7)
        ph = rng.random() * 2 * np.pi
        acc += np.sin(2 * np.pi * (kx * u + ky * v) + ph)
    return _norm(np.abs(acc))


def _circuit(size: int, seed: int):
    rng = _rng(seed)
    traces = np.zeros((size, size))
    pads = np.zeros((size, size))
    step = 32
    for gx in range(0, size, step):
        if rng.random() < 0.6:
            w = rng.integers(2, 5)
            traces[:, gx:gx + w] = 1.0
    for gy in range(0, size, step):
        if rng.random() < 0.6:
            w = rng.integers(2, 5)
            traces[gy:gy + w, :] = 1.0
    ys, xs = np.mgrid[0:size, 0:size].astype(np.float64)
    for _ in range(40):
        py = rng.integers(0, size)
        px = rng.integers(0, size)
        dy = np.abs(ys - py); dy = np.minimum(dy, size - dy)
        dx = np.abs(xs - px); dx = np.minimum(dx, size - dx)
        pads += np.clip(1.0 - np.sqrt(dx * dx + dy * dy) / 6.0, 0, 1)
    return np.clip(traces, 0, 1), np.clip(pads, 0, 1)


def _starfield(size: int, seed: int):
    rng = _rng(seed)
    stars = np.zeros((size, size))
    ys, xs = np.mgrid[0:size, 0:size].astype(np.float64)
    for _ in range(220):
        py = rng.integers(0, size)
        px = rng.integers(0, size)
        b = rng.random() ** 2
        dy = np.abs(ys - py); dy = np.minimum(dy, size - dy)
        dx = np.abs(xs - px); dx = np.minimum(dx, size - dx)
        stars += b * np.exp(-(dx * dx + dy * dy) / (2.0 * (1.2 ** 2)))
    stars = np.clip(stars, 0, 1)
    glow = fbm(size, 5, 4, seed + 40) * 0.5
    return stars, glow


def _runes(size: int, seed: int):
    rng = _rng(seed)
    field = np.zeros((size, size))
    step = 64
    for gy in range(0, size, step):
        for gx in range(0, size, step):
            # a few random axis-aligned strokes inside each cell (glyph-ish)
            for _ in range(rng.integers(2, 5)):
                if rng.random() < 0.5:
                    y = gy + rng.integers(8, step - 8)
                    x0 = gx + rng.integers(6, step // 2)
                    x1 = gx + rng.integers(step // 2, step - 6)
                    field[y:y + 3, x0:x1] = 1.0
                else:
                    x = gx + rng.integers(8, step - 8)
                    y0 = gy + rng.integers(6, step // 2)
                    y1 = gy + rng.integers(step // 2, step - 6)
                    field[y0:y1, x:x + 3] = 1.0
    return np.clip(field, 0, 1)


def _blob_cores(size: int, count: int, sigma: float, seed: int) -> np.ndarray:
    """Sparse toroidal Gaussian glow cores (bigger, softer than star points)."""
    rng = _rng(seed + 71)
    cores = np.zeros((size, size))
    ys, xs = np.mgrid[0:size, 0:size].astype(np.float64)
    for _ in range(count):
        py = rng.integers(0, size)
        px = rng.integers(0, size)
        b = 0.55 + 0.45 * rng.random()
        dy = np.abs(ys - py); dy = np.minimum(dy, size - dy)
        dx = np.abs(xs - px); dx = np.minimum(dx, size - dx)
        cores += b * np.exp(-(dx * dx + dy * dy) / (2.0 * sigma * sigma))
    return np.clip(cores, 0, 1)


def _medallions(size: int, per_axis: int, seed: int, petals: bool):
    """Cell-aligned radial medallions (seamless because each is fully contained
    in its sub-cell). petals=True: mandala petal wheels; False: iris fibers."""
    rng = _rng(seed + 81)
    cell = size // per_axis
    med = np.zeros((size, size))
    rings = np.zeros((size, size))
    glow = np.zeros((size, size))
    yy, xx = np.mgrid[0:cell, 0:cell].astype(np.float64)
    cy = cx = (cell - 1) / 2.0
    dy = (yy - cy) / (cell * 0.5)
    dx = (xx - cx) / (cell * 0.5)
    r = np.sqrt(dx * dx + dy * dy)
    theta = np.arctan2(dy, dx)
    for gy in range(per_axis):
        for gx in range(per_axis):
            k = int(rng.integers(6, 13))
            ring_n = float(rng.integers(5, 9))
            ph = rng.random() * 2 * np.pi
            envelope = np.clip(1.0 - r, 0, 1) ** 0.5
            if petals:
                pet = np.abs(np.cos(theta * k + ph)) ** 3
                ring = np.abs(np.sin(r * np.pi * ring_n)) ** 5
                m = _norm(pet * envelope)
                rg = _norm(ring * envelope)
                gl = np.clip(pet * ring * envelope * 2.0 - 0.35, 0, 1)
            else:
                fib = np.abs(np.sin(theta * k * 2 + ph + 2.5 * r)) ** 2
                band = _smooth01((r - 0.18) / 0.05) * _smooth01((0.85 - r) / 0.05)
                limbus = np.exp(-((r - 0.82) ** 2) / (2 * 0.03 ** 2))
                m = _norm(fib * band)
                rg = _norm(band * (0.4 + 0.6 * np.abs(np.sin(r * np.pi * ring_n))))
                gl = np.clip(fib * band - 0.45, 0, 1) * 1.6 + limbus * 0.5
            med[gy * cell:(gy + 1) * cell, gx * cell:(gx + 1) * cell] = m
            rings[gy * cell:(gy + 1) * cell, gx * cell:(gx + 1) * cell] = rg
            glow[gy * cell:(gy + 1) * cell, gx * cell:(gx + 1) * cell] = np.clip(gl, 0, 1)
    return med, rings, glow


def _glyph_rings(size: int, per_axis: int, seed: int):
    """Cell-aligned annular tick-glyph rings (runic clock faces)."""
    rng = _rng(seed + 91)
    cell = size // per_axis
    med = np.zeros((size, size))
    rings = np.zeros((size, size))
    glow = np.zeros((size, size))
    yy, xx = np.mgrid[0:cell, 0:cell].astype(np.float64)
    cy = cx = (cell - 1) / 2.0
    dy = (yy - cy) / (cell * 0.5)
    dx = (xx - cx) / (cell * 0.5)
    r = np.sqrt(dx * dx + dy * dy)
    theta = np.arctan2(dy, dx)
    for gy in range(per_axis):
        for gx in range(per_axis):
            n_ticks = int(rng.integers(8, 17))
            r0 = 0.55 + 0.15 * rng.random()
            ph = rng.random() * 2 * np.pi
            ring = np.exp(-((r - r0) ** 2) / (2 * 0.035 ** 2))
            ring_in = np.exp(-((r - r0 * 0.55) ** 2) / (2 * 0.025 ** 2))
            ticks = (np.abs(np.sin(theta * n_ticks * 0.5 + ph)) ** 12) \
                * _smooth01((r - (r0 - 0.14)) / 0.04) * _smooth01(((r0 + 0.14) - r) / 0.04)
            m = np.clip(ring + 0.7 * ring_in + ticks, 0, 1)
            med[gy * cell:(gy + 1) * cell, gx * cell:(gx + 1) * cell] = m
            rings[gy * cell:(gy + 1) * cell, gx * cell:(gx + 1) * cell] = _norm(ring + ring_in)
            glow[gy * cell:(gy + 1) * cell, gx * cell:(gx + 1) * cell] = np.clip(ring + ticks, 0, 1) * 0.9
    return med, rings, glow


def _plates(size: int, seed: int):
    """Brick-offset rectangular plates: (per-plate fill, bevel, rivet dots)."""
    rng = _rng(seed + 61)
    ph = size // 4   # plate height (4 rows)
    pw = size // 2   # plate width (2 cols, brick offset)
    plates = np.zeros((size, size))
    bevel = np.zeros((size, size))
    rivets = np.zeros((size, size))
    ys, xs = np.mgrid[0:size, 0:size].astype(np.float64)
    row = np.floor(ys / ph)
    off = np.mod(row, 2) * (pw // 2)
    lx = np.mod(xs + off, pw)
    ly = np.mod(ys, ph)
    # per-plate hash fill (wrapped ids)
    idx = (np.floor((xs + off) / pw).astype(np.int64) % 2) + 2 * (row.astype(np.int64) % 4)
    vals = rng.random(8)
    plates = vals[idx]
    edge = np.minimum(np.minimum(lx, pw - lx), np.minimum(ly, ph - ly))
    bevel = np.clip(edge / 10.0, 0, 1)
    # rivet dots along plate borders every 64 px
    rd = 3.2
    for c, along, across in ((lx, ly, ph), (ly, lx, pw)):
        near = np.minimum(c, (pw if c is lx else ph) - c)
        dot_phase = np.abs(np.mod(along + 32, 64) - 32)
        rivets += np.exp(-((near - 8.0) ** 2 + dot_phase ** 2) / (2 * rd * rd))
    return _norm(plates), bevel, np.clip(rivets, 0, 1)


def _write_png(path: Path, rgba: np.ndarray) -> None:
    h, w, _ = rgba.shape
    raw = bytearray()
    for y in range(h):
        raw.append(0)  # filter type 0
        raw.extend(rgba[y].tobytes())
    def chunk(tag: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)  # 8-bit RGBA
    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", ihdr)
           + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
           + chunk(b"IEND", b""))
    path.write_bytes(png)


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    atlas = np.zeros((ATLAS_H, ATLAS_W, 4), dtype=np.uint8)
    for i, name in enumerate(TILES):
        gy = (i // GRID_W) * TILE
        gx = (i % GRID_W) * TILE
        tile = build_tile(name, seed=1000 + i * 17)
        atlas[gy:gy + TILE, gx:gx + TILE] = tile
    _write_png(ATLAS_PATH, atlas)
    MCMETA_PATH.write_text('{\n  "texture": {\n    "blur": true,\n    "clamp": false\n  }\n}\n', encoding="utf-8")
    size_mb = ATLAS_PATH.stat().st_size / (1024 * 1024)
    print(f"wrote {ATLAS_PATH} ({ATLAS_W}x{ATLAS_H} RGBA, {size_mb:.1f} MB), {len(TILES)} tiles "
          f"({GRID_W}x{GRID_H} grid of {TILE}px)")
    print(f"wrote {MCMETA_PATH}")


if __name__ == "__main__":
    main()

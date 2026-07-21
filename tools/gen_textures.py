#!/usr/bin/env python3
"""Deterministic generator for the shared bubble SURFACE texture atlas.

Running `python3 tools/gen_textures.py` (re)writes
`src/main/resources/assets/bubbleshield/textures/effect/surface_atlas.png`
(+ its `.png.mcmeta`). The atlas is a 4x4 grid of 16 SEAMLESS 512x512 tiles
(2048x2048 total, RGBA). Every tile is tileable (wraps with no seam) so the
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
radially band-limited, inverse-FFT) which is periodic by construction, and all
cellular/lattice fields use wrapped (toroidal) distances, so every tile tiles
perfectly. Output is byte-stable (fixed seeds, fixed quantization).
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
GRID = 4  # 4x4 = 16 tiles
ATLAS = TILE * GRID

# Tile index -> technique name (mirrored in gen_surface_shaders.py ATLAS_TILES).
TILES = [
    "fbm_turbulence",   # 0
    "worley_cells",     # 1
    "cracked_glass",    # 2
    "hex_lattice",      # 3
    "caustic_web",      # 4
    "filaments",        # 5
    "scales",           # 6
    "circuit",          # 7
    "starfield",        # 8
    "chrome",           # 9
    "marble_ink",       # 10
    "honeycomb",        # 11
    "runes",            # 12
    "ridged",           # 13
    "foam",             # 14
    "nebula",           # 15
]


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


def _pack(coarse, mid, fine, emis) -> np.ndarray:
    """Stack 4 [0,1] fields into an HxWx4 uint8 tile."""
    out = np.zeros((TILE, TILE, 4), dtype=np.float64)
    out[..., 0] = coarse
    out[..., 1] = mid
    out[..., 2] = fine
    out[..., 3] = emis
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
    else:
        raise ValueError(name)
    return _pack(_norm(coarse), _norm(mid), _norm(fine), np.clip(emis, 0, 1))


# --- structured helpers (all toroidal / seamless) ---

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
    atlas = np.zeros((ATLAS, ATLAS, 4), dtype=np.uint8)
    for i, name in enumerate(TILES):
        gy = (i // GRID) * TILE
        gx = (i % GRID) * TILE
        tile = build_tile(name, seed=1000 + i * 17)
        atlas[gy:gy + TILE, gx:gx + TILE] = tile
    _write_png(ATLAS_PATH, atlas)
    MCMETA_PATH.write_text('{\n  "texture": {\n    "blur": true,\n    "clamp": false\n  }\n}\n', encoding="utf-8")
    size_mb = ATLAS_PATH.stat().st_size / (1024 * 1024)
    print(f"wrote {ATLAS_PATH} ({ATLAS}x{ATLAS} RGBA, {size_mb:.1f} MB), {len(TILES)} tiles")
    print(f"wrote {MCMETA_PATH}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Deterministic builder for the two bubble-INTERIOR sprite sheets.

Running `python3 tools/gen_interior_sprites.py` (re)writes

    src/main/resources/assets/bubbleshield/textures/interior/interior_pixel.png
    src/main/resources/assets/bubbleshield/textures/interior/interior_soft.png
    src/main/resources/assets/bubbleshield/textures/interior/interior_soft.png.mcmeta

* interior_pixel.png -- 512x512, an 8x8 grid of 64px cells, quantized
  pixel-art sprites cut from the AI-generated raw templates committed under
  tools/interior_sprites/raw/ (see tools/interior_sprites/README.md: the raw
  PNGs are one-off AI outputs and can NOT be regenerated; this script is the
  deterministic post-processing step over those frozen inputs). No .png.mcmeta
  is written so the resource pack samples it NEAREST (crisp pixels).
  Cell (ordinal) allocation:
      0..7   taco        (8 frames)
      8..11  donut       (4 frames)
      12..15 duck        (4 frames)
      16..19 disco_ball  (4 frames)
      20..27 fish        (4 frames, duplicated once to fill 8)
      28..31 book        (4 frames)
      32..47 glyph       (16 glyphs, 4x4 source grid)
      48..51 cat         (4 frames)
      52..57 lava_blob   (6 frames)
      58..63 transparent (reserved)
* interior_soft.png -- 512x512, a 4x4 grid of 128px cells, fully PROCEDURAL
  grayscale+alpha billboards (tinted at runtime by the renderer), with an 8px
  transparent gutter inside each cell so LINEAR sampling never bleeds across
  cells. The .mcmeta requests blur (LINEAR) + clamp.
  Cell (ordinal) allocation, row-major:
      0 glow_dot   1 star_soft  2 ring        3 shard
      4 streak     5 petal_soft 6 smoke_wisp  7 veil
      8 spore      9 tendril   10 ripple     11 ribbon
     12 flake     13 light_shaft 14 dome_gradient 15 arc_bolt

Both sheets are byte-stable across reruns: fixed seeds, sorted iteration, and
the same stdlib-zlib PNG writer as tools/gen_textures.py (PIL is deliberately
not used -- it is not installed in the dev environment).

Raw template layout (all RGB, magenta #FF00FF background key):
    taco_frames_raw.png       1536x1024, 8 equal horizontal cells
    donut/duck/disco_ball/fish/book/cat _frames_raw.png
                              1536x1024, 4 equal horizontal cells
    lava_blob_frames_raw.png  1536x1024, 6 equal horizontal cells
    glyph_set_raw.png         1024x1024, 4x4 grid of 16 cells

Per raw cell: foreground = chroma distance to magenta > 48; the foreground
bounding box is squarified + centered, NEAREST-downscaled to exactly 64x64
(integer-stride index sampling), quantized to <= 16 colors (4-bit/channel
pre-quantization, then a top-16 nearest-color map) with BINARY alpha (0/255).
A cell whose bbox is empty or tiny (< 8px) falls back to the nearest good
frame of the same file, so one bad AI frame can never hole the sheet.

Bbox robustness (documented deviation from the naive plan): the raw AI sheets
carry thin (<= ~6px) white/grey frame-separator lines along some cell borders,
which a naive non-magenta bbox would include (a stray fringe line above the
sprite). The bbox is therefore computed on a 7x7 box-EROSION of the mask
(pure numpy cumsum box filter -- no scipy) inflated back by 3px: line
artifacts thinner than 7px cannot survive the erosion, while every real
subject (hundreds of px across) keeps its full extent. The erosion is only
used for the bbox; the sprite itself keeps the full-resolution mask.

De-fringe: anti-aliased edge pixels blend toward the magenta key (they pass
the > 48 cut but still look pink). Foreground pixels whose chroma distance to
magenta is < 120 are recolored from the box-average of nearby SOLID
(distance >= 120) foreground pixels over a few widening passes; the mask and
alpha are untouched, only the halo color is replaced.
"""

import json
import struct
import sys
import zlib
from pathlib import Path

import numpy as np

REPO_ROOT = Path(__file__).resolve().parent.parent
RAW_DIR = REPO_ROOT / "tools/interior_sprites/raw"
OUT_DIR = REPO_ROOT / "src/main/resources/assets/bubbleshield/textures/interior"

PIXEL_CELL = 64
PIXEL_GRID = 8  # 8x8 grid -> 512x512
SOFT_CELL = 128
SOFT_GRID = 4  # 4x4 grid -> 512x512
SOFT_GUTTER = 8

MAGENTA = np.array([255.0, 0.0, 255.0])
KEY_DISTANCE = 48.0
MIN_BBOX = 8

# (file stem, horizontal frame count) for the single-row strips.
STRIPS = [
    ("taco_frames_raw", 8),
    ("donut_frames_raw", 4),
    ("duck_frames_raw", 4),
    ("disco_ball_frames_raw", 4),
    ("fish_frames_raw", 4),
    ("book_frames_raw", 4),
    ("cat_frames_raw", 4),
    ("lava_blob_frames_raw", 6),
]

# ordinal -> (file stem, source frame index). Fish frames 4..7 duplicate 0..3.
PIXEL_LAYOUT = (
    [("taco_frames_raw", i) for i in range(8)]            # 0..7
    + [("donut_frames_raw", i) for i in range(4)]         # 8..11
    + [("duck_frames_raw", i) for i in range(4)]          # 12..15
    + [("disco_ball_frames_raw", i) for i in range(4)]    # 16..19
    + [("fish_frames_raw", i % 4) for i in range(8)]      # 20..27
    + [("book_frames_raw", i) for i in range(4)]          # 28..31
    + [("glyph_set_raw", i) for i in range(16)]           # 32..47
    + [("cat_frames_raw", i) for i in range(4)]           # 48..51
    + [("lava_blob_frames_raw", i) for i in range(6)]     # 52..57
    + [(None, 0)] * 6                                     # 58..63 transparent
)


# --- minimal stdlib PNG I/O (mirrors tools/gen_textures.py's writer) ---

def _read_png_rgb(path: Path) -> np.ndarray:
    """Reads an 8-bit color-type-2 (RGB) or 6 (RGBA) PNG into HxWx3 uint8."""
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        sys.exit(f"{path}: not a PNG")
    pos, width, height, bit_depth, color_type = 8, 0, 0, 0, 0
    idat = bytearray()
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        tag = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if tag == b"IHDR":
            width, height, bit_depth, color_type = struct.unpack(">IIBB", chunk[:10])
            if bit_depth != 8 or color_type not in (2, 6) or chunk[12] != 0:
                sys.exit(f"{path}: unsupported PNG shape (need 8-bit RGB/RGBA, no interlace)")
        elif tag == b"IDAT":
            idat.extend(chunk)
        elif tag == b"IEND":
            break
    channels = 3 if color_type == 2 else 4
    raw = zlib.decompress(bytes(idat))
    stride = width * channels
    if len(raw) != height * (stride + 1):
        sys.exit(f"{path}: IDAT size mismatch")
    out = np.zeros((height, stride), dtype=np.uint8)
    prev = np.zeros(stride, dtype=np.uint8)
    for y in range(height):
        offset = y * (stride + 1)
        filter_type = raw[offset]
        row = np.frombuffer(raw, dtype=np.uint8, count=stride, offset=offset + 1).astype(np.int32)
        if filter_type == 0:
            cur = row
        elif filter_type == 2:  # Up
            cur = (row + prev) & 0xFF
        else:  # Sub(1) / Average(3) / Paeth(4) need the left neighbor -> per-pixel scan
            cur = np.zeros(stride, dtype=np.int32)
            for x in range(stride):
                left = cur[x - channels] if x >= channels else 0
                up = int(prev[x])
                ul = int(prev[x - channels]) if x >= channels else 0
                if filter_type == 1:
                    pred = left
                elif filter_type == 3:
                    pred = (left + up) // 2
                else:
                    p = left + up - ul
                    pa, pb, pc = abs(p - left), abs(p - up), abs(p - ul)
                    pred = left if (pa <= pb and pa <= pc) else (up if pb <= pc else ul)
                cur[x] = (row[x] + pred) & 0xFF
        out[y] = cur.astype(np.uint8)
        prev = out[y]
    pixels = out.reshape(height, width, channels)
    return pixels[:, :, :3].copy()


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


# --- pixel sheet ---

def _foreground_mask(rgb: np.ndarray) -> np.ndarray:
    """True where the pixel is far enough from the magenta key."""
    dist = np.sqrt(((rgb.astype(np.float64) - MAGENTA) ** 2).sum(axis=2))
    return dist > KEY_DISTANCE


EROSION = 7  # frame-separator lines in the raw sheets are <= ~6px thick


def _eroded(mask: np.ndarray, k: int) -> np.ndarray:
    """k x k binary box erosion via a cumsum box filter (no scipy)."""
    padded = np.zeros((mask.shape[0] + k - 1, mask.shape[1] + k - 1), dtype=np.int64)
    r = k // 2
    padded[r:r + mask.shape[0], r:r + mask.shape[1]] = mask
    cum = padded.cumsum(axis=0).cumsum(axis=1)
    cum = np.pad(cum, ((1, 0), (1, 0)))
    box = (cum[k:, k:] - cum[:-k, k:] - cum[k:, :-k] + cum[:-k, :-k])
    return box == k * k


HALO_DISTANCE = 120.0  # fg pixels closer than this to magenta are AA halo


def _box_sum(a: np.ndarray, k: int) -> np.ndarray:
    """k x k box sum with zero padding (cumsum trick), per 2D channel."""
    r = k // 2
    padded = np.zeros((a.shape[0] + k - 1, a.shape[1] + k - 1), dtype=np.float64)
    padded[r:r + a.shape[0], r:r + a.shape[1]] = a
    cum = padded.cumsum(axis=0).cumsum(axis=1)
    cum = np.pad(cum, ((1, 0), (1, 0)))
    return cum[k:, k:] - cum[:-k, k:] - cum[k:, :-k] + cum[:-k, :-k]


def _defringe(rgb: np.ndarray, mask: np.ndarray) -> np.ndarray:
    """Recolors magenta-halo foreground pixels from nearby solid pixels."""
    dist = np.sqrt(((rgb.astype(np.float64) - MAGENTA) ** 2).sum(axis=2))
    solid = mask & (dist >= HALO_DISTANCE)
    halo = mask & ~solid
    if not halo.any() or not solid.any():
        return rgb
    out = rgb.astype(np.float64)
    for _ in range(4):
        counts = _box_sum(solid.astype(np.float64), 7)
        fill = halo & (counts > 0)
        if not fill.any():
            break
        sums = np.stack([_box_sum(out[:, :, c] * solid, 7) for c in range(3)], axis=2)
        out[fill] = sums[fill] / counts[fill][:, None]
        solid = solid | fill
        halo = halo & ~fill
    return np.clip(np.round(out), 0, 255).astype(np.uint8)


def _cut_sprite(cell_rgb: np.ndarray) -> np.ndarray | None:
    """One raw cell -> 64x64 RGBA (binary alpha, <=16 colors), or None if the
    cell has no usable foreground (empty/tiny bbox)."""
    mask = _foreground_mask(cell_rgb)
    # Bbox from the eroded mask (kills thin separator-line artifacts), inflated
    # back by the erosion radius so the real subject keeps its full extent.
    core = _eroded(mask, EROSION)
    ys, xs = np.nonzero(core)
    if ys.size == 0:
        return None
    r = EROSION // 2
    y0 = max(int(ys.min()) - r, 0)
    y1 = min(int(ys.max()) + 1 + r, mask.shape[0])
    x0 = max(int(xs.min()) - r, 0)
    x1 = min(int(xs.max()) + 1 + r, mask.shape[1])
    bh, bw = y1 - y0, x1 - x0
    if max(bh, bw) < MIN_BBOX:
        return None

    # De-fringe the magenta halo inside the bbox (color only; mask unchanged).
    cell_rgb = cell_rgb.copy()
    cell_rgb[y0:y1, x0:x1] = _defringe(cell_rgb[y0:y1, x0:x1], mask[y0:y1, x0:x1])

    # Squarify + center: paste the bbox into a transparent side x side square.
    side = max(bh, bw)
    square_rgb = np.zeros((side, side, 3), dtype=np.uint8)
    square_mask = np.zeros((side, side), dtype=bool)
    oy = (side - bh) // 2
    ox = (side - bw) // 2
    square_rgb[oy:oy + bh, ox:ox + bw] = cell_rgb[y0:y1, x0:x1]
    square_mask[oy:oy + bh, ox:ox + bw] = mask[y0:y1, x0:x1]

    # NEAREST downscale to exactly 64x64 via integer-stride index sampling.
    idx = (np.arange(PIXEL_CELL, dtype=np.int64) * side) // PIXEL_CELL
    small_rgb = square_rgb[idx][:, idx]
    small_mask = square_mask[idx][:, idx]

    # Quantize to <= 16 colors: 4-bit/channel pre-quantization, then map every
    # foreground pixel to the nearest of the 16 most frequent quantized colors.
    quant = (small_rgb >> 4).astype(np.uint16) * 17  # 0,17,...,255 spread
    fg = quant[small_mask]
    if fg.size == 0:
        return None
    packed = (fg[:, 0].astype(np.int64) << 16) | (fg[:, 1].astype(np.int64) << 8) | fg[:, 2].astype(np.int64)
    colors, counts = np.unique(packed, return_counts=True)
    # Deterministic top-16: by count desc, then packed value asc.
    order = np.lexsort((colors, -counts))
    palette_packed = colors[order[:16]]
    palette = np.stack([(palette_packed >> 16) & 0xFF,
                        (palette_packed >> 8) & 0xFF,
                        palette_packed & 0xFF], axis=1).astype(np.int64)
    flat = quant.reshape(-1, 3).astype(np.int64)
    dists = ((flat[:, None, :] - palette[None, :, :]) ** 2).sum(axis=2)
    mapped = palette[np.argmin(dists, axis=1)].reshape(PIXEL_CELL, PIXEL_CELL, 3).astype(np.uint8)

    out = np.zeros((PIXEL_CELL, PIXEL_CELL, 4), dtype=np.uint8)
    out[:, :, :3] = np.where(small_mask[:, :, None], mapped, 0)
    out[:, :, 3] = np.where(small_mask, 255, 0).astype(np.uint8)
    return out


def _load_frames(stem: str) -> list:
    """All frames of one raw file, with empty/tiny cells replaced by the
    nearest good frame (preferring the closest lower index)."""
    rgb = _read_png_rgb(RAW_DIR / f"{stem}.png")
    cells = []
    if stem == "glyph_set_raw":
        ch = rgb.shape[0] // 4
        cw = rgb.shape[1] // 4
        for i in range(16):
            gy, gx = divmod(i, 4)
            cells.append(rgb[gy * ch:(gy + 1) * ch, gx * cw:(gx + 1) * cw])
    else:
        frames = dict(STRIPS)[stem]
        cw = rgb.shape[1] // frames
        for i in range(frames):
            cells.append(rgb[:, i * cw:(i + 1) * cw])

    sprites = [_cut_sprite(cell) for cell in cells]
    good = [i for i, s in enumerate(sprites) if s is not None]
    if not good:
        sys.exit(f"{stem}: no usable frames (all bboxes empty or < {MIN_BBOX}px)")
    for i, sprite in enumerate(sprites):
        if sprite is None:
            nearest = min(good, key=lambda j: (abs(j - i), j))
            print(f"  {stem} frame {i}: empty/tiny bbox, duplicating frame {nearest}")
            sprites[i] = sprites[nearest]
    return sprites


def build_pixel_sheet() -> np.ndarray:
    frames_by_stem = {}
    for stem in sorted({stem for stem, _ in PIXEL_LAYOUT if stem is not None}):
        frames_by_stem[stem] = _load_frames(stem)
    sheet = np.zeros((PIXEL_CELL * PIXEL_GRID, PIXEL_CELL * PIXEL_GRID, 4), dtype=np.uint8)
    for ordinal, (stem, frame) in enumerate(PIXEL_LAYOUT):
        if stem is None:
            continue  # reserved transparent cell
        gy, gx = divmod(ordinal, PIXEL_GRID)
        sheet[gy * PIXEL_CELL:(gy + 1) * PIXEL_CELL,
              gx * PIXEL_CELL:(gx + 1) * PIXEL_CELL] = frames_by_stem[stem][frame]
    return sheet


# --- soft sheet (all procedural; grayscale in RGB, shape in alpha) ---

def _grid() -> tuple:
    """Centered [-1,1] coordinate grid over the usable (gutter-inset) cell."""
    usable = SOFT_CELL - 2 * SOFT_GUTTER
    c = (np.arange(usable) + 0.5) / usable * 2.0 - 1.0
    x, y = np.meshgrid(c, c)
    return x, y, usable


def _value_noise(size: int, cells: int, seed: int) -> np.ndarray:
    """Smooth [0,1] value noise: seeded lattice + cosine-smoothed bilerp."""
    rng = np.random.default_rng(seed)
    lattice = rng.random((cells + 1, cells + 1))
    t = np.linspace(0.0, cells, size, endpoint=False)
    i = np.minimum(t.astype(np.int64), cells - 1)
    f = t - i
    s = f * f * (3.0 - 2.0 * f)
    sx = s[None, :]
    sy = s[:, None]
    ix = i[None, :]
    iy = i[:, None]
    v00 = lattice[iy, ix]
    v10 = lattice[iy, ix + 1]
    v01 = lattice[iy + 1, ix]
    v11 = lattice[iy + 1, ix + 1]
    return v00 + (v10 - v00) * sx + (v01 - v00) * sy + (v00 - v10 - v01 + v11) * sx * sy


def _soft_element(name: str) -> tuple:
    """-> (gray [0,1], alpha [0,1]) over the gutter-inset area."""
    x, y, usable = _grid()
    r = np.sqrt(x * x + y * y)
    theta = np.arctan2(y, x)
    gray = np.ones_like(x)

    if name == "glow_dot":
        alpha = np.exp(-(r / 0.45) ** 2)
    elif name == "star_soft":
        core = np.exp(-(r / 0.28) ** 2)
        flare_h = np.exp(-(np.abs(y) / 0.06) ** 2) * np.exp(-(np.abs(x) / 0.75) ** 2)
        flare_v = np.exp(-(np.abs(x) / 0.06) ** 2) * np.exp(-(np.abs(y) / 0.75) ** 2)
        alpha = np.clip(core + 0.85 * (flare_h + flare_v), 0.0, 1.0)
    elif name == "ring":
        alpha = np.exp(-((r - 0.62) / 0.10) ** 2)
    elif name == "shard":
        # Angular sliver: a thin elongated diamond along +x.
        along = np.clip(1.0 - np.abs(x), 0.0, 1.0)
        alpha = np.clip(along ** 1.5 * np.exp(-(np.abs(y) / (0.05 + 0.16 * along)) ** 2), 0.0, 1.0)
        gray = 0.65 + 0.35 * along
    elif name == "streak":
        alpha = np.exp(-(np.abs(y) / 0.09) ** 2) * np.clip(1.0 - np.abs(x), 0.0, 1.0) ** 0.75
    elif name == "petal_soft":
        petal_r = 0.85 * np.abs(np.cos(theta)) ** 0.5
        alpha = np.clip((petal_r - r) / 0.16, 0.0, 1.0) * np.exp(-(r / 0.9) ** 2)
    elif name == "smoke_wisp":
        noise = _value_noise(usable, 5, seed=41)
        noise = 0.6 * noise + 0.4 * _value_noise(usable, 11, seed=42)
        alpha = np.clip((noise - 0.35) * 2.2, 0.0, 1.0) * np.exp(-(r / 0.75) ** 2)
        gray = 0.75 + 0.25 * noise
    elif name == "veil":
        edge = _value_noise(usable, 7, seed=43)
        alpha = np.clip(1.0 - np.abs(y) * 1.15, 0.0, 1.0) * (0.55 + 0.45 * edge) \
            * np.clip(1.0 - np.abs(x) * 1.05, 0.0, 1.0) ** 0.5
        gray = 0.8 + 0.2 * edge
    elif name == "spore":
        rng = np.random.default_rng(44)
        alpha = np.zeros_like(x)
        for _ in range(9):
            cx, cy = rng.uniform(-0.6, 0.6, 2)
            size = rng.uniform(0.05, 0.14)
            alpha += np.exp(-(((x - cx) ** 2 + (y - cy) ** 2) / (size * size)))
        alpha = np.clip(alpha, 0.0, 1.0)
    elif name == "tendril":
        # A curling spiral polyline with gaussian thickness.
        alpha = np.zeros_like(x)
        t = np.linspace(0.0, 1.0, 160)
        angle = 4.6 * t + 0.6
        radius = 0.12 + 0.68 * t
        px = radius * np.cos(angle)
        py = radius * np.sin(angle)
        width = 0.085 * (1.0 - 0.65 * t)
        for k in range(t.size):
            alpha = np.maximum(alpha, np.exp(-(((x - px[k]) ** 2 + (y - py[k]) ** 2) / (width[k] ** 2))))
        gray = 0.7 + 0.3 * np.clip(1.0 - r, 0.0, 1.0)
    elif name == "ripple":
        alpha = (0.5 + 0.5 * np.cos(r * 19.0)) * np.clip(1.0 - r, 0.0, 1.0) ** 0.8
    elif name == "ribbon":
        wave = 0.32 * np.sin(x * 4.4)
        alpha = np.exp(-(np.abs(y - wave) / 0.11) ** 2) * np.clip(1.0 - np.abs(x), 0.0, 1.0) ** 0.5
    elif name == "flake":
        # Soft hexagon: signed distance to a hex via max over 3 axes.
        k0 = np.abs(x)
        k1 = np.abs(0.5 * x + 0.8660254 * y)
        k2 = np.abs(0.5 * x - 0.8660254 * y)
        hex_d = np.maximum(k0, np.maximum(k1, k2))
        alpha = np.clip((0.62 - hex_d) / 0.10, 0.0, 1.0)
        gray = 0.85 + 0.15 * np.clip((0.62 - hex_d), 0.0, 1.0)
    elif name == "light_shaft":
        alpha = np.exp(-(np.abs(x) / 0.22) ** 2) * np.clip(1.0 - (y + 1.0) / 2.0, 0.0, 1.0) ** 1.35
    elif name == "dome_gradient":
        # Hemisphere falloff: near-opaque center receding to a clear rim.
        alpha = np.clip(1.0 - r, 0.0, 1.0) ** 0.65
        gray = 0.55 + 0.45 * np.clip(1.0 - r, 0.0, 1.0)
    elif name == "arc_bolt":
        rng = np.random.default_rng(45)
        alpha = np.zeros_like(x)
        segs = 9
        pts_x = np.linspace(-0.85, 0.85, segs + 1)
        pts_y = np.concatenate(([0.0], rng.uniform(-0.4, 0.4, segs - 1), [0.0]))
        samples = 26
        for s in range(segs):
            ts = np.linspace(0.0, 1.0, samples)
            sx = pts_x[s] + (pts_x[s + 1] - pts_x[s]) * ts
            sy = pts_y[s] + (pts_y[s + 1] - pts_y[s]) * ts
            for k in range(samples):
                alpha = np.maximum(alpha, np.exp(-(((x - sx[k]) ** 2 + (y - sy[k]) ** 2) / (0.045 ** 2))))
        alpha = np.clip(alpha + 0.35 * np.exp(-(np.abs(y) / 0.5) ** 2) * alpha, 0.0, 1.0)
    else:
        sys.exit(f"unknown soft element: {name}")

    return np.clip(gray, 0.0, 1.0), np.clip(alpha, 0.0, 1.0)


SOFT_ELEMENTS = [
    "glow_dot", "star_soft", "ring", "shard",
    "streak", "petal_soft", "smoke_wisp", "veil",
    "spore", "tendril", "ripple", "ribbon",
    "flake", "light_shaft", "dome_gradient", "arc_bolt",
]


def build_soft_sheet() -> np.ndarray:
    sheet = np.zeros((SOFT_CELL * SOFT_GRID, SOFT_CELL * SOFT_GRID, 4), dtype=np.uint8)
    for ordinal, name in enumerate(SOFT_ELEMENTS):
        gray, alpha = _soft_element(name)
        usable = SOFT_CELL - 2 * SOFT_GUTTER
        cell = np.zeros((SOFT_CELL, SOFT_CELL, 4), dtype=np.uint8)
        value = np.round(gray * 255.0).astype(np.uint8)
        cell[SOFT_GUTTER:SOFT_GUTTER + usable, SOFT_GUTTER:SOFT_GUTTER + usable, 0] = value
        cell[SOFT_GUTTER:SOFT_GUTTER + usable, SOFT_GUTTER:SOFT_GUTTER + usable, 1] = value
        cell[SOFT_GUTTER:SOFT_GUTTER + usable, SOFT_GUTTER:SOFT_GUTTER + usable, 2] = value
        cell[SOFT_GUTTER:SOFT_GUTTER + usable, SOFT_GUTTER:SOFT_GUTTER + usable, 3] = \
            np.round(alpha * 255.0).astype(np.uint8)
        gy, gx = divmod(ordinal, SOFT_GRID)
        sheet[gy * SOFT_CELL:(gy + 1) * SOFT_CELL, gx * SOFT_CELL:(gx + 1) * SOFT_CELL] = cell
    return sheet


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    pixel = build_pixel_sheet()
    _write_png(OUT_DIR / "interior_pixel.png", pixel)
    print(f"wrote {OUT_DIR / 'interior_pixel.png'} ({pixel.shape[1]}x{pixel.shape[0]} RGBA, "
          f"{PIXEL_GRID}x{PIXEL_GRID} grid of {PIXEL_CELL}px; NEAREST -- no mcmeta)")
    soft = build_soft_sheet()
    _write_png(OUT_DIR / "interior_soft.png", soft)
    (OUT_DIR / "interior_soft.png.mcmeta").write_text(
        json.dumps({"texture": {"blur": True, "clamp": True}}) + "\n", encoding="utf-8", newline="\n")
    print(f"wrote {OUT_DIR / 'interior_soft.png'} ({soft.shape[1]}x{soft.shape[0]} RGBA, "
          f"{SOFT_GRID}x{SOFT_GRID} grid of {SOFT_CELL}px, {SOFT_GUTTER}px gutters; LINEAR via mcmeta)")


if __name__ == "__main__":
    main()

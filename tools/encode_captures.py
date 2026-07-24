#!/usr/bin/env python3
"""Encode the VideoCaptureTest frame sequences into reviewable videos.

Consumes the output of the BUBBLESHIELD_VIDEO=1 client-gametest run
(com.bubbleshield.clienttest.VideoCaptureTest): one subdir per clip under
$BUBBLESHIELD_VIDEO_DIR (default /tmp/bubble_video) containing frames named
f_%04d.png (starting at f_0000.png), plus manifest.json.

For every clip subdir it produces, under $BUBBLESHIELD_VIDEO_DIR/encoded/:
  - <clip>.mp4  (20 fps, libx264 crf 20, yuv420p — frames were captured one per
                 game tick and 1 tick = 0.05 s of GameTime, so 20 fps replays
                 real-time shader animation)
  - <clip>.gif  (10 fps, half-size, palette-optimized)
plus:
  - montage_*.mp4  side-by-side (hstack) family comparison montages
  - contact_sheet.png  one mid-clip frame per clip, tiled in a grid

Usage: python3 tools/encode_captures.py  [--video-dir DIR]
"""

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

FRAMERATE = 20
GIF_FPS = 10
CONTACT_COLUMNS = 4

# Side-by-side family montages: label -> ordered list of family substrings; the
# first clip dir matching each substring is used. Skipped (with a warning) when
# fewer than two of the families were captured.
MONTAGES = {
    "montage_glassy_families": ["crystalrefract", "stainedglass"],
    "montage_energy_families": ["lightning", "plasma"],
}


def run(cmd: list[str]) -> None:
    print("+ " + " ".join(str(c) for c in cmd), flush=True)
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)


def clip_dirs(video_dir: Path) -> list[Path]:
    dirs = []
    for entry in sorted(video_dir.iterdir()):
        if entry.is_dir() and entry.name != "encoded" and list(entry.glob("f_*.png")):
            dirs.append(entry)
    return dirs


def encode_clip(clip: Path, out_dir: Path) -> Path:
    pattern = str(clip / "f_%04d.png")
    mp4 = out_dir / f"{clip.name}.mp4"
    # Defensive even-dimension crop: libx264 + yuv420p rejects odd sizes.
    run([
        "ffmpeg", "-y", "-framerate", str(FRAMERATE), "-start_number", "0",
        "-i", pattern,
        "-vf", "crop=trunc(iw/2)*2:trunc(ih/2)*2",
        "-c:v", "libx264", "-crf", "20", "-pix_fmt", "yuv420p",
        str(mp4),
    ])

    gif = out_dir / f"{clip.name}.gif"
    run([
        "ffmpeg", "-y", "-framerate", str(FRAMERATE), "-start_number", "0",
        "-i", pattern,
        "-vf",
        (
            f"fps={GIF_FPS},scale=iw/2:-1:flags=lanczos,"
            "split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse"
        ),
        str(gif),
    ])
    return mp4


def build_montages(mp4_by_clip: dict[str, Path], out_dir: Path) -> list[Path]:
    made = []
    for label, families in MONTAGES.items():
        inputs = []
        for family in families:
            match = next((p for name, p in sorted(mp4_by_clip.items()) if family in name), None)
            if match is not None:
                inputs.append(match)
            else:
                print(f"  ! montage {label}: no clip matching family '{family}'")
        if len(inputs) < 2:
            print(f"  ! skipping montage {label} (need >= 2 clips)")
            continue
        out = out_dir / f"{label}.mp4"
        cmd = ["ffmpeg", "-y"]
        for mp4 in inputs:
            cmd += ["-i", str(mp4)]
        streams = "".join(f"[{i}:v]" for i in range(len(inputs)))
        cmd += [
            "-filter_complex", f"{streams}hstack=inputs={len(inputs)}",
            "-c:v", "libx264", "-crf", "20", "-pix_fmt", "yuv420p",
            str(out),
        ]
        run(cmd)
        made.append(out)
    return made


def build_contact_sheet(clips: list[Path], out_dir: Path) -> Path | None:
    """Tiles one mid-clip frame per clip into a labeled grid PNG."""
    frames = []
    for clip in clips:
        pngs = sorted(clip.glob("f_*.png"))
        frames.append((clip.name, pngs[len(pngs) // 2]))
    if not frames:
        return None

    out = out_dir / "contact_sheet.png"
    cols = min(CONTACT_COLUMNS, len(frames))
    rows = (len(frames) + cols - 1) // cols
    cmd = ["ffmpeg", "-y"]
    for _, png in frames:
        cmd += ["-i", str(png)]
    # Label each tile with its clip name, then tile into a cols x rows grid.
    parts = []
    for i, (name, _) in enumerate(frames):
        parts.append(
            f"[{i}:v]scale=iw/2:-1,"
            f"drawtext=text='{name}':x=8:y=8:fontsize=16:fontcolor=white:box=1:boxcolor=black@0.6[t{i}]"
        )
    tiles = "".join(f"[t{i}]" for i in range(len(frames)))
    parts.append(f"{tiles}xstack=inputs={len(frames)}:layout={xstack_layout(len(frames), cols)}:fill=black")
    cmd += ["-filter_complex", ";".join(parts), "-frames:v", "1", str(out)]
    run(cmd)
    return out


def xstack_layout(n: int, cols: int) -> str:
    """xstack layout string for a left-to-right, top-to-bottom grid of equal tiles."""
    cells = []
    for i in range(n):
        col, row = i % cols, i // cols
        x = "0" if col == 0 else "+".join(f"w{c}" for c in range(col))
        y = "0" if row == 0 else "+".join(f"h{r * cols}" for r in range(row))
        cells.append(f"{x}_{y}")
    return "|".join(cells)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--video-dir",
        default=os.environ.get("BUBBLESHIELD_VIDEO_DIR", "/tmp/bubble_video"),
        help="capture dir (default: $BUBBLESHIELD_VIDEO_DIR or /tmp/bubble_video)",
    )
    args = parser.parse_args()

    video_dir = Path(args.video_dir)
    if not video_dir.is_dir():
        print(f"error: {video_dir} does not exist — run the BUBBLESHIELD_VIDEO=1 capture first")
        return 1

    manifest = video_dir / "manifest.json"
    if manifest.is_file():
        meta = json.loads(manifest.read_text())
        print(f"manifest: {len(meta.get('clips', []))} clips declared")
    else:
        print("warning: no manifest.json (encoding whatever clip dirs exist)")

    clips = clip_dirs(video_dir)
    if not clips:
        print(f"error: no clip subdirs with f_*.png frames under {video_dir}")
        return 1

    out_dir = video_dir / "encoded"
    out_dir.mkdir(parents=True, exist_ok=True)

    mp4_by_clip: dict[str, Path] = {}
    for clip in clips:
        n = len(list(clip.glob("f_*.png")))
        print(f"encoding {clip.name} ({n} frames)")
        mp4_by_clip[clip.name] = encode_clip(clip, out_dir)

    montages = build_montages(mp4_by_clip, out_dir)
    sheet = build_contact_sheet(clips, out_dir)

    print("\nencoded outputs:")
    for path in sorted(out_dir.iterdir()):
        print(f"  {path}  ({path.stat().st_size / 1024:.0f} KiB)")
    print(f"\n{len(mp4_by_clip)} clips, {len(montages)} montages, "
          f"contact sheet: {sheet if sheet else 'none'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

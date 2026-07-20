#!/usr/bin/env python3
"""Dev tool: compile-validates every mod GLSL shader with glslangValidator.

The VM is headless (no GPU), so shaders can never be run; this is the closest
practical check. For each of the mod's shader files -- the bubble surface
shaders in src/client/resources (hand-written .fsh + surface.vsh + the
generated fx_*.fsh from tools/gen_surface_shaders.py) and the screen-fx
shaders in src/main/resources -- the script:

1. extracts the vanilla include files (fog/globals/dynamictransforms/projection
   .glsl) from the Minecraft 26.2 client jar in the loom cache,
2. inlines every '#moj_import <minecraft:X.glsl>' occurrence (dropping the
   includes' own '#version' lines, which may not repeat mid-file),
3. writes the stitched copy to /tmp/bubbleshield_shader_validation/, and
4. runs 'glslangValidator -S frag' (resp. '-S vert' for .vsh) on it,
   in a multiprocessing pool by default (use --serial to disable).

On top of per-file compilation it enforces the generated-shader invariants:

* byte-uniqueness: no two .fsh across the scanned dirs are identical (SHA-256);
* every generated fx_*.fsh carries all three structural layer markers
  ([layer:deep:...], [layer:mid:...], [layer:rim:...]);
* tools/surface_manifest.json exists, its entries match the fx_* files on
  disk 1:1, and its (family, warp, deep, rim, anim) stack tuples are pairwise
  distinct (the structural-variety guarantee).

The fx_*/manifest checks are skipped (with a notice) only while NEITHER any
fx_*.fsh NOR the manifest exists yet, i.e. before the generator has ever run;
any half-generated state is a hard failure.

Exits nonzero when any shader fails to compile or any invariant is violated.
Usage:

    python3 tools/validate_shaders.py [--serial]
"""

import argparse
import hashlib
import json
import multiprocessing
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
CLIENT_JAR = REPO_ROOT / (
    ".gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-clientOnly-043a8b3edf/26.2/"
    "minecraft-clientOnly-043a8b3edf-26.2.jar"
)
INCLUDE_NAMES = ["fog", "globals", "dynamictransforms", "projection"]
BUBBLE_DIR = REPO_ROOT / "src/client/resources/assets/bubbleshield/shaders/bubble"
SHADER_DIRS = [
    BUBBLE_DIR,
    REPO_ROOT / "src/main/resources/assets/bubbleshield/shaders/screenfx",
]
MANIFEST_PATH = REPO_ROOT / "tools/surface_manifest.json"
OUT_DIR = Path("/tmp/bubbleshield_shader_validation")
MOJ_IMPORT = re.compile(r"^#moj_import <minecraft:([a-z_]+)\.glsl>\s*$")
FX_NAME = re.compile(r"^fx_(\d{3})\.fsh$")
LAYER_MARKERS = ("// [layer:deep:", "// [layer:mid:", "// [layer:rim:")
STACK_KEYS = ("family", "warp", "deep", "rim", "anim")


def extract_includes() -> dict[str, str]:
    """Extracts the vanilla include sources, keyed by name (e.g. "fog")."""
    if not CLIENT_JAR.is_file():
        sys.exit(f"vanilla client jar not found: {CLIENT_JAR}")

    includes: dict[str, str] = {}
    with zipfile.ZipFile(CLIENT_JAR) as jar, tempfile.TemporaryDirectory() as tmp:
        for name in INCLUDE_NAMES:
            entry = f"assets/minecraft/shaders/include/{name}.glsl"
            extracted = Path(jar.extract(entry, tmp))
            source = extracted.read_text()
            # The includes carry their own '#version' header, which must not be
            # repeated mid-file after inlining.
            lines = [line for line in source.splitlines() if not line.startswith("#version")]
            includes[name] = "\n".join(lines).strip("\n")

    return includes


def stitch(shader: Path, includes: dict[str, str]) -> str:
    stitched: list[str] = []
    for line in shader.read_text().splitlines():
        match = MOJ_IMPORT.match(line.strip())
        if match:
            name = match.group(1)
            if name not in includes:
                sys.exit(f"{shader}: unknown moj_import 'minecraft:{name}.glsl' (add it to INCLUDE_NAMES)")
            stitched.append(f"// --- inlined minecraft:{name}.glsl ---")
            stitched.append(includes[name])
            stitched.append(f"// --- end minecraft:{name}.glsl ---")
        else:
            stitched.append(line)

    return "\n".join(stitched) + "\n"


def compile_one(job: tuple[str, str, str]) -> tuple[str, bool, str]:
    """Pool worker: runs glslangValidator on one pre-stitched file.

    Takes/returns plain strings so the job stays picklable for multiprocessing.
    """
    relative, stage, stitched_path = job
    result = subprocess.run(
        ["glslangValidator", "-S", stage, stitched_path],
        capture_output=True, text=True,
    )
    return relative, result.returncode == 0, (result.stdout + result.stderr).strip()


def check_generated_invariants(shaders: list[Path]) -> list[str]:
    """Byte-uniqueness + fx layer markers + manifest agreement. Returns errors."""
    errors: list[str] = []

    # 1. No two .fsh anywhere in the scanned dirs may be byte-identical.
    by_digest: dict[str, Path] = {}
    for shader in shaders:
        if shader.suffix != ".fsh":
            continue
        digest = hashlib.sha256(shader.read_bytes()).hexdigest()
        if digest in by_digest:
            errors.append(f"byte-identical shaders: {by_digest[digest].name} == {shader.name}")
        else:
            by_digest[digest] = shader

    fx_files = sorted(p for p in shaders if FX_NAME.match(p.name))

    if not fx_files and not MANIFEST_PATH.is_file():
        print("NOTE  no generated fx_*.fsh and no surface_manifest.json yet -- "
              "skipping generated-shader checks (run tools/gen_surface_shaders.py)")
        return errors

    # 2. Every generated fx_*.fsh must carry the three structural layer markers.
    for shader in fx_files:
        text = shader.read_text()
        for marker in LAYER_MARKERS:
            if marker not in text:
                errors.append(f"{shader.name}: missing structural marker '{marker}...]'")

    # 3. Manifest exists, matches the fx_* file set, and its stack tuples are
    # pairwise distinct.
    if not MANIFEST_PATH.is_file():
        errors.append(f"fx_* shaders exist but manifest is missing: {MANIFEST_PATH}")
        return errors
    try:
        manifest = json.loads(MANIFEST_PATH.read_text())
    except json.JSONDecodeError as e:
        errors.append(f"{MANIFEST_PATH.name}: invalid JSON ({e})")
        return errors

    manifest_files = {entry.get("file") for entry in manifest.values()}
    disk_files = {p.name for p in fx_files}
    for missing in sorted(manifest_files - disk_files):
        errors.append(f"manifest lists {missing} but the file does not exist")
    for extra in sorted(disk_files - manifest_files):
        errors.append(f"{extra} exists on disk but is not in the manifest")
    for effect_id, entry in sorted(manifest.items()):
        expected = f"fx_{int(effect_id):03d}.fsh"
        if entry.get("file") != expected:
            errors.append(f"manifest id {effect_id} points at {entry.get('file')}, expected {expected}")

    stacks: dict[tuple, str] = {}
    for effect_id, entry in sorted(manifest.items()):
        stack = tuple(entry.get(key) for key in STACK_KEYS)
        if stack in stacks:
            errors.append(f"manifest ids {stacks[stack]} and {effect_id} share the same "
                          f"stack tuple {dict(zip(STACK_KEYS, stack))}")
        else:
            stacks[stack] = effect_id

    print(f"OK    generated-shader invariants ({len(fx_files)} fx files, "
          f"{len(manifest)} manifest entries, {len(stacks)} distinct stacks)")
    return errors


def main() -> None:
    parser = argparse.ArgumentParser(description="compile-validate the mod's GLSL shaders")
    parser.add_argument("--serial", action="store_true",
                        help="compile one shader at a time instead of using a process pool")
    args = parser.parse_args()

    includes = extract_includes()
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    shaders: list[Path] = []
    for shader_dir in SHADER_DIRS:
        if not shader_dir.is_dir():
            sys.exit(f"shader directory not found: {shader_dir}")
        shaders += sorted(p for p in shader_dir.iterdir() if p.suffix in (".fsh", ".vsh"))

    jobs: list[tuple[str, str, str]] = []
    for shader in shaders:
        stage = "vert" if shader.suffix == ".vsh" else "frag"
        stitched = OUT_DIR / f"{shader.parent.name}_{shader.name}"
        stitched.write_text(stitch(shader, includes))
        jobs.append((str(shader.relative_to(REPO_ROOT)), stage, str(stitched)))

    if args.serial:
        results = [compile_one(job) for job in jobs]
    else:
        with multiprocessing.Pool() as pool:
            results = pool.map(compile_one, jobs)

    failures = 0
    for relative, ok, output in results:
        if ok:
            print(f"PASS  {relative}")
        else:
            failures += 1
            print(f"FAIL  {relative}")
            if output:
                print("      " + "\n      ".join(output.splitlines()))

    invariant_errors = check_generated_invariants(shaders)
    for error in invariant_errors:
        print(f"FAIL  {error}")

    print(f"\n{len(shaders) - failures}/{len(shaders)} shaders passed glslangValidator "
          f"(stitched copies in {OUT_DIR})")
    if failures or invariant_errors:
        sys.exit(1)


if __name__ == "__main__":
    main()

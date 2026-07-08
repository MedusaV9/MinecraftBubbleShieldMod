#!/usr/bin/env python3
"""Dev tool: compile-validates every mod GLSL shader with glslangValidator.

The VM is headless (no GPU), so shaders can never be run; this is the closest
practical check. For each of the mod's shader files -- the bubble surface
shaders in src/client/resources (16 .fsh + surface.vsh) and the screen-fx
shaders in src/main/resources (16 .fsh) -- the script:

1. extracts the vanilla include files (fog/globals/dynamictransforms/projection
   .glsl) from the Minecraft 26.2 client jar in the loom cache,
2. inlines every '#moj_import <minecraft:X.glsl>' occurrence (dropping the
   includes' own '#version' lines, which may not repeat mid-file),
3. writes the stitched copy to /tmp/bubbleshield_shader_validation/, and
4. runs 'glslangValidator -S frag' (resp. '-S vert' for .vsh) on it.

Exits nonzero when any shader fails to compile. Usage:

    python3 tools/validate_shaders.py
"""

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
SHADER_DIRS = [
    REPO_ROOT / "src/client/resources/assets/bubbleshield/shaders/bubble",
    REPO_ROOT / "src/main/resources/assets/bubbleshield/shaders/screenfx",
]
OUT_DIR = Path("/tmp/bubbleshield_shader_validation")
MOJ_IMPORT = re.compile(r"^#moj_import <minecraft:([a-z_]+)\.glsl>\s*$")


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


def main() -> None:
    includes = extract_includes()
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    shaders: list[Path] = []
    for shader_dir in SHADER_DIRS:
        if not shader_dir.is_dir():
            sys.exit(f"shader directory not found: {shader_dir}")
        shaders += sorted(p for p in shader_dir.iterdir() if p.suffix in (".fsh", ".vsh"))

    failures = 0
    for shader in shaders:
        stage = "vert" if shader.suffix == ".vsh" else "frag"
        stitched = OUT_DIR / f"{shader.parent.name}_{shader.name}"
        stitched.write_text(stitch(shader, includes))

        result = subprocess.run(
            ["glslangValidator", "-S", stage, str(stitched)],
            capture_output=True, text=True,
        )
        relative = shader.relative_to(REPO_ROOT)
        if result.returncode == 0:
            print(f"PASS  {relative}")
        else:
            failures += 1
            print(f"FAIL  {relative}")
            output = (result.stdout + result.stderr).strip()
            if output:
                print("      " + "\n      ".join(output.splitlines()))

    print(f"\n{len(shaders) - failures}/{len(shaders)} shaders passed glslangValidator "
          f"(stitched copies in {OUT_DIR})")
    if failures:
        sys.exit(1)


if __name__ == "__main__":
    main()

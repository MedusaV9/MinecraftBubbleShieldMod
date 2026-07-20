#!/usr/bin/env python3
"""Dev tool: hard validation gate for every mod GLSL shader + post-effect JSON.

The VM is headless (no GPU), so shaders can never be run; this is the closest
practical check. One invalid or missing surface shader crashes the whole client
at resource load, so this gate is deliberately fail-CLOSED: the expected file
sets are derived from EffectRegistry.java's COUNT (not from whatever happens to
be on disk), and any gap, extra, misplaced file or half-generated state is a
hard failure.

What it does:

1. parses `COUNT` from src/main/java/com/bubbleshield/effect/EffectRegistry.java
   (the single source of truth for the catalogue size),
2. extracts the vanilla include files (fog/globals/dynamictransforms/projection
   .glsl) and core/screenquad.vsh from the Minecraft 26.2 client jar in the
   loom cache,
3. inlines every '#moj_import <minecraft:X.glsl>' occurrence (dropping the
   includes' own '#version' lines, which may not repeat mid-file),
4. writes the stitched copies to a per-invocation tempfile.TemporaryDirectory
   (so concurrent runs can never cross-contaminate), and
5. runs 'glslangValidator -S frag' (resp. '-S vert' for .vsh) on every file,
   in a multiprocessing pool by default (use --serial to disable).

COUNT-derived inventory (fail-closed):

* the bubble dir must contain EXACTLY fx_000..fx_{COUNT-1}.fsh + surface.vsh
  (contiguous, no gaps, no extras);
* the screenfx dir must contain EXACTLY sfx_000..sfx_{COUNT-1}.fsh;
* the post_effect dir must contain EXACTLY effect_00..effect_{COUNT-1}.json;
* a recursive sweep of BOTH shader roots (src/client and src/main
  assets/bubbleshield/shaders) rejects any .fsh/.vsh/.glsl outside those exact
  path sets -- an fx_*.fsh misplaced under screenfx/ (or any stray shader) is
  a hard failure, not a silently-ignored file.

The only escape hatch is the explicit --allow-empty flag, which skips a side's
generated-set checks ONLY while that side has never been generated at all
(no generated files AND no manifest). Without the flag, a missing generated
set fails the run.

Cross-stage LINK validation (per-file -S frag alone cannot catch vsh<->fsh
interface mismatches that fail on real drivers):

* every fx_NNN.fsh is stitched together with bubble/surface.vsh and run
  through 'glslangValidator -l <vert> <frag>' (catches cross-stage type
  mismatches and other link-time errors);
* every sfx_NNN.fsh is linked the same way against the vanilla
  core/screenquad.vsh extracted from the client jar (the vertex shader the
  post_effect pipeline actually pairs it with);
* glslang's GLSL link is lenient about a fragment input that simply has no
  matching vertex output (a rename), so a declared-interface cross-check also
  verifies every fragment 'in' has a vertex 'out' with the same type and name.

On top of that it enforces the generated-shader invariants:

* byte-uniqueness: no two .fsh across the scanned dirs are identical (SHA-256);
* every generated fx_*.fsh carries all three structural layer markers
  ([layer:deep:...], [layer:mid:...], [layer:rim:...]);
* tools/surface_manifest.json exists, has EXACTLY the ids 0..COUNT-1, its
  entries match the fx_* files on disk 1:1, and its (family, warp, deep, rim,
  anim) stack tuples are pairwise distinct (the structural-variety guarantee);
* every generated screen sfx_*.fsh (tools/gen_screen_shaders.py) carries its
  structural stack marker ([screen:family:variant:motion:overlay]) and, after
  comment stripping, declares a `layout(std140) uniform FxConfig` block whose
  members are EXACTLY the four `vec4` declarations Primary, Secondary,
  ParamsA, ParamsB in that order -- no hidden extra members of any type;
* tools/screen_manifest.json exists, has EXACTLY the ids 0..COUNT-1, matches
  the sfx_* files on disk 1:1, its (family, variant, motion, overlay) stacks
  are pairwise distinct, and the classpath copy at
  src/main/resources/assets/bubbleshield/screen_manifest.json is byte-identical;
* every post_effect/effect_NN.json's pass 1 references that effect's own
  bubbleshield:screenfx/sfx_NNN and its FxConfig uniforms are EXACTLY the four
  expected names in the GLSL declaration order, each typed vec4 with a
  4-number value -- std140 wiring is positional, so a JSON/GLSL mismatch would
  silently scramble the uniforms at runtime.

The full scan at COUNT=350 is 351 files under bubble/ (350 fx_*.fsh +
surface.vsh) + 350 under screenfx/ (sfx_*.fsh) = 701 compiles, plus 700
vsh<->fsh link checks.

Exits nonzero when any shader fails to compile or link, any inventory entry is
missing/extra/misplaced, or any invariant is violated. Usage:

    python3 tools/validate_shaders.py [--serial] [--allow-empty]
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
SCREENQUAD_ENTRY = "assets/minecraft/shaders/core/screenquad.vsh"
REGISTRY_JAVA = REPO_ROOT / "src/main/java/com/bubbleshield/effect/EffectRegistry.java"
CLIENT_SHADER_ROOT = REPO_ROOT / "src/client/resources/assets/bubbleshield/shaders"
MAIN_SHADER_ROOT = REPO_ROOT / "src/main/resources/assets/bubbleshield/shaders"
BUBBLE_DIR = CLIENT_SHADER_ROOT / "bubble"
SCREENFX_DIR = MAIN_SHADER_ROOT / "screenfx"
SURFACE_VSH = BUBBLE_DIR / "surface.vsh"
MANIFEST_PATH = REPO_ROOT / "tools/surface_manifest.json"
SCREEN_MANIFEST_PATH = REPO_ROOT / "tools/screen_manifest.json"
CLASSPATH_SCREEN_MANIFEST = REPO_ROOT / "src/main/resources/assets/bubbleshield/screen_manifest.json"
POST_EFFECT_DIR = REPO_ROOT / "src/main/resources/assets/bubbleshield/post_effect"
MOJ_IMPORT = re.compile(r"^#moj_import <minecraft:([a-z_]+)\.glsl>\s*$")
COUNT_RE = re.compile(r"^\s*public static final int COUNT = (\d+);", re.MULTILINE)
FX_NAME = re.compile(r"^fx_(\d{3})\.fsh$")
SFX_NAME = re.compile(r"^sfx_(\d{3})\.fsh$")
LAYER_MARKERS = ("// [layer:deep:", "// [layer:mid:", "// [layer:rim:")
STACK_KEYS = ("family", "warp", "deep", "rim", "anim")
SCREEN_MARKER = re.compile(r"^// \[screen:([a-z0-9]+):([a-z0-9]+):([a-z0-9]+):([a-z0-9]+)\]$", re.MULTILINE)
SCREEN_STACK_KEYS = ("family", "variant", "motion", "overlay")
# The one std140 config block every generated sfx shader must declare, with this
# exact member order (the post_effect JSON uniform order is checked against it).
FXCONFIG_BLOCK = re.compile(r"layout\(std140\)\s*uniform\s+FxConfig\s*\{([^}]*)\}\s*;")
FXCONFIG_EXPECTED = ("Primary", "Secondary", "ParamsA", "ParamsB")
BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.DOTALL)
LINE_COMMENT = re.compile(r"//[^\n]*")
# Global-scope stage in/out declarations (the vanilla includes declare none, so
# scanning the raw sources is exact; function parameters never match because a
# declaration must end in ';' right after the name).
STAGE_IO = re.compile(r"^\s*(?:flat\s+|noperspective\s+|smooth\s+)?(in|out)\s+(\w+)\s+(\w+)\s*;", re.MULTILINE)


def parse_registry_count() -> int:
    """The catalogue size, parsed from EffectRegistry.java (source of truth)."""
    if not REGISTRY_JAVA.is_file():
        sys.exit(f"EffectRegistry.java not found: {REGISTRY_JAVA}")
    match = COUNT_RE.search(REGISTRY_JAVA.read_text(encoding="utf-8"))
    if not match:
        sys.exit(f"could not parse 'COUNT = <n>' from {REGISTRY_JAVA}")
    return int(match.group(1))


def strip_comments(text: str) -> str:
    return LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", text))


def extract_vanilla() -> tuple[dict[str, str], str]:
    """Extracts the vanilla include sources (keyed by name) + screenquad.vsh."""
    if not CLIENT_JAR.is_file():
        sys.exit(f"vanilla client jar not found: {CLIENT_JAR}")

    includes: dict[str, str] = {}
    with zipfile.ZipFile(CLIENT_JAR) as jar:
        for name in INCLUDE_NAMES:
            source = jar.read(f"assets/minecraft/shaders/include/{name}.glsl").decode("utf-8")
            # The includes carry their own '#version' header, which must not be
            # repeated mid-file after inlining.
            lines = [line for line in source.splitlines() if not line.startswith("#version")]
            includes[name] = "\n".join(lines).strip("\n")
        screenquad = jar.read(SCREENQUAD_ENTRY).decode("utf-8")

    return includes, screenquad


def stitch(source: str, includes: dict[str, str], label: str) -> str:
    stitched: list[str] = []
    for line in source.splitlines():
        match = MOJ_IMPORT.match(line.strip())
        if match:
            name = match.group(1)
            if name not in includes:
                sys.exit(f"{label}: unknown moj_import 'minecraft:{name}.glsl' (add it to INCLUDE_NAMES)")
            stitched.append(f"// --- inlined minecraft:{name}.glsl ---")
            stitched.append(includes[name])
            stitched.append(f"// --- end minecraft:{name}.glsl ---")
        else:
            stitched.append(line)

    return "\n".join(stitched) + "\n"


def run_glslang(job: tuple[str, list[str]]) -> tuple[str, bool, str]:
    """Pool worker: runs one glslangValidator invocation (compile or link).

    Takes/returns plain strings so the job stays picklable for multiprocessing.
    """
    label, argv = job
    result = subprocess.run(argv, capture_output=True, text=True)
    return label, result.returncode == 0, (result.stdout + result.stderr).strip()


def interface_errors(vert_source: str, frag_source: str, label: str) -> list[str]:
    """Declared-interface cross-check: every fragment 'in' needs a same-typed
    vertex 'out' of the same name (glslang's GLSL link pass is lenient about
    plain renames, which still fail on real drivers)."""
    vert_outs = {name: type_ for direction, type_, name in STAGE_IO.findall(strip_comments(vert_source))
                 if direction == "out"}
    errors = []
    for direction, type_, name in STAGE_IO.findall(strip_comments(frag_source)):
        if direction != "in":
            continue
        if name not in vert_outs:
            errors.append(f"{label}: fragment input '{type_} {name}' has no matching vertex output")
        elif vert_outs[name] != type_:
            errors.append(f"{label}: fragment input '{type_} {name}' vs vertex output "
                          f"'{vert_outs[name]} {name}' (type mismatch)")
    return errors


def check_inventory(count: int, skip_fx: bool, skip_sfx: bool, skip_post: bool) -> list[str]:
    """COUNT-derived exact file sets + recursive sweep for misplaced shaders."""
    errors: list[str] = []

    def diff_exact(directory: Path, expected: set[str], what: str) -> None:
        if not directory.is_dir():
            errors.append(f"{what} directory not found: {directory}")
            return
        actual = {p.name for p in directory.iterdir() if p.is_file()}
        for missing in sorted(expected - actual):
            errors.append(f"{what}: missing expected file {directory / missing}")
        for extra in sorted(actual - expected):
            errors.append(f"{what}: unexpected file {directory / extra}")

    expected_bubble = {"surface.vsh"}
    if not skip_fx:
        expected_bubble |= {f"fx_{i:03d}.fsh" for i in range(count)}
    diff_exact(BUBBLE_DIR, expected_bubble, f"bubble shader set (COUNT={count})")

    expected_screen = set() if skip_sfx else {f"sfx_{i:03d}.fsh" for i in range(count)}
    diff_exact(SCREENFX_DIR, expected_screen, f"screenfx shader set (COUNT={count})")

    if not skip_post:
        diff_exact(POST_EFFECT_DIR, {f"effect_{i:02d}.json" for i in range(count)},
                   f"post_effect JSON set (COUNT={count})")

    # Recursive sweep: no .fsh/.vsh/.glsl may exist anywhere under the mod's
    # shader roots outside the exact per-directory sets above (a misplaced
    # fx_*.fsh under screenfx/ or a stray nested dir must fail, not be ignored).
    allowed = {(CLIENT_SHADER_ROOT, Path("bubble") / name) for name in expected_bubble}
    allowed |= {(MAIN_SHADER_ROOT, Path("screenfx") / name) for name in expected_screen}
    for root in (CLIENT_SHADER_ROOT, MAIN_SHADER_ROOT):
        if not root.is_dir():
            errors.append(f"shader root not found: {root}")
            continue
        for path in sorted(root.rglob("*")):
            if path.is_file() and path.suffix in (".fsh", ".vsh", ".glsl") \
                    and (root, path.relative_to(root)) not in allowed:
                errors.append(f"unexpected shader file: {path.relative_to(REPO_ROOT)}")

    if not errors:
        print(f"OK    shader/post_effect inventory is exactly COUNT={count} derived from EffectRegistry.java")
    return errors


def check_manifest_ids(manifest: dict, count: int, name: str) -> list[str]:
    """The manifest key set must be exactly the string ids 0..COUNT-1."""
    errors = []
    expected = {str(i) for i in range(count)}
    actual = set(manifest)
    for missing in sorted(expected - actual, key=int):
        errors.append(f"{name} is missing id {missing} (must cover exactly 0..{count - 1})")
    for extra in sorted(actual - expected):
        errors.append(f"{name} has unexpected id {extra} (must cover exactly 0..{count - 1})")
    return errors


def check_generated_invariants(shaders: list[Path], count: int, skip_fx: bool) -> list[str]:
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

    if skip_fx:
        print("NOTE  --allow-empty: no generated fx_*.fsh and no surface_manifest.json yet -- "
              "skipping generated-shader checks (run tools/gen_surface_shaders.py)")
        return errors
    fx_files = sorted(p for p in shaders if FX_NAME.match(p.name))

    # 2. Every generated fx_*.fsh must carry the three structural layer markers.
    for shader in fx_files:
        text = shader.read_text()
        for marker in LAYER_MARKERS:
            if marker not in text:
                errors.append(f"{shader.name}: missing structural marker '{marker}...]'")

    # 3. Manifest exists, covers exactly ids 0..COUNT-1, matches the fx_* file
    # set, and its stack tuples are pairwise distinct.
    if not MANIFEST_PATH.is_file():
        errors.append(f"surface manifest is missing: {MANIFEST_PATH}")
        return errors
    try:
        manifest = json.loads(MANIFEST_PATH.read_text())
    except json.JSONDecodeError as e:
        errors.append(f"{MANIFEST_PATH.name}: invalid JSON ({e})")
        return errors

    errors += check_manifest_ids(manifest, count, MANIFEST_PATH.name)
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


def fxconfig_members(text: str) -> tuple[list[str] | None, str | None]:
    """Parses the FxConfig block after comment stripping.

    Returns (member names, None) when the block is EXACTLY the four expected
    vec4 members in order, else (None, error description). Any declaration that
    is not literally 'vec4 <name>' (hidden extra members of any type included)
    is rejected.
    """
    match = FXCONFIG_BLOCK.search(strip_comments(text))
    if not match:
        return None, "missing 'layout(std140) uniform FxConfig' block"
    members: list[str] = []
    for decl in match.group(1).split(";"):
        decl = " ".join(decl.split())
        if not decl:
            continue
        decl_match = re.fullmatch(r"vec4 (\w+)", decl)
        if not decl_match:
            return None, f"FxConfig block has a non-'vec4 <name>' member declaration: '{decl}'"
        members.append(decl_match.group(1))
    if tuple(members) != FXCONFIG_EXPECTED:
        return None, f"FxConfig members {members} != expected {list(FXCONFIG_EXPECTED)}"
    return members, None


def check_screen_invariants(shaders: list[Path], count: int, skip_sfx: bool) -> list[str]:
    """sfx marker/FxConfig checks + screen manifest agreement + JSON uniforms.

    Mirrors check_generated_invariants for the screen side: every generated
    sfx_*.fsh must carry its structural stack marker and the standardized
    FxConfig block (exactly the four expected vec4 members, in order, after
    comment stripping); tools/screen_manifest.json must cover exactly ids
    0..COUNT-1 and match the sfx_* file set 1:1 with pairwise-distinct stacks
    and a byte-identical classpath copy; and every post_effect/effect_NN.json
    must reference its own sfx shader in pass 1 with FxConfig uniforms that are
    exactly the expected four names in GLSL declaration order, each a vec4 with
    a 4-number value (std140 wiring is positional). Returns errors.
    """
    errors: list[str] = []
    if skip_sfx:
        print("NOTE  --allow-empty: no generated sfx_*.fsh and no screen_manifest.json yet -- "
              "skipping screen-shader checks (run tools/gen_screen_shaders.py)")
        return errors
    sfx_files = sorted(p for p in shaders if SFX_NAME.match(p.name))

    # 1. Every generated sfx_*.fsh: structural marker + standardized FxConfig
    # block with exactly the expected members in the expected order.
    valid_fxconfig: set[str] = set()
    for shader in sfx_files:
        text = shader.read_text()
        if not SCREEN_MARKER.search(text):
            errors.append(f"{shader.name}: missing structural marker '// [screen:...]'")
        members, member_error = fxconfig_members(text)
        if member_error is not None:
            errors.append(f"{shader.name}: {member_error}")
        else:
            valid_fxconfig.add(shader.name)

    # 2. Screen manifest exists, covers exactly ids 0..COUNT-1, matches the
    # sfx_* file set, stacks pairwise distinct, classpath copy byte-identical.
    if not SCREEN_MANIFEST_PATH.is_file():
        errors.append(f"screen manifest is missing: {SCREEN_MANIFEST_PATH}")
        return errors
    try:
        manifest = json.loads(SCREEN_MANIFEST_PATH.read_text())
    except json.JSONDecodeError as e:
        errors.append(f"{SCREEN_MANIFEST_PATH.name}: invalid JSON ({e})")
        return errors

    errors += check_manifest_ids(manifest, count, SCREEN_MANIFEST_PATH.name)
    manifest_files = {entry.get("file") for entry in manifest.values()}
    disk_files = {p.name for p in sfx_files}
    for missing in sorted(manifest_files - disk_files):
        errors.append(f"screen manifest lists {missing} but the file does not exist")
    for extra in sorted(disk_files - manifest_files):
        errors.append(f"{extra} exists on disk but is not in the screen manifest")
    for effect_id, entry in sorted(manifest.items()):
        expected = f"sfx_{int(effect_id):03d}.fsh"
        if entry.get("file") != expected:
            errors.append(f"screen manifest id {effect_id} points at {entry.get('file')}, expected {expected}")

    stacks: dict[tuple, str] = {}
    for effect_id, entry in sorted(manifest.items()):
        stack = tuple(entry.get(key) for key in SCREEN_STACK_KEYS)
        if stack in stacks:
            errors.append(f"screen manifest ids {stacks[stack]} and {effect_id} share the same "
                          f"stack tuple {dict(zip(SCREEN_STACK_KEYS, stack))}")
        else:
            stacks[stack] = effect_id

    if not CLASSPATH_SCREEN_MANIFEST.is_file():
        errors.append(f"classpath screen manifest copy missing: {CLASSPATH_SCREEN_MANIFEST}")
    elif CLASSPATH_SCREEN_MANIFEST.read_bytes() != SCREEN_MANIFEST_PATH.read_bytes():
        errors.append(f"{CLASSPATH_SCREEN_MANIFEST} is not byte-identical to {SCREEN_MANIFEST_PATH} "
                      "(rerun tools/gen_screen_shaders.py)")

    # 3. Each post_effect JSON: pass 1 references the id's own sfx shader and
    # lists EXACTLY the expected FxConfig uniforms in the GLSL declaration
    # order, each typed vec4 with a 4-number value.
    checked_jsons = 0
    for effect_id in sorted(manifest, key=int):
        json_path = POST_EFFECT_DIR / f"effect_{int(effect_id):02d}.json"
        if not json_path.is_file():
            errors.append(f"screen manifest has id {effect_id} but {json_path.name} does not exist")
            continue
        try:
            config = json.loads(json_path.read_text())
        except json.JSONDecodeError as e:
            errors.append(f"{json_path.name}: invalid JSON ({e})")
            continue

        passes = config.get("passes") or []
        if not passes:
            errors.append(f"{json_path.name}: no passes")
            continue
        expected_shader = f"bubbleshield:screenfx/sfx_{int(effect_id):03d}"
        actual_shader = passes[0].get("fragment_shader")
        if actual_shader != expected_shader:
            errors.append(f"{json_path.name}: pass 1 references {actual_shader}, expected {expected_shader}")

        fx_uniforms = (passes[0].get("uniforms") or {}).get("FxConfig")
        if not fx_uniforms:
            errors.append(f"{json_path.name}: pass 1 has no FxConfig uniforms")
            continue
        entry_errors = False
        for uniform in fx_uniforms:
            name = uniform.get("name")
            if uniform.get("type") != "vec4":
                errors.append(f"{json_path.name}: FxConfig uniform {name} has type "
                              f"{uniform.get('type')!r}, expected 'vec4'")
                entry_errors = True
            value = uniform.get("value")
            if not (isinstance(value, list) and len(value) == 4
                    and all(isinstance(component, (int, float)) and not isinstance(component, bool)
                            for component in value)):
                errors.append(f"{json_path.name}: FxConfig uniform {name} value {value!r} "
                              "is not a 4-float vector")
                entry_errors = True
        json_order = [uniform.get("name") for uniform in fx_uniforms]
        if json_order != list(FXCONFIG_EXPECTED):
            errors.append(f"{json_path.name}: FxConfig uniform order {json_order} != "
                          f"expected {list(FXCONFIG_EXPECTED)} (the GLSL block order)")
            entry_errors = True
        sfx_name = f"sfx_{int(effect_id):03d}.fsh"
        if sfx_name not in valid_fxconfig and (SCREENFX_DIR / sfx_name).is_file():
            entry_errors = True  # the GLSL side already failed; do not count this JSON as checked
        if not entry_errors:
            checked_jsons += 1

    print(f"OK    screen-shader invariants ({len(sfx_files)} sfx files, "
          f"{len(manifest)} manifest entries, {len(stacks)} distinct stacks, "
          f"{checked_jsons} JSON FxConfig blocks checked)")
    return errors


def main() -> None:
    parser = argparse.ArgumentParser(description="compile/link-validate the mod's GLSL shaders")
    parser.add_argument("--serial", action="store_true",
                        help="run one glslangValidator at a time instead of using a process pool")
    parser.add_argument("--allow-empty", action="store_true",
                        help="explicit opt-in: skip a side's generated-set checks while that side "
                             "has never been generated (no files AND no manifest). Without this "
                             "flag a missing generated set is a hard failure.")
    args = parser.parse_args()

    count = parse_registry_count()
    includes, screenquad_source = extract_vanilla()

    fx_never_generated = not any(BUBBLE_DIR.glob("fx_*.fsh")) and not MANIFEST_PATH.is_file()
    sfx_never_generated = not any(SCREENFX_DIR.glob("sfx_*.fsh")) and not SCREEN_MANIFEST_PATH.is_file()
    post_never_generated = not POST_EFFECT_DIR.is_dir() or not any(POST_EFFECT_DIR.iterdir())
    skip_fx = args.allow_empty and fx_never_generated
    skip_sfx = args.allow_empty and sfx_never_generated
    skip_post = args.allow_empty and post_never_generated

    inventory_errors = check_inventory(count, skip_fx, skip_sfx, skip_post)

    shaders: list[Path] = []
    for shader_root in (CLIENT_SHADER_ROOT, MAIN_SHADER_ROOT):
        if shader_root.is_dir():
            shaders += sorted(p for p in shader_root.rglob("*") if p.is_file() and p.suffix in (".fsh", ".vsh"))

    with tempfile.TemporaryDirectory(prefix="bubbleshield_shader_validation_") as tmp:
        out_dir = Path(tmp)

        # Stitch every mod shader once; keep raw sources for the interface check.
        stitched_paths: dict[Path, Path] = {}
        raw_sources: dict[Path, str] = {}
        compile_jobs: list[tuple[str, list[str]]] = []
        for shader in shaders:
            stage = "vert" if shader.suffix == ".vsh" else "frag"
            relative = shader.relative_to(REPO_ROOT)
            stitched = out_dir / ("__".join(relative.parts[-2:]) + (".vert" if stage == "vert" else ".frag"))
            raw_sources[shader] = shader.read_text()
            stitched.write_text(stitch(raw_sources[shader], includes, str(relative)))
            stitched_paths[shader] = stitched
            compile_jobs.append((str(relative), ["glslangValidator", "-S", stage, str(stitched)]))

        # Link jobs: every fx against surface.vsh, every sfx against the vanilla
        # core/screenquad.vsh the post_effect pipeline pairs it with.
        screenquad_stitched = out_dir / "vanilla__screenquad.vert"
        screenquad_stitched.write_text(stitch(screenquad_source, includes, SCREENQUAD_ENTRY))
        link_jobs: list[tuple[str, list[str]]] = []
        interface_issues: list[str] = []
        for shader in shaders:
            if FX_NAME.match(shader.name) and SURFACE_VSH in stitched_paths:
                label = f"link surface.vsh <-> {shader.name}"
                link_jobs.append((label, ["glslangValidator", "-l",
                                          str(stitched_paths[SURFACE_VSH]), str(stitched_paths[shader])]))
                interface_issues += interface_errors(raw_sources[SURFACE_VSH], raw_sources[shader], label)
            elif SFX_NAME.match(shader.name):
                label = f"link screenquad.vsh <-> {shader.name}"
                link_jobs.append((label, ["glslangValidator", "-l",
                                          str(screenquad_stitched), str(stitched_paths[shader])]))
                interface_issues += interface_errors(screenquad_source, raw_sources[shader], label)

        jobs = compile_jobs + link_jobs
        if args.serial:
            results = [run_glslang(job) for job in jobs]
        else:
            with multiprocessing.Pool() as pool:
                results = pool.map(run_glslang, jobs)

    compile_failures = 0
    link_failures = 0
    for (label, ok, output), is_link in zip(results, [False] * len(compile_jobs) + [True] * len(link_jobs)):
        if ok:
            print(f"PASS  {label}")
        else:
            if is_link:
                link_failures += 1
            else:
                compile_failures += 1
            print(f"FAIL  {label}")
            if output:
                print("      " + "\n      ".join(output.splitlines()))

    for issue in interface_issues:
        print(f"FAIL  {issue}")

    invariant_errors = inventory_errors
    invariant_errors += check_generated_invariants(shaders, count, skip_fx)
    invariant_errors += check_screen_invariants(shaders, count, skip_sfx)
    for error in invariant_errors:
        print(f"FAIL  {error}")

    print(f"\n{len(compile_jobs) - compile_failures}/{len(compile_jobs)} shaders passed glslangValidator")
    print(f"{len(link_jobs) - link_failures}/{len(link_jobs)} vsh<->fsh links passed glslangValidator -l "
          f"(+{len(interface_issues)} declared-interface issues)")
    if compile_failures or link_failures or interface_issues or invariant_errors:
        sys.exit(1)


if __name__ == "__main__":
    main()

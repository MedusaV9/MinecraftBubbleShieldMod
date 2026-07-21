#!/usr/bin/env python3
"""Dev tool: hard validation gate for every mod GLSL shader + post-effect JSON.

The VM has no GPU driver; the only way to run shaders is the slow llvmpipe
software-GL `runClientGameTest` screenshot harness (see AGENTS.md), so this is
the fast, exhaustive check. One invalid or missing surface shader crashes the
whole client at resource load, so this gate is deliberately fail-CLOSED: the expected file
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
* the beam dir must contain EXACTLY one hand-written beam_<style>.fsh per
  rendered BeamStyle -- the name set is DERIVED from BeamStyle.java's RENDERED
  array (single source of truth) and cross-checked against
  ShieldPipelines.java's BEAM_STYLE_NAMES registration list, so the enum, the
  pipeline registration and the shader files can never drift apart. A small
  NAMED set, one per style, NOT per-effect, so it is deliberately excluded
  from the fx_/sfx_ COUNT contiguity cross-check but still fully compile- and
  link-validated (against bubble/surface.vsh, whose varyings they share);
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

* code-uniqueness: no two .fsh across the scanned dirs may share the same
  COMMENT-STRIPPED, whitespace-normalized GLSL (SHA-256 of the executable
  source, not the raw bytes) -- a unique-id comment or seed annotation can
  never make two copies of the same executable shader count as distinct;
* every generated fx_*.fsh carries all four structural layer markers
  ([layer:deep:...], [layer:mid:...], [layer:rim:...], [layer:motif:...]),
  and the in-file motif marker's (class, envelope) pair must MATCH the
  manifest's recorded per-id motif fingerprint (motif/env), so the marker and
  the manifest can never drift apart;
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

Sampler0 texture-binding contract (fail-closed; glslangValidator compiles a
declared-but-unused sampler cleanly and never sees the Java side or the PNG,
so a renamed RenderSetup key, a half-migrated shader or a missing/corrupt
atlas would otherwise surface only at real client resource load -- which the
GPU-less CI VM cannot run):

* ShieldPipelines.java must reference BindGroupLayouts.SAMPLER0 (the bubble
  snippet's sampler slot) AND bind the atlas via
  RenderSetup .withTexture("Sampler0", <id>) where <id> resolves (directly or
  through the assigned Identifier constant, cross-checked like
  parse_beam_names) to BubbleShield.id("textures/effect/surface_atlas.png");
* every fx_*.fsh must both DECLARE 'uniform sampler2D Sampler0;' and USE it
  ('texture(Sampler0' present after comment stripping) -- a
  declared-but-unused sampler is a hard failure (per-pipeline warning spam /
  a half-migrated shader), as is sampling without a declaration;
* no bubble fx (or beam) shader may carry a 'layout(binding' qualifier (it
  fails to compile at #version 330; the binding comes from the bind group,
  not from GLSL), and the beam shaders must NOT reference ANY SamplerN at
  all (their RenderSetup binds no texture);
* the refraction scene-copy samplers (Sampler1 = scene color, Sampler2 =
  scene depth, provided by SceneCopy.java) are OPTIONAL per fx during the
  incremental rollout, but they are a PAIR: a shader declares BOTH Sampler1
  and Sampler2 (and samples both) or NEITHER -- SceneCopy arms/blits color
  and depth together, so a Sampler1-alone (or Sampler2-alone) shader is a
  hard failure, as is declaring one without sampling it (or sampling without
  declaring). As soon as ANY fx uses the pair ShieldPipelines.java must bind
  BOTH via .withTexture("Sampler1"/"Sampler2", ...) AND reference
  BindGroupLayouts.SAMPLER0_SAMPLER1_SAMPLER2, otherwise the real client dies
  with "Missing sampler" at the first bubble draw (invisible to glslang);
* the atlas itself must ship at
  src/main/resources/assets/bubbleshield/textures/effect/surface_atlas.png as
  a valid PNG with the expected geometry (4096x2048, 8-bit, color-type 6 =
  truecolor+alpha RGBA), and it is decoded END TO END, not just its IHDR: the
  chunk chain must be well-formed, the concatenated IDAT payload must
  zlib-decompress to exactly height * (1 + width*4) filtered bytes and the
  IEND chunk must be present -- a truncated or corrupted atlas upload fails
  here instead of at client resource load. Its .png.mcmeta sibling must be
  valid JSON.

The full scan at COUNT=840 is 841 files under bubble/ (840 fx_*.fsh +
surface.vsh) + 8 under beam/ + 840 under screenfx/ (sfx_*.fsh) = 1689
compiles, plus 1688 vsh<->fsh link checks (the tallies scale with COUNT and
the rendered-BeamStyle set).

Exits nonzero when any shader fails to compile or link, any inventory entry is
missing/extra/misplaced, or any invariant is violated. Usage:

    python3 tools/validate_shaders.py [--serial] [--allow-empty]
"""

import argparse
import hashlib
import json
import multiprocessing
import re
import struct
import subprocess
import sys
import tempfile
import zipfile
import zlib
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
BEAM_DIR = CLIENT_SHADER_ROOT / "beam"
SCREENFX_DIR = MAIN_SHADER_ROOT / "screenfx"
SURFACE_VSH = BUBBLE_DIR / "surface.vsh"
# The projector-beam shaders: a fixed NAMED set (one per rendered BeamStyle in
# com.bubbleshield.shield.BeamStyle), hand-written rather than generated, so
# they sit outside the fx_/sfx_ COUNT contiguity contract but are still
# compile-validated and link-validated against bubble/surface.vsh. The name set
# is DERIVED from BeamStyle.java's RENDERED array (single source of truth, see
# parse_beam_names) and cross-checked against ShieldPipelines.java's
# BEAM_STYLE_NAMES pipeline registration list.
BEAM_STYLE_JAVA = REPO_ROOT / "src/main/java/com/bubbleshield/shield/BeamStyle.java"
SHIELD_PIPELINES_JAVA = REPO_ROOT / "src/client/java/com/bubbleshield/client/render/ShieldPipelines.java"
BEAM_RENDERED_RE = re.compile(r"BeamStyle\[\]\s+RENDERED\s*=\s*\{([^}]*)\}\s*;")
BEAM_PIPELINE_NAMES_RE = re.compile(r"String\[\]\s+BEAM_STYLE_NAMES\s*=\s*\{([^}]*)\}\s*;")
MANIFEST_PATH = REPO_ROOT / "tools/surface_manifest.json"
SCREEN_MANIFEST_PATH = REPO_ROOT / "tools/screen_manifest.json"
CLASSPATH_SCREEN_MANIFEST = REPO_ROOT / "src/main/resources/assets/bubbleshield/screen_manifest.json"
POST_EFFECT_DIR = REPO_ROOT / "src/main/resources/assets/bubbleshield/post_effect"
MOJ_IMPORT = re.compile(r"^#moj_import <minecraft:([a-z_]+)\.glsl>\s*$")
COUNT_RE = re.compile(r"^\s*public static final int COUNT = (\d+);", re.MULTILINE)
FX_NAME = re.compile(r"^fx_(\d{3})\.fsh$")
SFX_NAME = re.compile(r"^sfx_(\d{3})\.fsh$")
LAYER_MARKERS = ("// [layer:deep:", "// [layer:mid:", "// [layer:rim:", "// [layer:motif:")
# The per-id motif fingerprint marker the generator writes into every fx file
# (// [layer:motif:<class>:<envelope>]); its (class, envelope) pair is
# cross-checked against the manifest's recorded (motif, env) so the in-file
# marker and the manifest can never drift apart.
MOTIF_MARKER_RE = re.compile(r"^\s*// \[layer:motif:([a-z0-9]+):([a-z0-9]+)\]\s*$", re.MULTILINE)
STACK_KEYS = ("family", "warp", "deep", "rim", "anim", "motif", "motifN", "env")
# v9 per-EFFECT motif fingerprint: within one family, no two ids may share
# the same (motif class, element count, envelope) triple -- the structural
# per-id distinctness guarantee beyond the palette.
MOTIF_KEYS = ("motif", "motifN", "env")
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

# --- Sampler0 texture-binding contract (see module docstring) ---------------
# The shipped surface atlas every bubble fx samples through Sampler0, plus its
# .mcmeta sibling. Kept under src/main resources (like the post_effect assets)
# so the headless gametest server classpath sees it for the asset-existence
# gametest; this validator checks the same shipped copy.
SURFACE_ATLAS_PNG = REPO_ROOT / "src/main/resources/assets/bubbleshield/textures/effect/surface_atlas.png"
SURFACE_ATLAS_MCMETA = SURFACE_ATLAS_PNG.with_name(SURFACE_ATLAS_PNG.name + ".mcmeta")
# The Identifier path ShieldPipelines must bind via withTexture("Sampler0", ...).
SURFACE_ATLAS_ID_PATH = "textures/effect/surface_atlas.png"
SURFACE_ATLAS_SIZE = (4096, 2048)  # 8x4 grid of 512px tiles (32 tiles)
SURFACE_ATLAS_BIT_DEPTH = 8
SURFACE_ATLAS_COLOR_TYPE = 6  # truecolor + alpha (RGBA); A is the emission mask
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
# The uniform declaration every fx must carry, and the sampling call that must
# consume it (both matched after comment stripping; a declared-but-unused
# sampler is a hard failure, not a warning).
SAMPLER0_DECL_RE = re.compile(r"^\s*uniform\s+sampler2D\s+Sampler0\s*;", re.MULTILINE)
SAMPLER0_USE_RE = re.compile(r"\btexture\s*\(\s*Sampler0\b")
SAMPLER0_ANY_RE = re.compile(r"\bSampler0\b")
# The refraction scene-copy samplers (SceneCopy.java): OPTIONAL per fx during
# the incremental rollout, but per-file fail-closed -- declaring without
# sampling (or sampling without declaring) is a hard failure, exactly like
# Sampler0. Maps sampler name -> the SceneCopy.java Identifier constant that
# ShieldPipelines must bind it to.
SCENE_SAMPLERS = {"Sampler1": "SCENE_COLOR_ID", "Sampler2": "SCENE_DEPTH_ID"}
# Beam shaders bind no texture at all: ANY SamplerN reference (not just
# Sampler0) would die with "Missing sampler" on the real client.
ANY_SAMPLER_RE = re.compile(r"\bSampler[0-9]\b")
# layout(binding = N) needs #version 420 / ARB_shading_language_420pack; at the
# bubble shaders' #version 330 it fails to compile on real drivers. The slot
# comes from the SAMPLER0 bind group, never from GLSL.
LAYOUT_BINDING_RE = re.compile(r"layout\s*\(\s*binding\b")
# ShieldPipelines.java binding-chain patterns (searched after comment
# stripping, so a javadoc mention can never satisfy the check): the direct
# form withTexture("Sampler0", BubbleShield.id("...")), the indirect form
# withTexture("Sampler0", CONSTANT) plus that constant's
# CONSTANT = BubbleShield.id("...") assignment (cross-checked the same way
# parse_beam_names cross-checks BEAM_STYLE_NAMES).
WITH_TEXTURE_DIRECT_RE = re.compile(
    r'\.withTexture\(\s*"Sampler0"\s*,\s*BubbleShield\.id\(\s*"([^"]+)"\s*\)\s*\)')
WITH_TEXTURE_VAR_RE = re.compile(r'\.withTexture\(\s*"Sampler0"\s*,\s*(\w+)\s*\)')


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


def normalized_code(text: str) -> str:
    """The shader's EXECUTABLE source: comments stripped, per-line whitespace
    collapsed, empty lines dropped. The uniqueness check hashes this (not the
    raw bytes), so a unique-id comment or seed annotation can never make two
    copies of the same executable shader count as distinct."""
    lines = (" ".join(line.split()) for line in strip_comments(text).splitlines())
    return "\n".join(line for line in lines if line)


def parse_beam_names() -> tuple[str, ...]:
    """The expected beam shader file names, derived from BeamStyle.java's
    RENDERED array (the single source of truth) and cross-checked against
    ShieldPipelines.java's BEAM_STYLE_NAMES pipeline registration list. Exits
    when either cannot be parsed or when they disagree, so the enum, the
    pipeline registration and the shader file set can never silently drift."""
    if not BEAM_STYLE_JAVA.is_file():
        sys.exit(f"BeamStyle.java not found: {BEAM_STYLE_JAVA}")
    match = BEAM_RENDERED_RE.search(strip_comments(BEAM_STYLE_JAVA.read_text(encoding="utf-8")))
    if not match:
        sys.exit(f"could not parse 'BeamStyle[] RENDERED = {{...}}' from {BEAM_STYLE_JAVA}")
    styles = [entry.strip().lower() for entry in match.group(1).split(",") if entry.strip()]
    if not styles:
        sys.exit(f"BeamStyle.RENDERED parsed empty from {BEAM_STYLE_JAVA}")

    if not SHIELD_PIPELINES_JAVA.is_file():
        sys.exit(f"ShieldPipelines.java not found: {SHIELD_PIPELINES_JAVA}")
    pipelines_match = BEAM_PIPELINE_NAMES_RE.search(
        strip_comments(SHIELD_PIPELINES_JAVA.read_text(encoding="utf-8")))
    if not pipelines_match:
        sys.exit(f"could not parse 'String[] BEAM_STYLE_NAMES = {{...}}' from {SHIELD_PIPELINES_JAVA}")
    pipeline_names = [entry.strip().strip('"') for entry in pipelines_match.group(1).split(",") if entry.strip()]
    if pipeline_names != styles:
        sys.exit(f"BeamStyle.RENDERED {styles} != ShieldPipelines.BEAM_STYLE_NAMES {pipeline_names} "
                 "(the enum and the pipeline registration drifted apart)")
    return tuple(f"beam_{name}.fsh" for name in styles)


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


def check_inventory(count: int, beam_names: tuple[str, ...], skip_fx: bool, skip_sfx: bool, skip_post: bool) -> list[str]:
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

    # The beam set is COUNT-independent (one shader per rendered BeamStyle,
    # derived from BeamStyle.java), but still exact: a missing style crashes
    # the client's pipeline registration at resource load, an extra file is a
    # stray.
    expected_beam = set(beam_names)
    diff_exact(BEAM_DIR, expected_beam, f"beam shader set (BeamStyle.RENDERED, {len(beam_names)} styles)")

    expected_screen = set() if skip_sfx else {f"sfx_{i:03d}.fsh" for i in range(count)}
    diff_exact(SCREENFX_DIR, expected_screen, f"screenfx shader set (COUNT={count})")

    if not skip_post:
        diff_exact(POST_EFFECT_DIR, {f"effect_{i:02d}.json" for i in range(count)},
                   f"post_effect JSON set (COUNT={count})")

    # Recursive sweep: no .fsh/.vsh/.glsl may exist anywhere under the mod's
    # shader roots outside the exact per-directory sets above (a misplaced
    # fx_*.fsh under screenfx/ or a stray nested dir must fail, not be ignored).
    allowed = {(CLIENT_SHADER_ROOT, Path("bubble") / name) for name in expected_bubble}
    allowed |= {(CLIENT_SHADER_ROOT, Path("beam") / name) for name in expected_beam}
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
    """Code-uniqueness + fx layer markers + manifest agreement. Returns errors."""
    errors: list[str] = []

    # 1. No two .fsh anywhere in the scanned dirs may share the same
    # COMMENT-STRIPPED, whitespace-normalized source: hashing the raw bytes
    # would let two executably-identical shaders pass as "distinct" on the
    # strength of a unique-id comment or seed annotation alone.
    by_digest: dict[str, Path] = {}
    for shader in shaders:
        if shader.suffix != ".fsh":
            continue
        digest = hashlib.sha256(normalized_code(shader.read_text(encoding="utf-8")).encode("utf-8")).hexdigest()
        if digest in by_digest:
            errors.append(f"code-identical shaders (identical after comment stripping): "
                          f"{by_digest[digest].name} == {shader.name}")
        else:
            by_digest[digest] = shader

    if skip_fx:
        print("NOTE  --allow-empty: no generated fx_*.fsh and no surface_manifest.json yet -- "
              "skipping generated-shader checks (run tools/gen_surface_shaders.py)")
        return errors
    fx_files = sorted(p for p in shaders if FX_NAME.match(p.name))

    # 2. Every generated fx_*.fsh must carry the four structural layer markers;
    # the motif marker's (class, envelope) pair is remembered for the manifest
    # cross-check below.
    file_motifs: dict[str, tuple[str, str]] = {}
    for shader in fx_files:
        text = shader.read_text()
        for marker in LAYER_MARKERS:
            if marker not in text:
                errors.append(f"{shader.name}: missing structural marker '{marker}...]'")
        motif_match = MOTIF_MARKER_RE.search(text)
        if motif_match:
            file_motifs[shader.name] = (motif_match.group(1), motif_match.group(2))

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

    # v9 per-EFFECT motif fingerprint distinctness: the (motif, motifN, env)
    # triple must be present on every entry and pairwise distinct WITHIN each
    # family -- two effects of one family must differ in structure AND
    # motion, not just hue. The in-file '// [layer:motif:<class>:<envelope>]'
    # marker must also MATCH the manifest's recorded (motif, env) pair, so the
    # marker and the manifest can never drift apart (e.g. a stale manifest
    # over regenerated files, or a hand-edited fx file).
    family_motifs: dict[tuple, str] = {}
    motif_markers_checked = 0
    fx_names_on_disk = {p.name for p in fx_files}
    for effect_id, entry in sorted(manifest.items()):
        triple = tuple(entry.get(key) for key in MOTIF_KEYS)
        if any(value is None for value in triple):
            errors.append(f"manifest id {effect_id} is missing a motif fingerprint key "
                          f"(expected all of {list(MOTIF_KEYS)})")
            continue
        fam_key = (entry.get("family"),) + triple
        if fam_key in family_motifs:
            errors.append(f"manifest ids {family_motifs[fam_key]} and {effect_id} (family "
                          f"{entry.get('family')}) share the same motif fingerprint "
                          f"{dict(zip(MOTIF_KEYS, triple))}")
        else:
            family_motifs[fam_key] = effect_id

        file_name = entry.get("file")
        if file_name not in fx_names_on_disk:
            continue  # already reported as a missing file above
        marker = file_motifs.get(file_name)
        if marker is None:
            errors.append(f"{file_name}: manifest id {effect_id} records motif "
                          f"({entry.get('motif')}, {entry.get('env')}) but the file has no "
                          "parseable '// [layer:motif:<class>:<envelope>]' marker")
        elif marker != (entry.get("motif"), entry.get("env")):
            errors.append(f"{file_name}: in-file motif marker '// [layer:motif:{marker[0]}:{marker[1]}]' "
                          f"does not match manifest id {effect_id}'s recorded motif "
                          f"({entry.get('motif')}, {entry.get('env')})")
        else:
            motif_markers_checked += 1

    print(f"OK    generated-shader invariants ({len(fx_files)} fx files, "
          f"{len(manifest)} manifest entries, {len(stacks)} distinct stacks, "
          f"{len(family_motifs)} per-family-distinct motif fingerprints, "
          f"{motif_markers_checked} in-file motif markers match the manifest)")
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


def parse_png_ihdr(data: bytes) -> tuple[int, int, int, int] | str:
    """(width, height, bit depth, color type) from the IHDR chunk, or an error
    description string when the data is not a structurally valid PNG header."""
    if data[:8] != PNG_SIGNATURE:
        return "does not start with the 8-byte PNG signature"
    if len(data) < 33:  # signature + IHDR length/type/13-byte payload/CRC
        return "truncated before a complete IHDR chunk"
    length, chunk_type = struct.unpack(">I4s", data[8:16])
    if chunk_type != b"IHDR" or length != 13:
        return f"first chunk is {chunk_type!r} with length {length}, expected a 13-byte IHDR"
    width, height, bit_depth, color_type = struct.unpack(">IIBB", data[16:26])
    return width, height, bit_depth, color_type


# PNG color type -> samples per pixel, for the decoded-raster size check.
PNG_CHANNELS = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}


def parse_png(data: bytes) -> tuple[int, int, int, int] | str:
    """Full structural decode of a PNG (not just the IHDR): the chunk chain
    must be well-formed with valid per-chunk CRCs and terminated by IEND, and
    the concatenated IDAT payload must zlib-decompress to EXACTLY the
    height * (1 filter byte + scanline bytes) the IHDR geometry implies --
    a truncated or bit-corrupted atlas passes an IHDR-only parse but fails
    here instead of at client resource load. Returns (width, height, bit
    depth, color type) on success, else an error description string."""
    header = parse_png_ihdr(data)
    if isinstance(header, str):
        return header
    width, height, bit_depth, color_type = header
    compression, filter_method, interlace = struct.unpack(">BBB", data[26:29])
    if compression != 0 or filter_method != 0:
        return f"IHDR compression/filter method {compression}/{filter_method}, expected 0/0"
    if interlace != 0:
        # The Adam7 pass layout has a different decompressed size; the
        # generator always writes non-interlaced scanlines.
        return f"IHDR interlace method {interlace}, expected 0 (non-interlaced)"
    channels = PNG_CHANNELS.get(color_type)
    if channels is None:
        return f"unknown PNG color type {color_type}"

    idat = bytearray()
    saw_iend = False
    offset = 8
    while offset < len(data):
        if offset + 8 > len(data):
            return f"truncated chunk header at byte {offset}"
        length, chunk_type = struct.unpack(">I4s", data[offset:offset + 8])
        chunk_name = chunk_type.decode("latin-1")
        end = offset + 8 + length + 4  # header + payload + CRC
        if end > len(data):
            return f"truncated {chunk_name} chunk at byte {offset} (file ends {end - len(data)} bytes early)"
        payload = data[offset + 8:offset + 8 + length]
        (crc,) = struct.unpack(">I", data[end - 4:end])
        if zlib.crc32(chunk_type + payload) != crc:
            return f"{chunk_name} chunk at byte {offset} fails its CRC (corrupted)"
        if chunk_type == b"IDAT":
            idat += payload
        elif chunk_type == b"IEND":
            saw_iend = True
            if end != len(data):
                return f"{len(data) - end} trailing bytes after the IEND chunk"
            break
        offset = end
    if not saw_iend:
        return "no IEND chunk (truncated PNG)"
    if not idat:
        return "no IDAT chunk (no image data)"

    try:
        raster = zlib.decompress(bytes(idat))
    except zlib.error as e:
        return f"IDAT payload fails to zlib-decompress ({e})"
    scanline_bytes = (width * channels * bit_depth + 7) // 8
    expected = height * (1 + scanline_bytes)
    if len(raster) != expected:
        return (f"IDAT decompresses to {len(raster)} bytes, expected {expected} "
                f"({height} scanlines x (1 filter byte + {scanline_bytes} pixel bytes))")
    return width, height, bit_depth, color_type


def check_texture_binding(shaders: list[Path], beam_names: tuple[str, ...]) -> list[str]:
    """The Sampler0 texture-binding contract, fail-closed (see module
    docstring). glslangValidator compiles a declared-but-unused sampler
    cleanly and never sees ShieldPipelines.java or the shipped PNG, so a
    renamed withTexture key, a half-migrated shader or a missing/corrupt atlas
    would otherwise surface only at real client resource load -- which the
    GPU-less CI VM cannot run. Returns errors."""
    errors: list[str] = []

    # 1. The Java side of the chain: the bubble snippet must carry the
    # SAMPLER0 bind group and the RenderSetup must bind the shipped atlas to
    # the "Sampler0" key (comment-stripped source, so documentation mentions
    # can never satisfy the check).
    bound_path = None
    pipelines_src = None
    if not SHIELD_PIPELINES_JAVA.is_file():
        errors.append(f"ShieldPipelines.java not found: {SHIELD_PIPELINES_JAVA}")
    else:
        pipelines_src = strip_comments(SHIELD_PIPELINES_JAVA.read_text(encoding="utf-8"))
        if "BindGroupLayouts.SAMPLER0" not in pipelines_src:
            errors.append("ShieldPipelines.java no longer references BindGroupLayouts.SAMPLER0 "
                          "(the bubble snippet lost its sampler bind group; every fx samples Sampler0)")
        direct = WITH_TEXTURE_DIRECT_RE.search(pipelines_src)
        if direct:
            bound_path = direct.group(1)
        else:
            via_var = WITH_TEXTURE_VAR_RE.search(pipelines_src)
            if not via_var:
                errors.append("ShieldPipelines.java has no RenderSetup '.withTexture(\"Sampler0\", ...)' "
                              "call (the surface atlas is no longer bound to the bubble pipelines; "
                              "a renamed key fails at client resource load, invisible to glslang)")
            else:
                constant = via_var.group(1)
                assign = re.search(
                    rf'\b{re.escape(constant)}\s*=\s*BubbleShield\.id\(\s*"([^"]+)"\s*\)', pipelines_src)
                if not assign:
                    errors.append(f"ShieldPipelines.java: withTexture(\"Sampler0\", {constant}) found, but "
                                  f"{constant} is not assigned from BubbleShield.id(\"...\") -- cannot "
                                  "verify the bound atlas path (the Identifier constant drifted)")
                else:
                    bound_path = assign.group(1)
        if bound_path is not None and bound_path != SURFACE_ATLAS_ID_PATH:
            errors.append(f"ShieldPipelines.java: withTexture(\"Sampler0\", ...) binds \"{bound_path}\", "
                          f"expected \"{SURFACE_ATLAS_ID_PATH}\"")

    # 2. The GLSL side: every fx must both declare AND sample Sampler0 (a
    # declared-but-unused sampler is per-pipeline warning spam and the
    # signature of a half-migrated shader; sampling without a declaration
    # cannot compile), the OPTIONAL scene-copy samplers obey the same
    # declare<->use pairing per file, no fx/beam shader may use
    # layout(binding, and the beam shaders must stay free of ANY SamplerN
    # (their RenderSetup binds no texture).
    fx_files = sorted(p for p in shaders if FX_NAME.match(p.name))
    beam_files = sorted(p for p in shaders if p.name in beam_names)
    scene_sampler_users = 0
    for shader in fx_files:
        text = strip_comments(shader.read_text(encoding="utf-8"))
        declared = SAMPLER0_DECL_RE.search(text) is not None
        used = SAMPLER0_USE_RE.search(text) is not None
        if declared and not used:
            errors.append(f"{shader.name}: declares 'uniform sampler2D Sampler0;' but never calls "
                          "'texture(Sampler0' (declared-but-unused sampler: per-pipeline warning "
                          "spam / half-migrated shader)")
        elif used and not declared:
            errors.append(f"{shader.name}: samples Sampler0 without the "
                          "'uniform sampler2D Sampler0;' declaration")
        elif not declared:
            errors.append(f"{shader.name}: missing 'uniform sampler2D Sampler0;' + 'texture(Sampler0' "
                          "(every bubble fx must sample the shared surface atlas)")
        # Scene-copy samplers: optional per fx (mixed rollout state is legal),
        # but a declared sampler must be used and vice versa -- same fail-closed
        # shape as Sampler0 -- AND the two are a PAIR: SceneCopy arms/blits the
        # scene color and depth together, so a shader references {Sampler1,
        # Sampler2} together or neither. Sampler1-alone (or Sampler2-alone) is
        # a hard failure, not a legal half-migrated state.
        scene_referenced: dict[str, bool] = {}
        scene_complete = True
        for sampler in SCENE_SAMPLERS:
            scene_declared = re.search(
                rf"^\s*uniform\s+sampler2D\s+{sampler}\s*;", text, re.MULTILINE) is not None
            scene_used = re.search(rf"\btexture\s*\(\s*{sampler}\b", text) is not None
            if scene_declared and not scene_used:
                errors.append(f"{shader.name}: declares 'uniform sampler2D {sampler};' but never calls "
                              f"'texture({sampler}' (declared-but-unused sampler)")
            elif scene_used and not scene_declared:
                errors.append(f"{shader.name}: samples {sampler} without the "
                              f"'uniform sampler2D {sampler};' declaration")
            scene_referenced[sampler] = scene_declared or scene_used
            scene_complete &= scene_declared and scene_used
        if any(scene_referenced.values()) and not all(scene_referenced.values()):
            present = ", ".join(s for s, r in sorted(scene_referenced.items()) if r)
            absent = ", ".join(s for s, r in sorted(scene_referenced.items()) if not r)
            errors.append(f"{shader.name}: references {present} but not {absent} -- the SceneCopy "
                          "scene-copy contract is {Sampler1, Sampler2} together or neither "
                          "(color and depth are armed/blitted as a pair)")
        if scene_complete:
            scene_sampler_users += 1
        if LAYOUT_BINDING_RE.search(text):
            errors.append(f"{shader.name}: 'layout(binding' qualifier found (fails to compile at "
                          "#version 330; the slot comes from the sampler bind group, not GLSL)")
    for shader in beam_files:
        text = strip_comments(shader.read_text(encoding="utf-8"))
        if ANY_SAMPLER_RE.search(text):
            errors.append(f"{shader.name}: references a SamplerN uniform, but the beam RenderSetup "
                          "binds no texture (BEAM_SNIPPET has no sampler bind group)")
        if LAYOUT_BINDING_RE.search(text):
            errors.append(f"{shader.name}: 'layout(binding' qualifier found (fails to compile at "
                          "#version 330; beam pipelines bind no sampler at all)")

    # 2b. As soon as ANY fx samples the scene copy, ShieldPipelines.java must
    # carry the 3-sampler bind group AND bind both SceneCopy identifiers, or
    # the real client dies with "Missing sampler" at the first bubble draw
    # (never visible to glslang, which compiles each stage in isolation).
    if scene_sampler_users and pipelines_src is not None:
        if "BindGroupLayouts.SAMPLER0_SAMPLER1_SAMPLER2" not in pipelines_src:
            errors.append(f"{scene_sampler_users} fx shader(s) sample the scene copy, but "
                          "ShieldPipelines.java does not reference "
                          "BindGroupLayouts.SAMPLER0_SAMPLER1_SAMPLER2 (the bubble snippet must "
                          "expose the Sampler1/Sampler2 slots)")
        for sampler, constant in SCENE_SAMPLERS.items():
            if not re.search(rf'\.withTexture\(\s*"{sampler}"\s*,\s*SceneCopy\.{constant}\s*,', pipelines_src):
                errors.append(f"{scene_sampler_users} fx shader(s) sample the scene copy, but "
                              f"ShieldPipelines.java has no '.withTexture(\"{sampler}\", "
                              f"SceneCopy.{constant}, ...)' binding")

    # 3. The shipped atlas: a fully-decodable PNG (well-formed chunk chain,
    # CRCs, IEND, IDAT zlib-decompresses to the exact raster size -- not just
    # a plausible IHDR) with the exact geometry the shaders' 8x4 tile math
    # assumes, plus a valid-JSON .mcmeta sibling.
    atlas_geometry = None
    if not SURFACE_ATLAS_PNG.is_file():
        errors.append(f"surface atlas is missing: {SURFACE_ATLAS_PNG.relative_to(REPO_ROOT)} "
                      "(every bubble fx samples it; a missing texture fails at client resource load)")
    else:
        parsed = parse_png(SURFACE_ATLAS_PNG.read_bytes())
        if isinstance(parsed, str):
            errors.append(f"{SURFACE_ATLAS_PNG.relative_to(REPO_ROOT)}: {parsed}")
        else:
            width, height, bit_depth, color_type = parsed
            atlas_geometry = f"{width}x{height}"
            if (width, height) != SURFACE_ATLAS_SIZE:
                errors.append(f"{SURFACE_ATLAS_PNG.name}: {width}x{height}, expected "
                              f"{SURFACE_ATLAS_SIZE[0]}x{SURFACE_ATLAS_SIZE[1]} "
                              "(the shaders' 8x4 tile UV math assumes this geometry)")
            if bit_depth != SURFACE_ATLAS_BIT_DEPTH or color_type != SURFACE_ATLAS_COLOR_TYPE:
                errors.append(f"{SURFACE_ATLAS_PNG.name}: bit depth {bit_depth} / color type {color_type}, "
                              f"expected {SURFACE_ATLAS_BIT_DEPTH} / {SURFACE_ATLAS_COLOR_TYPE} "
                              "(truecolor+alpha RGBA; the A channel is the emission mask)")
    if not SURFACE_ATLAS_MCMETA.is_file():
        errors.append(f"surface atlas mcmeta is missing: {SURFACE_ATLAS_MCMETA.relative_to(REPO_ROOT)}")
    else:
        try:
            json.loads(SURFACE_ATLAS_MCMETA.read_text(encoding="utf-8"))
        except json.JSONDecodeError as e:
            errors.append(f"{SURFACE_ATLAS_MCMETA.name}: invalid JSON ({e})")

    if not errors:
        print(f"OK    Sampler0 texture-binding contract ({len(fx_files)} fx declare+sample Sampler0, "
              f"{scene_sampler_users} fx sample the Sampler1/Sampler2 scene copy, "
              f"{len(beam_files)} beam shaders sampler-free, no layout(binding, "
              f"atlas {atlas_geometry} RGBA fully decoded + valid mcmeta, "
              f"ShieldPipelines binds \"{bound_path}\")")
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
    beam_names = parse_beam_names()
    includes, screenquad_source = extract_vanilla()

    fx_never_generated = not any(BUBBLE_DIR.glob("fx_*.fsh")) and not MANIFEST_PATH.is_file()
    sfx_never_generated = not any(SCREENFX_DIR.glob("sfx_*.fsh")) and not SCREEN_MANIFEST_PATH.is_file()
    post_never_generated = not POST_EFFECT_DIR.is_dir() or not any(POST_EFFECT_DIR.iterdir())
    skip_fx = args.allow_empty and fx_never_generated
    skip_sfx = args.allow_empty and sfx_never_generated
    skip_post = args.allow_empty and post_never_generated

    inventory_errors = check_inventory(count, beam_names, skip_fx, skip_sfx, skip_post)

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
            if (FX_NAME.match(shader.name) or shader.name in beam_names) and SURFACE_VSH in stitched_paths:
                # The beam shaders pair with the same bubble/surface.vsh
                # passthrough as the per-effect fx_* set (BEAM_SNIPPET reuses it).
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
    # The Sampler0/atlas contract runs unconditionally: ShieldPipelines.java
    # and the shipped atlas are hand-maintained (not generated), so even an
    # --allow-empty run must verify them. With no fx generated yet the per-fx
    # loop is simply empty.
    invariant_errors += check_texture_binding(shaders, beam_names)
    for error in invariant_errors:
        print(f"FAIL  {error}")

    print(f"\n{len(compile_jobs) - compile_failures}/{len(compile_jobs)} shaders passed glslangValidator")
    print(f"{len(link_jobs) - link_failures}/{len(link_jobs)} vsh<->fsh links passed glslangValidator -l "
          f"(+{len(interface_issues)} declared-interface issues)")
    if compile_failures or link_failures or interface_issues or invariant_errors:
        sys.exit(1)


if __name__ == "__main__":
    main()

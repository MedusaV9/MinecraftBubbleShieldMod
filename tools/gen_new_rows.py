#!/usr/bin/env python3
"""Dev tool: deterministically generates the 420 NEW catalogue rows (ids 420..839)
for the 840-effect flip, plus their lang entries and the gen_post_effects.py
EFFECTS mirror tuples.

The frozen ground truth is EffectRegistry.java rows 0..419: this script PARSES
them to collect every used (primary, secondary) palette pair, every used
(sound, pitch, period) ambient triple and the set of ambient sound ids (the
guaranteed-valid sound POOL), then emits 420 new rows on the fixed scheme
below. Everything is pure arithmetic on the row index i (no RNG), so reruns
are byte-stable.

Row scheme for i in 0..419 (id = 420 + i):
  behavior = NEW_BEHAVIORS[i % 60], variant = i // 60   (exact 60 x 7 cover)
  surface  = NEW_SURFACES[i % 20]
  screen   = SCREEN28[i % 28]   (20 existing families + the 8 v5 ones)
  guard    = GUARDS[(i + i//7) % 7]
  context  = CONTEXTS[(i + i//6 + i//60) % 6]
  palette  = deterministic HSV pair per (family nf = i//5, shade s = i%5),
             nudged on collision until globally unique
  sound    = POOL[i % len(POOL)] with a deterministically probed
             (pitch, period) making the triple globally unique

The guard/context formulas are deliberately NOT the plain i%7 / i%6 residues:
60 % 6 == 0 would lock every behavior to a single context, and 7 | 28 would
make the guard a pure function of the screen family. Mixing in i//7 (resp.
i//6 and the variant i//60) de-correlates the axes: every screen family sees
all 7 guards (for i = s + 28t the guard walks (.. + 4t) % 7, and 4 is coprime
to 7) and every behavior's 7 rows span all 6 contexts (for i = b + 60k the
context walks (.. + 5k) % 6, and 5 is coprime to 6). Both properties are
machine-asserted below before anything is emitted.

Validity against EffectRegistry.validate() (all machine-asserted below before
anything is emitted):
  * 5 consecutive i always have distinct residues mod 20/28/60, so no
    per-(id/5)-family repeats of surface/screen/behavior;
  * a (surface, screen) pair repeats iff i == j (mod lcm(20,28) = 140), so
    each new pair occurs exactly 420/140 = 3 times (== the <=3 cap), and new
    surfaces never appear in old rows so no old pair count moves;
  * every new SurfaceTemplate and every SCREEN_TEMPLATES entry is used;
  * each new behavior is used exactly 7 times covering variants 0..6.

Outputs (under --out-dir, default /tmp/newrows):
  rows_java.txt        the 420 `all.add(row(...));` lines + family comments
  effects_mirror.txt   the 420 gen_post_effects.py EFFECTS tuples
  lang_en_effects.json / lang_de_effects.json   names + descs for 420..839
  lang_en_axis.json / lang_de_axis.json         20 surface + 60 behavior keys

Usage:
    python3 tools/gen_new_rows.py [--out-dir DIR]
"""

import argparse
import colorsys
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
REGISTRY_JAVA = REPO_ROOT / "src/main/java/com/bubbleshield/effect/EffectRegistry.java"
LANG_EN = REPO_ROOT / "src/main/resources/assets/bubbleshield/lang/en_us.json"
LANG_DE = REPO_ROOT / "src/main/resources/assets/bubbleshield/lang/de_de.json"

BASE_COUNT = 420  # the frozen rows 0..419
NEW_COUNT = 420   # the rows this script emits (ids 420..839)

# The 60 new behavior ids, in the EXACT registerAll() append order; this order
# IS the row-assignment index (behavior = NEW_BEHAVIORS[i % 60]).
NEW_BEHAVIORS = [
    "banshee_wails", "ghost_riders", "spirit_lanterns", "haunted_portraits",
    "poltergeist_toss", "wailing_choir", "grave_hands", "ecto_mist_maze",
    "phantom_bells", "seance_table", "ghost_wolves", "spectral_stag",
    "wisp_owls", "bone_fish", "carrion_crows", "styx_ferry", "soul_wells",
    "chained_specters", "reaper_scythe", "purgatory_queue", "spirit_rain",
    "ecto_fog_banks", "aurora_ghosts", "static_haunt", "moonbeam_shafts",
    "creeper_effigies", "enderman_stalkers", "skeleton_army", "slime_ghosts",
    "drowned_procession", "constellation_wheel", "comet_orrery",
    "eclipse_disc", "meteor_shower_veil", "zodiac_beams", "dryad_bloom",
    "mushroom_ring_sprites", "pollen_elementals", "vine_serpents",
    "seasons_wheel", "clockwork_gears", "rune_forge", "alchemy_circles",
    "mirror_maze", "arcane_turbines", "abyssal_jellies", "void_rifts_inside",
    "leviathan_shadow", "anglerfish_lures", "singularity_heart",
    "lantern_festival", "firework_regatta", "ghost_masquerade",
    "drumline_golems", "chime_curtains", "sentinel_totems", "valkyrie_patrol",
    "shield_maidens", "cerberus_watch", "genie_plumes",
]

# The 20 new surface family names, in the EXACT SurfaceTemplate /
# gen_surface_shaders.py FAMILIES append order.
NEW_SURFACES = [
    "spectralveil", "raymarchfog", "prismdisperse", "holoparallax",
    "orbittrap", "crystalsdf", "fluidink", "irisfilm", "aethersmoke",
    "stainedglass", "phantomecho", "gravlens", "mycelia", "solarflare",
    "deepice", "runecircuit", "oilslick", "plasmaglobe", "ectoplasm",
    "voidrift",
]

# The 20 existing SCREEN_TEMPLATES names (EffectRegistry declaration order)
# followed by the 8 new v5 families.
SCREEN28 = [
    "tint", "wobble", "vignette", "chroma", "pixelate", "desat",
    "bloomglow", "ripple", "scanlines", "edgeglow", "frostlens", "heathaze",
    "posterize", "radialblur", "glitch", "duotone",
    "kaleido", "huedrift", "dreamblur", "moire",
    "spectral", "aberration", "underwater", "thermal",
    "sketch", "starburst", "vhs", "gloom",
]

GUARDS = ["NONE", "GUST", "SLOW", "BLIND", "DARK", "GLOW", "STING"]
CONTEXTS = ["NONE", "NIGHT_BLOOM", "STORM_CHARGED", "CROWD_SCALE",
            "LOW_HEALTH_FRENZY", "HEALTH_HUE"]

PITCHES = ["0.6", "0.7", "0.8", "0.9", "1.0", "1.1", "1.2", "1.3", "1.4"]


def guard_index(i: int) -> int:
    """Guard for row index i. NOT i % 7 (7 | 28 would make the guard a pure
    function of the screen family i % 28); the i//7 term makes every screen
    family cycle through all 7 guards."""
    return (i + i // 7) % 7


def context_index(i: int) -> int:
    """Context for row index i. NOT i % 6 (6 | 60 would lock every behavior
    i % 60 to a single context); the i//6 + i//60 terms make every behavior's
    7 variant rows (i = b + 60k) walk (.. + 5k) % 6, spanning all 6 contexts."""
    return (i + i // 6 + i // 60) % 6


# ---------------------------------------------------------------------------
# Theme + naming word lists. 84 new color families (F84..F167), organized as 7
# cycles of the 12 behavior blocks (each family's 5 rows span 5 consecutive
# entries of NEW_BEHAVIORS, so nf % 12 tracks the behavior block the family
# leans on). Every EN theme word and every DE theme word is distinct, and the
# names are "<theme word> <shade word>" (EN) / a proper German compound of
# theme + shade (DE, see de_compound_name), so all 420 names per language are
# pairwise distinct by construction (still checked against the existing 420
# below, with a deterministic nudge on collision).
# ---------------------------------------------------------------------------
THEMES = [
    # cycle A (F84..F95)
    ("Manor", "Herrenhaus"), ("Chapel", "Kapelle"), ("Menagerie", "Menagerie"),
    ("Ferryman", "Fährmann"), ("Spiritstorm", "Geistersturm"), ("Effigy", "Bildnis"),
    ("Orrery", "Planetarium"), ("Dryad", "Dryade"), ("Clockwork", "Uhrwerk"),
    ("Abyss", "Abgrund"), ("Regatta", "Regatta"), ("Totem", "Totem"),
    # cycle B (F96..F107)
    ("Gallows", "Galgen"), ("Requiem", "Requiem"), ("Stag", "Hirsch"),
    ("Styx", "Styx"), ("Mistfall", "Nebelfall"), ("Marionette", "Marionette"),
    ("Zodiac", "Tierkreis"), ("Greenwood", "Grünwald"), ("Athanor", "Athanor"),
    ("Leviathan", "Leviathan"), ("Masquerade", "Maskenball"), ("Valkyrie", "Walküre"),
    # cycle C (F108..F119)
    ("Haunting", "Spuk"), ("Belltower", "Glockenturm"), ("Crowfield", "Krähenfeld"),
    ("Reaper", "Schnitter"), ("Moonshaft", "Mondschacht"), ("Boneyard", "Knochenhof"),
    ("Cometfall", "Kometenfall"), ("Pollen", "Blütenstaub"), ("Runesmith", "Runenschmied"),
    ("Anglerfish", "Anglerfisch"), ("Drumline", "Trommelzug"), ("Cerberus", "Zerberus"),
    # cycle D (F120..F131)
    ("Portrait", "Porträt"), ("Gravebloom", "Grabblüte"), ("Owllight", "Eulenlicht"),
    ("Purgatory", "Fegefeuer"), ("Stormghost", "Sturmgeist"), ("Slimewisp", "Schleimirrlicht"),
    ("Eclipse", "Finsternis"), ("Vineheart", "Rankenherz"), ("Alembic", "Destille"),
    ("Singularity", "Singularität"), ("Skyrocket", "Himmelsrakete"), ("Shieldmaiden", "Schildmaid"),
    # cycle E (F132..F143)
    ("Candleghost", "Kerzengeist"), ("Sexton", "Küster"), ("Bonefish", "Grätenfisch"),
    ("Soulwell", "Seelenbrunnen"), ("Aurorawraith", "Polargeist"), ("Creeperhusk", "Creeperhülle"),
    ("Starwheel", "Sternenrad"), ("Solstice", "Sonnenwende"), ("Mirrormaze", "Spiegellabyrinth"),
    ("Voidrift", "Leerenriss"), ("Chimefall", "Klangspiel"), ("Genie", "Dschinn"),
    # cycle F (F144..F155)
    ("Nightmare", "Nachtmahr"), ("Gravehand", "Grabhand"), ("Spiritwolf", "Geisterwolf"),
    ("Chainwraith", "Kettengeist"), ("Static", "Statik"), ("Drowned", "Ertrunkene"),
    ("Meteorveil", "Meteorschleier"), ("Fairyring", "Hexenring"), ("Turbine", "Turbine"),
    ("Medusa", "Meduse"), ("Lampion", "Lampion"), ("Sentinel", "Wächter"),
    # cycle G (F156..F167)
    ("Attic", "Dachboden"), ("Choirloft", "Chorempore"), ("Antler", "Geweih"),
    ("Limbo", "Limbus"), ("Spiritrain", "Geisterregen"), ("Ossuary", "Beinhaus"),
    ("Constellation", "Sternbild"), ("Blossom", "Blüte"), ("Gearsong", "Zahnradlied"),
    ("Trench", "Tiefsee"), ("Parade", "Parade"), ("Aegis", "Ägide"),
]

CYCLE_WORDS = ["Haunt", "Vigil", "Realm", "Rite", "Watch", "Veil", "Chorus"]

SHADE_SETS_EN = [
    ["Lament", "Wake", "Crown", "Waltz", "Embers"],
    ["Hush", "Cortege", "Halo", "Reverie", "Beacon"],
    ["Shroud", "Sonata", "Diadem", "Drift", "Kindling"],
    ["Murmur", "Pilgrimage", "Circlet", "Lullaby", "Flare"],
    ["Sigh", "Cavalcade", "Garland", "Nocturne", "Spark"],
    ["Dirge", "Retinue", "Wreath", "Serenade", "Glimmer"],
    ["Plaint", "Vanguard", "Chaplet", "Berceuse", "Gleam"],
]

SHADE_SETS_DE = [
    ["Klage", "Wacht", "Krone", "Walzer", "Glut"],
    ["Stille", "Trauerzug", "Lichtkranz", "Träumerei", "Leuchtfeuer"],
    ["Schleier", "Sonate", "Diadem", "Treiben", "Zunder"],
    ["Raunen", "Pilgerzug", "Reif", "Wiegenlied", "Lohe"],
    ["Seufzer", "Kavalkade", "Girlande", "Notturno", "Funke"],
    ["Grablied", "Gefolge", "Kranz", "Serenade", "Schimmer"],
    ["Litanei", "Vorhut", "Kranz", "Wiegenlied", "Glanz"],
]

# DE themes whose compounds need a linking form (Fugenelement): feminine nouns
# in -e take -n ("Kapelle" -> "Kapellenstille", not the ungrammatical
# "Kapelle-Stille"). Short compounds are fused, long ones keep a hyphen for
# readability ("Kapellen-Trauerzug"). Themes absent here (e.g. "Menagerie",
# "Tiefsee", "Parade") link without -n and stay plainly hyphenated.
DE_THEME_LINK = {
    "Kapelle": "Kapellen", "Dryade": "Dryaden", "Marionette": "Marionetten",
    "Walküre": "Walküren", "Grabblüte": "Grabblüten", "Destille": "Destillen",
    "Himmelsrakete": "Himmelsraketen", "Creeperhülle": "Creeperhüllen",
    "Sonnenwende": "Sonnenwenden", "Ertrunkene": "Ertrunkenen",
    "Turbine": "Turbinen", "Meduse": "Medusen", "Chorempore": "Choremporen",
    "Blüte": "Blüten",
}


def de_compound_name(theme_de: str, shade_de: str) -> str:
    """German display-name compound of theme + shade: hyphenated by default;
    themes with a linking form fuse when short ("Kapellenstille") and keep a
    hyphen after the linking form when long ("Kapellen-Trauerzug")."""
    link = DE_THEME_LINK.get(theme_de)
    if link is None:
        return f"{theme_de}-{shade_de}"
    fused = link + shade_de.lower()
    if len(fused) <= 15:
        return fused
    return f"{link}-{shade_de}"


# Per-behavior display labels: EN / DE (used for the behavior.bubbleshield.*
# axis keys, prefixed "Inside: " / "Innen: ").
BEHAVIOR_LABELS = {
    "banshee_wails": ("Banshee wails", "Banshee-Klagen"),
    "ghost_riders": ("Ghost riders", "Geisterreiter"),
    "spirit_lanterns": ("Spirit lanterns", "Geisterlaternen"),
    "haunted_portraits": ("Haunted portraits", "Verwunschene Porträts"),
    "poltergeist_toss": ("Poltergeist tosses", "Poltergeist-Würfe"),
    "wailing_choir": ("Wailing choir", "Klagender Chor"),
    "grave_hands": ("Grave hands", "Grabhände"),
    "ecto_mist_maze": ("Ecto-mist maze", "Ektonebel-Labyrinth"),
    "phantom_bells": ("Phantom bells", "Phantomglocken"),
    "seance_table": ("Séance table", "Séance-Tisch"),
    "ghost_wolves": ("Ghost wolves", "Geisterwölfe"),
    "spectral_stag": ("Spectral stag", "Spektralhirsch"),
    "wisp_owls": ("Wisp owls", "Irrlicht-Eulen"),
    "bone_fish": ("Bone fish", "Knochenfische"),
    "carrion_crows": ("Carrion crows", "Aaskrähen"),
    "styx_ferry": ("Styx ferry", "Styx-Fähre"),
    "soul_wells": ("Soul wells", "Seelenquellen"),
    "chained_specters": ("Chained specters", "Gekettete Schemen"),
    "reaper_scythe": ("Reaper's scythe", "Schnittersense"),
    "purgatory_queue": ("Purgatory queue", "Fegefeuer-Warteschlange"),
    "spirit_rain": ("Spirit rain", "Seelenregen"),
    "ecto_fog_banks": ("Ecto fog banks", "Ektonebelbänke"),
    "aurora_ghosts": ("Aurora ghosts", "Aurora-Geister"),
    "static_haunt": ("Static haunt", "Statik-Spuk"),
    "moonbeam_shafts": ("Moonbeam shafts", "Mondlichtsäulen"),
    "creeper_effigies": ("Creeper effigies", "Creeper-Bildnisse"),
    "enderman_stalkers": ("Enderman stalkers", "Enderman-Schleicher"),
    "skeleton_army": ("Skeleton army", "Skelettarmee"),
    "slime_ghosts": ("Slime ghosts", "Schleimgeister"),
    "drowned_procession": ("Drowned procession", "Ertrunkenen-Prozession"),
    "constellation_wheel": ("Constellation wheel", "Sternbildrad"),
    "comet_orrery": ("Comet orrery", "Kometen-Umlaufwerk"),
    "eclipse_disc": ("Eclipse disc", "Finsternisscheibe"),
    "meteor_shower_veil": ("Meteor-shower veil", "Meteorschauer-Schleier"),
    "zodiac_beams": ("Zodiac beams", "Tierkreis-Strahlen"),
    "dryad_bloom": ("Dryad bloom", "Dryadenblüte"),
    "mushroom_ring_sprites": ("Mushroom-ring sprites", "Hexenring-Kobolde"),
    "pollen_elementals": ("Pollen elementals", "Pollen-Elementare"),
    "vine_serpents": ("Vine serpents", "Rankenschlangen"),
    "seasons_wheel": ("Wheel of seasons", "Rad der Jahreszeiten"),
    "clockwork_gears": ("Clockwork gears", "Uhrwerk-Zahnräder"),
    "rune_forge": ("Rune forge", "Runenschmiede"),
    "alchemy_circles": ("Alchemy circles", "Alchemiekreise"),
    "mirror_maze": ("Mirror maze", "Spiegelkabinett"),
    "arcane_turbines": ("Arcane turbines", "Arkane Turbinen"),
    "abyssal_jellies": ("Abyssal jellies", "Abyssale Quallen"),
    "void_rifts_inside": ("Void rifts", "Leerenrisse"),
    "leviathan_shadow": ("Leviathan shadow", "Leviathan-Schatten"),
    "anglerfish_lures": ("Anglerfish lures", "Anglerfisch-Köder"),
    "singularity_heart": ("Singularity heart", "Singularitätsherz"),
    "lantern_festival": ("Lantern festival", "Laternenfest"),
    "firework_regatta": ("Firework regatta", "Feuerwerks-Regatta"),
    "ghost_masquerade": ("Ghost masquerade", "Geister-Maskenball"),
    "drumline_golems": ("Drumline golems", "Trommel-Golems"),
    "chime_curtains": ("Chime curtains", "Klangvorhänge"),
    "sentinel_totems": ("Sentinel totems", "Wächter-Totems"),
    "valkyrie_patrol": ("Valkyrie patrol", "Walküren-Patrouille"),
    "shield_maidens": ("Shield maidens", "Schildmaiden"),
    "cerberus_watch": ("Cerberus watch", "Zerberus-Wache"),
    "genie_plumes": ("Genie plumes", "Dschinn-Schwaden"),
}

# Per-behavior grammar for the desc sentences:
#   (en_np, en_plural, de_np, de_plural)
# en_np / de_np are mid-sentence noun phrases WITH their article where one is
# needed ("a wailing choir", "the Styx ferry", "ein klagender Chor"); nouns
# keep their German capitalization, only articles/adjectives are lowercase
# (sentence-initial use upper-cases the first character). The plural flags
# drive verb agreement (haunts/haunt, spukt/spuken, ...). Each behavior b is
# only ever rendered by desc template b % 5, so the phrasing below is chosen
# to read naturally in exactly that template.
BEHAVIOR_GRAMMAR = {
    "banshee_wails": ("banshee wails", True, "Banshee-Klagen", True),
    "ghost_riders": ("ghost riders", True, "Geisterreiter", True),
    "spirit_lanterns": ("spirit lanterns", True, "Geisterlaternen", True),
    "haunted_portraits": ("haunted portraits", True, "verwunschene Porträts", True),
    "poltergeist_toss": ("poltergeist tosses", True, "Poltergeist-Würfe", True),
    "wailing_choir": ("a wailing choir", False, "ein klagender Chor", False),
    "grave_hands": ("grave hands", True, "Grabhände", True),
    "ecto_mist_maze": ("an ecto-mist maze", False, "ein Ektonebel-Labyrinth", False),
    "phantom_bells": ("phantom bells", True, "Phantomglocken", True),
    "seance_table": ("the séance table", False, "ein Séance-Tisch", False),
    "ghost_wolves": ("ghost wolves", True, "Geisterwölfe", True),
    "spectral_stag": ("a spectral stag", False, "ein Spektralhirsch", False),
    "wisp_owls": ("wisp owls", True, "Irrlicht-Eulen", True),
    "bone_fish": ("bone fish", True, "Knochenfische", True),
    "carrion_crows": ("carrion crows", True, "Aaskrähen", True),
    "styx_ferry": ("the Styx ferry", False, "die Styx-Fähre", False),
    "soul_wells": ("soul wells", True, "Seelenquellen", True),
    "chained_specters": ("chained specters", True, "gekettete Schemen", True),
    "reaper_scythe": ("the reaper's scythe", False, "die Schnittersense", False),
    "purgatory_queue": ("a purgatory queue", False, "eine Fegefeuer-Warteschlange", False),
    "spirit_rain": ("spirit rain", False, "Seelenregen", False),
    "ecto_fog_banks": ("ecto fog banks", True, "Ektonebelbänke", True),
    "aurora_ghosts": ("aurora ghosts", True, "Aurora-Geister", True),
    "static_haunt": ("a static haunt", False, "ein Statik-Spuk", False),
    "moonbeam_shafts": ("moonbeam shafts", True, "Mondlichtsäulen", True),
    "creeper_effigies": ("creeper effigies", True, "Creeper-Bildnisse", True),
    "enderman_stalkers": ("enderman stalkers", True, "Enderman-Schleicher", True),
    "skeleton_army": ("a skeleton army", False, "eine Skelettarmee", False),
    "slime_ghosts": ("slime ghosts", True, "Schleimgeister", True),
    "drowned_procession": ("a drowned procession", False, "eine Ertrunkenen-Prozession", False),
    "constellation_wheel": ("a constellation wheel", False, "ein Sternbildrad", False),
    "comet_orrery": ("a comet orrery", False, "ein Kometen-Umlaufwerk", False),
    "eclipse_disc": ("an eclipse disc", False, "eine Finsternisscheibe", False),
    "meteor_shower_veil": ("a meteor-shower veil", False, "ein Meteorschauer-Schleier", False),
    "zodiac_beams": ("zodiac beams", True, "Tierkreis-Strahlen", True),
    "dryad_bloom": ("dryad bloom", False, "Dryadenblüten", True),
    "mushroom_ring_sprites": ("mushroom-ring sprites", True, "Hexenring-Kobolde", True),
    "pollen_elementals": ("pollen elementals", True, "Pollen-Elementare", True),
    "vine_serpents": ("vine serpents", True, "Rankenschlangen", True),
    "seasons_wheel": ("the wheel of seasons", False, "das Rad der Jahreszeiten", False),
    "clockwork_gears": ("clockwork gears", True, "Uhrwerk-Zahnräder", True),
    "rune_forge": ("a rune forge", False, "eine Runenschmiede", False),
    "alchemy_circles": ("alchemy circles", True, "Alchemiekreise", True),
    "mirror_maze": ("a mirror maze", False, "ein Spiegelkabinett", False),
    "arcane_turbines": ("arcane turbines", True, "arkane Turbinen", True),
    "abyssal_jellies": ("abyssal jellies", True, "abyssale Quallen", True),
    "void_rifts_inside": ("void rifts", True, "Leerenrisse", True),
    "leviathan_shadow": ("a leviathan shadow", False, "ein Leviathan-Schatten", False),
    "anglerfish_lures": ("anglerfish lures", True, "Anglerfisch-Köder", True),
    "singularity_heart": ("a singularity heart", False, "ein Singularitätsherz", False),
    "lantern_festival": ("a lantern festival", False, "ein Laternenfest", False),
    "firework_regatta": ("a firework regatta", False, "eine Feuerwerks-Regatta", False),
    "ghost_masquerade": ("a ghost masquerade", False, "ein Geister-Maskenball", False),
    "drumline_golems": ("drumline golems", True, "Trommel-Golems", True),
    "chime_curtains": ("chime curtains", True, "Klangvorhänge", True),
    "sentinel_totems": ("sentinel totems", True, "Wächter-Totems", True),
    "valkyrie_patrol": ("a valkyrie patrol", False, "eine Walküren-Patrouille", False),
    "shield_maidens": ("shield maidens", True, "Schildmaiden", True),
    "cerberus_watch": ("the Cerberus watch", False, "die Zerberus-Wache", False),
    "genie_plumes": ("genie plumes", True, "Dschinn-Schwaden", True),
}

# Per-surface axis labels: (EN axis value, DE axis value, EN desc noun, DE desc noun).
# The DE desc noun always follows "aus ..." in the templates, so it is given in
# a form that reads naturally there (mass noun or dative plural).
SURFACE_LABELS = {
    "spectralveil": ("Spectral veil", "Spektralschleier", "spectral-veil", "Spektralschleiern"),
    "raymarchfog": ("Raymarched fog", "Strahlmarsch-Nebel", "raymarched-fog", "Strahlennebel"),
    "prismdisperse": ("Prism dispersion", "Prismenzerstreuung", "prism-split", "Prismenlicht"),
    "holoparallax": ("Holographic parallax", "Holo-Parallaxe", "holo-parallax", "Holo-Parallaxe"),
    "orbittrap": ("Orbit-trap fractal", "Orbitfallen-Fraktal", "orbit-trap", "Orbitfraktalen"),
    "crystalsdf": ("Crystal facets", "Kristallfacetten", "crystal-facet", "Kristallfacetten"),
    "fluidink": ("Fluid ink", "Fließende Tinte", "fluid-ink", "fließender Tinte"),
    "irisfilm": ("Iridescent film", "Irisierender Film", "iridescent-film", "irisierendem Film"),
    "aethersmoke": ("Aether smoke", "Ätherrauch", "aether-smoke", "Ätherrauch"),
    "stainedglass": ("Stained glass", "Buntglas", "stained-glass", "Buntglas"),
    "phantomecho": ("Phantom echoes", "Phantomechos", "phantom-echo", "Phantomechos"),
    "gravlens": ("Gravitational lens", "Gravitationslinse", "gravity-lens", "Gravitationslinsen"),
    "mycelia": ("Mycelial threads", "Myzelfäden", "mycelial", "Myzel"),
    "solarflare": ("Solar flares", "Sonneneruptionen", "solar-flare", "Sonneneruptionen"),
    "deepice": ("Deep ice", "Tiefeneis", "deep-ice", "Tiefeneis"),
    "runecircuit": ("Rune circuitry", "Runenschaltkreise", "rune-circuit", "Runenschaltkreisen"),
    "oilslick": ("Oil slick", "Ölfilm", "oil-slick", "Ölfilm"),
    "plasmaglobe": ("Plasma globe", "Plasmakugel", "plasma-globe", "Kugelplasma"),
    "ectoplasm": ("Ectoplasm", "Ektoplasma", "ectoplasm", "Ektoplasma"),
    "voidrift": ("Void rift", "Leerenspalt", "void-rift", "Leerenspalten"),
}

HUE_NAMES = ["crimson", "ember", "amber", "gold", "lime", "verdant",
             "teal", "cyan", "azure", "indigo", "violet", "magenta"]

ROW_RE = re.compile(
    r'all\.add\(row\((\d+), 0x([0-9A-Fa-f]{6}), 0x([0-9A-Fa-f]{6}), '
    r'"([a-z]+)", "([a-z_]+)", (\d+), GuardStyle\.([A-Z_]+), ContextProfile\.([A-Z_]+), '
    r'"([a-z0-9_.]+)", ([0-9.]+)F, (\d+), "([a-z]+)"\)\);')


def parse_base_rows():
    """Parses the frozen rows 0..419 (id, prim, sec, surface, behavior, variant,
    guard, context, sound, pitch, period, screen). Rows >= 420 (present after
    the flip lands) are ignored: the base pools stay anchored to the frozen
    range so reruns are idempotent."""
    text = REGISTRY_JAVA.read_text(encoding="utf-8")
    rows = {}
    for m in ROW_RE.finditer(text):
        rid = int(m.group(1))
        rows[rid] = {
            "id": rid,
            "prim": int(m.group(2), 16),
            "sec": int(m.group(3), 16),
            "surface": m.group(4),
            "behavior": m.group(5),
            "variant": int(m.group(6)),
            "guard": m.group(7),
            "context": m.group(8),
            "sound": m.group(9),
            "pitch": m.group(10),
            "period": int(m.group(11)),
            "screen": m.group(12),
        }
    base = {rid: r for rid, r in rows.items() if rid < BASE_COUNT}
    if sorted(base) != list(range(BASE_COUNT)):
        sys.exit(f"EffectRegistry.java parse failed: found {len(base)} base rows, "
                 f"expected dense ids 0..{BASE_COUNT - 1}")
    return [base[i] for i in range(BASE_COUNT)]


def hsv24(h_deg: float, s: float, v: float) -> int:
    r, g, b = colorsys.hsv_to_rgb((h_deg % 360.0) / 360.0, min(max(s, 0.0), 1.0), min(max(v, 0.0), 1.0))
    return (int(round(r * 255.0)) << 16) | (int(round(g * 255.0)) << 8) | int(round(b * 255.0))


def hue_bucket(rgb: int) -> str:
    r, g, b = (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF
    h, s, v = colorsys.rgb_to_hsv(r / 255.0, g / 255.0, b / 255.0)
    if s < 0.15:
        return "silver"
    return HUE_NAMES[int(h * 12.0) % 12]


SAT_P = [0.82, 0.62, 0.90, 0.48, 0.72]
VAL_P = [0.96, 0.88, 0.72, 0.98, 0.60]


def palette_for(i: int, used_pairs: set) -> tuple:
    """Deterministic (primary, secondary) pair for row index i: per new-family
    hue with per-shade sat/val variation, secondary a darker kin or a
    complementary shade; nudged deterministically until the PAIR is unique."""
    nf, s5 = divmod(i, 5)
    hue = (nf * 360.0 / 84.0 + 4.0 * s5) % 360.0
    prim = hsv24(hue, SAT_P[s5], VAL_P[s5])
    if s5 % 2 == 0:
        sec = hsv24(hue + 24.0, min(1.0, SAT_P[s5] + 0.10), VAL_P[s5] * 0.42)
    else:
        sec = hsv24(hue + 180.0 + 10.0 * s5, max(0.30, SAT_P[s5] - 0.10), min(1.0, VAL_P[s5] * 0.75))
    while (prim, sec) in used_pairs:
        prim = (prim + 0x010307) & 0xFFFFFF  # deterministic nudge
    used_pairs.add((prim, sec))
    return prim, sec


def sound_triple_for(i: int, pool: list, used_triples: set) -> tuple:
    """sound = POOL[i % P]; the (pitch, period) is probed deterministically
    until the (sound, pitch, period) triple is globally unique. The probe
    space is 9 pitches x 221 periods (80..300, all > 0), far larger than any
    per-sound usage, so it always terminates."""
    sound = pool[i % len(pool)]
    for k in range(9 * 221):
        pitch = PITCHES[(i * 7 + k) % 9]
        period = 80 + ((i * 37 + 11 * (k // 9)) % 221)
        triple = (sound, pitch, period)
        if triple not in used_triples:
            used_triples.add(triple)
            return sound, pitch, period
    sys.exit(f"sound triple probing exhausted for row index {i} (sound {sound})")


def build_new_rows(base_rows):
    used_pairs = {(r["prim"], r["sec"]) for r in base_rows}
    assert len(used_pairs) == BASE_COUNT, "base palette pairs are not pairwise distinct"
    used_triples = {(r["sound"], r["pitch"], r["period"]) for r in base_rows}
    assert len(used_triples) == BASE_COUNT, "base sound triples are not pairwise distinct"
    pool = sorted({r["sound"] for r in base_rows})
    assert pool, "empty sound pool"

    new_rows = []
    for i in range(NEW_COUNT):
        prim, sec = palette_for(i, used_pairs)
        sound, pitch, period = sound_triple_for(i, pool, used_triples)
        new_rows.append({
            "id": BASE_COUNT + i,
            "i": i,
            "prim": prim,
            "sec": sec,
            "surface": NEW_SURFACES[i % 20],
            "behavior": NEW_BEHAVIORS[i % 60],
            "variant": i // 60,
            "guard": GUARDS[guard_index(i)],
            "context": CONTEXTS[context_index(i)],
            "sound": sound,
            "pitch": pitch,
            "period": period,
            "screen": SCREEN28[i % 28],
        })
    return new_rows


def build_names(base_rows):
    """EN + DE display names for ids 420..839, pairwise distinct per language
    (checked against the existing 420 too; deterministic ' II'/' III' nudge)."""
    en_existing = set()
    de_existing = set()
    en_json = json.loads(LANG_EN.read_text(encoding="utf-8"))
    de_json = json.loads(LANG_DE.read_text(encoding="utf-8"))
    for i in range(BASE_COUNT):
        key = f"effect.bubbleshield.{i:02d}"
        en_existing.add(en_json[key])
        de_existing.add(de_json[key])
    assert len(en_existing) == BASE_COUNT and len(de_existing) == BASE_COUNT, \
        "existing effect names are not pairwise distinct"

    en_names, de_names = [], []
    en_used, de_used = set(en_existing), set(de_existing)
    for i in range(NEW_COUNT):
        nf, s5 = divmod(i, 5)
        theme_en, theme_de = THEMES[nf]
        shade_en = SHADE_SETS_EN[nf % 7][s5]
        shade_de = SHADE_SETS_DE[nf % 7][s5]
        en = f"{theme_en} {shade_en}"
        de = de_compound_name(theme_de, shade_de)
        for suffix in ("", " II", " III", " IV", " V"):
            if en + suffix not in en_used:
                en = en + suffix
                break
        for suffix in ("", " II", " III", " IV", " V"):
            if de + suffix not in de_used:
                de = de + suffix
                break
        assert en not in en_used and de not in de_used, f"name nudge exhausted at i={i}"
        en_used.add(en)
        de_used.add(de)
        en_names.append(en)
        de_names.append(de)
    return en_names, de_names


# Per-variant closing sentences: the 7 variants of a behavior share one base
# sentence, so each variant appends its own short flavor sentence (keeps the
# 420 descs per language pairwise distinct and less templated). Every base
# sentence's subject shell noun is feminine in DE (Hülle/Kuppel/Membran), so
# the "Ihr ..." tails always agree.
VARIANT_TAILS_EN = [
    "Its light holds low and steady.",
    "Faint sparks chase along the rim.",
    "A slow pulse rolls through the wall.",
    "Pale motes drift where the wall thins.",
    "Its sheen sharpens as night falls.",
    "Soft echoes ring along the curve.",
    "The crown of the dome burns brightest.",
]

VARIANT_TAILS_DE = [
    "Ihr Licht bleibt ruhig und gedämpft.",
    "Schwache Funken jagen den Rand entlang.",
    "Ein langsamer Puls rollt durch die Wand.",
    "Blasse Lichtpunkte treiben, wo die Wand dünn wird.",
    "Ihr Schimmer wird schärfer, wenn die Nacht hereinbricht.",
    "Sanfte Echos klingen die Wölbung entlang.",
    "Der Scheitel der Kuppel glüht am hellsten.",
]


def cap_first(s: str) -> str:
    return s[0].upper() + s[1:]


def en_article(word: str) -> str:
    return "an" if word[0] in "aeiou" else "a"


def descs_for(row):
    """One EN + one DE desc per row: template picked by i % 5 (== behavior % 5,
    so each behavior's phrasing was chosen for exactly its template), with
    number-correct verbs, correct articles (a/an, ein/eine) and a per-variant
    closing sentence."""
    i = row["i"]
    s5 = i % 5
    en_np, en_pl, de_np, de_pl = BEHAVIOR_GRAMMAR[row["behavior"]]
    s_en = SURFACE_LABELS[row["surface"]][2]
    s_de = SURFACE_LABELS[row["surface"]][3]
    a_s = en_article(s_en)
    if s5 == 0:
        en = f"{cap_first(en_np)} {'haunt' if en_pl else 'haunts'} the bubble beneath {a_s} {s_en} shell."
        de = f"{cap_first(de_np)} {'spuken' if de_pl else 'spukt'} unter einer Hülle aus {s_de}."
    elif s5 == 1:
        en = f"{cap_first(en_np)} {'swirl' if en_pl else 'swirls'} inside {a_s} {s_en} canopy."
        de = f"{cap_first(de_np)} {'wirbeln' if de_pl else 'wirbelt'} in einer Kuppel aus {s_de}."
    elif s5 == 2:
        en = f"{a_s.capitalize()} {s_en} dome sheltering {en_np}."
        de = f"Eine Kuppel aus {s_de}, in der {de_np} {'hausen' if de_pl else 'haust'}."
    elif s5 == 3:
        en = f"{cap_first(en_np)} {'gather' if en_pl else 'gathers'} under the {s_en} membrane."
        if de_pl:
            de = f"{cap_first(de_np)} sammeln sich unter der Membran aus {s_de}."
        else:
            de = f"{cap_first(de_np)} lauert unter der Membran aus {s_de}."
    else:
        en = f"The {s_en} shell hums with {en_np}."
        de = f"Unter der summenden Hülle aus {s_de} {'regen' if de_pl else 'regt'} sich {de_np}."
    variant = row["variant"]
    return f"{en} {VARIANT_TAILS_EN[variant]}", f"{de} {VARIANT_TAILS_DE[variant]}"


def assert_valid(base_rows, new_rows):
    """Re-checks EVERY EffectRegistry.validate() invariant over the combined
    840 rows before anything is emitted."""
    combined = base_rows + new_rows
    assert [r["id"] for r in combined] == list(range(BASE_COUNT + NEW_COUNT)), "ids are not dense"

    pairs = {(r["prim"], r["sec"]) for r in combined}
    assert len(pairs) == len(combined), "palette pairs are not pairwise distinct"

    bv = {}
    for r in combined:
        key = (r["behavior"], r["variant"])
        assert key not in bv, f"behavior/variant reuse: {key}"
        bv[key] = r["id"]
    behaviors = {}
    for (b, v) in bv:
        behaviors.setdefault(b, set()).add(v)
    for b, variants in behaviors.items():
        assert variants == set(range(7)), f"behavior {b} variants {sorted(variants)} != 0..6"
    assert len(behaviors) == 120, f"expected 120 behaviors used, found {len(behaviors)}"
    for b in NEW_BEHAVIORS:
        assert b in behaviors, f"new behavior {b} unused"

    triples = {(r["sound"], r["pitch"], r["period"]) for r in combined}
    assert len(triples) == len(combined), "sound triples are not pairwise distinct"
    for r in combined:
        assert r["period"] > 0, f"row {r['id']} non-positive period"

    screens_ok = set(SCREEN28)
    for r in combined:
        assert r["screen"] in screens_ok, f"row {r['id']} unknown screen {r['screen']}"
    used_screens = {r["screen"] for r in combined}
    assert used_screens == screens_ok, f"unused screen families: {screens_ok - used_screens}"

    surfaces_all = {r["surface"] for r in base_rows} | set(NEW_SURFACES)
    used_surfaces = {r["surface"] for r in combined}
    assert used_surfaces == surfaces_all, f"unused surfaces: {surfaces_all - used_surfaces}"

    fam_surface, fam_screen, fam_behavior = {}, {}, {}
    pair_counts = {}
    for r in combined:
        fam = r["id"] // 5
        assert r["surface"] not in fam_surface.setdefault(fam, set()), \
            f"family {fam} repeats surface {r['surface']}"
        fam_surface[fam].add(r["surface"])
        assert r["screen"] not in fam_screen.setdefault(fam, set()), \
            f"family {fam} repeats screen {r['screen']}"
        fam_screen[fam].add(r["screen"])
        assert r["behavior"] not in fam_behavior.setdefault(fam, set()), \
            f"family {fam} repeats behavior {r['behavior']}"
        fam_behavior[fam].add(r["behavior"])
        pk = (r["surface"], r["screen"])
        pair_counts[pk] = pair_counts.get(pk, 0) + 1
        assert pair_counts[pk] <= 3, f"(surface, screen) pair {pk} exceeds the 3 cap"

    # Guard/context de-correlation (validate() imposes nothing on these axes,
    # but the assignment must not lock any behavior to one context nor make
    # the guard a deterministic function of the screen family).
    behavior_contexts, screen_guards = {}, {}
    behavior_guards, guard_counts, context_counts = {}, {}, {}
    for r in new_rows:
        i = r["i"]
        assert r["guard"] == GUARDS[guard_index(i)]
        assert r["context"] == CONTEXTS[context_index(i)]
        behavior_contexts.setdefault(r["behavior"], set()).add(r["context"])
        behavior_guards.setdefault(r["behavior"], set()).add(r["guard"])
        screen_guards.setdefault(r["screen"], set()).add(r["guard"])
        guard_counts[r["guard"]] = guard_counts.get(r["guard"], 0) + 1
        context_counts[r["context"]] = context_counts.get(r["context"], 0) + 1
    for b, ctxs in behavior_contexts.items():
        assert len(ctxs) >= 2, f"behavior {b} is locked to a single context: {ctxs}"
    for s, gs in screen_guards.items():
        assert len(gs) >= 2, f"screen {s} deterministically fixes the guard: {gs}"
    assert set(guard_counts) == set(GUARDS), f"unused guards: {set(GUARDS) - set(guard_counts)}"
    assert set(context_counts) == set(CONTEXTS), \
        f"unused contexts: {set(CONTEXTS) - set(context_counts)}"

    print(f"asserted: {len(combined)} rows valid; behaviors {len(behaviors)} x 7; "
          f"screens {len(used_screens)}; surfaces {len(used_surfaces)}; "
          f"max (surface,screen) pair count {max(pair_counts.values())}")
    print(f"de-correlation: contexts per behavior min "
          f"{min(len(v) for v in behavior_contexts.values())}/6, guards per screen min "
          f"{min(len(v) for v in screen_guards.values())}/7, guards per behavior min "
          f"{min(len(v) for v in behavior_guards.values())}/7; "
          f"guard counts {sorted(guard_counts.values())}, "
          f"context counts {sorted(context_counts.values())}")


def emit(base_rows, new_rows, en_names, de_names, out_dir: Path):
    out_dir.mkdir(parents=True, exist_ok=True)

    # (a) the Java rows, with a family comment before every group of 5.
    java_lines = []
    for r in new_rows:
        i = r["i"]
        nf, s5 = divmod(i, 5)
        if s5 == 0:
            theme_en = THEMES[nf][0]
            cycle = CYCLE_WORDS[nf // 12]
            note = f"{hue_bucket(r['prim'])} x {hue_bucket(r['sec'])}"
            java_lines.append(f'\t\t// F{84 + nf} "{theme_en} {cycle}" ({note})')
        java_lines.append(
            f'\t\tall.add(row({r["id"]}, 0x{r["prim"]:06X}, 0x{r["sec"]:06X}, "{r["surface"]}", '
            f'"{r["behavior"]}", {r["variant"]}, GuardStyle.{r["guard"]}, ContextProfile.{r["context"]}, '
            f'"{r["sound"]}", {r["pitch"]}F, {r["period"]}, "{r["screen"]}"));')
    (out_dir / "rows_java.txt").write_text("\n".join(java_lines) + "\n", encoding="utf-8", newline="\n")

    # (b) the gen_post_effects.py EFFECTS mirror tuples.
    mirror_lines = []
    for r in new_rows:
        i = r["i"]
        nf, s5 = divmod(i, 5)
        if s5 == 0:
            theme_en = THEMES[nf][0]
            cycle = CYCLE_WORDS[nf // 12]
            note = f"{hue_bucket(r['prim'])} x {hue_bucket(r['sec'])}"
            mirror_lines.append(f'    # F{84 + nf} "{theme_en} {cycle}" ({note})')
        tup = f'    (0xFF{r["prim"]:06X}, 0xFF{r["sec"]:06X}, "{r["screen"]}"),'
        mirror_lines.append(f'{tup:<44}# {r["id"]} {en_names[i]}')
    (out_dir / "effects_mirror.txt").write_text("\n".join(mirror_lines) + "\n", encoding="utf-8", newline="\n")

    # (c) lang fragments: effect names + descs (insertion order: name, desc per id).
    en_eff, de_eff = {}, {}
    for r in new_rows:
        i = r["i"]
        key = f"effect.bubbleshield.{r['id']:02d}"
        desc_en, desc_de = descs_for(r)
        en_eff[key] = en_names[i]
        en_eff[key + ".desc"] = desc_en
        de_eff[key] = de_names[i]
        de_eff[key + ".desc"] = desc_de
    (out_dir / "lang_en_effects.json").write_text(
        json.dumps(en_eff, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")
    (out_dir / "lang_de_effects.json").write_text(
        json.dumps(de_eff, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")

    # (d) axis lang fragments: 20 surfaces then 60 behaviors, per language.
    en_axis, de_axis = {}, {}
    for name in NEW_SURFACES:
        en_axis[f"surface.bubbleshield.{name}"] = f"Surface: {SURFACE_LABELS[name][0]}"
        de_axis[f"surface.bubbleshield.{name}"] = f"Oberfläche: {SURFACE_LABELS[name][1]}"
    for b in NEW_BEHAVIORS:
        en_axis[f"behavior.bubbleshield.{b}"] = f"Inside: {BEHAVIOR_LABELS[b][0]}"
        de_axis[f"behavior.bubbleshield.{b}"] = f"Innen: {BEHAVIOR_LABELS[b][1]}"
    (out_dir / "lang_en_axis.json").write_text(
        json.dumps(en_axis, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")
    (out_dir / "lang_de_axis.json").write_text(
        json.dumps(de_axis, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")

    print(f"wrote rows_java.txt, effects_mirror.txt and 4 lang fragments to {out_dir}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out-dir", default="/tmp/newrows", help="fragment output directory")
    args = parser.parse_args()

    assert len(NEW_BEHAVIORS) == 60 and len(set(NEW_BEHAVIORS)) == 60
    assert len(NEW_SURFACES) == 20 and len(set(NEW_SURFACES)) == 20
    assert len(SCREEN28) == 28 and len(set(SCREEN28)) == 28
    assert len(THEMES) == 84
    assert len({t[0] for t in THEMES}) == 84, "EN theme words are not distinct"
    assert len({t[1] for t in THEMES}) == 84, "DE theme words are not distinct"
    for shades in SHADE_SETS_EN + SHADE_SETS_DE:
        assert len(set(shades)) == 5
    assert set(BEHAVIOR_LABELS) == set(NEW_BEHAVIORS)
    assert set(BEHAVIOR_GRAMMAR) == set(NEW_BEHAVIORS)
    assert set(SURFACE_LABELS) == set(NEW_SURFACES)
    assert set(DE_THEME_LINK) <= {t[1] for t in THEMES}, "DE_THEME_LINK has unknown themes"
    assert len(VARIANT_TAILS_EN) == 7 and len(set(VARIANT_TAILS_EN)) == 7
    assert len(VARIANT_TAILS_DE) == 7 and len(set(VARIANT_TAILS_DE)) == 7

    base_rows = parse_base_rows()
    new_rows = build_new_rows(base_rows)
    assert_valid(base_rows, new_rows)
    en_names, de_names = build_names(base_rows)
    emit(base_rows, new_rows, en_names, de_names, Path(args.out_dir))


if __name__ == "__main__":
    main()

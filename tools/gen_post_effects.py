#!/usr/bin/env python3
"""Dev tool: generates the 105 shield screen post-effect JSONs.

Emits src/main/resources/assets/bubbleshield/post_effect/effect_00.json ...
effect_104.json. The assets live in the main (not client) source set so the
postEffectAssetsExist game test can find them on the dedicated-server classpath;
the client resource manager loads them from the merged mod jar all the same.
Not wired into the build; rerun manually after changing the mapping below and
commit the regenerated files:

    python3 tools/gen_post_effects.py

The EFFECTS table mirrors com.bubbleshield.effect.EffectRegistry row for row:
(argbPrimary, argbSecondary, screenTemplate name). All 16 screen-fx templates
ship a shader, so every JSON references its effect's final template directly
(the screenTemplateMatchesJson gametest cross-checks every generated JSON
against EffectDefinition.screenTemplate).
Each JSON follows the vanilla post_effect schema (see
assets/minecraft/post_effect/creeper.json in 26.2): a "targets"/"passes"
document where every pass runs a fragment shader over a screenquad. Pass 1
applies our template main -> swap; pass 2 blits swap -> main with the vanilla
blit shader, because a pass cannot read and write the same target.
"""

import json
from pathlib import Path

OUT_DIR = Path(__file__).resolve().parent.parent / "src/main/resources/assets/bubbleshield/post_effect"

EFFECT_COUNT = 105

# Modulus/denominator of the per-id param_b derivation below. FROZEN at the V1
# catalogue size (75): retuning it to EFFECT_COUNT would silently change the
# generated uniforms of ids 0..74, which must stay byte-identical across
# catalogue expansions. Mirrors EffectRegistry.PARAM_CYCLE -- keep in lockstep.
PARAM_CYCLE = 75

# Mirrors EffectRegistry.buildAll(): (argbPrimary, argbSecondary, screenTemplate).
EFFECTS = [
    # F0 "Aurora Borealis" (greens)
    (0xFF66FFAA, 0xFF1E9E6E, "tint"),       # 0 Aurora Storm
    (0xFF8CFFC1, 0xFF0FBF8F, "bloomglow"),  # 1 Polar Curtain
    (0xFF3DF5B0, 0xFF136B4F, "edgeglow"),   # 2 Greenwash Veil
    (0xFFA1FFD9, 0xFF2FBF71, "ripple"),     # 3 Borealis Bloom
    (0xFF57E68C, 0xFF0E5C38, "desat"),      # 4 Verdant Halo
    # F1 "Plasma Nexus" (magentas)
    (0xFFFF33CC, 0xFF7A00FF, "wobble"),     # 5 Plasma Storm
    (0xFFFF66E0, 0xFF5500BB, "chroma"),     # 6 Ion Cascade
    (0xFFE01FA9, 0xFF3D0066, "heathaze"),   # 7 Magenta Meteor
    (0xFFFF4DD6, 0xFF8A1FB8, "scanlines"),  # 8 Fuchsia Purge
    (0xFFFF00B3, 0xFFBF00FF, "bloomglow"),  # 9 Neon Overdrive
    # F2 "Deep Ocean" (blues)
    (0xFF2E7BFF, 0xFF00CFEA, "ripple"),     # 10 Tidal Veil
    (0xFF1F5FD6, 0xFF00A3C4, "tint"),       # 11 Abyssal Mend
    (0xFF66B8FF, 0xFF0E4FA3, "frostlens"),  # 12 Frostline Current
    (0xFF3E8FE0, 0xFF123C78, "wobble"),     # 13 Deepsea Mist
    (0xFF8FD4FF, 0xFF1F73B8, "vignette"),   # 14 Blizzard Reef
    # F3 "Ember Forge" (oranges)
    (0xFFFF7A1A, 0xFFFFC23D, "heathaze"),   # 15 Ember Storm
    (0xFFE85D04, 0xFF9D2B06, "vignette"),   # 16 Cinder Ward
    (0xFFFFA347, 0xFFB33F00, "bloomglow"),  # 17 Meteor Forge
    (0xFFFF8C29, 0xFFFFD97A, "tint"),       # 18 Gilded Sprint
    (0xFFD9480F, 0xFF5A0F05, "chroma"),     # 19 Furnace Pulse
    # F4 "Void Whisper" (purples)
    (0xFF8A2BE2, 0xFF3B0A66, "chroma"),     # 20 Soul Well
    (0xFF6A0DAD, 0xFF2E0854, "scanlines"),  # 21 Gravity Hush
    (0xFF9D4EDD, 0xFF4A148C, "desat"),      # 22 Rune Whisper
    (0xFFB388FF, 0xFF1A0033, "vignette"),   # 23 Event Horizon
    (0xFF7C43BD, 0xFF26094D, "edgeglow"),   # 24 Umbral Frost
    # F5 "Solar Crown" (golds)
    (0xFFFFD24D, 0xFFB8860B, "bloomglow"),  # 25 Midas Rush
    (0xFFFFE066, 0xFFCC9A06, "tint"),       # 26 Sun Sprinter
    (0xFFF5C518, 0xFF8A6D00, "heathaze"),   # 27 Gold Firefly
    (0xFFFFDF80, 0xFFA67C00, "edgeglow"),   # 28 Royal Chorus
    (0xFFEAB530, 0xFF6B4E00, "pixelate"),   # 29 Crown Mender
    # F6 "Glacial Palace" (ice cyans)
    (0xFF7FE9FF, 0xFF2FA8C9, "frostlens"),  # 30 Glacial Lattice
    (0xFFB3F0FF, 0xFF1B7A99, "desat"),      # 31 Permafrost Bite
    (0xFFA0E8F0, 0xFF33808C, "ripple"),     # 32 Rimefog
    (0xFF66D9E8, 0xFF0B525B, "pixelate"),   # 33 Frozen Tide
    (0xFFD0FBFF, 0xFF4FB3C9, "tint"),       # 34 Aegis of Winter
    # F7 "Crimson Rite" (reds)
    (0xFFC80F1E, 0xFF5A0710, "vignette"),   # 35 Blood Pulse
    (0xFFE5383B, 0xFF7F0A0F, "heathaze"),   # 36 Pyre Rain
    (0xFFFF5A5F, 0xFF8C1C13, "chroma"),     # 37 Red Reckoning
    (0xFFA4161A, 0xFF3D0000, "scanlines"),  # 38 Hexen Bane
    (0xFFF25C54, 0xFF661411, "ripple"),     # 39 Martyr's Ward
    # F8 "Verdant Grove" (emerald/lime)
    (0xFF2ECC71, 0xFF0E7A3C, "tint"),       # 40 Petal Grove
    (0xFF7AE582, 0xFF1E6B2F, "ripple"),     # 41 Lush Firefly
    (0xFF58D68D, 0xFF145A32, "bloomglow"),  # 42 Grove Mender
    (0xFF9CCC65, 0xFF33691E, "frostlens"),  # 43 Sporesong
    (0xFF66BB6A, 0xFF1B5E20, "wobble"),     # 44 Owl Sight
    # F9 "Monochrome Static" (greys)
    (0xFFF2F2F2, 0xFF3C3C3C, "desat"),      # 45 Ashen Shroud
    (0xFFE0E0E0, 0xFF616161, "pixelate"),   # 46 White Noise
    (0xFFBDBDBD, 0xFF212121, "scanlines"),  # 47 Grey Ring
    (0xFF9E9E9E, 0xFF424242, "edgeglow"),   # 48 Static Cage
    (0xFFEEEEEE, 0xFF757575, "vignette"),   # 49 Silent Chord
    # F10 "Celestial Vault" (indigo/silver)
    (0xFF7986CB, 0xFFE8EAF6, "edgeglow"),   # 50 Starfall Vault
    (0xFF5C6BC0, 0xFFC5CAE9, "bloomglow"),  # 51 Zodiac Script
    (0xFF3F51B5, 0xFF9FA8DA, "pixelate"),   # 52 Comet Spiral
    (0xFF283593, 0xFFB0BEC5, "desat"),      # 53 Nebula Firefly
    (0xFF9FA8DA, 0xFF1A237E, "wobble"),     # 54 Lunar Sight
    # F11 "Tempest Cell" (storm blue/electric)
    (0xFF4FC3F7, 0xFF01579B, "scanlines"),  # 55 Storm Lattice
    (0xFF29B6F6, 0xFF0D47A1, "chroma"),     # 56 Thunder Meteor
    (0xFF81D4FA, 0xFF0277BD, "wobble"),     # 57 Gale Purge
    (0xFF00B0FF, 0xFF002F6C, "heathaze"),   # 58 Ion Orbit
    (0xFF40C4FF, 0xFF01426A, "frostlens"),  # 59 Slipstream
    # F12 "Sakura Dream" (pink/white)
    (0xFFF8BBD0, 0xFFAD1457, "ripple"),     # 60 Sakura Drift
    (0xFFF48FB1, 0xFF880E4F, "tint"),       # 61 Blossom Tide
    (0xFFFF80AB, 0xFFC2185B, "pixelate"),   # 62 Pink Lattice
    (0xFFFFC1E3, 0xFFD81B60, "wobble"),     # 63 Petal Haste
    (0xFFEC407A, 0xFF4A0025, "bloomglow"),  # 64 Heart Bloom
    # F13 "Sculk Depths" (dark teals)
    (0xFF1DE9B6, 0xFF0B3D33, "desat"),      # 65 Sculk Souls
    (0xFF00BFA5, 0xFF00332B, "edgeglow"),   # 66 Deep Freeze
    (0xFF26A69A, 0xFF00201C, "scanlines"),  # 67 Warden's Ring
    (0xFF4DB6AC, 0xFF00251F, "vignette"),   # 68 Echo Aegis
    (0xFF80CBC4, 0xFF0E2E29, "chroma"),     # 69 Sporeveil
    # F14 "Twin Nether" (crimson/warped duotones)
    (0xFFDD2C00, 0xFF00C853, "heathaze"),   # 70 Crimson Spores
    (0xFFFF3D00, 0xFF1B5E20, "vignette"),   # 71 Nether Ward
    (0xFF00E5FF, 0xFFBF360C, "frostlens"),  # 72 Soulfire Rain
    (0xFF76FF03, 0xFF8D1007, "pixelate"),   # 73 Wailing Souls
    (0xFFCFD8DC, 0xFF4E342E, "ripple"),     # 74 Basalt Blizzard
    # F15 "Copper Patina" (teal x copper)
    (0xFF2FBFA3, 0xFFB35A2D, "posterize"),  # 75 Verdigris Leap
    (0xFF53D9C0, 0xFF8C4A21, "duotone"),    # 76 Patina Tide
    (0xFF1FA08A, 0xFFD97C4A, "heathaze"),   # 77 Smelter's Guard
    (0xFF7FE0CF, 0xFF6B3517, "tint"),       # 78 Burnished Charm
    (0xFF0F8C77, 0xFFE09A66, "edgeglow"),   # 79 Oxide Echo
    # F16 "Amber Twilight" (amber x violet)
    (0xFFFFB733, 0xFF6A2C91, "duotone"),    # 80 Duskray Prism
    (0xFFE6960F, 0xFF8F5BC2, "glitch"),     # 81 Gloaming Tendrils
    (0xFFFFCC66, 0xFF4A1A70, "vignette"),   # 82 Amber Nectar
    (0xFFCC8419, 0xFFB08AE0, "bloomglow"),  # 83 Candlewax Dusk
    (0xFFF2A63B, 0xFF33104D, "scanlines"),  # 84 Twilight Cage
    # F17 "Toxic Bloom" (acid-green x charcoal)
    (0xFFA6E22E, 0xFF2B2B2B, "glitch"),     # 85 Acid Hop
    (0xFFC3F73A, 0xFF1A1F16, "posterize"),  # 86 Sludge Surge
    (0xFF86B300, 0xFF3D3D3D, "chroma"),     # 87 Caustic Guard
    (0xFFD6FF66, 0xFF22261C, "desat"),      # 88 Blight Charm
    (0xFF9ACD32, 0xFF101410, "edgeglow"),   # 89 Spore Echo
    # F18 "Royal Abyss" (navy x gold)
    (0xFF16296B, 0xFFF0C24A, "bloomglow"),  # 90 Crown Prism
    (0xFF0A1A4D, 0xFFD4AF37, "duotone"),    # 91 Abyssal Court
    (0xFF24408F, 0xFFFFDD80, "radialblur"), # 92 Gilded Nectar
    (0xFF33509E, 0xFFB8912F, "vignette"),   # 93 Sovereign Seal
    (0xFF0D2140, 0xFFE6C35C, "posterize"),  # 94 Regal Tempest
    # F19 "Coral Reef" (coral x turquoise)
    (0xFFFF6F61, 0xFF20B2AA, "radialblur"), # 95 Reef Leap
    (0xFFFF8A75, 0xFF0E8074, "ripple"),     # 96 Lagoon Swell
    (0xFFE85A4F, 0xFF30D5C8, "duotone"),    # 97 Anemone Guard
    (0xFFFFA599, 0xFF117A6F, "tint"),       # 98 Pearl Charm
    (0xFFD94F41, 0xFF66E5DB, "glitch"),     # 99 Tidepool Echo
    # F20 "Spectral Circus" (high-contrast complements)
    (0xFFFF2E9A, 0xFF2EFF93, "glitch"),     # 100 Neon Big Top
    (0xFF7A00E6, 0xFFE6C800, "posterize"),  # 101 Jester's Void
    (0xFFFF6600, 0xFF0066FF, "pixelate"),   # 102 Carnival Clash
    (0xFF00E5B0, 0xFFE5003F, "radialblur"), # 103 Ringmaster's Glow
    (0xFFFFE600, 0xFF3D0099, "duotone"),    # 104 Spectral Finale
]


def argb_to_vec4(argb: int) -> list[float]:
    a = (argb >> 24) & 0xFF
    r = (argb >> 16) & 0xFF
    g = (argb >> 8) & 0xFF
    b = argb & 0xFF
    return [round(c / 255.0, 4) for c in (r, g, b, a)]


def uniform(name: str, type_: str, value) -> dict:
    return {"name": name, "type": type_, "value": value}


def template_uniforms(template: str, primary: list[float], secondary: list[float],
                      param_a: float, param_b: float) -> dict:
    """One uniform block per template; entry order must match the GLSL std140 block."""
    if template == "tint":
        return {"TintConfig": [
            uniform("Primary", "vec4", primary),
            uniform("Secondary", "vec4", secondary),
            uniform("Intensity", "float", round(0.5 * param_b, 4)),
        ]}
    if template == "wobble":
        return {"WobbleConfig": [
            uniform("Primary", "vec4", primary),
            uniform("Frequency", "float", 24.0),
            uniform("Amplitude", "float", round(0.004 * param_b, 5)),
            uniform("Speed", "float", round(param_a, 4)),
        ]}
    if template == "vignette":
        return {"VignetteConfig": [
            uniform("Primary", "vec4", primary),
            uniform("Intensity", "float", round(0.6 * param_b, 4)),
            uniform("PulseSpeed", "float", round(param_a, 4)),
        ]}
    if template == "chroma":
        return {"ChromaConfig": [
            uniform("Primary", "vec4", primary),
            uniform("OffsetAmount", "float", round(0.012 * param_b, 5)),
        ]}
    if template == "pixelate":
        return {"PixelateConfig": [
            uniform("Primary", "vec4", primary),
            uniform("Resolution", "float", 24.0),
            uniform("MosaicSize", "float", round(2.0 + 2.0 * param_b, 4)),
        ]}
    if template == "desat":
        return {"DesatConfig": [
            uniform("Primary", "vec4", primary),
            uniform("Desaturation", "float", round(0.7 * param_b, 4)),
        ]}
    if template == "bloomglow":
        return {"BloomGlowConfig": [
            uniform("Primary", "vec4", primary),
            uniform("Intensity", "float", round(0.9 * param_b, 4)),
            uniform("Threshold", "float", round(0.35 + 0.25 * param_a, 4)),
        ]}
    if template == "ripple":
        return {"RippleConfig": [
            uniform("Primary", "vec4", primary),
            uniform("Intensity", "float", round(0.8 * param_b, 4)),
            uniform("Speed", "float", round(param_a, 4)),
        ]}
    if template == "scanlines":
        return {"ScanlinesConfig": [
            uniform("Primary", "vec4", primary),
            uniform("Intensity", "float", round(0.7 * param_b, 4)),
            uniform("LineDensity", "float", round(160.0 + 180.0 * param_a, 4)),
        ]}
    if template == "edgeglow":
        return {"EdgeGlowConfig": [
            uniform("Primary", "vec4", primary),
            uniform("Intensity", "float", round(0.8 * param_b, 4)),
        ]}
    if template == "frostlens":
        return {"FrostLensConfig": [
            uniform("Primary", "vec4", primary),
            uniform("Secondary", "vec4", secondary),
            uniform("Intensity", "float", round(0.75 * param_b, 4)),
            uniform("CrystalScale", "float", round(10.0 + 20.0 * param_a, 4)),
        ]}
    if template == "heathaze":
        return {"HeatHazeConfig": [
            uniform("Primary", "vec4", primary),
            uniform("Intensity", "float", round(0.9 * param_b, 4)),
            uniform("Speed", "float", round(param_a, 4)),
        ]}
    if template == "posterize":
        return {"PosterizeConfig": [
            uniform("Primary", "vec4", primary),
            uniform("Levels", "float", round(5.0 + 3.0 * param_b, 4)),
            uniform("PulseSpeed", "float", round(param_a, 4)),
        ]}
    if template == "radialblur":
        return {"RadialBlurConfig": [
            uniform("Primary", "vec4", primary),
            uniform("Intensity", "float", round(0.85 * param_b, 4)),
        ]}
    if template == "glitch":
        return {"GlitchConfig": [
            uniform("Primary", "vec4", primary),
            uniform("Intensity", "float", round(0.8 * param_b, 4)),
            uniform("Speed", "float", round(param_a, 4)),
        ]}
    if template == "duotone":
        return {"DuotoneConfig": [
            uniform("Primary", "vec4", primary),
            uniform("Secondary", "vec4", secondary),
            uniform("Intensity", "float", round(0.65 * param_b, 4)),
        ]}
    raise ValueError(f"unknown template {template}")


def build_effect(effect_id: int) -> dict:
    argb_primary, argb_secondary, template = EFFECTS[effect_id]
    # Same per-id derivation as EffectRegistry.row(): paramA = pattern scale,
    # paramB = scroll speed / intensity driver. The modulus is PARAM_CYCLE (not
    # EFFECT_COUNT) so ids 0..74 keep their V1 params across expansions.
    param_a = 0.3 + 0.012 * effect_id
    param_b = 0.4 + ((effect_id * 37) % PARAM_CYCLE) / PARAM_CYCLE

    return {
        "targets": {
            "swap": {}
        },
        "passes": [
            {
                "vertex_shader": "minecraft:core/screenquad",
                "fragment_shader": f"bubbleshield:screenfx/{template}",
                "inputs": [
                    {"sampler_name": "In", "target": "minecraft:main"}
                ],
                "output": "swap",
                "uniforms": template_uniforms(
                    template, argb_to_vec4(argb_primary), argb_to_vec4(argb_secondary),
                    param_a, param_b
                ),
            },
            {
                "vertex_shader": "minecraft:core/screenquad",
                "fragment_shader": "minecraft:post/blit",
                "inputs": [
                    {"sampler_name": "In", "target": "swap"}
                ],
                "output": "minecraft:main",
                "uniforms": {
                    "BlitConfig": [
                        uniform("ColorModulate", "vec4", [1.0, 1.0, 1.0, 1.0])
                    ]
                },
            },
        ],
    }


def main() -> None:
    assert len(EFFECTS) == EFFECT_COUNT, f"expected {EFFECT_COUNT} rows, found {len(EFFECTS)}"
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for effect_id in range(EFFECT_COUNT):
        path = OUT_DIR / f"effect_{effect_id:02d}.json"
        path.write_text(json.dumps(build_effect(effect_id), indent=4) + "\n")
    print(f"Wrote {EFFECT_COUNT} post effect JSONs to {OUT_DIR}")


if __name__ == "__main__":
    main()

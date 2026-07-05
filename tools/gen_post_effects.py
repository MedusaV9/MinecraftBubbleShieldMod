#!/usr/bin/env python3
"""Dev tool: generates the 50 shield screen post-effect JSONs.

Emits src/main/resources/assets/bubbleshield/post_effect/effect_00.json ...
effect_49.json. The assets live in the main (not client) source set so the
postEffectAssetsExist game test can find them on the dedicated-server classpath;
the client resource manager loads them from the merged mod jar all the same.
Not wired into the build; rerun manually after changing the mapping below and
commit the regenerated files:

    python3 tools/gen_post_effects.py

The mapping mirrors com.bubbleshield.effect.EffectRegistry: id = theme * 5 + surface,
where theme (id // 5) picks the colors + base screen template and surface (id % 5)
nudges the template choice and the intensity. Each JSON follows the vanilla
post_effect schema (see assets/minecraft/post_effect/creeper.json in 26.2): a
"targets"/"passes" document where every pass runs a fragment shader over a
screenquad. Pass 1 applies our template main -> swap; pass 2 blits swap -> main
with the vanilla blit shader, because a pass cannot read and write the same target.
"""

import json
from pathlib import Path

OUT_DIR = Path(__file__).resolve().parent.parent / "src/main/resources/assets/bubbleshield/post_effect"

# Mirrors EffectRegistry.THEMES: (argbPrimary, argbSecondary, base screen template).
THEMES = [
    (0xFF66FFAA, 0xFF1E9E6E, "tint"),      # 0 aurora greens
    (0xFFFF33CC, 0xFF7A00FF, "wobble"),    # 1 plasma magenta
    (0xFF2E7BFF, 0xFF00CFEA, "tint"),      # 2 ocean blue
    (0xFFFF7A1A, 0xFFFFC23D, "vignette"),  # 3 ember orange
    (0xFF8A2BE2, 0xFF3B0A66, "chroma"),    # 4 void purple
    (0xFFFFD24D, 0xFFB8860B, "tint"),      # 5 gold
    (0xFF7FE9FF, 0xFF2FA8C9, "desat"),     # 6 cyan ice
    (0xFFC80F1E, 0xFF5A0710, "vignette"),  # 7 blood red
    (0xFF2ECC71, 0xFF0E7A3C, "tint"),      # 8 emerald
    (0xFFF2F2F2, 0xFF3C3C3C, "desat"),     # 9 monochrome
]

# Surfaces, in EffectRegistry.SURFACES order: PLASMA, HEX, WAVES, AURORA, SPARKLE.
# HEX's lattice look maps to the mosaic template, SPARKLE's glitter to the chroma
# fringe; the other three keep the theme's base template.
SURFACE_TEMPLATE_NUDGE = {1: "pixelate", 4: "chroma"}


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
    raise ValueError(f"unknown template {template}")


def build_effect(effect_id: int) -> dict:
    theme, surface = divmod(effect_id, 5)
    argb_primary, argb_secondary, base_template = THEMES[theme]
    template = SURFACE_TEMPLATE_NUDGE.get(surface, base_template)
    # Same derivation as EffectRegistry: theme drives speed, surface drives intensity.
    param_a = 0.4 + 0.08 * theme
    param_b = 0.6 + 0.1 * surface

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
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for effect_id in range(50):
        path = OUT_DIR / f"effect_{effect_id:02d}.json"
        path.write_text(json.dumps(build_effect(effect_id), indent=4) + "\n")
    print(f"Wrote 50 post effect JSONs to {OUT_DIR}")


if __name__ == "__main__":
    main()

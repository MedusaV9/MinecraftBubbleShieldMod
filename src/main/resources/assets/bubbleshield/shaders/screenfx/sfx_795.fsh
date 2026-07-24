#version 330

#moj_import <minecraft:globals.glsl>

// GENERATED FILE -- do not edit by hand. Emitted by tools/gen_screen_shaders.py
// for effect 795. Edit the generator and regenerate instead
// (byte-stable, fixed seed).
// [screen:heathaze:embers:surge:none]

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

// Standardized per-effect config; member order is load-bearing (it must match
// the uniform order in post_effect/effect_NN.json -- checked by the validator).
// ParamsA = [Speed, Strength, Scale, Aux]; ParamsB = [Phase, Drift, TintMix, LumaFloor].
layout(std140) uniform FxConfig {
    vec4 Primary;
    vec4 Secondary;
    vec4 ParamsA;
    vec4 ParamsB;
};

out vec4 fragColor;

float luma(vec3 c) {
    return dot(c, vec3(0.3, 0.59, 0.11));
}

float hash21(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// Gameplay-safety: any scene-sample displacement is bounded per axis.
// Call sites pass the TOTAL displacement (all offsets summed) so the bound
// cannot be defeated by stacking two half-size offsets.
vec2 safeOffset(vec2 off) {
    return clamp(off, vec2(-0.0200), vec2(0.0200));
}

vec3 sampleAt(vec2 uv) {
    return texture(InSampler, clamp(uv, 0.0, 1.0)).rgb;
}

void main() {
    // Undisplaced scene sample: the gameplay-safety floor references this.
    vec3 base = texture(InSampler, texCoord).rgb;
    float baseLuma = luma(base);
    // InSize is driver-fed; guard it so no divide below can hit zero.
    vec2 safeInSize = max(InSize, vec2(1.0));
    vec2 centered = texCoord - vec2(0.5);
    vec2 aspectCentered = centered * vec2(safeInSize.x / safeInSize.y, 1.0);
    float centerDist = length(aspectCentered);
    // GameTime wraps once per day cycle (24000 ticks); scale to roughly seconds.
    float anim = GameTime * 1200.0 * ParamsA.x + ParamsB.x * 61.8;
    float surge = 0.5 + 0.5 * sin(anim * 0.5989 + ParamsB.x * 3.1416);
    float animAmp = 0.7 + 0.3 * surge * surge * surge;
    float strength = ParamsA.y * animAmp;

    // Gentle haze plus sparse embers drifting up the screen.
    vec2 off = vec2(
        sin(texCoord.y * 69.0476 + anim * 3.6),
        cos(texCoord.x * 61.6669 + anim * 2.4) * 0.4
    ) * 0.0014 * ParamsA.y * animAmp;
    vec3 scene = sampleAt(texCoord + safeOffset(off));
    // The ember cell's y id scrolls with time; wrap it at 256 so the hash
    // input stays small enough for fp32 over the whole GameTime day.
    vec2 emberCell = floor(vec2(texCoord.x * 56.8444, texCoord.y * 26.8434 + anim * 2.7699));
    float ember = step(0.9902, hash21(vec2(emberCell.x, mod(emberCell.y, 256.0))));
    scene += Primary.rgb * ember * 0.35 * animAmp;
    // Palette-aware haze cast: lean toward the effect's own Primary hue
    // (normalized to its max channel so brightness holds) instead of a
    // hard-coded amber -- recolor-safe for non-fire palettes.
    vec3 hazeTint = mix(vec3(1.0), Primary.rgb / max(max(Primary.r, max(Primary.g, Primary.b)), 0.001), 0.1521);
    vec3 warm = scene * hazeTint;
    vec3 outColor = mix(scene, warm * mix(vec3(1.0), Primary.rgb, ParamsB.z), 0.6582);

    // Richness pass (v3): a bounded soft-contrast curve plus a vibrance
    // lift deepen the effect's read (anti-washout). Both are bounded and
    // hue-preserving, and the luma floor below still guarantees the world
    // stays readable.
    vec3 curved = clamp(outColor, 0.0, 1.0);
    outColor = mix(outColor, curved * curved * (3.0 - 2.0 * curved), 0.1589);
    outColor = clamp(mix(vec3(luma(outColor)), outColor, 1.1910), 0.0, 1.5);

    // Gameplay-safety floor: never crush the world below ParamsB.w (~0.35x),
    // and always output an opaque frame.
    outColor = max(outColor, base * ParamsB.w);
    fragColor = vec4(outColor, 1.0);
}

#version 330

#moj_import <minecraft:globals.glsl>

// GENERATED FILE -- do not edit by hand. Emitted by tools/gen_screen_shaders.py
// for effect 612. Edit the generator and regenerate instead
// (byte-stable, fixed seed).
// [screen:sketch:blueprint:surge:pulseglow]

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

// Gameplay-safety: any scene-sample displacement is bounded per axis.
// Call sites pass the TOTAL displacement (all offsets summed) so the bound
// cannot be defeated by stacking two half-size offsets.
vec2 safeOffset(vec2 off) {
    return clamp(off, vec2(-0.0200), vec2(0.0200));
}

float lumaAt(vec2 uv) {
    return dot(texture(InSampler, clamp(uv, 0.0, 1.0)).rgb, vec3(0.3, 0.59, 0.11));
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
    float surge = 0.5 + 0.5 * sin(anim * 0.5624 + ParamsB.x * 3.1416);
    float animAmp = 0.7 + 0.3 * surge * surge * surge;
    float strength = ParamsA.y * animAmp;

    // 4-tap cross-difference Sobel over scene luma.
    vec2 texel = 1.1060 / safeInSize;
    float gl = lumaAt(texCoord + safeOffset(vec2(-texel.x, 0.0)));
    float gr = lumaAt(texCoord + safeOffset(vec2(texel.x, 0.0)));
    float gu = lumaAt(texCoord + safeOffset(vec2(0.0, -texel.y)));
    float gd = lumaAt(texCoord + safeOffset(vec2(0.0, texel.y)));
    float edge = clamp(length(vec2(gr - gl, gd - gu)) * 3.4854, 0.0, 1.0);
    // Paper base: contrast-flattened luma with a palette paper tint.
    float flatTone = 0.5927 + 0.3560 * baseLuma;
    vec3 paper = vec3(flatTone) * mix(vec3(1.0), Secondary.rgb, ParamsB.z);
    // Blueprint: glowing drafting lines over a deep palette paper.
    vec3 paperDeep = Secondary.rgb * (0.2423 + 0.1781 * baseLuma);
    vec3 inked = paperDeep + Primary.rgb * edge * 0.7455;
    vec3 outColor = mix(base, inked, clamp(strength, 0.0, 1.0));

    // Overlay: a faint breathing glow of the effect color at the rim.
    float oBreath = 0.5 + 0.5 * sin(anim * 0.5804 + ParamsB.x * 6.2831);
    float oRim = smoothstep(0.4649, 1.0, centerDist);
    outColor += Primary.rgb * oRim * oBreath * 0.1439;

    // Richness pass (v3): a bounded soft-contrast curve plus a vibrance
    // lift deepen the effect's read (anti-washout). Both are bounded and
    // hue-preserving, and the luma floor below still guarantees the world
    // stays readable.
    vec3 curved = clamp(outColor, 0.0, 1.0);
    outColor = mix(outColor, curved * curved * (3.0 - 2.0 * curved), 0.1805);
    outColor = clamp(mix(vec3(luma(outColor)), outColor, 1.0974), 0.0, 1.5);

    // Gameplay-safety floor: never crush the world below ParamsB.w (~0.35x),
    // and always output an opaque frame.
    outColor = max(outColor, base * ParamsB.w);
    fragColor = vec4(outColor, 1.0);
}

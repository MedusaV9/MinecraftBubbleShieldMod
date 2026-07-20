#version 330

#moj_import <minecraft:globals.glsl>

// GENERATED FILE -- do not edit by hand. Emitted by tools/gen_screen_shaders.py
// for effect 161. Edit the generator and regenerate instead
// (byte-stable, fixed seed).
// [screen:bloomglow:diag:surge:pulseglow]

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

// Bright-pass sample: keeps only the luma above the threshold.
vec3 brightTap(vec2 uv, float threshold) {
    vec3 texel = texture(InSampler, clamp(uv, 0.0, 1.0)).rgb;
    return texel * max(luma(texel) - threshold, 0.0);
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
    float surge = 0.5 + 0.5 * sin(anim * 0.5087 + ParamsB.x * 3.1416);
    float animAmp = 0.7 + 0.3 * surge * surge * surge;
    float strength = ParamsA.y * animAmp;

    // X-shaped bright-pass blur: diagonal streaks around hot pixels.
    vec2 texel = 3.7722 / safeInSize;
    vec3 glow = brightTap(texCoord, ParamsA.w) * 0.36
        + brightTap(texCoord + safeOffset(texel), ParamsA.w) * 0.16
        + brightTap(texCoord + safeOffset(-texel), ParamsA.w) * 0.16
        + brightTap(texCoord + safeOffset(vec2(texel.x, -texel.y)), ParamsA.w) * 0.16
        + brightTap(texCoord + safeOffset(vec2(-texel.x, texel.y)), ParamsA.w) * 0.16;
    vec3 outColor = base + glow * mix(vec3(1.0), Primary.rgb, 0.5264) * strength;

    // Overlay: a faint breathing glow of the effect color at the rim.
    float oBreath = 0.5 + 0.5 * sin(anim * 0.8032 + ParamsB.x * 6.2831);
    float oRim = smoothstep(0.3609, 1.0, centerDist);
    outColor += Primary.rgb * oRim * oBreath * 0.1126;

    // Gameplay-safety floor: never crush the world below ParamsB.w (~0.35x),
    // and always output an opaque frame.
    outColor = max(outColor, base * ParamsB.w);
    fragColor = vec4(outColor, 1.0);
}

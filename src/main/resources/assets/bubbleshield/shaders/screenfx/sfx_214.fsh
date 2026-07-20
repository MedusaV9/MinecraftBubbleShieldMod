#version 330

#moj_import <minecraft:globals.glsl>

// GENERATED FILE -- do not edit by hand. Emitted by tools/gen_screen_shaders.py
// for effect 214. Edit the generator and regenerate instead
// (byte-stable, fixed seed).
// [screen:dreamblur:edgehalo:drift:pulseglow]

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
    float animRaw = GameTime * 1200.0 * ParamsA.x + ParamsB.x * 61.8;
    float anim = animRaw + 2.1356 * sin(animRaw * 0.1273) * ParamsB.y;
    float animAmp = 1.0;
    float strength = ParamsA.y * animAmp;

    // The veil thickens toward the edges: a dreamy porthole.
    vec2 texel = 3.5572 / safeInSize;
    vec3 blurred = sampleAt(texCoord) * 0.32
        + sampleAt(texCoord + safeOffset(texel)) * 0.17
        + sampleAt(texCoord + safeOffset(-texel)) * 0.17
        + sampleAt(texCoord + safeOffset(vec2(texel.x, -texel.y))) * 0.17
        + sampleAt(texCoord + safeOffset(vec2(-texel.x, texel.y))) * 0.17;
    float halo = smoothstep(0.1214, 0.7395, centerDist);
    vec3 dream = mix(base, blurred * 1.0678, clamp(strength * halo, 0.0, 0.9));
    vec2 cellUv = floor(texCoord * safeInSize / 9.0520);
    float tw = hash21(cellUv);
    float twinkle = smoothstep(0.7896, 1.0, sin(anim * 2.1356 + tw * 6.2831) * 0.5 + 0.5) * step(0.9848, tw);
    vec3 outColor = dream + Primary.rgb * twinkle * 0.3084 * animAmp;

    // Overlay: a faint breathing glow of the effect color at the rim.
    float oBreath = 0.5 + 0.5 * sin(anim * 0.7240 + ParamsB.x * 6.2831);
    float oRim = smoothstep(0.4677, 1.0, centerDist);
    outColor += Primary.rgb * oRim * oBreath * 0.0956;

    // Richness pass (v3): a bounded soft-contrast curve plus a vibrance
    // lift deepen the effect's read (anti-washout). Both are bounded and
    // hue-preserving, and the luma floor below still guarantees the world
    // stays readable.
    vec3 curved = clamp(outColor, 0.0, 1.0);
    outColor = mix(outColor, curved * curved * (3.0 - 2.0 * curved), 0.1415);
    outColor = clamp(mix(vec3(luma(outColor)), outColor, 1.0615), 0.0, 1.5);

    // Gameplay-safety floor: never crush the world below ParamsB.w (~0.35x),
    // and always output an opaque frame.
    outColor = max(outColor, base * ParamsB.w);
    fragColor = vec4(outColor, 1.0);
}

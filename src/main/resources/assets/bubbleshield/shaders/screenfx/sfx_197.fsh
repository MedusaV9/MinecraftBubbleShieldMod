#version 330

#moj_import <minecraft:globals.glsl>

// GENERATED FILE -- do not edit by hand. Emitted by tools/gen_screen_shaders.py
// for effect 197. Edit the generator and regenerate instead
// (byte-stable, fixed seed).
// [screen:frostlens:veins:pulse:sparkle]

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

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
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
    float animAmp = 0.8 + 0.2 * sin(anim * 1.0106 + ParamsB.x * 6.2831);
    float strength = ParamsA.y * animAmp;

    // Ridged ice veins crawl across the whole pane.
    float ridge = 1.0 - abs(2.0 * vnoise(texCoord * ParamsA.z + vec2(anim * 0.0243, 0.0)) - 1.0);
    float crystals = ridge * 0.7 + vnoise(texCoord * ParamsA.z * 2.3) * 0.3;
    float frost = smoothstep(0.5888, 0.8527, crystals * (0.75 + 0.25 * centerDist)) * strength;
    vec2 grain = vec2(
        vnoise(texCoord * ParamsA.z + 13.7) - 0.5,
        vnoise(texCoord * ParamsA.z + 71.3) - 0.5
    );
    vec3 scene = sampleAt(texCoord + safeOffset(grain * 0.012 * frost));
    vec3 iceColor = mix(Secondary.rgb, Primary.rgb, crystals);
    vec3 frosted = mix(scene, iceColor * (0.6 + 0.4 * crystals), 0.5144);
    vec3 outColor = mix(scene, frosted, frost);

    // Overlay: sparse twinkling motes.
    vec2 oCell = floor(texCoord * safeInSize / 16.8733);
    float oTw = hash21(oCell + vec2(37.0, 91.0));
    float oTwinkle = smoothstep(0.8650, 1.0, sin(anim * 1.3009 + oTw * 6.2831) * 0.5 + 0.5) * step(0.9783, oTw);
    outColor += Secondary.rgb * oTwinkle * 0.3553;

    // Gameplay-safety floor: never crush the world below ParamsB.w (~0.35x),
    // and always output an opaque frame.
    outColor = max(outColor, base * ParamsB.w);
    fragColor = vec4(outColor, 1.0);
}

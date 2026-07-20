#version 330

#moj_import <minecraft:globals.glsl>

// GENERATED FILE -- do not edit by hand. Emitted by tools/gen_screen_shaders.py
// for effect 327. Edit the generator and regenerate instead
// (byte-stable, fixed seed).
// [screen:duotone:tritone:pulse:sparkle]

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
    float animAmp = 0.8 + 0.2 * sin(anim * 0.8039 + ParamsB.x * 6.2831);
    float strength = ParamsA.y * animAmp;

    // Three-tone remap: shadows, a blended mid, and highlights.
    vec3 midTone = mix(Primary.rgb, Secondary.rgb, 0.5) * 0.8049;
    float lo = smoothstep(0.1569, 0.4817, baseLuma);
    float hi = smoothstep(0.6397, 0.8536, baseLuma);
    vec3 duotone = mix(mix(Secondary.rgb * 0.55, midTone, lo), Primary.rgb, hi);
    vec3 outColor = mix(base, duotone, clamp(strength, 0.0, 1.0));

    // Overlay: sparse twinkling motes.
    vec2 oCell = floor(texCoord * safeInSize / 15.1371);
    float oTw = hash21(oCell + vec2(37.0, 91.0));
    float oTwinkle = smoothstep(0.8354, 1.0, sin(anim * 1.5733 + oTw * 6.2831) * 0.5 + 0.5) * step(0.9784, oTw);
    outColor += Secondary.rgb * oTwinkle * 0.3829;

    // Gameplay-safety floor: never crush the world below ParamsB.w (~0.35x),
    // and always output an opaque frame.
    outColor = max(outColor, base * ParamsB.w);
    fragColor = vec4(outColor, 1.0);
}

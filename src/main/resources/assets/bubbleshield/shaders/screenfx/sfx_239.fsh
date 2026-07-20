#version 330

#moj_import <minecraft:globals.glsl>

// GENERATED FILE -- do not edit by hand. Emitted by tools/gen_screen_shaders.py
// for effect 239. Edit the generator and regenerate instead
// (byte-stable, fixed seed).
// [screen:bloomglow:starburst:pulse:grain]

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
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
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
    vec2 centered = texCoord - vec2(0.5);
    vec2 aspectCentered = centered * vec2(InSize.x / max(InSize.y, 1.0), 1.0);
    float centerDist = length(aspectCentered);
    // GameTime wraps once per day cycle (24000 ticks); scale to roughly seconds.
    float anim = GameTime * 1200.0 * ParamsA.x + ParamsB.x * 61.8;
    float animAmp = 0.8 + 0.2 * sin(anim * 1.0831 + ParamsB.x * 6.2831);
    float strength = ParamsA.y * animAmp;

    // 6-tap three-axis starburst around bright sources.
    vec2 texel = 4.7330 / InSize;
    vec3 glow = brightTap(texCoord, ParamsA.w) * 0.34;
    for (int i = 0; i < 3; i++) {
        float a = float(i) * 1.0471976 + 0.3147;
        vec2 arm = vec2(cos(a), sin(a)) * texel;
        glow += brightTap(texCoord + arm, ParamsA.w) * 0.11;
        glow += brightTap(texCoord - arm, ParamsA.w) * 0.11;
    }
    vec3 outColor = base + glow * mix(vec3(1.0), Primary.rgb, 0.6006) * strength;

    // Overlay: living film grain.
    outColor += (hash21(floor(texCoord * InSize) + vec2(floor(anim * 5.7123), 0.0)) - 0.5) * 0.0371;

    // Gameplay-safety floor: never crush the world below ParamsB.w (~0.35x),
    // and always output an opaque frame.
    outColor = max(outColor, base * ParamsB.w);
    fragColor = vec4(outColor, 1.0);
}

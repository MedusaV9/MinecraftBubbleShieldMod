#version 330

#moj_import <minecraft:globals.glsl>

// GENERATED FILE -- do not edit by hand. Emitted by tools/gen_screen_shaders.py
// for effect 055. Edit the generator and regenerate instead
// (byte-stable, fixed seed).
// [screen:scanlines:vertical:pulse:grain]

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

float hash11(float p) {
    p = fract(p * 443.8975);
    p += p * (p + 19.19);
    return fract(p * p);
}

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

// Gameplay-safety: any scene-sample displacement is bounded per axis.
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
    vec2 centered = texCoord - vec2(0.5);
    vec2 aspectCentered = centered * vec2(InSize.x / max(InSize.y, 1.0), 1.0);
    float centerDist = length(aspectCentered);
    // GameTime wraps once per day cycle (24000 ticks); scale to roughly seconds.
    float anim = GameTime * 1200.0 * ParamsA.x + ParamsB.x * 61.8;
    float animAmp = 0.8 + 0.2 * sin(anim * 0.6388 + ParamsB.x * 6.2831);
    float strength = ParamsA.y * animAmp;

    // Vertical raster columns with per-column shimmer.
    float col = floor(texCoord.x * ParamsA.z);
    float jitter = (hash11(col + floor(anim * 6.0) * 47.3) - 0.5) * 0.0031 * ParamsA.y;
    vec3 scene = sampleAt(texCoord + safeOffset(vec2(0.0, jitter)));
    float scan = 0.5 + 0.5 * sin((texCoord.x * ParamsA.z + anim * 0.4319) * 6.2831);
    float darken = 1.0 - ParamsA.y * 0.2864 * scan * animAmp;
    vec3 outColor = mix(scene * darken, scene * darken * Primary.rgb, ParamsB.z);

    // Overlay: living film grain.
    outColor += (hash21(floor(texCoord * InSize) + vec2(floor(anim * 8.0186), 0.0)) - 0.5) * 0.0347;

    // Gameplay-safety floor: never crush the world below ParamsB.w (~0.35x),
    // and always output an opaque frame.
    outColor = max(outColor, base * ParamsB.w);
    fragColor = vec4(outColor, 1.0);
}

#version 330

#moj_import <minecraft:globals.glsl>

// GENERATED FILE -- do not edit by hand. Emitted by tools/gen_screen_shaders.py
// for effect 303. Edit the generator and regenerate instead
// (byte-stable, fixed seed).
// [screen:glitch:rowcol:pulse:grain]

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
    float animAmp = 0.8 + 0.2 * sin(anim * 0.8081 + ParamsB.x * 6.2831);
    float strength = ParamsA.y * animAmp;

    // Row tears and column jitters interleave on alternating frames.
    float frame = floor(anim * 7.2415);
    float rowRoll = hash11(floor(texCoord.y * 30.9835) * 7.31 + frame * 13.7);
    float colRoll = hash11(floor(texCoord.x * 17.6228) * 5.13 + frame * 7.9);
    float tearX = (rowRoll > 0.88 ? rowRoll - 0.88 : 0.0) * 0.4150 * ParamsA.y * animAmp;
    float tearY = (colRoll > 0.9 ? colRoll - 0.9 : 0.0) * 0.2253 * ParamsA.y * animAmp;
    vec2 baseCoord = texCoord + safeOffset(vec2(tearX, tearY));
    float split = (0.003 + (tearX + tearY) * 0.3) * ParamsA.y;
    float red = sampleAt(baseCoord + safeOffset(vec2(split, 0.0))).r;
    float green = sampleAt(baseCoord).g;
    float blue = sampleAt(baseCoord - safeOffset(vec2(split, 0.0))).b;
    vec3 torn = vec3(red, green, blue);
    float flash = (tearX + tearY) > 0.0001 ? ParamsB.z : 0.0;
    vec3 outColor = mix(torn, torn * Primary.rgb, flash);

    // Overlay: living film grain.
    outColor += (hash21(floor(texCoord * InSize) + vec2(floor(anim * 6.3514), 0.0)) - 0.5) * 0.0397;

    // Gameplay-safety floor: never crush the world below ParamsB.w (~0.35x),
    // and always output an opaque frame.
    outColor = max(outColor, base * ParamsB.w);
    fragColor = vec4(outColor, 1.0);
}

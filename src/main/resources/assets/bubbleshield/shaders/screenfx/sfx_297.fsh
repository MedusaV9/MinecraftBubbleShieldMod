#version 330

#moj_import <minecraft:globals.glsl>

// GENERATED FILE -- do not edit by hand. Emitted by tools/gen_screen_shaders.py
// for effect 297. Edit the generator and regenerate instead
// (byte-stable, fixed seed).
// [screen:glitch:blocks:surge:sparkle]

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
    float surge = 0.5 + 0.5 * sin(anim * 0.3819 + ParamsB.x * 3.1416);
    float animAmp = 0.7 + 0.3 * surge * surge * surge;
    float strength = ParamsA.y * animAmp;

    // Coarse block dropouts: cells occasionally displace as a chunk.
    float frame = floor(anim * 4.3778);
    vec2 cell = floor(texCoord * vec2(9.4194, 6.7916));
    float cellRoll = hash11(cell.x * 3.7 + cell.y * 11.9 + frame * 5.3);
    vec2 jitter = cellRoll > 0.9079
        ? (vec2(hash11(cellRoll * 91.7), hash11(cellRoll * 47.3)) - 0.5) * 0.0343 * ParamsA.y
        : vec2(0.0);
    vec2 baseCoord = texCoord + safeOffset(jitter);
    float split = 0.003 * ParamsA.y;
    float red = sampleAt(baseCoord + safeOffset(vec2(split, 0.0))).r;
    float green = sampleAt(baseCoord).g;
    float blue = sampleAt(baseCoord - safeOffset(vec2(split, 0.0))).b;
    vec3 torn = vec3(red, green, blue);
    float flash = length(jitter) > 0.0001 ? ParamsB.z * 1.2 : 0.0;
    vec3 outColor = mix(torn, mix(torn * Primary.rgb, torn * Secondary.rgb, hash11(cellRoll * 3.1)), clamp(flash, 0.0, 1.0));

    // Overlay: sparse twinkling motes.
    vec2 oCell = floor(texCoord * InSize / 14.7375);
    float oTw = hash21(oCell + vec2(37.0, 91.0));
    float oTwinkle = smoothstep(0.8628, 1.0, sin(anim * 2.1931 + oTw * 6.2831) * 0.5 + 0.5) * step(0.9862, oTw);
    outColor += Secondary.rgb * oTwinkle * 0.2835;

    // Gameplay-safety floor: never crush the world below ParamsB.w (~0.35x),
    // and always output an opaque frame.
    outColor = max(outColor, base * ParamsB.w);
    fragColor = vec4(outColor, 1.0);
}

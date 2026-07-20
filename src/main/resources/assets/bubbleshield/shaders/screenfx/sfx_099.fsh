#version 330

#moj_import <minecraft:globals.glsl>

// GENERATED FILE -- do not edit by hand. Emitted by tools/gen_screen_shaders.py
// for effect 099. Edit the generator and regenerate instead
// (byte-stable, fixed seed).
// [screen:glitch:rowcol:pulse:pulseglow]

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

// small-multiplier hash (Hoskins 0.1031 style): stays alive in fp32 for
// inputs up to ~1e5, unlike the fract(p * 443.8975) form which collapses
// to 0 once time-derived inputs grow past a few minutes of GameTime.
float hash11(float p) {
    p = fract(p * 0.1031);
    p *= p + 33.33;
    p *= p + p;
    return fract(p);
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
    float animAmp = 0.8 + 0.2 * sin(anim * 0.9191 + ParamsB.x * 6.2831);
    float strength = ParamsA.y * animAmp;

    // Row tears and column jitters interleave on alternating frames.
    // Frame counter wrapped at 1024: keeps the hash input fp32-friendly.
    float frame = mod(floor(anim * 5.3562), 1024.0);
    float rowRoll = hash11(floor(texCoord.y * 26.4448) * 7.31 + frame * 13.7);
    float colRoll = hash11(floor(texCoord.x * 14.3942) * 5.13 + frame * 7.9);
    float tearX = (rowRoll > 0.88 ? rowRoll - 0.88 : 0.0) * 0.4379 * ParamsA.y * animAmp;
    float tearY = (colRoll > 0.9 ? colRoll - 0.9 : 0.0) * 0.3837 * ParamsA.y * animAmp;
    vec2 baseOff = vec2(tearX, tearY);
    float split = (0.003 + (tearX + tearY) * 0.3) * ParamsA.y;
    float red = sampleAt(texCoord + safeOffset(baseOff + vec2(split, 0.0))).r;
    float green = sampleAt(texCoord + safeOffset(baseOff)).g;
    float blue = sampleAt(texCoord + safeOffset(baseOff - vec2(split, 0.0))).b;
    vec3 torn = vec3(red, green, blue);
    float flash = (tearX + tearY) > 0.0001 ? ParamsB.z : 0.0;
    vec3 outColor = mix(torn, torn * Primary.rgb, flash);

    // Overlay: a faint breathing glow of the effect color at the rim.
    float oBreath = 0.5 + 0.5 * sin(anim * 0.9779 + ParamsB.x * 6.2831);
    float oRim = smoothstep(0.4741, 1.0, centerDist);
    outColor += Primary.rgb * oRim * oBreath * 0.1005;

    // Gameplay-safety floor: never crush the world below ParamsB.w (~0.35x),
    // and always output an opaque frame.
    outColor = max(outColor, base * ParamsB.w);
    fragColor = vec4(outColor, 1.0);
}

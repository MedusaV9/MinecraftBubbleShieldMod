#version 330

#moj_import <minecraft:globals.glsl>

// GENERATED FILE -- do not edit by hand. Emitted by tools/gen_screen_shaders.py
// for effect 754. Edit the generator and regenerate instead
// (byte-stable, fixed seed).
// [screen:vhs:tracking:steady:pulseglow]

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

// 1 - smoothstep with ASCENDING edges. Replaces every reversed-edge
// smoothstep(hi, lo, x) call: edge0 >= edge1 is undefined by the GLSL
// spec; this form is numerically identical on conforming drivers.
float invsmooth(float lo, float hi, float x) {
    return 1.0 - smoothstep(lo, hi, x);
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
    float animAmp = 1.0;
    float strength = ParamsA.y * animAmp;

    float vhsK = min(ParamsA.y, 1.0);
    float frame = mod(floor(anim * 0.2070), 1024.0);
    float row = floor(texCoord.y * 133.8535);
    // Scanline-phase wobble: every row leans on a slow sine phase.
    float phaseWobble = sin(texCoord.y * 256.1569 + anim * 1.2134) * 0.0013 * vhsK * animAmp;
    // A tracking band crawls up the tape and shears the rows it crosses.
    float bandPos = fract(anim * 0.0455);
    float bandMask = invsmooth(0.0, 0.0785, abs(texCoord.y - bandPos));
    float shear = (hash11(row * 3.7 + frame * 17.3) - 0.5) * 0.0304 * vhsK * bandMask;
    vec2 baseOff = vec2(phaseWobble + shear, 0.0);
    // Chroma bleed: the color channels smear sideways off the luma. The
    // row shift and the bleed are summed before the single clamp.
    float bleed = 0.0033 * vhsK;
    float red = sampleAt(texCoord + safeOffset(baseOff + vec2(bleed, 0.0))).r;
    float green = sampleAt(texCoord + safeOffset(baseOff)).g;
    float blue = sampleAt(texCoord + safeOffset(baseOff - vec2(bleed * 1.6427, 0.0))).b;
    vec3 taped = vec3(red, green, blue);
    taped = mix(taped, vec3(luma(taped)) * 1.1922, bandMask * 0.4053 * vhsK);
    vec3 outColor = mix(taped, taped * Primary.rgb, ParamsB.z);

    // Overlay: a faint breathing glow of the effect color at the rim.
    float oBreath = 0.5 + 0.5 * sin(anim * 0.5542 + ParamsB.x * 6.2831);
    float oRim = smoothstep(0.4458, 1.0, centerDist);
    outColor += Primary.rgb * oRim * oBreath * 0.0940;

    // Richness pass (v3): a bounded soft-contrast curve plus a vibrance
    // lift deepen the effect's read (anti-washout). Both are bounded and
    // hue-preserving, and the luma floor below still guarantees the world
    // stays readable.
    vec3 curved = clamp(outColor, 0.0, 1.0);
    outColor = mix(outColor, curved * curved * (3.0 - 2.0 * curved), 0.2048);
    outColor = clamp(mix(vec3(luma(outColor)), outColor, 1.1841), 0.0, 1.5);

    // Gameplay-safety floor: never crush the world below ParamsB.w (~0.35x),
    // and always output an opaque frame.
    outColor = max(outColor, base * ParamsB.w);
    fragColor = vec4(outColor, 1.0);
}

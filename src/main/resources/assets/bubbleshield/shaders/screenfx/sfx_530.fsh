#version 330

#moj_import <minecraft:globals.glsl>

// GENERATED FILE -- do not edit by hand. Emitted by tools/gen_screen_shaders.py
// for effect 530. Edit the generator and regenerate instead
// (byte-stable, fixed seed).
// [screen:vhs:headswitch:drift:none]

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
    float anim = animRaw + 1.6677 * sin(animRaw * 0.1739) * ParamsB.y;
    float animAmp = 1.0;
    float strength = ParamsA.y * animAmp;

    float vhsK = min(ParamsA.y, 1.0);
    float frame = mod(floor(anim * 0.2048), 1024.0);
    float row = floor(texCoord.y * 135.1296);
    // Scanline-phase wobble: every row leans on a slow sine phase.
    float phaseWobble = sin(texCoord.y * 267.2155 + anim * 1.2152) * 0.0017 * vhsK * animAmp;
    // Head-switch tear: the bottom edge of the frame always shears.
    float switchZone = invsmooth(0.0, 0.0738, texCoord.y);
    float shear = switchZone * (sin(anim * 0.6706 + texCoord.y * 73.4929) * 0.5 + 0.7) * 0.0137 * vhsK;
    vec2 baseOff = vec2(phaseWobble + shear, 0.0);
    // Chroma bleed: the color channels smear sideways off the luma. The
    // row shift and the bleed are summed before the single clamp.
    float bleed = 0.0032 * vhsK;
    float red = sampleAt(texCoord + safeOffset(baseOff + vec2(bleed, 0.0))).r;
    float green = sampleAt(texCoord + safeOffset(baseOff)).g;
    float blue = sampleAt(texCoord + safeOffset(baseOff - vec2(bleed * 1.4885, 0.0))).b;
    vec3 taped = vec3(red, green, blue);
    taped = mix(taped, vec3(luma(taped)), switchZone * 0.3567);
    vec3 outColor = mix(taped, taped * Primary.rgb, ParamsB.z);

    // Richness pass (v3): a bounded soft-contrast curve plus a vibrance
    // lift deepen the effect's read (anti-washout). Both are bounded and
    // hue-preserving, and the luma floor below still guarantees the world
    // stays readable.
    vec3 curved = clamp(outColor, 0.0, 1.0);
    outColor = mix(outColor, curved * curved * (3.0 - 2.0 * curved), 0.2070);
    outColor = clamp(mix(vec3(luma(outColor)), outColor, 1.1953), 0.0, 1.5);

    // Gameplay-safety floor: never crush the world below ParamsB.w (~0.35x),
    // and always output an opaque frame.
    outColor = max(outColor, base * ParamsB.w);
    fragColor = vec4(outColor, 1.0);
}

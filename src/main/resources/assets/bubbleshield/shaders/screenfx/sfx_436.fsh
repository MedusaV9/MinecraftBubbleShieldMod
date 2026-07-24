#version 330

#moj_import <minecraft:globals.glsl>

// GENERATED FILE -- do not edit by hand. Emitted by tools/gen_screen_shaders.py
// for effect 436. Edit the generator and regenerate instead
// (byte-stable, fixed seed).
// [screen:kaleido:wedge6:steady:none]

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

// two-argument atan is undefined at the exact origin; guard it
float safeAtan(float y, float x) {
    return (abs(x) < 1e-6 && abs(y) < 1e-6) ? 0.0 : atan(y, x);
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

    // Kaleidoscopic refraction: 6 angular wedges; each pixel leans toward
    // its fold position (bounded), and the wedge seams glow.
    float angle = safeAtan(aspectCentered.y, aspectCentered.x);
    float wedge = 6.2831853 / 6.0000;
    float local = mod(angle + anim * 0.1378, wedge) - wedge * 0.5;
    float folded = abs(local);
    vec2 dir = vec2(cos(folded + anim * 0.0790), sin(folded + anim * 0.0414));
    vec2 invAspect = vec2(safeInSize.y / safeInSize.x, 1.0);
    vec2 target = vec2(0.5) + dir * centerDist * invAspect;
    vec3 scene = sampleAt(texCoord + safeOffset((target - texCoord) * strength));
    float seam = invsmooth(0.0, 0.0812, abs(local)) * smoothstep(0.05, 0.25, centerDist);
    vec3 outColor = scene + mix(Primary.rgb, Secondary.rgb, texCoord.y) * seam * 0.2110 * strength;

    // Richness pass (v3): a bounded soft-contrast curve plus a vibrance
    // lift deepen the effect's read (anti-washout). Both are bounded and
    // hue-preserving, and the luma floor below still guarantees the world
    // stays readable.
    vec3 curved = clamp(outColor, 0.0, 1.0);
    outColor = mix(outColor, curved * curved * (3.0 - 2.0 * curved), 0.1200);
    outColor = clamp(mix(vec3(luma(outColor)), outColor, 1.1924), 0.0, 1.5);

    // Gameplay-safety floor: never crush the world below ParamsB.w (~0.35x),
    // and always output an opaque frame.
    outColor = max(outColor, base * ParamsB.w);
    fragColor = vec4(outColor, 1.0);
}

#version 330

#moj_import <minecraft:globals.glsl>

// GENERATED FILE -- do not edit by hand. Emitted by tools/gen_screen_shaders.py
// for effect 115. Edit the generator and regenerate instead
// (byte-stable, fixed seed).
// [screen:frostlens:veins:surge:pulseglow]

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
    float surge = 0.5 + 0.5 * sin(anim * 0.6269 + ParamsB.x * 3.1416);
    float animAmp = 0.7 + 0.3 * surge * surge * surge;
    float strength = ParamsA.y * animAmp;

    // Ridged ice veins crawl across the whole pane.
    float ridge = 1.0 - abs(2.0 * vnoise(texCoord * ParamsA.z + vec2(anim * 0.0197, 0.0)) - 1.0);
    float crystals = ridge * 0.7 + vnoise(texCoord * ParamsA.z * 2.3) * 0.3;
    float frost = smoothstep(0.5754, 0.8210, crystals * (0.75 + 0.25 * centerDist)) * strength;
    vec2 grain = vec2(
        vnoise(texCoord * ParamsA.z + 13.7) - 0.5,
        vnoise(texCoord * ParamsA.z + 71.3) - 0.5
    );
    vec3 scene = sampleAt(texCoord + safeOffset(grain * 0.012 * frost));
    vec3 iceColor = mix(Secondary.rgb, Primary.rgb, crystals);
    vec3 frosted = mix(scene, iceColor * (0.6 + 0.4 * crystals), 0.5641);
    // Frost visibility calibration: real rime scatters WHITE over bright
    // backgrounds (sky), so the frosted layer whitens with baseLuma, and
    // sparse crystal facets catch glints that keep the sheet readable.
    frosted = mix(frosted, vec3(1.0), baseLuma * baseLuma * 0.2595 * crystals);
    float glint = smoothstep(0.8534, 1.0, crystals) * (0.5 + 0.5 * sin(anim * 1.2319 + crystals * 37.0));
    vec3 outColor = mix(scene, frosted, frost) + iceColor * glint * frost * 0.4;

    // Overlay: a faint breathing glow of the effect color at the rim.
    float oBreath = 0.5 + 0.5 * sin(anim * 0.9451 + ParamsB.x * 6.2831);
    float oRim = smoothstep(0.3561, 1.0, centerDist);
    outColor += Primary.rgb * oRim * oBreath * 0.1536;

    // Richness pass (v3): a bounded soft-contrast curve plus a vibrance
    // lift deepen the effect's read (anti-washout). Both are bounded and
    // hue-preserving, and the luma floor below still guarantees the world
    // stays readable.
    vec3 curved = clamp(outColor, 0.0, 1.0);
    outColor = mix(outColor, curved * curved * (3.0 - 2.0 * curved), 0.1462);
    outColor = clamp(mix(vec3(luma(outColor)), outColor, 1.0615), 0.0, 1.5);

    // Gameplay-safety floor: never crush the world below ParamsB.w (~0.35x),
    // and always output an opaque frame.
    outColor = max(outColor, base * ParamsB.w);
    fragColor = vec4(outColor, 1.0);
}

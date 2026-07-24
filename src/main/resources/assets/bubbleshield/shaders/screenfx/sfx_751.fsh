#version 330

#moj_import <minecraft:globals.glsl>

// GENERATED FILE -- do not edit by hand. Emitted by tools/gen_screen_shaders.py
// for effect 751. Edit the generator and regenerate instead
// (byte-stable, fixed seed).
// [screen:thermal:inverted:steady:sparkle]

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

    // Drifting hotspot field biases the reading before the ramp.
    float blob = vnoise(texCoord * 4.4456 + vec2(anim * 0.0506, anim * 0.0278));
    // Inverted sensor: shadows read hot, highlights read cold. Dark
    // scenes therefore read ~fully hot, so the blend cap is tighter
    // (anti-whiteout: a black scene stays under ~0.5 output luma).
    float thermalMix = clamp(strength, 0.0, 0.55);
    float heat = clamp(1.0 - baseLuma + (blob - 0.5) * 0.2105 * min(strength, 1.0), 0.0, 1.0);
    // False-color ramp: cold Secondary depths through the palette to a
    // capped hot peak (never pure white -- legibility ceiling).
    vec3 coldTone = Secondary.rgb * 0.2132;
    vec3 ramped = mix(coldTone, Secondary.rgb, smoothstep(0.0, 0.4616, heat));
    ramped = mix(ramped, Primary.rgb, smoothstep(0.4292, 0.7858, heat));
    ramped = mix(ramped, vec3(0.8349), smoothstep(0.8750, 1.0, heat));
    // Luma band: keep a fixed share of the real scene, ceiling the read
    // hue-preservingly at 0.75 luma and floor it at 0.05 per channel so
    // no palette/variant can white-out or black-out the screen.
    vec3 toned = mix(base, ramped, thermalMix);
    float tonedLuma = luma(toned);
    toned *= min(tonedLuma, 0.75) / max(tonedLuma, 0.001);
    vec3 outColor = max(toned, vec3(0.05));

    // Overlay: sparse twinkling motes. Photosensitivity: the twinkle
    // sine runs on an INDEPENDENT unit-rate clock (GameTime only, never
    // the paramA-scaled anim, which reaches ~3-5 Hz at these ids); the
    // baked per-id rate keeps every flash cycle under 2.4 Hz.
    vec2 oCell = floor(texCoord * safeInSize / 10.5699);
    float oTw = hash21(oCell + vec2(37.0, 91.0));
    float oClock = GameTime * 1200.0 + ParamsB.x * 61.8;
    float oTwinkle = smoothstep(0.8410, 1.0, sin(oClock * 8.7149 + oTw * 6.2831) * 0.5 + 0.5) * step(0.9767, oTw);
    outColor += Secondary.rgb * oTwinkle * 0.3891;

    // Richness pass (v3): a bounded soft-contrast curve plus a vibrance
    // lift deepen the effect's read (anti-washout). Both are bounded and
    // hue-preserving, and the luma floor below still guarantees the world
    // stays readable.
    vec3 curved = clamp(outColor, 0.0, 1.0);
    outColor = mix(outColor, curved * curved * (3.0 - 2.0 * curved), 0.2179);
    outColor = clamp(mix(vec3(luma(outColor)), outColor, 1.1121), 0.0, 1.5);

    // Gameplay-safety floor: never crush the world below ParamsB.w (~0.35x),
    // and always output an opaque frame.
    outColor = max(outColor, base * ParamsB.w);
    fragColor = vec4(outColor, 1.0);
}

#version 330

#moj_import <minecraft:globals.glsl>

// GENERATED FILE -- do not edit by hand. Emitted by tools/gen_screen_shaders.py
// for effect 583. Edit the generator and regenerate instead
// (byte-stable, fixed seed).
// [screen:thermal:classic:steady:pulseglow]

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
    float blob = vnoise(texCoord * 5.2393 + vec2(anim * 0.0708, anim * 0.0242));
    float heat = clamp(baseLuma + (blob - 0.5) * 0.2484 * min(strength, 1.0), 0.0, 1.0);
    // False-color ramp: cold Secondary depths through the palette to a
    // white-hot peak.
    vec3 coldTone = Secondary.rgb * 0.1411;
    vec3 ramped = mix(coldTone, Secondary.rgb, smoothstep(0.0, 0.4747, heat));
    ramped = mix(ramped, Primary.rgb, smoothstep(0.4070, 0.7802, heat));
    ramped = mix(ramped, vec3(1.0), smoothstep(0.8580, 1.0, heat));
    vec3 outColor = mix(base, ramped, clamp(strength, 0.0, 1.0));

    // Overlay: a faint breathing glow of the effect color at the rim.
    float oBreath = 0.5 + 0.5 * sin(anim * 0.5563 + ParamsB.x * 6.2831);
    float oRim = smoothstep(0.4700, 1.0, centerDist);
    outColor += Primary.rgb * oRim * oBreath * 0.1044;

    // Richness pass (v3): a bounded soft-contrast curve plus a vibrance
    // lift deepen the effect's read (anti-washout). Both are bounded and
    // hue-preserving, and the luma floor below still guarantees the world
    // stays readable.
    vec3 curved = clamp(outColor, 0.0, 1.0);
    outColor = mix(outColor, curved * curved * (3.0 - 2.0 * curved), 0.1912);
    outColor = clamp(mix(vec3(luma(outColor)), outColor, 1.2193), 0.0, 1.5);

    // Gameplay-safety floor: never crush the world below ParamsB.w (~0.35x),
    // and always output an opaque frame.
    outColor = max(outColor, base * ParamsB.w);
    fragColor = vec4(outColor, 1.0);
}

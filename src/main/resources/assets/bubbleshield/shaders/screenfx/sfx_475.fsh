#version 330

#moj_import <minecraft:globals.glsl>

// GENERATED FILE -- do not edit by hand. Emitted by tools/gen_screen_shaders.py
// for effect 475. Edit the generator and regenerate instead
// (byte-stable, fixed seed).
// [screen:gloom:heartbeat:pulse:none]

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
    float animAmp = 0.8 + 0.2 * sin(anim * 1.1130 + ParamsB.x * 6.2831);
    float strength = ParamsA.y * animAmp;

    // Cold lift: the scene drains toward a Secondary-tinted grey.
    vec3 coldGrey = vec3(baseLuma) * mix(vec3(1.0), Secondary.rgb, ParamsB.z);
    vec3 chilled = mix(base, coldGrey, 0.5380 * clamp(strength, 0.0, 1.0));
    // Double-lobed slow pulse (a sub-1 Hz heartbeat, never a strobe).
    float beatPhase = anim * 0.3073 + ParamsB.x * 6.2831;
    float lub = max(sin(beatPhase), 0.0);
    float dub = max(sin(beatPhase + 2.4393), 0.0);
    float beat = lub * lub + 0.4668 * dub * dub;
    float swell = 1.0 - 0.1211 * beat;
    float rim = smoothstep(0.3200, 0.7533, centerDist + beat * 0.0696);
    vec3 dimmed = chilled * swell;
    vec3 outColor = mix(dimmed, Primary.rgb * 0.1303, rim * clamp(strength, 0.0, 1.0) * 0.6621);

    // Richness pass (v3): a bounded soft-contrast curve plus a vibrance
    // lift deepen the effect's read (anti-washout). Both are bounded and
    // hue-preserving, and the luma floor below still guarantees the world
    // stays readable.
    vec3 curved = clamp(outColor, 0.0, 1.0);
    outColor = mix(outColor, curved * curved * (3.0 - 2.0 * curved), 0.1104);
    outColor = clamp(mix(vec3(luma(outColor)), outColor, 1.1700), 0.0, 1.5);

    // Gameplay-safety floor: never crush the world below ParamsB.w (~0.35x),
    // and always output an opaque frame.
    outColor = max(outColor, base * ParamsB.w);
    fragColor = vec4(outColor, 1.0);
}

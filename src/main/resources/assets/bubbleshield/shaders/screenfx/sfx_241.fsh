#version 330

#moj_import <minecraft:globals.glsl>

// GENERATED FILE -- do not edit by hand. Emitted by tools/gen_screen_shaders.py
// for effect 241. Edit the generator and regenerate instead
// (byte-stable, fixed seed).
// [screen:wobble:harmonic:pulse:pulseglow]

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
    float animAmp = 0.8 + 0.2 * sin(anim * 1.0271 + ParamsB.x * 6.2831);
    float strength = ParamsA.y * animAmp;

    // Two out-of-phase harmonics per axis give a rolling, watery sway.
    vec2 off = (vec2(sin(texCoord.y * ParamsA.z + anim), cos(texCoord.x * ParamsA.z + anim))
        + 0.5823 * vec2(sin(texCoord.y * 49.3505 - anim * 1.7),
            cos(texCoord.x * 45.8222 + anim * 1.3))) * ParamsA.y * animAmp;
    vec3 scene = sampleAt(texCoord + safeOffset(off));
    vec3 outColor = mix(scene, scene * Primary.rgb, ParamsB.z);

    // Overlay: a faint breathing glow of the effect color at the rim.
    float oBreath = 0.5 + 0.5 * sin(anim * 0.6070 + ParamsB.x * 6.2831);
    float oRim = smoothstep(0.4189, 1.0, centerDist);
    outColor += Primary.rgb * oRim * oBreath * 0.1222;

    // Gameplay-safety floor: never crush the world below ParamsB.w (~0.35x),
    // and always output an opaque frame.
    outColor = max(outColor, base * ParamsB.w);
    fragColor = vec4(outColor, 1.0);
}

#version 330

#moj_import <minecraft:globals.glsl>

// GENERATED FILE -- do not edit by hand. Emitted by tools/gen_screen_shaders.py
// for effect 214. Edit the generator and regenerate instead
// (byte-stable, fixed seed).
// [screen:dreamblur:edgehalo:drift:pulseglow]

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
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
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
    float animRaw = GameTime * 1200.0 * ParamsA.x + ParamsB.x * 61.8;
    float anim = animRaw + 2.1356 * sin(animRaw * 0.1273) * ParamsB.y;
    float animAmp = 1.0;
    float strength = ParamsA.y * animAmp;

    // The veil thickens toward the edges: a dreamy porthole.
    vec2 texel = 3.5572 / InSize;
    vec3 blurred = sampleAt(texCoord) * 0.32
        + sampleAt(texCoord + texel) * 0.17
        + sampleAt(texCoord - texel) * 0.17
        + sampleAt(texCoord + vec2(texel.x, -texel.y)) * 0.17
        + sampleAt(texCoord + vec2(-texel.x, texel.y)) * 0.17;
    float halo = smoothstep(0.1214, 0.7395, centerDist);
    vec3 dream = mix(base, blurred * 1.0678, clamp(strength * halo, 0.0, 0.9));
    vec2 cellUv = floor(texCoord * InSize / 9.0520);
    float tw = hash21(cellUv);
    float twinkle = smoothstep(0.7896, 1.0, sin(anim * 2.1356 + tw * 6.2831) * 0.5 + 0.5) * step(0.9848, tw);
    vec3 outColor = dream + Primary.rgb * twinkle * 0.3084 * animAmp;

    // Overlay: a faint breathing glow of the effect color at the rim.
    float oBreath = 0.5 + 0.5 * sin(anim * 0.7240 + ParamsB.x * 6.2831);
    float oRim = smoothstep(0.4677, 1.0, centerDist);
    outColor += Primary.rgb * oRim * oBreath * 0.0956;

    // Gameplay-safety floor: never crush the world below ParamsB.w (~0.35x),
    // and always output an opaque frame.
    outColor = max(outColor, base * ParamsB.w);
    fragColor = vec4(outColor, 1.0);
}

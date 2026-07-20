#version 330

#moj_import <minecraft:globals.glsl>

// GENERATED FILE -- do not edit by hand. Emitted by tools/gen_screen_shaders.py
// for effect 039. Edit the generator and regenerate instead
// (byte-stable, fixed seed).
// [screen:ripple:twin:surge:grain]

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
    float surge = 0.5 + 0.5 * sin(anim * 0.6333 + ParamsB.x * 3.1416);
    float animAmp = 0.7 + 0.3 * surge * surge * surge;
    float strength = ParamsA.y * animAmp;

    // Two off-center ripple sources interfere across the frame.
    vec2 d1 = texCoord - vec2(0.3748, 0.4118);
    vec2 d2 = texCoord - vec2(0.7623, 0.5353);
    float ring = 0.5 * sin(length(d1) * ParamsA.z - anim * 2.6) + 0.5 * sin(length(d2) * ParamsA.z - anim * 3.4);
    float fade = smoothstep(0.95, 0.4, centerDist);
    vec2 off = (normalize(d1 + vec2(0.0001)) + normalize(d2 + vec2(0.0001))) * ring * fade * 0.0058 * ParamsA.y * animAmp;
    vec3 scene = sampleAt(texCoord + safeOffset(off));
    float crest = smoothstep(0.5, 1.0, ring) * fade;
    vec3 outColor = mix(scene, scene * Primary.rgb, crest * ParamsB.z * animAmp);

    // Overlay: living film grain.
    outColor += (hash21(floor(texCoord * InSize) + vec2(floor(anim * 5.5134), 0.0)) - 0.5) * 0.0245;

    // Gameplay-safety floor: never crush the world below ParamsB.w (~0.35x),
    // and always output an opaque frame.
    outColor = max(outColor, base * ParamsB.w);
    fragColor = vec4(outColor, 1.0);
}

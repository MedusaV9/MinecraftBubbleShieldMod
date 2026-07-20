#version 330

#moj_import <minecraft:globals.glsl>

// GENERATED FILE -- do not edit by hand. Emitted by tools/gen_screen_shaders.py
// for effect 269. Edit the generator and regenerate instead
// (byte-stable, fixed seed).
// [screen:heathaze:rising:drift:sparkle]

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
    float animRaw = GameTime * 1200.0 * ParamsA.x + ParamsB.x * 61.8;
    float anim = animRaw + 2.7807 * sin(animRaw * 0.1066) * ParamsB.y;
    float animAmp = 1.0;
    float strength = ParamsA.y * animAmp;

    // Rising shimmer, strongest in the lower half where the hot air is.
    float rising = smoothstep(0.9, 0.2, texCoord.y);
    vec2 off = vec2(
        sin(texCoord.y * 80.7169 + anim * 4.0) + sin(texCoord.y * 50.1338 - anim * 2.6),
        cos(texCoord.x * 51.1649 + anim * 3.1) * 0.4
    ) * 0.0018 * ParamsA.y * animAmp * (0.4 + 0.6 * rising);
    vec3 scene = sampleAt(texCoord + safeOffset(off));
    vec3 warm = scene * vec3(1.0824, 1.0, 0.9037);
    vec3 outColor = mix(scene, warm * mix(vec3(1.0), Primary.rgb, ParamsB.z), 0.6808);

    // Overlay: sparse twinkling motes.
    vec2 oCell = floor(texCoord * InSize / 10.7140);
    float oTw = hash21(oCell + vec2(37.0, 91.0));
    float oTwinkle = smoothstep(0.8138, 1.0, sin(anim * 1.5077 + oTw * 6.2831) * 0.5 + 0.5) * step(0.9796, oTw);
    outColor += Secondary.rgb * oTwinkle * 0.3886;

    // Gameplay-safety floor: never crush the world below ParamsB.w (~0.35x),
    // and always output an opaque frame.
    outColor = max(outColor, base * ParamsB.w);
    fragColor = vec4(outColor, 1.0);
}

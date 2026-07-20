#version 330

#moj_import <minecraft:globals.glsl>

// GENERATED FILE -- do not edit by hand. Emitted by tools/gen_screen_shaders.py
// for effect 234. Edit the generator and regenerate instead
// (byte-stable, fixed seed).
// [screen:radialblur:zoomspin:steady:sparkle]

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
    float animAmp = 1.0;
    float strength = ParamsA.y * animAmp;

    // 12-tap combined zoom + spin streak: a slow vortex pull.
    float blur = 0.0281 * ParamsA.y * animAmp * smoothstep(0.08, 0.7, centerDist);
    float arc = 0.0322 * ParamsA.y * animAmp;
    vec3 accum = vec3(0.0);
    for (int i = 0; i < 12; i++) {
        float t = float(i) / 11.0000;
        float a = (t - 0.5) * arc;
        float ca = cos(a);
        float sa = sin(a);
        vec2 rc = vec2(centered.x * ca - centered.y * sa, centered.x * sa + centered.y * ca);
        accum += sampleAt(texCoord + safeOffset(vec2(0.5) + rc * (1.0 - blur * t) - texCoord));
    }
    vec3 streaked = accum / 12.0000;
    vec3 outColor = mix(streaked, streaked * Primary.rgb, smoothstep(0.4, 0.8, centerDist) * ParamsB.z);

    // Overlay: sparse twinkling motes.
    vec2 oCell = floor(texCoord * InSize / 11.5605);
    float oTw = hash21(oCell + vec2(37.0, 91.0));
    float oTwinkle = smoothstep(0.8797, 1.0, sin(anim * 2.2727 + oTw * 6.2831) * 0.5 + 0.5) * step(0.9857, oTw);
    outColor += Secondary.rgb * oTwinkle * 0.2866;

    // Gameplay-safety floor: never crush the world below ParamsB.w (~0.35x),
    // and always output an opaque frame.
    outColor = max(outColor, base * ParamsB.w);
    fragColor = vec4(outColor, 1.0);
}

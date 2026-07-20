#version 330

#moj_import <minecraft:globals.glsl>

// GENERATED FILE -- do not edit by hand. Emitted by tools/gen_screen_shaders.py
// for effect 092. Edit the generator and regenerate instead
// (byte-stable, fixed seed).
// [screen:radialblur:zoomspin:pulse:grain]

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
    float animAmp = 0.8 + 0.2 * sin(anim * 0.8441 + ParamsB.x * 6.2831);
    float strength = ParamsA.y * animAmp;

    // 12-tap combined zoom + spin streak: a slow vortex pull.
    float blur = 0.0308 * ParamsA.y * animAmp * smoothstep(0.08, 0.7, centerDist);
    float arc = 0.0221 * ParamsA.y * animAmp;
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

    // Overlay: living film grain (frame counter wrapped at 256 so the
    // hash input stays fp32-friendly across the whole GameTime day).
    float grainFrame = mod(floor(anim * 7.2016), 256.0);
    outColor += (hash21(floor(texCoord * safeInSize) + vec2(grainFrame, 0.0)) - 0.5) * 0.0228;

    // Gameplay-safety floor: never crush the world below ParamsB.w (~0.35x),
    // and always output an opaque frame.
    outColor = max(outColor, base * ParamsB.w);
    fragColor = vec4(outColor, 1.0);
}

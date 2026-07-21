#version 330

#moj_import <minecraft:globals.glsl>

// GENERATED FILE -- do not edit by hand. Emitted by tools/gen_screen_shaders.py
// for effect 531. Edit the generator and regenerate instead
// (byte-stable, fixed seed).
// [screen:gloom:creeping:pulse:sparkle]

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
    float animAmp = 0.8 + 0.2 * sin(anim * 1.0966 + ParamsB.x * 6.2831);
    float strength = ParamsA.y * animAmp;

    // Cold lift: the scene drains toward a Secondary-tinted grey.
    vec3 coldGrey = vec3(baseLuma) * mix(vec3(1.0), Secondary.rgb, ParamsB.z);
    vec3 chilled = mix(base, coldGrey, 0.4737 * clamp(strength, 0.0, 1.0));
    // The dark rim creeps inward along a noise-eaten front, then recedes.
    float front = vnoise(texCoord * 6.2143 + vec2(anim * 0.0384, 0.0));
    float breathe = 0.5 + 0.5 * sin(anim * 0.2238 + ParamsB.x * 6.2831);
    float reach = 0.3337 + 0.1171 * breathe;
    float rim = smoothstep(reach, reach + 0.3584, centerDist + (front - 0.5) * 0.1838);
    float swell = 1.0 - 0.0933 * breathe;
    vec3 dimmed = chilled * swell;
    vec3 outColor = mix(dimmed, Primary.rgb * 0.0779, rim * clamp(strength, 0.0, 1.0) * 0.6588);

    // Overlay: sparse twinkling motes.
    vec2 oCell = floor(texCoord * safeInSize / 17.2915);
    float oTw = hash21(oCell + vec2(37.0, 91.0));
    float oTwinkle = smoothstep(0.8481, 1.0, sin(anim * 2.0429 + oTw * 6.2831) * 0.5 + 0.5) * step(0.9856, oTw);
    outColor += Secondary.rgb * oTwinkle * 0.3327;

    // Richness pass (v3): a bounded soft-contrast curve plus a vibrance
    // lift deepen the effect's read (anti-washout). Both are bounded and
    // hue-preserving, and the luma floor below still guarantees the world
    // stays readable.
    vec3 curved = clamp(outColor, 0.0, 1.0);
    outColor = mix(outColor, curved * curved * (3.0 - 2.0 * curved), 0.1583);
    outColor = clamp(mix(vec3(luma(outColor)), outColor, 1.1165), 0.0, 1.5);

    // Gameplay-safety floor: never crush the world below ParamsB.w (~0.35x),
    // and always output an opaque frame.
    outColor = max(outColor, base * ParamsB.w);
    fragColor = vec4(outColor, 1.0);
}

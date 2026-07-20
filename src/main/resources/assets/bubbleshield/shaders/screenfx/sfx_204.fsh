#version 330

#moj_import <minecraft:globals.glsl>

// GENERATED FILE -- do not edit by hand. Emitted by tools/gen_screen_shaders.py
// for effect 204. Edit the generator and regenerate instead
// (byte-stable, fixed seed).
// [screen:scanlines:grid:drift:sparkle]

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
    float animRaw = GameTime * 1200.0 * ParamsA.x + ParamsB.x * 61.8;
    float anim = animRaw + 2.6355 * sin(animRaw * 0.1232) * ParamsB.y;
    float animAmp = 1.0;
    float strength = ParamsA.y * animAmp;

    // Faint raster grid: both axes carry drifting line sets.
    float lineY = min(ParamsA.z, safeInSize.y * 0.25);
    float lineX = min(ParamsA.z * 0.7159, safeInSize.x * 0.25);
    float scanY = 0.5 + 0.5 * sin((texCoord.y * lineY - anim * 0.3339) * 6.2831);
    float scanX = 0.5 + 0.5 * sin((texCoord.x * lineX + anim * 0.3401) * 6.2831);
    float darken = 1.0 - ParamsA.y * animAmp * (0.2056 * scanY + 0.1318 * scanX);
    vec3 outColor = mix(base * darken, base * darken * Primary.rgb, ParamsB.z);

    // Overlay: sparse twinkling motes.
    vec2 oCell = floor(texCoord * safeInSize / 12.3712);
    float oTw = hash21(oCell + vec2(37.0, 91.0));
    float oTwinkle = smoothstep(0.8193, 1.0, sin(anim * 1.8558 + oTw * 6.2831) * 0.5 + 0.5) * step(0.9827, oTw);
    outColor += Secondary.rgb * oTwinkle * 0.3548;

    // Richness pass (v3): a bounded soft-contrast curve plus a vibrance
    // lift deepen the effect's read (anti-washout). Both are bounded and
    // hue-preserving, and the luma floor below still guarantees the world
    // stays readable.
    vec3 curved = clamp(outColor, 0.0, 1.0);
    outColor = mix(outColor, curved * curved * (3.0 - 2.0 * curved), 0.1807);
    outColor = clamp(mix(vec3(luma(outColor)), outColor, 1.0689), 0.0, 1.5);

    // Gameplay-safety floor: never crush the world below ParamsB.w (~0.35x),
    // and always output an opaque frame.
    outColor = max(outColor, base * ParamsB.w);
    fragColor = vec4(outColor, 1.0);
}

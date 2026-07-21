#version 330

#moj_import <minecraft:globals.glsl>

// GENERATED FILE -- do not edit by hand. Emitted by tools/gen_screen_shaders.py
// for effect 625. Edit the generator and regenerate instead
// (byte-stable, fixed seed).
// [screen:edgeglow:thick:steady:none]

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
// Call sites pass the TOTAL displacement (all offsets summed) so the bound
// cannot be defeated by stacking two half-size offsets.
vec2 safeOffset(vec2 off) {
    return clamp(off, vec2(-0.0200), vec2(0.0200));
}

float lumaAt(vec2 uv) {
    return dot(texture(InSampler, clamp(uv, 0.0, 1.0)).rgb, vec3(0.3, 0.59, 0.11));
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

    // Wide-radius Sobel: thick painterly outlines.
    vec2 texel = 2.0 / safeInSize;
    float tl = lumaAt(texCoord + safeOffset(texel * vec2(-1.0, -1.0)));
    float tc = lumaAt(texCoord + safeOffset(texel * vec2(0.0, -1.0)));
    float tr = lumaAt(texCoord + safeOffset(texel * vec2(1.0, -1.0)));
    float ml = lumaAt(texCoord + safeOffset(texel * vec2(-1.0, 0.0)));
    float mr = lumaAt(texCoord + safeOffset(texel * vec2(1.0, 0.0)));
    float bl = lumaAt(texCoord + safeOffset(texel * vec2(-1.0, 1.0)));
    float bc = lumaAt(texCoord + safeOffset(texel * vec2(0.0, 1.0)));
    float br = lumaAt(texCoord + safeOffset(texel * vec2(1.0, 1.0)));
    float gx = (tr + 2.0 * mr + br) - (tl + 2.0 * ml + bl);
    float gy = (bl + 2.0 * bc + br) - (tl + 2.0 * tc + tr);
    float edge = clamp(length(vec2(gx, gy)), 0.0, 1.0);
    // Additive-family calibration: the glow strength is capped at 1.0 and
    // attenuated on already-bright pixels so edges accent, not overpower.
    float glowK = min(strength, 1.0) * (1.0 - 0.5 * baseLuma);
    vec3 outColor = base + Primary.rgb * pow(edge, 0.7196) * glowK * 0.7800;

    // Richness pass (v3): a bounded soft-contrast curve plus a vibrance
    // lift deepen the effect's read (anti-washout). Both are bounded and
    // hue-preserving, and the luma floor below still guarantees the world
    // stays readable.
    vec3 curved = clamp(outColor, 0.0, 1.0);
    outColor = mix(outColor, curved * curved * (3.0 - 2.0 * curved), 0.1972);
    outColor = clamp(mix(vec3(luma(outColor)), outColor, 1.1298), 0.0, 1.5);

    // Gameplay-safety floor: never crush the world below ParamsB.w (~0.35x),
    // and always output an opaque frame.
    outColor = max(outColor, base * ParamsB.w);
    fragColor = vec4(outColor, 1.0);
}

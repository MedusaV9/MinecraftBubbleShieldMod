#version 330

#moj_import <minecraft:globals.glsl>

// GENERATED FILE -- do not edit by hand. Emitted by tools/gen_screen_shaders.py
// for effect 121. Edit the generator and regenerate instead
// (byte-stable, fixed seed).
// [screen:radialblur:spin:pulse:pulseglow]

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
    float animAmp = 0.8 + 0.2 * sin(anim * 1.1722 + ParamsB.x * 6.2831);
    float strength = ParamsA.y * animAmp;

    // 12-tap tangential spin streak around the center.
    float arc = 0.0353 * ParamsA.y * animAmp * smoothstep(0.1, 0.7, centerDist);
    vec3 accum = vec3(0.0);
    for (int i = 0; i < 12; i++) {
        float t = (float(i) / 11.0000 - 0.5) * arc;
        float ca = cos(t);
        float sa = sin(t);
        vec2 rc = vec2(centered.x * ca - centered.y * sa, centered.x * sa + centered.y * ca);
        accum += sampleAt(texCoord + safeOffset(vec2(0.5) + rc - texCoord));
    }
    vec3 streaked = accum / 12.0000;
    vec3 outColor = mix(streaked, streaked * Primary.rgb, smoothstep(0.35, 0.8, centerDist) * ParamsB.z);

    // Overlay: a faint breathing glow of the effect color at the rim.
    float oBreath = 0.5 + 0.5 * sin(anim * 0.9971 + ParamsB.x * 6.2831);
    float oRim = smoothstep(0.4900, 1.0, centerDist);
    outColor += Primary.rgb * oRim * oBreath * 0.1287;

    // Richness pass (v3): a bounded soft-contrast curve plus a vibrance
    // lift deepen the effect's read (anti-washout). Both are bounded and
    // hue-preserving, and the luma floor below still guarantees the world
    // stays readable.
    vec3 curved = clamp(outColor, 0.0, 1.0);
    outColor = mix(outColor, curved * curved * (3.0 - 2.0 * curved), 0.1662);
    outColor = clamp(mix(vec3(luma(outColor)), outColor, 1.1606), 0.0, 1.5);

    // Gameplay-safety floor: never crush the world below ParamsB.w (~0.35x),
    // and always output an opaque frame.
    outColor = max(outColor, base * ParamsB.w);
    fragColor = vec4(outColor, 1.0);
}

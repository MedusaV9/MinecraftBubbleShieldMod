#version 330

#moj_import <minecraft:globals.glsl>

// GENERATED FILE -- do not edit by hand. Emitted by tools/gen_screen_shaders.py
// for effect 694. Edit the generator and regenerate instead
// (byte-stable, fixed seed).
// [screen:underwater:shallows:drift:sparkle]

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

// 1 - smoothstep with ASCENDING edges. Replaces every reversed-edge
// smoothstep(hi, lo, x) call: edge0 >= edge1 is undefined by the GLSL
// spec; this form is numerically identical on conforming drivers.
float invsmooth(float lo, float hi, float x) {
    return 1.0 - smoothstep(lo, hi, x);
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
    float animRaw = GameTime * 1200.0 * ParamsA.x + ParamsB.x * 61.8;
    float anim = animRaw + 1.5665 * sin(animRaw * 0.1008) * ParamsB.y;
    float animAmp = 1.0;
    float strength = ParamsA.y * animAmp;

    // Caustic field (slow vnoise drift) modulates the sway amplitude.
    float caustic = vnoise(texCoord * 6.4737 + vec2(anim * 0.0811, anim * 0.0929));
    vec2 sway = vec2(
        sin(texCoord.y * 49.8177 + anim * 1.2038),
        cos(texCoord.x * 42.0606 - anim * 0.9997)
    ) * 0.0041 * min(strength, 1.0) * animAmp * (0.35 + 0.65 * caustic);
    vec3 scene = sampleAt(texCoord + safeOffset(sway));
    // Light shafts: soft diagonal bands, brightest near the surface (top).
    vec2 shaftDir = vec2(-0.5870, 0.8096);
    float shaftBand = pow(0.5 + 0.5 * sin(dot(texCoord, shaftDir) * 11.6318 + anim * 0.4383), 5.0785);
    float shaft = shaftBand * smoothstep(0.2523, 0.8947, texCoord.y) * (0.4 + 0.6 * caustic);
    // Depth grade: the scene sinks toward the palette with screen depth.
    float depthMix = invsmooth(0.1255, 0.8069, texCoord.y);
    vec3 deepTone = scene * mix(Primary.rgb, Secondary.rgb, depthMix);
    vec3 graded = mix(scene, deepTone, 0.3527 * clamp(strength, 0.0, 1.0));
    vec3 outColor = graded + Primary.rgb * shaft * 0.1848 * min(strength, 1.0);

    // Overlay: sparse twinkling motes.
    vec2 oCell = floor(texCoord * safeInSize / 16.8221);
    float oTw = hash21(oCell + vec2(37.0, 91.0));
    float oTwinkle = smoothstep(0.8712, 1.0, sin(anim * 2.0221 + oTw * 6.2831) * 0.5 + 0.5) * step(0.9844, oTw);
    outColor += Secondary.rgb * oTwinkle * 0.2747;

    // Richness pass (v3): a bounded soft-contrast curve plus a vibrance
    // lift deepen the effect's read (anti-washout). Both are bounded and
    // hue-preserving, and the luma floor below still guarantees the world
    // stays readable.
    vec3 curved = clamp(outColor, 0.0, 1.0);
    outColor = mix(outColor, curved * curved * (3.0 - 2.0 * curved), 0.1200);
    outColor = clamp(mix(vec3(luma(outColor)), outColor, 1.0911), 0.0, 1.5);

    // Gameplay-safety floor: never crush the world below ParamsB.w (~0.35x),
    // and always output an opaque frame.
    outColor = max(outColor, base * ParamsB.w);
    fragColor = vec4(outColor, 1.0);
}

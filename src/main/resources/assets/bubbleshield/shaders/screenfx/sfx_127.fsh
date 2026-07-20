#version 330

#moj_import <minecraft:globals.glsl>

// GENERATED FILE -- do not edit by hand. Emitted by tools/gen_screen_shaders.py
// for effect 127. Edit the generator and regenerate instead
// (byte-stable, fixed seed).
// [screen:kaleido:wedge6:steady:grain]

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

    // Kaleidoscopic refraction: 6 angular wedges; each pixel leans toward
    // its fold position (bounded), and the wedge seams glow.
    float angle = atan(aspectCentered.y, aspectCentered.x);
    float wedge = 6.2831853 / 6.0000;
    float local = mod(angle + anim * 0.1320, wedge) - wedge * 0.5;
    float folded = abs(local);
    vec2 dir = vec2(cos(folded + anim * 0.0650), sin(folded + anim * 0.0717));
    vec2 invAspect = vec2(max(InSize.y, 1.0) / max(InSize.x, 1.0), 1.0);
    vec2 target = vec2(0.5) + dir * centerDist * invAspect;
    vec3 scene = sampleAt(texCoord + safeOffset((target - texCoord) * strength));
    float seam = smoothstep(0.0918, 0.0, abs(local)) * smoothstep(0.05, 0.25, centerDist);
    vec3 outColor = scene + mix(Primary.rgb, Secondary.rgb, texCoord.y) * seam * 0.2256 * strength;

    // Overlay: living film grain.
    outColor += (hash21(floor(texCoord * InSize) + vec2(floor(anim * 6.5612), 0.0)) - 0.5) * 0.0227;

    // Gameplay-safety floor: never crush the world below ParamsB.w (~0.35x),
    // and always output an opaque frame.
    outColor = max(outColor, base * ParamsB.w);
    fragColor = vec4(outColor, 1.0);
}

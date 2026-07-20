#version 330

#moj_import <minecraft:globals.glsl>

// GENERATED FILE -- do not edit by hand. Emitted by tools/gen_screen_shaders.py
// for effect 342. Edit the generator and regenerate instead
// (byte-stable, fixed seed).
// [screen:moire:rotmoire:drift:none]

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

void main() {
    // Undisplaced scene sample: the gameplay-safety floor references this.
    vec3 base = texture(InSampler, texCoord).rgb;
    float baseLuma = luma(base);
    vec2 centered = texCoord - vec2(0.5);
    vec2 aspectCentered = centered * vec2(InSize.x / max(InSize.y, 1.0), 1.0);
    float centerDist = length(aspectCentered);
    // GameTime wraps once per day cycle (24000 ticks); scale to roughly seconds.
    float animRaw = GameTime * 1200.0 * ParamsA.x + ParamsB.x * 61.8;
    float anim = animRaw + 2.8003 * sin(animRaw * 0.1044) * ParamsB.y;
    float animAmp = 1.0;
    float strength = ParamsA.y * animAmp;

    // The second grating slowly rotates: the fringes wheel and breathe.
    float rot = 0.9856 + 0.1165 + sin(anim * 0.1046) * 0.0562;
    float g1 = sin((texCoord.x * 0.5524 + texCoord.y * 0.8336) * ParamsA.z);
    float g2 = sin((texCoord.x * cos(rot) + texCoord.y * sin(rot)) * ParamsA.z * 1.0261);
    float inter = g1 * g2;
    float mask = smoothstep(-0.2, 1.0, inter);
    vec3 outColor = base * (1.0 - 0.2919 * strength * (1.0 - mask));
    outColor = mix(outColor, outColor * Primary.rgb, ParamsB.z * strength * mask);

    // Gameplay-safety floor: never crush the world below ParamsB.w (~0.35x),
    // and always output an opaque frame.
    outColor = max(outColor, base * ParamsB.w);
    fragColor = vec4(outColor, 1.0);
}

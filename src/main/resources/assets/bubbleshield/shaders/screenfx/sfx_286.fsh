#version 330

#moj_import <minecraft:globals.glsl>

// GENERATED FILE -- do not edit by hand. Emitted by tools/gen_screen_shaders.py
// for effect 286. Edit the generator and regenerate instead
// (byte-stable, fixed seed).
// [screen:edgeglow:duo:pulse:sparkle]

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

float lumaAt(vec2 uv) {
    return dot(texture(InSampler, clamp(uv, 0.0, 1.0)).rgb, vec3(0.3, 0.59, 0.11));
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
    float animAmp = 0.8 + 0.2 * sin(anim * 0.6189 + ParamsB.x * 6.2831);
    float strength = ParamsA.y * animAmp;

    // Direction-split edges: horizontal gradients glow Primary, vertical Secondary.
    vec2 texel = 1.0 / InSize;
    float tl = lumaAt(texCoord + texel * vec2(-1.0, -1.0));
    float tc = lumaAt(texCoord + texel * vec2(0.0, -1.0));
    float tr = lumaAt(texCoord + texel * vec2(1.0, -1.0));
    float ml = lumaAt(texCoord + texel * vec2(-1.0, 0.0));
    float mr = lumaAt(texCoord + texel * vec2(1.0, 0.0));
    float bl = lumaAt(texCoord + texel * vec2(-1.0, 1.0));
    float bc = lumaAt(texCoord + texel * vec2(0.0, 1.0));
    float br = lumaAt(texCoord + texel * vec2(1.0, 1.0));
    float gx = (tr + 2.0 * mr + br) - (tl + 2.0 * ml + bl);
    float gy = (bl + 2.0 * bc + br) - (tl + 2.0 * tc + tr);
    float edge = clamp(length(vec2(gx, gy)), 0.0, 1.0);
    vec3 glowColor = mix(Secondary.rgb, Primary.rgb, clamp(0.5 + 0.5 * (abs(gx) - abs(gy)) * 2.0, 0.0, 1.0));
    vec3 outColor = base + glowColor * edge * strength;

    // Overlay: sparse twinkling motes.
    vec2 oCell = floor(texCoord * InSize / 15.6016);
    float oTw = hash21(oCell + vec2(37.0, 91.0));
    float oTwinkle = smoothstep(0.8508, 1.0, sin(anim * 2.0367 + oTw * 6.2831) * 0.5 + 0.5) * step(0.9795, oTw);
    outColor += Secondary.rgb * oTwinkle * 0.2668;

    // Gameplay-safety floor: never crush the world below ParamsB.w (~0.35x),
    // and always output an opaque frame.
    outColor = max(outColor, base * ParamsB.w);
    fragColor = vec4(outColor, 1.0);
}

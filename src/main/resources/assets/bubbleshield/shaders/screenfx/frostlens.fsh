#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform FrostLensConfig {
    vec4 Primary;
    vec4 Secondary;
    float Intensity;
    float CrystalScale;
};

out vec4 fragColor;

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
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
    // Frost mask: strongest at the screen edges, clear in the middle (vignette-shaped),
    // with crystalline noise eating inward so the creep line looks jagged.
    vec2 centered = texCoord - vec2(0.5);
    float edgeDist = length(centered);
    float crystals = vnoise(texCoord * CrystalScale) * 0.6 + vnoise(texCoord * CrystalScale * 2.7) * 0.4;
    float frost = smoothstep(0.28, 0.62, edgeDist + (crystals - 0.5) * 0.28) * Intensity;

    // Frozen areas refract slightly along the crystal gradient.
    vec2 grain = vec2(
        vnoise(texCoord * CrystalScale + 13.7) - 0.5,
        vnoise(texCoord * CrystalScale + 71.3) - 0.5
    );
    vec4 diffuseColor = texture(InSampler, clamp(texCoord + grain * 0.012 * frost, 0.0, 1.0));

    // Ice grade: brightened, leaning from Secondary (deep ice) to Primary (rime).
    vec3 iceColor = mix(Secondary.rgb, Primary.rgb, crystals);
    vec3 frosted = mix(diffuseColor.rgb, iceColor * (0.6 + 0.4 * crystals), 0.55);
    vec3 outColor = mix(diffuseColor.rgb, frosted, frost);
    fragColor = vec4(outColor, 1.0);
}

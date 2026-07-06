#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform BloomGlowConfig {
    vec4 Primary;
    float Intensity;
    float Threshold;
};

out vec4 fragColor;

// Bright-pass sample: keeps only the luma above Threshold.
vec3 brightTap(vec2 uv) {
    vec3 texel = texture(InSampler, clamp(uv, 0.0, 1.0)).rgb;
    float luma = dot(texel, vec3(0.3, 0.59, 0.11));
    return texel * max(luma - Threshold, 0.0);
}

void main() {
    vec4 diffuseColor = texture(InSampler, texCoord);

    // 5-tap cross blur of the bright pass, weighted toward the center.
    vec2 texel = 4.0 / InSize;
    vec3 glow = brightTap(texCoord) * 0.4
            + brightTap(texCoord + vec2(texel.x, 0.0)) * 0.15
            + brightTap(texCoord - vec2(texel.x, 0.0)) * 0.15
            + brightTap(texCoord + vec2(0.0, texel.y)) * 0.15
            + brightTap(texCoord - vec2(0.0, texel.y)) * 0.15;

    // Additive glow, tinted toward the shield's primary color.
    vec3 outColor = diffuseColor.rgb + glow * mix(vec3(1.0), Primary.rgb, 0.6) * Intensity;
    fragColor = vec4(outColor, 1.0);
}

#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform PixelateConfig {
    vec4 Primary;
    float Resolution;
    float MosaicSize;
};

out vec4 fragColor;

void main() {
    // Mosaic + posterize, following vanilla's bits.fsh.
    vec2 mosaicInSize = InSize / MosaicSize;
    vec2 fractPix = fract(texCoord * mosaicInSize) / mosaicInSize;

    vec4 baseTexel = texture(InSampler, texCoord - fractPix);
    vec3 posterized = baseTexel.rgb - fract(baseTexel.rgb * Resolution) / Resolution;

    vec3 outColor = mix(posterized, posterized * Primary.rgb, 0.15);
    fragColor = vec4(outColor, 1.0);
}

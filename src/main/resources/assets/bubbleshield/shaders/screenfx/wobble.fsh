#version 330

#moj_import <minecraft:globals.glsl>

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform WobbleConfig {
    vec4 Primary;
    float Frequency;
    float Amplitude;
    float Speed;
};

out vec4 fragColor;

void main() {
    // GameTime wraps once per day cycle (24000 ticks); scale to roughly seconds.
    float time = GameTime * 1200.0 * Speed;
    vec2 offset = vec2(
        sin(texCoord.y * Frequency + time),
        cos(texCoord.x * Frequency + time)
    ) * Amplitude;
    vec2 uv = clamp(texCoord + offset, 0.0, 1.0);

    vec4 diffuseColor = texture(InSampler, uv);
    vec3 outColor = mix(diffuseColor.rgb, diffuseColor.rgb * Primary.rgb, 0.15);
    fragColor = vec4(outColor, 1.0);
}

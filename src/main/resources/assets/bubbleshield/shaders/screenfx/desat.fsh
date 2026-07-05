#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform DesatConfig {
    vec4 Primary;
    float Desaturation;
};

out vec4 fragColor;

void main() {
    vec4 diffuseColor = texture(InSampler, texCoord);
    float luma = dot(diffuseColor.rgb, vec3(0.3, 0.59, 0.11));
    // Grays pick up a whisper of the shield's primary color.
    vec3 desaturated = vec3(luma) * mix(vec3(1.0), Primary.rgb, 0.2);
    vec3 outColor = mix(diffuseColor.rgb, desaturated, Desaturation);
    fragColor = vec4(outColor, 1.0);
}

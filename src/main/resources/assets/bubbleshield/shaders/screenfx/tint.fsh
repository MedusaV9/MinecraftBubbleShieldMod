#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform TintConfig {
    vec4 Primary;
    vec4 Secondary;
    float Intensity;
};

out vec4 fragColor;

void main() {
    vec4 diffuseColor = texture(InSampler, texCoord);
    float luma = dot(diffuseColor.rgb, vec3(0.3, 0.59, 0.11));
    // Color-grade: shadows lean toward Secondary, highlights toward Primary.
    vec3 graded = mix(Secondary.rgb, Primary.rgb, luma) * luma;
    vec3 outColor = mix(diffuseColor.rgb, graded, Intensity);
    fragColor = vec4(outColor, 1.0);
}

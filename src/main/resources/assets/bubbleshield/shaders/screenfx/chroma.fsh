#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform ChromaConfig {
    vec4 Primary;
    float OffsetAmount;
};

out vec4 fragColor;

void main() {
    vec2 centered = texCoord - vec2(0.5);
    // Channel separation grows toward the screen edges, like a lens fringe.
    vec2 shift = centered * OffsetAmount;

    float r = texture(InSampler, clamp(texCoord + shift, 0.0, 1.0)).r;
    float g = texture(InSampler, texCoord).g;
    float b = texture(InSampler, clamp(texCoord - shift, 0.0, 1.0)).b;

    vec3 outColor = mix(vec3(r, g, b), vec3(r, g, b) * Primary.rgb, 0.1);
    fragColor = vec4(outColor, 1.0);
}

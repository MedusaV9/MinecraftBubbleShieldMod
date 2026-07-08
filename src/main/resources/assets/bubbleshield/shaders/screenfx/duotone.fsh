#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform DuotoneConfig {
    vec4 Primary;
    vec4 Secondary;
    float Intensity;
};

out vec4 fragColor;

void main() {
    vec4 diffuseColor = texture(InSampler, texCoord);

    // Two-tone remap: shadows sink into the Secondary color, highlights rise
    // into the Primary color, with a soft-knee luminance ramp between them.
    float luma = dot(diffuseColor.rgb, vec3(0.3, 0.59, 0.11));
    float ramp = smoothstep(0.08, 0.92, luma);
    vec3 duotone = mix(Secondary.rgb * 0.55, Primary.rgb * (0.75 + 0.25 * ramp), ramp);

    vec3 outColor = mix(diffuseColor.rgb, duotone, Intensity);
    fragColor = vec4(outColor, 1.0);
}

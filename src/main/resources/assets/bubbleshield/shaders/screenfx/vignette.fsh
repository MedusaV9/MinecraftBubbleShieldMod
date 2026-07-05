#version 330

#moj_import <minecraft:globals.glsl>

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform VignetteConfig {
    vec4 Primary;
    float Intensity;
    float PulseSpeed;
};

out vec4 fragColor;

void main() {
    vec4 diffuseColor = texture(InSampler, texCoord);

    // GameTime wraps once per day cycle (24000 ticks); scale to roughly seconds.
    float pulse = 0.75 + 0.25 * sin(GameTime * 1200.0 * PulseSpeed);
    vec2 centered = texCoord - vec2(0.5);
    float edge = smoothstep(0.25, 0.71, length(centered));

    vec3 outColor = mix(diffuseColor.rgb, Primary.rgb, edge * Intensity * pulse);
    fragColor = vec4(outColor, 1.0);
}

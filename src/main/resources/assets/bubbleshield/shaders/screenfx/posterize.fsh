#version 330

#moj_import <minecraft:globals.glsl>

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform PosterizeConfig {
    vec4 Primary;
    float Levels;
    float PulseSpeed;
};

out vec4 fragColor;

void main() {
    // GameTime wraps once per day cycle (24000 ticks); scale to roughly seconds.
    float time = GameTime * 1200.0 * PulseSpeed;

    vec4 diffuseColor = texture(InSampler, texCoord);

    // Quantize each channel to a slowly breathing number of levels, so the
    // banding coarsens and refines on the GameTime pulse.
    float levels = max(2.0, Levels + sin(time) * 1.5);
    vec3 quantized = floor(diffuseColor.rgb * levels + 0.5) / levels;

    // The flattened bands lean toward the shield's primary color.
    vec3 outColor = mix(quantized, quantized * Primary.rgb, 0.2);
    fragColor = vec4(outColor, 1.0);
}

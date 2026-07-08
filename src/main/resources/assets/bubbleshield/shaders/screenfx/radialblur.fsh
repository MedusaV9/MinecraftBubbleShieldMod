#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform RadialBlurConfig {
    vec4 Primary;
    float Intensity;
};

out vec4 fragColor;

void main() {
    // 8-tap streak from the screen center outward: each tap samples a bit
    // closer to the center, so bright content smears radially. The center
    // itself stays sharp (zero offset) and the streak grows toward the edges.
    vec2 centered = texCoord - vec2(0.5);
    float dist = length(centered);
    float strength = 0.035 * Intensity * smoothstep(0.05, 0.7, dist);

    vec3 accum = vec3(0.0);
    for (int i = 0; i < 8; i++) {
        float t = float(i) / 7.0;
        vec2 tapCoord = texCoord - centered * strength * t;
        accum += texture(InSampler, clamp(tapCoord, 0.0, 1.0)).rgb;
    }
    vec3 streaked = accum / 8.0;

    // The streaked rim picks up a whisper of the shield's primary color.
    vec3 outColor = mix(streaked, streaked * Primary.rgb, smoothstep(0.4, 0.8, dist) * 0.2);
    fragColor = vec4(outColor, 1.0);
}

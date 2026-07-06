#version 330

#moj_import <minecraft:globals.glsl>

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform HeatHazeConfig {
    vec4 Primary;
    float Intensity;
    float Speed;
};

out vec4 fragColor;

void main() {
    // GameTime wraps once per day cycle (24000 ticks); scale to roughly seconds.
    float time = GameTime * 1200.0 * Speed;

    // Small rising refraction shimmer: two out-of-phase sine columns, stronger in
    // the lower half of the screen where the "hot air" is.
    float rising = smoothstep(0.9, 0.2, texCoord.y);
    vec2 offset = vec2(
        sin(texCoord.y * 90.0 + time * 4.0) + sin(texCoord.y * 47.0 - time * 2.6),
        cos(texCoord.x * 63.0 + time * 3.1) * 0.4
    ) * 0.0016 * Intensity * (0.4 + 0.6 * rising);

    vec4 diffuseColor = texture(InSampler, clamp(texCoord + offset, 0.0, 1.0));

    // Warm grade: lift the reds, sink the blues, tinted by the shield's primary color.
    vec3 warm = diffuseColor.rgb * vec3(1.08, 1.0, 0.92);
    vec3 outColor = mix(diffuseColor.rgb, warm * mix(vec3(1.0), Primary.rgb, 0.12), 0.6);
    fragColor = vec4(outColor, 1.0);
}

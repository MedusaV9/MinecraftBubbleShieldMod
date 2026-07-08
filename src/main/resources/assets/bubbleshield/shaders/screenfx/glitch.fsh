#version 330

#moj_import <minecraft:globals.glsl>

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform GlitchConfig {
    vec4 Primary;
    float Intensity;
    float Speed;
};

out vec4 fragColor;

float hash11(float p) {
    p = fract(p * 443.8975);
    p += p * (p + 19.19);
    return fract(p * p);
}

void main() {
    // GameTime wraps once per day cycle (24000 ticks); scale to roughly seconds.
    float time = GameTime * 1200.0 * Speed;
    // The whole glitch state re-rolls a few times per second.
    float frame = floor(time * 6.0);

    // Horizontal tear lines: a handful of scan bands shear sideways this frame.
    float band = floor(texCoord.y * 24.0);
    float tearRoll = hash11(band * 7.31 + frame * 13.7);
    float tear = (tearRoll > 0.85 ? (tearRoll - 0.85) / 0.15 - 0.5 : 0.0) * 0.06 * Intensity;

    // Block jitter: coarse cells occasionally displace as a chunk.
    vec2 cell = floor(texCoord * vec2(8.0, 6.0));
    float cellRoll = hash11(cell.x * 3.7 + cell.y * 11.9 + frame * 5.3);
    vec2 jitter = cellRoll > 0.92
        ? (vec2(hash11(cellRoll * 91.7), hash11(cellRoll * 47.3)) - 0.5) * 0.03 * Intensity
        : vec2(0.0);

    vec2 baseCoord = clamp(texCoord + vec2(tear, 0.0) + jitter, 0.0, 1.0);

    // RGB tear: the red and blue channels split horizontally on torn lines.
    float split = (0.004 + abs(tear) * 0.5) * Intensity;
    float red = texture(InSampler, clamp(baseCoord + vec2(split, 0.0), 0.0, 1.0)).r;
    float green = texture(InSampler, baseCoord).g;
    float blue = texture(InSampler, clamp(baseCoord - vec2(split, 0.0), 0.0, 1.0)).b;
    vec3 torn = vec3(red, green, blue);

    // Torn bands flash faintly with the shield's primary color.
    float flash = abs(tear) > 0.0001 ? 0.25 : 0.0;
    vec3 outColor = mix(torn, torn * Primary.rgb, flash);
    fragColor = vec4(outColor, 1.0);
}

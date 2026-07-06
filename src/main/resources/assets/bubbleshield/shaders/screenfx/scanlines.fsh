#version 330

#moj_import <minecraft:globals.glsl>

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform ScanlinesConfig {
    vec4 Primary;
    float Intensity;
    float LineDensity;
};

out vec4 fragColor;

float hash11(float p) {
    p = fract(p * 443.8975);
    p += p * (p + 19.19);
    return fract(p * p);
}

void main() {
    // GameTime wraps once per day cycle (24000 ticks); scale to roughly seconds.
    float time = GameTime * 1200.0;

    // Subtle per-row horizontal jitter, like a CRT losing sync for a moment.
    float row = floor(texCoord.y * LineDensity);
    float jitter = (hash11(row + floor(time * 8.0) * 91.7) - 0.5) * 0.0025 * Intensity;
    vec2 uv = clamp(vec2(texCoord.x + jitter, texCoord.y), 0.0, 1.0);

    vec4 diffuseColor = texture(InSampler, uv);

    // Rolling scanlines: dark gaps between lines, drifting slowly downward.
    float scan = 0.5 + 0.5 * sin((texCoord.y * LineDensity - time * 0.5) * 6.2831);
    float darken = 1.0 - Intensity * 0.35 * scan;

    // Phosphor tint toward the shield's primary color.
    vec3 outColor = diffuseColor.rgb * darken;
    outColor = mix(outColor, outColor * Primary.rgb, 0.15);
    fragColor = vec4(outColor, 1.0);
}

#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform EdgeGlowConfig {
    vec4 Primary;
    float Intensity;
};

out vec4 fragColor;

float lumaAt(vec2 uv) {
    return dot(texture(InSampler, clamp(uv, 0.0, 1.0)).rgb, vec3(0.3, 0.59, 0.11));
}

void main() {
    vec4 diffuseColor = texture(InSampler, texCoord);
    vec2 texel = 1.0 / InSize;

    // 3x3 Sobel over scene luma.
    float tl = lumaAt(texCoord + texel * vec2(-1.0, -1.0));
    float tc = lumaAt(texCoord + texel * vec2(0.0, -1.0));
    float tr = lumaAt(texCoord + texel * vec2(1.0, -1.0));
    float ml = lumaAt(texCoord + texel * vec2(-1.0, 0.0));
    float mr = lumaAt(texCoord + texel * vec2(1.0, 0.0));
    float bl = lumaAt(texCoord + texel * vec2(-1.0, 1.0));
    float bc = lumaAt(texCoord + texel * vec2(0.0, 1.0));
    float br = lumaAt(texCoord + texel * vec2(1.0, 1.0));

    float gx = (tr + 2.0 * mr + br) - (tl + 2.0 * ml + bl);
    float gy = (bl + 2.0 * bc + br) - (tl + 2.0 * tc + tr);
    float edge = clamp(length(vec2(gx, gy)), 0.0, 1.0);

    // Edges glow in the shield's primary color over the unchanged scene.
    vec3 outColor = diffuseColor.rgb + Primary.rgb * edge * Intensity;
    fragColor = vec4(outColor, 1.0);
}

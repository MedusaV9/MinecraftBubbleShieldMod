#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>

in vec2 texCoord0;
in vec4 vertexColor;
in float sphericalVertexDistance;
in float cylindricalVertexDistance;

out vec4 fragColor;

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(vec2 p) {
    float value = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < 4; i++) {
        value += amplitude * vnoise(p);
        p = p * 2.03 + vec2(17.7, 9.2);
        amplitude *= 0.5;
    }
    return value;
}

void main() {
    // GameTime spans one day cycle in [0, 1); scale to roughly seconds.
    float time = GameTime * 1200.0;
    vec2 uv = texCoord0 * 6.0;

    float swirl = fbm(uv + vec2(fbm(uv + time * 0.13), fbm(uv - time * 0.11)));
    float plasma = 0.5 + 0.5 * sin(6.2831 * swirl + time * 0.7);

    float brightness = 0.55 + 0.65 * plasma;
    float alpha = vertexColor.a * (0.45 + 0.55 * plasma);

    vec4 color = vec4(vertexColor.rgb * brightness, alpha);
    if (color.a < 0.01) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

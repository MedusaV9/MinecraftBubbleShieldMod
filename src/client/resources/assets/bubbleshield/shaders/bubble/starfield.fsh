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

// One parallax star layer: hashed star points on a drifting grid.
float starLayer(vec2 uv, float scale, float drift, float threshold, float time) {
    vec2 p = uv * scale + vec2(time * drift, time * drift * 0.4);
    vec2 cell = floor(p);
    vec2 local = fract(p);
    vec2 star = vec2(hash21(cell + 5.3), hash21(cell + 9.1)) * 0.7 + 0.15;
    float twinkle = 0.5 + 0.5 * sin(time * (0.8 + hash21(cell) * 1.6) + hash21(cell + 2.7) * 6.2831);
    float present = step(threshold, hash21(cell + 13.7));
    return smoothstep(0.14, 0.0, length(local - star)) * twinkle * present;
}

void main() {
    float time = GameTime * 1200.0;
    vec2 uv = texCoord0;

    // Slow nebula wash behind everything.
    float nebula = fbm(uv * 3.0 + vec2(time * 0.02, -time * 0.015));
    float wash = smoothstep(0.35, 0.85, nebula) * 0.35;

    // Three star layers at different densities and drift speeds for depth.
    float stars = starLayer(uv, 24.0, 0.010, 0.55, time)
            + starLayer(uv, 14.0, 0.018, 0.70, time) * 1.3
            + starLayer(uv, 8.0, 0.030, 0.82, time) * 1.7;

    float glow = clamp(stars, 0.0, 1.6) + wash;

    float brightness = 0.5 + 1.0 * glow;
    float alpha = vertexColor.a * (0.14 + 0.86 * clamp(glow, 0.0, 1.0));

    vec4 color = vec4(vertexColor.rgb * brightness, alpha);
    if (color.a < 0.01) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

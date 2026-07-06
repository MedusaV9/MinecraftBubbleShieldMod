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

// Ridged fbm: |2n-1| folded and inverted so noise ridges become thin bright creases.
float ridgedFbm(vec2 p) {
    float value = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < 4; i++) {
        float n = 1.0 - abs(2.0 * vnoise(p) - 1.0);
        value += amplitude * n * n;
        p = p * 2.11 + vec2(13.5, 7.3);
        amplitude *= 0.5;
    }
    return value;
}

void main() {
    float time = GameTime * 1200.0;
    vec2 uv = texCoord0 * 5.0;

    // Two ridged layers crawling in opposite directions form forking filaments.
    float bolt1 = ridgedFbm(uv + vec2(time * 0.21, -time * 0.13));
    float bolt2 = ridgedFbm(uv * 1.7 + vec2(-time * 0.17, time * 0.09) + 41.7);
    float filament = max(smoothstep(0.72, 0.98, bolt1), smoothstep(0.78, 0.99, bolt2));

    // Random strobe: whole-surface lightning flashes on an irregular clock.
    float strobe = 0.7 + 0.3 * step(0.55, hash21(vec2(floor(time * 3.0), 3.3)));
    float haze = 0.18 * smoothstep(0.4, 0.9, bolt1);

    float glow = filament * strobe + haze;

    float brightness = 0.45 + 1.3 * glow;
    float alpha = vertexColor.a * (0.12 + 0.88 * clamp(glow, 0.0, 1.0));

    vec4 color = vec4(vertexColor.rgb * brightness, alpha);
    if (color.a < 0.01) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

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
    float time = GameTime * 1200.0;
    vec2 uv = texCoord0;

    // Vertical curtains: noise sampled mostly along longitude, drifting over time.
    float curtain = fbm(vec2(uv.x * 8.0 + time * 0.25, uv.y * 2.0 - time * 0.07));
    float rays = pow(clamp(curtain * 1.6 - 0.25, 0.0, 1.0), 2.0);
    // Fade the curtains toward the poles so they hang like drapes around the equator.
    float drape = smoothstep(0.0, 0.35, uv.y) * smoothstep(1.0, 0.55, uv.y);
    float shimmer = 0.5 + 0.5 * sin(time * 1.1 + uv.x * 12.566);

    float brightness = 0.5 + 0.9 * rays * (0.7 + 0.3 * shimmer);
    float alpha = vertexColor.a * (0.12 + 0.88 * rays * drape);

    vec4 color = vec4(vertexColor.rgb * brightness, alpha);
    if (color.a < 0.01) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

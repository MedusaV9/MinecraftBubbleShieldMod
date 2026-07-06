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

void main() {
    float time = GameTime * 1200.0;
    vec2 uv = texCoord0;

    // Distance from the nearer pole (0 at poles, 1 at equator): the swirl pivot.
    float latDist = 1.0 - abs(uv.y * 2.0 - 1.0);

    // Spiral: longitude twisted by an angle proportional to latitude distance,
    // so bands wind tighter toward the equator and the whole spiral rotates.
    float twist = uv.x * 6.2831 + latDist * 7.0 - time * 0.9;
    float spiralWave = sin(twist * 3.0);
    float band = smoothstep(0.15, 0.9, spiralWave);

    // Counter-rotating inner spiral adds interleaved arms.
    float twist2 = uv.x * 6.2831 - latDist * 4.5 + time * 0.6;
    float arms = smoothstep(0.45, 0.95, sin(twist2 * 2.0));

    // The vortex eye at each pole glows and pulses.
    float eye = pow(1.0 - latDist, 3.0) * (0.6 + 0.4 * sin(time * 1.7));

    // Faint speckle riding along the bands keeps the funnel from looking flat.
    float grain = 0.12 * hash21(floor(vec2(twist * 3.0, uv.y * 24.0)));

    float pattern = clamp(band * 0.9 + arms * 0.5 + eye + grain, 0.0, 1.4);

    float brightness = 0.45 + 0.9 * pattern;
    float alpha = vertexColor.a * (0.18 + 0.82 * clamp(pattern, 0.0, 1.0));

    vec4 color = vec4(vertexColor.rgb * brightness, alpha);
    if (color.a < 0.01) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

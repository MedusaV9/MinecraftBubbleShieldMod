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

// Rose curve r = |cos(k * theta)| petal mask around a cell center; the bloom
// factor breathes the petal radius so flowers open and close.
float rose(vec2 p, float k, float bloom) {
    float angle = atan(p.y, p.x);
    float radius = length(p);
    float petalEdge = abs(cos(k * angle)) * 0.42 * bloom + 0.06;
    return smoothstep(petalEdge, petalEdge - 0.10, radius);
}

void main() {
    float time = GameTime * 1200.0;
    // Defensive: the flower lattice below assumes UV in [0,1], so wrap
    // out-of-range UVs back into the periodic domain.
    vec2 uv = fract(texCoord0);

    // A staggered lattice of rose-curve flowers, one per cell; odd rows are
    // shifted half a cell so the blossoms interlock.
    vec2 grid = uv * vec2(6.0, 5.0);
    grid.x += step(0.5, fract(grid.y * 0.5)) * 0.5;
    vec2 cellId = floor(grid);
    vec2 cellUv = fract(grid) - vec2(0.5);

    // Every flower breathes and slowly spins on its own clock.
    float seed = hash21(cellId + 11.3);
    float bloom = 0.75 + 0.25 * sin(time * 0.6 + seed * 6.2831);
    float spin = time * (0.1 + 0.15 * seed) + seed * 6.2831;
    vec2 spun = vec2(
        cellUv.x * cos(spin) - cellUv.y * sin(spin),
        cellUv.x * sin(spin) + cellUv.y * cos(spin));

    // 5-petal blossoms with a bright pistil core.
    float petals = rose(spun, 5.0, bloom);
    float core = smoothstep(0.09, 0.02, length(cellUv)) * (0.8 + 0.2 * sin(time + seed * 9.4));

    // Faint drifting pollen dust between the flowers.
    float pollen = step(0.94, hash21(floor(uv * 24.0 + vec2(0.0, time * 0.5)))) * 0.5;

    float pattern = petals * (0.55 + 0.45 * bloom) + core * 1.1 + pollen;

    float brightness = 0.5 + 0.9 * pattern;
    float alpha = vertexColor.a * (0.15 + 0.85 * clamp(pattern, 0.0, 1.0));

    vec4 color = vec4(vertexColor.rgb * brightness, alpha);
    if (color.a < 0.01) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

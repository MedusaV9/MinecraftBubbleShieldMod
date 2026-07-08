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
    // Defensive: the wedge folding below assumes UV in [0,1], so wrap
    // out-of-range UVs back into the periodic domain.
    vec2 uv = fract(texCoord0);

    // Kaleidoscope: fold the sphere UV into a rotating 6-wedge mirror fan
    // around the tile center, so every wedge repeats the same slice.
    vec2 centered = uv - vec2(0.5);
    float angle = atan(centered.y, centered.x) + time * 0.15;
    float radius = length(centered) * 2.0;
    float wedge = 3.14159265 / 3.0;
    // Mirror-fold the angle into one wedge (triangle fold, not sawtooth).
    float folded = abs(mod(angle, 2.0 * wedge) - wedge);

    // Pattern inside the mirrored wedge: radial spokes crossed by breathing rings.
    float spokes = smoothstep(0.28, 0.04, abs(fract(folded * 4.5 / wedge + time * 0.1) - 0.5));
    float ringWave = abs(fract(radius * 3.0 - time * 0.3) - 0.5) * 2.0;
    float rings = smoothstep(0.5, 0.08, ringWave);

    // Gem facets: each wedge cell twinkles on its own clock.
    float cell = floor(folded * 4.5 / wedge) + floor(radius * 3.0) * 7.0;
    float twinkle = 0.7 + 0.3 * sin(time * 1.1 + hash21(vec2(cell, 5.1)) * 6.2831);

    float pattern = clamp(spokes * rings * 1.4 + 0.25 * spokes + 0.15 * rings, 0.0, 1.5) * twinkle;

    float brightness = 0.5 + 0.9 * pattern;
    float alpha = vertexColor.a * (0.15 + 0.85 * clamp(pattern, 0.0, 1.0));

    vec4 color = vec4(vertexColor.rgb * brightness, alpha);
    if (color.a < 0.01) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

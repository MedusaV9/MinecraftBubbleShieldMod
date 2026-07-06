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

    // Stack of latitude rings: sharp bright bands on a fine latitude grating,
    // each ring slightly offset in phase so the stack shimmers.
    float lat = uv.y * 14.0;
    float ringWave = abs(fract(lat - time * 0.35) - 0.5) * 2.0;
    float ring = smoothstep(0.35, 0.05, ringWave);

    // One wide orbital ring sweeping pole-to-pole and back (triangle wave).
    float sweepPos = abs(fract(time * 0.12) * 2.0 - 1.0);
    float sweep = smoothstep(0.09, 0.0, abs(uv.y - sweepPos));

    // Thin longitude seams give the rings an orbital "cage" look.
    float seamWave = abs(fract(uv.x * 6.0 + time * 0.05) - 0.5) * 2.0;
    float seam = smoothstep(0.16, 0.02, seamWave) * 0.35;

    // Per-ring flicker so individual bands pulse independently.
    float band = floor(lat - time * 0.35);
    float flicker = 0.75 + 0.25 * sin(time * 1.3 + hash21(vec2(band, 1.7)) * 6.2831);

    float pattern = max(ring * flicker, sweep * 1.2) + seam;

    float brightness = 0.5 + 0.9 * pattern;
    float alpha = vertexColor.a * (0.15 + 0.85 * clamp(pattern, 0.0, 1.0));

    vec4 color = vec4(vertexColor.rgb * brightness, alpha);
    if (color.a < 0.01) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

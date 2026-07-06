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
    vec2 uv = texCoord0 * vec2(12.0, 9.0);

    // Diamond scale lattice: odd rows shift half a cell, like overlapping dragon scales.
    float row = floor(uv.y);
    vec2 cellUv = vec2(uv.x + 0.5 * mod(row, 2.0), uv.y);
    vec2 id = floor(cellUv);
    vec2 local = fract(cellUv) - 0.5;

    // Diamond (L1) distance to the scale center; edges where neighbors meet.
    float diamond = abs(local.x) + abs(local.y);
    float rim = smoothstep(0.55, 0.42, diamond);
    float outline = smoothstep(0.42, 0.55, diamond) * smoothstep(0.68, 0.55, diamond);

    // Glint sweep: a diagonal highlight wave travels across the lattice, and each
    // scale flashes when the sweep passes its phase slot.
    float sweep = fract((id.x + id.y * 0.5) * 0.11 - time * 0.22);
    float glint = pow(smoothstep(0.25, 0.0, abs(sweep - 0.5)), 2.0);
    float shimmer = 0.6 + 0.4 * sin(time * 0.9 + hash21(id) * 6.2831);

    // Scales darken slightly toward their tips for a curved, plated look.
    float shade = 1.0 - 0.45 * clamp(diamond * 1.4, 0.0, 1.0);

    float pattern = rim * shade * shimmer + outline * 0.9 + glint * rim * 1.1;

    float brightness = 0.45 + 0.85 * pattern;
    float alpha = vertexColor.a * (0.2 + 0.8 * clamp(outline + rim * (0.25 + 0.75 * glint), 0.0, 1.0));

    vec4 color = vec4(vertexColor.rgb * brightness, alpha);
    if (color.a < 0.01) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

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
    // Defensive: the trace grid and pulse tracks below assume UV in [0,1],
    // so wrap out-of-range UVs back into the periodic domain.
    vec2 uv = fract(texCoord0);

    // Circuit-board substrate: a grid of cells where each cell keeps either its
    // horizontal or vertical copper trace (per-cell coin flip).
    vec2 grid = uv * vec2(10.0, 8.0);
    vec2 cellId = floor(grid);
    vec2 cellUv = fract(grid);
    float pick = step(0.5, hash21(cellId + 0.7));
    float hTrace = smoothstep(0.14, 0.05, abs(cellUv.y - 0.5));
    float vTrace = smoothstep(0.14, 0.05, abs(cellUv.x - 0.5));
    float trace = mix(hTrace, vTrace, pick);

    // Solder pads at the cell corners join the traces together.
    float pad = smoothstep(0.24, 0.10, length(cellUv - vec2(0.5)));

    // Travelling pulses: bright packets racing along the trace rows/columns,
    // each lane offset by a per-lane phase so they never march in lockstep.
    float lanePhaseH = hash21(vec2(cellId.y, 2.3));
    float pulseH = smoothstep(0.12, 0.0, abs(fract(uv.x * 2.0 - time * 0.35 + lanePhaseH) - 0.5)) * hTrace * (1.0 - pick);
    float lanePhaseV = hash21(vec2(cellId.x, 4.9));
    float pulseV = smoothstep(0.12, 0.0, abs(fract(uv.y * 2.0 - time * 0.28 + lanePhaseV) - 0.5)) * vTrace * pick;
    float pulse = max(pulseH, pulseV);

    // Idle blink: a few pads flicker like status LEDs.
    float blink = step(0.85, hash21(cellId + floor(time * 0.8)));

    float pattern = trace * 0.45 + pad * (0.3 + 0.5 * blink) + pulse * 1.3;

    float brightness = 0.5 + 0.9 * pattern;
    float alpha = vertexColor.a * (0.15 + 0.85 * clamp(pattern, 0.0, 1.0));

    vec4 color = vec4(vertexColor.rgb * brightness, alpha);
    if (color.a < 0.01) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

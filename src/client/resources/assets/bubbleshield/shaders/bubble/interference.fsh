#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>

in vec2 texCoord0;
in vec4 vertexColor;
in float sphericalVertexDistance;
in float cylindricalVertexDistance;

out vec4 fragColor;

void main() {
    float time = GameTime * 1200.0;
    // Defensive: the wave sources orbit inside [0,1]^2, so wrap out-of-range UVs
    // back into that domain (the fringe distances stay latitude-correct).
    vec2 uv = fract(texCoord0);

    // Two wave sources orbiting the surface on offset tracks; each emits expanding
    // concentric rings and the superposition draws moire interference fringes.
    vec2 sourceA = vec2(0.5 + 0.30 * sin(time * 0.23), 0.5 + 0.22 * cos(time * 0.31));
    vec2 sourceB = vec2(0.5 + 0.28 * cos(time * 0.19 + 2.1), 0.5 + 0.24 * sin(time * 0.27 + 4.2));

    // Wrap-aware longitudinal distance so fringes stay seamless across u = 0/1.
    vec2 dA = uv - sourceA;
    dA.x = abs(fract(dA.x + 0.5) - 0.5);
    vec2 dB = uv - sourceB;
    dB.x = abs(fract(dB.x + 0.5) - 0.5);

    float waveA = sin(length(dA) * 60.0 - time * 2.2);
    float waveB = sin(length(dB) * 60.0 - time * 1.8);

    // Superposition: |A + B| peaks where crests align (constructive fringes).
    float fringes = abs(waveA + waveB) * 0.5;
    float crest = smoothstep(0.55, 0.95, fringes);
    float wash = smoothstep(0.1, 0.8, fringes) * 0.35;

    // Beat envelope: the whole pattern slowly breathes as the sources drift.
    float beat = 0.8 + 0.2 * sin(time * 0.5);

    float pattern = (crest + wash) * beat;

    float brightness = 0.5 + 0.9 * pattern;
    float alpha = vertexColor.a * (0.16 + 0.84 * clamp(pattern, 0.0, 1.0));

    vec4 color = vec4(vertexColor.rgb * brightness, alpha);
    if (color.a < 0.01) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

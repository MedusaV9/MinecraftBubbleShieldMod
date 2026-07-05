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
    vec2 uv = texCoord0;

    // Latitudinal bands rippling around the sphere, distorted by a longitude wobble.
    float wobble = sin(uv.x * 6.2831 * 3.0 + time * 0.9) * 0.06;
    float band = sin((uv.y + wobble) * 6.2831 * 5.0 - time * 1.6);
    float crest = smoothstep(0.35, 0.95, band);
    float glow = 0.5 + 0.5 * band;

    float brightness = 0.55 + 0.75 * crest;
    float alpha = vertexColor.a * (0.25 + 0.55 * glow + 0.20 * crest);

    vec4 color = vec4(vertexColor.rgb * brightness, alpha);
    if (color.a < 0.01) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

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
    vec2 uv = texCoord0 * 18.0;

    vec2 cell = floor(uv);
    vec2 local = fract(uv);
    // One star per cell at a random offset, twinkling on a random phase.
    vec2 star = vec2(hash21(cell + 3.1), hash21(cell + 7.7)) * 0.6 + 0.2;
    float twinkle = pow(0.5 + 0.5 * sin(time * (1.0 + hash21(cell) * 2.0) + hash21(cell + 11.3) * 6.2831), 6.0);
    float spark = smoothstep(0.22, 0.0, length(local - star)) * twinkle;

    float brightness = 0.6 + 1.2 * spark;
    float alpha = vertexColor.a * (0.15 + 0.85 * spark);

    vec4 color = vec4(vertexColor.rgb * brightness, alpha);
    if (color.a < 0.01) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

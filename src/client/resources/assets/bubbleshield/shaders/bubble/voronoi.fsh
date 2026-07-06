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

vec2 hash22(vec2 p) {
    return vec2(hash21(p), hash21(p + 19.19));
}

void main() {
    float time = GameTime * 1200.0;
    vec2 uv = texCoord0 * 8.0;

    vec2 cell = floor(uv);
    vec2 local = fract(uv);

    // 2D voronoi over a jittered grid; every site orbits its cell so the cells drift.
    float minDist = 8.0;
    float secondDist = 8.0;
    vec2 nearestId = vec2(0.0);
    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            vec2 neighbor = vec2(float(dx), float(dy));
            vec2 id = cell + neighbor;
            vec2 jitter = hash22(id);
            vec2 site = neighbor + 0.5 + 0.35 * sin(time * 0.6 + jitter * 6.2831);
            float dist = length(site - local);
            if (dist < minDist) {
                secondDist = minDist;
                minDist = dist;
                nearestId = id;
            } else if (dist < secondDist) {
                secondDist = dist;
            }
        }
    }

    // Distance to the shared edge between the two nearest sites: bright cell borders.
    float edge = secondDist - minDist;
    float border = smoothstep(0.14, 0.02, edge);
    float cellGlow = 0.5 + 0.5 * sin(time * 1.1 + hash21(nearestId) * 6.2831);
    float interior = smoothstep(0.9, 0.2, minDist) * 0.3 * cellGlow;

    float brightness = 0.45 + 1.0 * border + 0.5 * interior;
    float alpha = vertexColor.a * (0.16 + 0.84 * clamp(border + interior, 0.0, 1.0));

    vec4 color = vec4(vertexColor.rgb * brightness, alpha);
    if (color.a < 0.01) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

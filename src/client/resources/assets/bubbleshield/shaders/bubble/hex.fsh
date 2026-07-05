#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>

in vec2 texCoord0;
in vec4 vertexColor;
in float sphericalVertexDistance;
in float cylindricalVertexDistance;

out vec4 fragColor;

float hexDist(vec2 p) {
    p = abs(p);
    return max(dot(p, normalize(vec2(1.0, 1.73))), p.x);
}

// Returns distance to the nearest hex edge (0 at edge, 0.5 at cell center) and the cell id.
vec3 hexCoords(vec2 uv) {
    vec2 rep = vec2(1.0, 1.73);
    vec2 half_rep = rep * 0.5;
    vec2 a = mod(uv, rep) - half_rep;
    vec2 b = mod(uv - half_rep, rep) - half_rep;
    vec2 gv = dot(a, a) < dot(b, b) ? a : b;
    vec2 id = uv - gv;
    return vec3(0.5 - hexDist(gv), id);
}

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

void main() {
    float time = GameTime * 1200.0;
    vec2 uv = texCoord0 * 10.0;

    vec3 hex = hexCoords(uv);
    float edge = smoothstep(0.10, 0.02, hex.x);
    float cellPulse = 0.5 + 0.5 * sin(time * 1.5 + hash21(hex.yz) * 6.2831);

    float brightness = 0.5 + 0.8 * edge + 0.2 * cellPulse;
    float alpha = vertexColor.a * (0.18 + 0.82 * edge * (0.6 + 0.4 * cellPulse));

    vec4 color = vec4(vertexColor.rgb * brightness, alpha);
    if (color.a < 0.01) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

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

// A single jagged bolt: a pole-to-pole polyline whose x position is re-rolled
// per latitude segment and per strike, plus a short-lived branch forking off
// halfway down. Returns the bolt glow at this uv.
float bolt(vec2 uv, float lane, float time) {
    // Each bolt re-strikes on its own irregular clock.
    float strike = floor(time * (0.9 + 0.35 * hash21(vec2(lane, 3.1))));
    float life = fract(time * (0.9 + 0.35 * hash21(vec2(lane, 3.1))));
    // Sharp attack, fast decay: visible only for the first ~35% of the cycle.
    float flash = smoothstep(0.0, 0.04, life) * smoothstep(0.35, 0.12, life);

    // Jagged path: x wanders by segment, interpolated between segment knots.
    float seg = uv.y * 9.0;
    float segId = floor(seg);
    float segT = fract(seg);
    float x0 = hash21(vec2(segId, lane + strike * 17.0));
    float x1 = hash21(vec2(segId + 1.0, lane + strike * 17.0));
    float path = mix(x0, x1, segT) * 0.6 + 0.2;

    float dist = abs(uv.x - path);
    float coreGlow = smoothstep(0.02, 0.002, dist);
    float halo = smoothstep(0.10, 0.0, dist) * 0.35;

    // A branch forks off the mid segments at a slant, same strike lifetime.
    float branchPath = path + (uv.y - 0.5) * (hash21(vec2(lane, strike + 7.7)) - 0.5) * 1.6;
    float branchMask = smoothstep(0.30, 0.45, uv.y) * smoothstep(0.95, 0.75, uv.y);
    float branch = smoothstep(0.012, 0.001, abs(uv.x - branchPath)) * branchMask * 0.8;

    return (coreGlow + halo + branch) * flash;
}

void main() {
    float time = GameTime * 1200.0;
    // Defensive: the bolt paths below assume UV in [0,1], so wrap
    // out-of-range UVs back into the periodic domain.
    vec2 uv = fract(texCoord0);

    // Three independent bolts strike around the sphere; each lane owns a
    // longitude third so strikes spread over the whole surface.
    float glow = 0.0;
    for (int i = 0; i < 3; i++) {
        float lane = float(i);
        vec2 laneUv = vec2(fract(uv.x * 3.0 - lane * 0.33), uv.y);
        glow += bolt(laneUv, lane + 1.0, time);
    }

    // Ambient storm shimmer so the surface never goes fully dark between strikes.
    float shimmer = 0.10 + 0.06 * sin(time * 0.7 + uv.y * 12.0);
    // Whole-sky afterglow flash synced to the fastest bolt clock.
    float sky = 0.18 * step(0.92, hash21(vec2(floor(time * 1.25), 9.2)));

    float pattern = clamp(glow, 0.0, 1.6) + shimmer + sky;

    float brightness = 0.45 + 1.1 * pattern;
    float alpha = vertexColor.a * (0.14 + 0.86 * clamp(pattern, 0.0, 1.0));

    vec4 color = vec4(vertexColor.rgb * brightness, alpha);
    if (color.a < 0.01) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>

// Beam surface shader beam_pulse -- sharp energy rings racing up the column
// over a soft body glow, each ring with its own hash-rolled intensity.
// HAND-WRITTEN (not generated; one shader per BeamStyle). Follows the frozen
// bubble fragment contract: fog+globals imports only, no custom uniforms or
// textures, GameTime-only animation with day-quantized speeds (ring scroll
// 0.4*1200 = 480 rings/day with ring ids hashed modulo 32, which divides 480,
// so the daily wrap re-rolls nothing; sin speeds are integer multiples of
// 2*pi/1200), integer u-harmonics across the tube seam, recolor-safe output
// (rgb multiplies vertexColor.rgb, final alpha = vertexColor.a * pattern),
// discard < 0.01, apply_fog last. Rendered with the additive LIGHTNING blend,
// so alpha is intensity. u = angle fraction, v = height fraction (the outer
// glow shell flips v, so its rings sweep the opposite way).

in vec2 texCoord0;
in vec4 vertexColor;
in float sphericalVertexDistance;
in float cylindricalVertexDistance;

out vec4 fragColor;

float hash11(float p) {
    p = fract(p * 0.1031);
    p *= p + 33.33;
    p *= p + p;
    return fract(p);
}

void main() {
    // GameTime spans one day in [0,1); t is in [0,1200) "seconds".
    float t = GameTime * 1200.0;
    float u = texCoord0.x;
    float v = texCoord0.y;

    // Ring field: 6 rings on the column, rising 0.4 column-heights per second
    // (= 480 ring slots per day; slot ids repeat every 32 slots and 480 is an
    // integer multiple of 32, so the daily wrap lands on the same roll).
    float ringPhase = v * 6.0 - t * 0.4;
    float ringId = mod(floor(ringPhase), 32.0);
    float ringRoll = hash11(ringId * 0.113 + 0.7);
    // Sharp double-exponential profile centered mid-cell: a crisp ring with a
    // soft skirt, per-ring brightness 0.35..1.0.
    float ringDist = abs(fract(ringPhase) - 0.5);
    float ring = exp(-16.0 * ringDist) * (0.35 + 0.65 * ringRoll);

    // Slight azimuthal shimmer on the rings (integer harmonic 3 wraps the
    // seam; 2*pi*150/1200 per second keeps the daily wrap seamless).
    float shimmer = 0.85 + 0.15 * sin(u * 18.8495559 + t * 0.7853982);

    // Soft column body with a slow breathing swell (2*pi*40/1200 per second)
    // so the beam holds presence between rings.
    float body = 0.28 + 0.07 * sin(t * 0.2094395 + v * 6.2831853);

    float pattern = clamp(body + 1.2 * ring * shimmer, 0.0, 1.5);

    // Recolor-safe: the palette rides vertexColor.rgb; ring crests lift white.
    vec3 rgb = vertexColor.rgb * (0.55 + 0.85 * pattern);
    rgb = mix(rgb, vec3(1.0), 0.4 * smoothstep(0.9, 1.4, pattern));

    float alpha = vertexColor.a * clamp(pattern, 0.0, 1.0);
    vec4 color = vec4(clamp(rgb, 0.0, 1.0), alpha);
    if (color.a < 0.01) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

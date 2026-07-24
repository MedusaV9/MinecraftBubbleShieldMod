#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>

// Beam surface shader beam_pulse -- rising energy RINGS dominate: bright
// hash-rolled pulses race up a deliberately faint column (low body floor), each
// pulse bulging the glow skirt as it passes.
// HAND-WRITTEN (not generated; one shader per BeamStyle). Follows the frozen
// bubble fragment contract: fog+globals imports only, no custom uniforms or
// textures, GameTime-only animation with day-quantized speeds (ring scroll
// 0.4*1200 = 480 ring-cells/day with ring ids hashed modulo 32, which divides
// 480, so the daily wrap re-rolls nothing; sin speeds are integer multiples of
// 2*pi/1200), recolor-safe output (rgb is a HUE-PRESERVING soft clip of
// vertexColor.rgb -- 1-exp(-k*palette*energy), so ring crests saturate toward
// the palette's bright tint and NEVER toward white), discard < 0.01, apply_fog
// last. Rendered with the additive LIGHTNING blend on 2 crossed camera-facing
// planes: u runs ACROSS the beam width (u = 0.5 is the axis; x = 2u-1 is the
// signed cross-beam coordinate), v = height fraction with the membrane
// crossing pinned at v = 0.75 (BeamMesh.APEX_V). No angular seam on a plane.

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
    float x = 2.0 * u - 1.0;

    // Cross-section: a slim quiet core inside a soft glow skirt. PULSE keeps
    // both floors LOW so the rising rings own the look.
    float core = exp(-28.0 * x * x);
    float glow = exp(-3.0 * x * x);

    // Ring field: 6 ring cells on the column, rising 0.4 column-heights per
    // second (= 480 cells/day; ids repeat every 32 cells, 480 = 15*32, so the
    // daily wrap lands on the same roll). Smooth gaussian crest mid-cell.
    float ringPhase = v * 6.0 - t * 0.4;
    float ringId = mod(floor(ringPhase), 32.0);
    float ringRoll = hash11(ringId * 0.113 + 0.7);
    float rd = fract(ringPhase) - 0.5;
    float ring = exp(-90.0 * rd * rd) * (0.40 + 0.60 * ringRoll);

    // Each passing ring BULGES the skirt: its lateral reach is wider than the
    // resting glow, so pulses read as traveling energy donuts, not stripes.
    float ringSpread = exp(-1.6 * x * x);

    // Slow breathing swell on the body (2*pi*40/1200 per second).
    float breathe = 0.85 + 0.15 * sin(t * 0.2094395 + v * 6.2831853);

    // Base impact flare at the projector and an apex bloom + faint splash ring
    // where the beam pierces the membrane (pinned at v = 0.75 by BeamMesh).
    float flare = exp(-10.0 * v) * exp(-1.8 * x * x);
    float av = v - 0.75;
    float apex = exp(-180.0 * av * av);
    float ringx = abs(x) - 0.62;
    float apexRing = apex * exp(-60.0 * ringx * ringx);

    float energy = glow * 0.10 * breathe
            + core * 0.30 * breathe
            + ring * 1.15 * ringSpread
            + flare * 0.8
            + apex * 0.50 * glow + apexRing * 0.40;

    // HUE-PRESERVING soft clip: ring crests saturate toward the palette's
    // bright tint (zero channels never light up), never toward white.
    vec3 rgb = 1.0 - exp(-2.6 * vertexColor.rgb * energy);

    float alpha = vertexColor.a * clamp(energy, 0.0, 1.0);
    vec4 color = vec4(clamp(rgb, 0.0, 1.0), alpha);
    if (color.a < 0.01) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

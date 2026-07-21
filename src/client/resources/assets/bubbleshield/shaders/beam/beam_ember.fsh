#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>

// Beam surface shader beam_ember -- the FORGE UPDRAFT: a hot core gradient
// (brightest at the projector, cooling with height) wrapped in heat-shimmer
// u-jitter, with hash-rolled spark cells tumbling upward and winking out as
// they climb.
// HAND-WRITTEN (not generated; one shader per BeamStyle). Follows the frozen
// bubble fragment contract: fog+globals imports only, no custom uniforms or
// textures, GameTime-only animation with day-quantized speeds (spark rows rise
// 0.6 column-heights per second on a 20-row lattice = 720 rows/day with ids
// hashed modulo 24 and 720 = 30*24, so the daily wrap re-rolls nothing; both
// shimmer sines and the per-spark twinkle are integer multiples of 2*pi/1200),
// recolor-safe output (rgb is a HUE-PRESERVING soft clip of vertexColor.rgb --
// 1-exp(-k*palette*energy), so sparks saturate toward the palette's bright
// tint and NEVER toward white), discard < 0.01, apply_fog last. Rendered with
// the additive LIGHTNING blend on 2 crossed camera-facing planes: u runs
// ACROSS the beam width (u = 0.5 is the axis; x = 2u-1 is the signed
// cross-beam coordinate), v = height fraction with the membrane crossing
// pinned at v = 0.75 (BeamMesh.APEX_V -- a soft spark-splash blooms there).
// Epilepsy-safe: twinkles are per-spark-local and the body only breathes.

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

float hash21(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

void main() {
    // GameTime spans one day in [0,1); t is in [0,1200) "seconds".
    float t = GameTime * 1200.0;
    float u = texCoord0.x;
    float v = texCoord0.y;
    float x = 2.0 * u - 1.0;

    // Heat-shimmer: the whole cross-section is u-jittered by two incommensurate
    // ripples (2*pi*160/1200 and 2*pi*100/1200 per second), strongest low where
    // the air is hottest -- the classic above-a-forge wobble.
    float shimmerAmp = 0.5 + 0.5 * exp(-2.0 * v);
    float xs = x + shimmerAmp * (0.05 * sin(v * 23.0 - t * 0.8377580)
            + 0.03 * sin(v * 41.0 + t * 0.5235988));

    // Hot core gradient: white-hot at the base, cooling and thinning with
    // height; the skirt follows the same cooling curve.
    float heat = 0.45 + 0.55 * exp(-2.4 * v);
    float core = exp(-26.0 * xs * xs) * heat;
    float glow = exp(-3.2 * xs * xs) * (0.22 + 0.34 * exp(-1.8 * v));

    // Spark cells: a 10x20 lattice rising 0.6 column-heights per second
    // (720 rows/day, ids modulo 24 -- seamless daily wrap). Each cell rolls a
    // spark; survivors get a hash-jittered position inside the cell, a soft
    // round dot, a slow per-spark twinkle (2*pi*200/1200) and a lifetime fade
    // with altitude, so the swarm thins out as it climbs.
    vec2 cell = vec2(floor(u * 10.0), floor(v * 20.0 - t * 0.6));
    float h = hash21(vec2(mod(cell.x, 10.0), mod(cell.y, 24.0)));
    float present = smoothstep(0.66, 0.78, h);
    float jx = (hash11(h * 13.7 + 0.29) - 0.5) * 0.6;
    float jy = (hash11(h * 7.1 + 0.61) - 0.5) * 0.5;
    float px = fract(u * 10.0) - 0.5 - jx;
    float py = fract(v * 20.0 - t * 0.6) - 0.5 - jy;
    float dot_ = exp(-(px * px * 46.0 + py * py * 30.0));
    float twinkle = 0.6 + 0.4 * sin(t * 1.0471976 + h * 44.0);
    float sparkFade = 0.30 + 0.70 * exp(-1.6 * v);
    float sparks = present * dot_ * twinkle * sparkFade;

    // Slow bellows breathing on the body (2*pi*30/1200 per second, ~0.025 Hz).
    float breathe = 0.90 + 0.10 * sin(t * 0.1570796 + v * 3.1415927);

    // Base impact flare at the projector (the forge mouth, the hottest spot)
    // and a soft spark-splash bloom + faint ring where the column pierces the
    // membrane (pinned at v = 0.75 by BeamMesh).
    float flare = exp(-8.0 * v) * exp(-1.6 * xs * xs);
    float av = v - 0.75;
    float apex = exp(-180.0 * av * av);
    float ringx = abs(xs) - 0.58;
    float apexRing = apex * exp(-60.0 * ringx * ringx);

    float energy = glow * breathe
            + core * 0.80 * breathe
            + sparks * 0.95
            + flare * 1.0
            + apex * 0.30 * exp(-3.2 * xs * xs) + apexRing * 0.28;

    // HUE-PRESERVING soft clip: the core and sparks saturate toward the
    // palette's bright tint (zero channels never light up), never toward white.
    vec3 rgb = 1.0 - exp(-2.6 * vertexColor.rgb * energy);

    float alpha = vertexColor.a * clamp(energy, 0.0, 1.0);
    vec4 color = vec4(clamp(rgb, 0.0, 1.0), alpha);
    if (color.a < 0.01) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

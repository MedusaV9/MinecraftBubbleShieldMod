#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>

// Beam surface shader beam_frost -- the CRYSTAL SPIRE: two counter-rotating
// spirals of BROKEN crystalline shard slivers (short hash-gated slots instead
// of continuous strands, so the column reads as suspended ice splinters, not a
// helix), dusted with sparse slow glitter glints around a pale restrained
// core.
// HAND-WRITTEN (not generated; one shader per BeamStyle). Follows the frozen
// bubble fragment contract: fog+globals imports only, no custom uniforms or
// textures, GameTime-only animation with day-quantized speeds (both spiral
// rotations and the twinkles are integer multiples of 2*pi/1200; glitter rows
// drift 0.25 column-heights per second on a 14-row lattice = 300 rows/day with
// ids hashed modulo 20 and 300 = 15*20, so the daily wrap re-rolls nothing;
// the shard slot lattice is STATIONARY -- only the spirals sweep through it),
// recolor-safe output (rgb is a HUE-PRESERVING soft clip of vertexColor.rgb --
// 1-exp(-k*palette*energy), so shard glints saturate toward the palette's
// bright tint and NEVER toward white), discard < 0.01, apply_fog last.
// Rendered with the additive LIGHTNING blend on 2 crossed camera-facing
// planes: u runs ACROSS the beam width (u = 0.5 is the axis; x = 2u-1 is the
// signed cross-beam coordinate), v = height fraction with the membrane
// crossing pinned at v = 0.75 (BeamMesh.APEX_V -- a frost bloom + rime ring
// forms there). Epilepsy-safe: glints twinkle at ~1 Hz per isolated cell and
// the body only breathes (~0.025 Hz); nothing gates the whole field.

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

    // Pale core: the most restrained of the styles -- a slim cold filament and
    // a whisper of skirt, breathing at 2*pi*30/1200 per second (~0.025 Hz).
    float breathe = 0.90 + 0.10 * sin(t * 0.1570796 + v * 6.2831853);
    float core = exp(-34.0 * x * x) * 0.50;
    float glow = exp(-3.4 * x * x);

    // Two counter-rotating spirals (3 turns over the column; 2*pi*50/1200 and
    // 2*pi*40/1200 per second) CHOPPED into shard slivers by a stationary
    // 16-slot lattice: each slot rolls once per strand -- survivors show a
    // short tapered splinter wherever the spiral sweeps through, with a cosine
    // depth cue so front-side shards outshine back-side ones.
    float row = floor(v * 16.0);
    float rf = fract(v * 16.0);
    float slotEnv = smoothstep(0.06, 0.30, rf) * smoothstep(0.94, 0.70, rf);

    float phase1 = v * 18.8495559 - t * 0.2617994;
    float xs1 = 0.52 * sin(phase1);
    float depth1 = 0.62 + 0.38 * cos(phase1);
    float gate1 = smoothstep(0.30, 0.46, hash11(row * 0.371 + 0.13));
    float shard1 = exp(-90.0 * (x - xs1) * (x - xs1)) * slotEnv * gate1 * depth1;

    float phase2 = -v * 18.8495559 - t * 0.2094395 + 2.1;
    float xs2 = 0.52 * sin(phase2);
    float depth2 = 0.62 + 0.38 * cos(phase2);
    float gate2 = smoothstep(0.30, 0.46, hash11(row * 0.549 + 0.71));
    float shard2 = exp(-90.0 * (x - xs2) * (x - xs2)) * slotEnv * gate2 * depth2;

    // Sparse glitter glints: a 9x14 cell lattice drifting up 0.25
    // column-heights per second (300 rows/day, ids modulo 20 -- seamless daily
    // wrap); only the top ~10% of rolls glint, twinkling at 2*pi*120/1200
    // (~1 Hz) and masked to the beam body so no fleck floats free.
    vec2 cell = vec2(floor(u * 9.0), floor(v * 14.0 - t * 0.25));
    float g = hash21(vec2(mod(cell.x, 9.0), mod(cell.y, 20.0)));
    float twinkle = 0.5 + 0.5 * sin(t * 0.6283185 + g * 61.0);
    float glint = smoothstep(0.88, 0.97, g) * twinkle * glow;

    // Base impact flare at the projector (a cold, tight one) and a frost bloom
    // + rime ring where the spire pierces the membrane (pinned at v = 0.75 by
    // BeamMesh).
    float flare = exp(-12.0 * v) * exp(-2.0 * x * x);
    float av = v - 0.75;
    float apex = exp(-180.0 * av * av);
    float ringx = abs(x) - 0.60;
    float apexRing = apex * exp(-70.0 * ringx * ringx);

    float energy = glow * 0.10 * breathe
            + core * breathe
            + (shard1 + shard2) * 0.90
            + glint * 0.55
            + flare * 0.7
            + apex * 0.40 * glow + apexRing * 0.38;

    // HUE-PRESERVING soft clip: shard glints saturate toward the palette's
    // bright tint (zero channels never light up), never toward white.
    vec3 rgb = 1.0 - exp(-2.6 * vertexColor.rgb * energy);

    float alpha = vertexColor.a * clamp(energy, 0.0, 1.0);
    vec4 color = vec4(clamp(rgb, 0.0, 1.0), alpha);
    if (color.a < 0.01) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

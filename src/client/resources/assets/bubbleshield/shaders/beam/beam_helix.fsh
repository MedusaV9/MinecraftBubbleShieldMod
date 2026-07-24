#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>

// Beam surface shader beam_helix -- a CLEAN DOUBLE HELIX: two counter-phased
// strands winding up around a slim quiet core, with soft depth-cued crossings
// (the "front" strand brightens, the "back" one dims) and gentle smoothstepped
// sparkle glints masked to the beam body.
// HAND-WRITTEN (not generated; one shader per BeamStyle). Follows the frozen
// bubble fragment contract: fog+globals imports only, no custom uniforms or
// textures, GameTime-only animation with day-quantized speeds (every helix /
// glint sin speed is an integer multiple of 2*pi/1200; glint cell rows advance
// 0.5/s = 600 rows/day with ids hashed modulo 24 and 600 = 25*24, so the daily
// wrap re-rolls nothing), recolor-safe output (rgb is a HUE-PRESERVING soft
// clip of vertexColor.rgb -- 1-exp(-k*palette*energy), so strand crests
// saturate toward the palette's bright tint and NEVER toward white), discard
// < 0.01, apply_fog last. Rendered with the additive LIGHTNING blend on 2
// crossed camera-facing planes: u runs ACROSS the beam width (u = 0.5 is the
// axis; x = 2u-1 is the signed cross-beam coordinate), v = height fraction
// with the membrane crossing pinned at v = 0.75 (BeamMesh.APEX_V). No angular
// seam on a plane.

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
    // GameTime spans one day in [0,1); t is in [0,1200) "seconds". All sin
    // speeds below are 2*pi*k/1200 (k integer), so the daily wrap is seamless.
    float t = GameTime * 1200.0;
    float u = texCoord0.x;
    float v = texCoord0.y;
    float x = 2.0 * u - 1.0;

    // Cross-section: slim quiet core + soft glow skirt; both restrained so the
    // two strands stay the star of the show.
    float core = exp(-30.0 * x * x);
    float glow = exp(-3.2 * x * x);

    // The double helix, projected onto the camera-facing plane: each strand's
    // lateral offset is a sinusoid (5 turns over the column, rotating at
    // 2*pi*160/1200 per second), drawn as a smooth gaussian. cos of the same
    // phase is the depth cue: the strand swings brighter in "front", dimmer
    // "behind" -- the crossings read 3D instead of flat.
    float phase = v * 31.4159265 - t * 0.8377580;
    float xa = 0.55 * sin(phase);
    float xb = 0.55 * sin(phase + 3.1415927);
    float strandA = exp(-70.0 * (x - xa) * (x - xa)) * (0.68 + 0.32 * cos(phase));
    float strandB = exp(-70.0 * (x - xb) * (x - xb)) * (0.68 + 0.32 * cos(phase + 3.1415927));

    // Rising sparkle glints: hash cells climbing the column, SOFTLY gated with
    // smoothstep (no hard step() pop-in) and masked to the glow skirt so they
    // never float off the beam body. 8 cells across, 16 rows per column.
    vec2 cell = vec2(floor(u * 8.0), floor(v * 16.0 - t * 0.5));
    float glint = hash11(mod(cell.x, 8.0) * 7.0 + mod(cell.y, 24.0) * 0.173);
    float twinkle = 0.5 + 0.5 * sin(t * 0.8377580 + glint * 39.0);
    float sparkle = smoothstep(0.72, 0.92, glint) * twinkle * glow;

    // Base impact flare at the projector and an apex bloom + faint ring where
    // the strands pierce the membrane (pinned at v = 0.75 by BeamMesh).
    float flare = exp(-10.0 * v) * exp(-1.8 * x * x);
    float av = v - 0.75;
    float apex = exp(-180.0 * av * av);
    float ringx = abs(x) - 0.62;
    float apexRing = apex * exp(-60.0 * ringx * ringx);

    float energy = glow * 0.14
            + core * 0.42
            + (strandA + strandB) * 0.85
            + sparkle * 0.30
            + flare * 0.8
            + apex * 0.45 * glow + apexRing * 0.35;

    // HUE-PRESERVING soft clip: strand crests saturate toward the palette's
    // bright tint (zero channels never light up), never toward white.
    vec3 rgb = 1.0 - exp(-2.6 * vertexColor.rgb * energy);

    float alpha = vertexColor.a * clamp(energy, 0.0, 1.0);
    vec4 color = vec4(clamp(rgb, 0.0, 1.0), alpha);
    if (color.a < 0.01) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

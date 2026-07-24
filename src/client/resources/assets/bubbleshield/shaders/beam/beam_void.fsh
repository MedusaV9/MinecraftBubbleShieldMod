#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>

// Beam surface shader beam_void -- the INVERTED column: a dark hollow core
// (additive blending emits nothing there, so the world shows through) walled
// by a bright event-horizon rim, with infalling matter streaks accelerating
// TOWARD the membrane crossing from both sides and an occasional slow flare
// racing along the rim.
// HAND-WRITTEN (not generated; one shader per BeamStyle). Follows the frozen
// bubble fragment contract: fog+globals imports only, no custom uniforms or
// textures, GameTime-only animation with day-quantized speeds (streak infall
// 0.35*1200 = 420 whole dash-cycles/day; flare intervals are 3 s wide, 1200/3
// = 400 per day, so fract(t/3) is continuous across the daily wrap and the
// wrap boundary is just another re-roll; every sin speed is an integer
// multiple of 2*pi/1200), recolor-safe output (rgb is a HUE-PRESERVING soft
// clip of vertexColor.rgb -- 1-exp(-k*palette*energy), so the rim saturates
// toward the palette's bright tint and NEVER toward white), discard < 0.01,
// apply_fog last. Rendered with the additive LIGHTNING blend on 2 crossed
// camera-facing planes: u runs ACROSS the beam width (u = 0.5 is the axis;
// x = 2u-1 is the signed cross-beam coordinate), v = height fraction with the
// membrane crossing pinned at v = 0.75 (BeamMesh.APEX_V -- the streaks fall
// toward it and the horizon disc sits on it). No angular seam on a plane.
// Epilepsy-safe: the rim flare fires at most once per 3 s (~0.33 Hz) with a
// smooth gaussian envelope, and nothing gates the whole field.

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

    // The event horizon: a bright rim at |x| = rimR, wobbling gently (5
    // in-phase waves over the column, drifting at 2*pi*40/1200 per second).
    // The core between the rims stays DARK: the well mask kills the center.
    float rimR = 0.45 + 0.02 * sin(v * 12.566371 + t * 0.2094395);
    float rx = abs(x) - rimR;
    float rim = exp(-300.0 * rx * rx);
    float wellMask = 1.0 - exp(-9.0 * x * x);
    float skirtGlow = exp(-3.0 * x * x) * 0.16 * wellMask;

    // A faint shimmer crawling DOWN the rim (2*pi*80/1200 per second) so the
    // horizon reads active without ever strobing.
    float shimmer = 0.5 + 0.5 * sin(v * 25.132741 - t * 0.4188790);

    // Infalling matter streaks: 12 hash-jittered lanes of dashes converging on
    // the membrane crossing from BOTH sides -- below it they climb, above it
    // they sink (the |v - 0.75| phase runs toward the apex as t grows). Dashes
    // brighten as they near the horizon and are masked to the dark interior.
    float lane = floor(u * 12.0);
    float roll = hash11(lane * 0.173 + 0.5);
    float px = fract(u * 12.0) - 0.5;
    float jitter = (roll - 0.5) * 0.6;
    float lateral = exp(-40.0 * (px - jitter) * (px - jitter));
    float d = v - 0.75;
    float sp = fract(abs(d) * 4.0 + t * 0.35 + roll * 7.0);
    float dash = exp(-50.0 * (sp - 0.5) * (sp - 0.5));
    float acc = 0.35 + 0.65 * exp(-4.0 * abs(d));
    float interior = smoothstep(rimR, rimR - 0.14, abs(x));
    float streaks = lateral * dash * acc * interior;

    // Occasional rim flare: every 3 s interval rolls once; a bit under half of
    // the rolls pass the gate, and a smooth gaussian envelope (~0.7 s wide)
    // slides a bright patch onto a hash-picked height of the rim.
    float fid = floor(t / 3.0);
    float fgate = smoothstep(0.55, 0.7, hash11(fid * 0.719 + 0.37));
    float fpos = mix(0.06, 0.66, hash11(fid * 0.531 + 0.11));
    float fph = fract(t / 3.0);
    float fenv = exp(-30.0 * (fph - 0.4) * (fph - 0.4));
    float flarePulse = fgate * fenv * exp(-40.0 * (v - fpos) * (v - fpos)) * rim;

    // Base impact glow at the projector (kept dim -- the column is a well, not
    // a torch) and the horizon disc where the beam pierces the membrane
    // (pinned at v = 0.75 by BeamMesh): a bright ring on the rim radius.
    float flare = exp(-10.0 * v) * exp(-1.8 * x * x);
    float av = v - 0.75;
    float apex = exp(-180.0 * av * av);
    float horizonRing = apex * exp(-90.0 * (abs(x) - rimR) * (abs(x) - rimR));

    float energy = skirtGlow
            + rim * (0.85 + 0.15 * shimmer)
            + streaks * 0.60
            + flarePulse * 0.80
            + flare * 0.45
            + horizonRing * 0.55 + apex * 0.12 * wellMask;

    // HUE-PRESERVING soft clip: the horizon saturates toward the palette's
    // bright tint (zero channels never light up), never toward white.
    vec3 rgb = 1.0 - exp(-2.6 * vertexColor.rgb * energy);

    float alpha = vertexColor.a * clamp(energy, 0.0, 1.0);
    vec4 color = vec4(clamp(rgb, 0.0, 1.0), alpha);
    if (color.a < 0.01) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

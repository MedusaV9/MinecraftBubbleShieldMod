#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>

// Beam surface shader beam_runic -- the WARD PILLAR: three vertical bands of
// angular segment-SDF glyphs (a spine plus hash-gated diagonal/crossbar
// strokes, so every cell carves a different rune) igniting and fading in an
// upward-climbing sequence, around a quiet core and under a slowly pulsing
// apex ring.
// HAND-WRITTEN (not generated; one shader per BeamStyle). Follows the frozen
// bubble fragment contract: fog+globals imports only, no custom uniforms or
// textures, GameTime-only animation with day-quantized speeds (the ignition
// wave and the apex pulse are integer multiples of 2*pi/1200 -- the wave's
// 2*pi*200/1200 gives each cell one 6 s ignite/fade cycle; rune shapes re-roll
// per cell every 12 s, 100 epochs/day, and the cells are STATIONARY so the
// daily-wrap epoch jump is just another re-roll), recolor-safe output (rgb is
// a HUE-PRESERVING soft clip of vertexColor.rgb -- 1-exp(-k*palette*energy),
// so lit runes saturate toward the palette's bright tint and NEVER toward
// white), discard < 0.01, apply_fog last. Rendered with the additive LIGHTNING
// blend on 2 crossed camera-facing planes: u runs ACROSS the beam width
// (u = 0.5 is the axis; x = 2u-1 is the signed cross-beam coordinate), v =
// height fraction with the membrane crossing pinned at v = 0.75
// (BeamMesh.APEX_V -- the pulsing ward ring lives there). Epilepsy-safe: each
// rune breathes over 6 s and the shared field never gates faster than that.

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

// distance from p to the segment a-b
float segd(vec2 p, vec2 a, vec2 b) {
    vec2 pa = p - a;
    vec2 ba = b - a;
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return length(pa - ba * h);
}

// One rune in cell-local coords (p in about [-0.5, 0.5]^2): a constant spine
// plus five hash-gated strokes -- 32 distinct angular glyphs per seed space.
float glyph(vec2 p, float seed) {
    float d = segd(p, vec2(0.0, -0.38), vec2(0.0, 0.38));
    if (hash11(seed + 1.3) > 0.45) {
        d = min(d, segd(p, vec2(0.0, 0.10), vec2(0.30, 0.34)));
    }
    if (hash11(seed + 2.6) > 0.45) {
        d = min(d, segd(p, vec2(0.0, 0.10), vec2(-0.30, 0.34)));
    }
    if (hash11(seed + 3.9) > 0.45) {
        d = min(d, segd(p, vec2(0.0, -0.10), vec2(0.30, -0.34)));
    }
    if (hash11(seed + 5.2) > 0.45) {
        d = min(d, segd(p, vec2(0.0, -0.10), vec2(-0.30, -0.34)));
    }
    if (hash11(seed + 6.5) > 0.55) {
        d = min(d, segd(p, vec2(-0.26, 0.0), vec2(0.26, 0.0)));
    }
    return smoothstep(0.085, 0.02, d);
}

void main() {
    // GameTime spans one day in [0,1); t is in [0,1200) "seconds".
    float t = GameTime * 1200.0;
    float u = texCoord0.x;
    float v = texCoord0.y;
    float x = 2.0 * u - 1.0;

    // Quiet body: a dim steady core and skirt keep the pillar legible while
    // the runes carry the animation.
    float core = exp(-30.0 * x * x);
    float glow = exp(-3.0 * x * x);

    // Three glyph bands (left rail, axis, right rail) with faint always-on
    // rail lines; 12 stationary rune cells per band. Each cell's brightness
    // follows the upward-climbing ignition wave (one 6 s cycle per cell,
    // adjacent rows 1.1 rad apart, bands offset by 2*pi/3), and its rune
    // shape re-rolls every 12 s on a per-row-staggered epoch.
    float runes = 0.0;
    float rails = 0.0;
    float row = floor(v * 12.0);
    float ry = fract(v * 12.0) - 0.5;
    for (int i = 0; i < 3; i++) {
        float xl = (float(i) - 1.0) * 0.52;
        float px = (x - xl) * 2.6;
        float laneMask = smoothstep(0.62, 0.50, abs(px));
        float epoch = floor((t + row * 1.7) / 12.0);
        float seed = float(i) * 17.0 + row * 3.7 + epoch * 29.0;
        float wave = 0.5 + 0.5 * sin(t * 1.0471976 - row * 1.1 - float(i) * 2.0943951);
        float ignite = smoothstep(0.35, 0.80, wave);
        runes += glyph(vec2(px, ry), seed) * ignite * laneMask;
        rails += exp(-500.0 * (x - xl) * (x - xl)) * 0.10;
    }

    // Base impact flare at the projector and the apex WARD RING where the
    // pillar pierces the membrane (pinned at v = 0.75 by BeamMesh): its radius
    // sweeps 0.40..0.60 and its brightness swells in quadrature, one full
    // pulse every 12 s (2*pi*100/1200 per second).
    float flare = exp(-10.0 * v) * exp(-1.8 * x * x);
    float av = v - 0.75;
    float apex = exp(-180.0 * av * av);
    float ringR = 0.50 + 0.10 * sin(t * 0.5235988);
    float ringPulse = 0.70 + 0.30 * sin(t * 0.5235988 + 1.5707963);
    float apexRing = apex * exp(-80.0 * (abs(x) - ringR) * (abs(x) - ringR)) * ringPulse;

    float energy = glow * 0.12
            + core * 0.30
            + rails
            + runes * 1.05
            + flare * 0.8
            + apex * 0.30 * glow + apexRing * 0.55;

    // HUE-PRESERVING soft clip: lit runes saturate toward the palette's
    // bright tint (zero channels never light up), never toward white.
    vec3 rgb = 1.0 - exp(-2.6 * vertexColor.rgb * energy);

    float alpha = vertexColor.a * clamp(energy, 0.0, 1.0);
    vec4 color = vec4(clamp(rgb, 0.0, 1.0), alpha);
    if (color.a < 0.01) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

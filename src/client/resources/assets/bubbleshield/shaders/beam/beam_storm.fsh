#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>

// Beam surface shader beam_storm -- the "storm shield" column: a thin white-hot
// core wobbling inside a soft glow skirt, flanked by two flickery turbulence
// filaments and gated by an electric strobe.
// HAND-WRITTEN (not generated; one shader per BeamStyle). Follows the frozen
// bubble fragment contract: fog+globals imports only, no custom uniforms or
// textures, GameTime-only animation with day-quantized speeds (every scroll's
// per-day drift is an integer multiple of the lattice y-period and every sin
// speed is an integer multiple of 2*pi/1200), recolor-safe output (rgb is a
// HUE-PRESERVING soft clip of vertexColor.rgb -- 1-exp(-k*palette*energy), so
// hot areas saturate toward the palette's bright tint and NEVER toward white;
// zero channels stay zero), discard < 0.01, apply_fog last. Rendered with the
// additive LIGHTNING blend on 2 crossed camera-facing planes: u runs ACROSS
// the beam width (u = 0.5 is the axis; x = 2u-1 is the signed cross-beam
// coordinate), v = height fraction with the membrane crossing pinned at
// v = 0.75 (BeamMesh.APEX_V -- the piecewise CPU v-map stands in for a
// uniform). There is no angular seam on a plane, so u needs no wrapping.

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

// value noise on a lattice wrapping every per.x cells in x and every per.y
// cells in y (so a y drift of an integer number of periods per day is
// seamless across the daily time wrap)
float vnoise(vec2 p, vec2 per) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 w = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float a = hash21(mod(i, per));
    float b = hash21(mod(i + vec2(1.0, 0.0), per));
    float c = hash21(mod(i + vec2(0.0, 1.0), per));
    float d = hash21(mod(i + vec2(1.0, 1.0), per));
    return mix(mix(a, b, w.x), mix(c, d, w.x), w.y);
}

// 3-octave fbm; lacunarity exactly 2 with the wrap period doubling in
// lockstep, so every octave keeps the day-quantized y drift an integer
// number of (scaled) periods
float fbm2(vec2 p, vec2 per) {
    float value = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < 3; i++) {
        value += amplitude * vnoise(p, per);
        p = p * 2.0 + vec2(19.1, 7.3);
        per *= 2.0;
        amplitude *= 0.55;
    }
    return value;
}

void main() {
    // GameTime spans one day in [0,1); t is in [0,1200) "seconds". Noise
    // scrolls: 0.4*1200 = 480 = 120*4 and 0.65*1200 = 780 = 195*4, integer
    // multiples of the y period (4); band scroll 0.3*1200 = 360 whole cycles
    // per day; sin speeds are integer multiples of 2*pi/1200.
    float t = GameTime * 1200.0;
    float u = texCoord0.x;
    float v = texCoord0.y;
    float x = 2.0 * u - 1.0;
    vec2 per = vec2(97.0, 4.0);

    // Cross-section: a thin bright core inside a soft wide glow skirt. The
    // storm core wobbles off-axis with a turbulence-driven sway.
    float sway = (fbm2(vec2(2.7, v * 7.0 - t * 0.4), per) - 0.5) * 0.5;
    float core = exp(-30.0 * (x - sway) * (x - sway));
    float glow = exp(-3.5 * x * x);

    // Two flickery filaments braiding around the core, their lateral offsets
    // driven by counter-drifting turbulence curtains.
    float offA = (fbm2(vec2(11.3, v * 7.0 - t * 0.4), per) - 0.5) * 1.1;
    float offB = (fbm2(vec2(29.1, v * 9.0 - t * 0.65), per) - 0.5) * 1.1;
    float filaments = exp(-55.0 * (x - offA) * (x - offA))
            + exp(-55.0 * (x - offB) * (x - offB));

    // Electric flicker: a per-interval gate (8 re-rolls per second; 9600/day)
    // dips the filaments, plus a fast strobe shimmer (2*pi*200/1200 per
    // second) riding them. The base glow is NOT gated, so the column never
    // fully vanishes.
    float gate = 0.72 + 0.28 * hash11(floor(t * 8.0) * 0.113);
    float strobe = 0.85 + 0.15 * sin(t * 1.0471976 + v * 18.0);

    // Shader-side rising energy: a smooth packet climbing the column
    // (0.3 columns per second = 360/day), widened by the glow skirt.
    float bd = fract(v - t * 0.3) - 0.5;
    float band = exp(-40.0 * bd * bd);

    // Base impact flare at the projector and an apex bloom + faint ring where
    // the beam pierces the membrane (pinned at v = 0.75 by BeamMesh).
    float flare = exp(-10.0 * v) * exp(-1.8 * x * x);
    float av = v - 0.75;
    float apex = exp(-180.0 * av * av);
    float ringx = abs(x) - 0.62;
    float apexRing = apex * exp(-60.0 * ringx * ringx);

    float energy = glow * (0.20 + 0.10 * fbm2(vec2(u * 2.0 + 5.0, v * 5.0 - t * 0.4), per))
            + core * 0.95 * strobe
            + filaments * 0.55 * gate * strobe
            + band * 0.65 * glow
            + flare * 0.8
            + apex * 0.45 * glow + apexRing * 0.35;

    // HUE-PRESERVING soft clip: saturates toward the palette's bright tint
    // (zero channels never light up), never mixes toward white.
    vec3 rgb = 1.0 - exp(-2.6 * vertexColor.rgb * energy);

    float alpha = vertexColor.a * clamp(energy, 0.0, 1.0);
    vec4 color = vec4(clamp(rgb, 0.0, 1.0), alpha);
    if (color.a < 0.01) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

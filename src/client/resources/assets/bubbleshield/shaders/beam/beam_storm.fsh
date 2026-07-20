#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>

// Beam surface shader beam_storm -- the "storm shield" column: turbulent fbm
// streamers rising around the tube + an electric flicker gate.
// HAND-WRITTEN (not generated; one shader per BeamStyle). Follows the frozen
// bubble fragment contract: fog+globals imports only, no custom uniforms or
// textures, GameTime-only animation with day-quantized speeds (every scroll's
// per-day drift is an integer multiple of the lattice y-period and every sin
// speed is an integer multiple of 2*pi/1200), integer u-harmonics / wrapping
// lattice across the tube seam, recolor-safe output (rgb multiplies
// vertexColor.rgb, final alpha = vertexColor.a * pattern), discard < 0.01,
// apply_fog last. Rendered with the additive LIGHTNING blend, so alpha is
// intensity. u = angle fraction [0,1] around the tube, v = height fraction
// [0,1] (the outer glow shell flips v, so it scrolls opposite the core).

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

// value noise on a lattice wrapping every per.x cells in x (the tube seam)
// and every per.y cells in y (so a y drift of an integer number of periods
// per day is seamless across the daily time wrap)
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
// lockstep, so every octave tiles the u seam AND keeps the day-quantized
// y drift an integer number of (scaled) periods
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
    // GameTime spans one day in [0,1); t is in [0,1200) "seconds". Scroll
    // speeds below: 0.4*1200 = 480 = 120*4 and 0.65*1200 = 780 = 195*4, both
    // integer multiples of the y period (4), so the daily wrap never pops.
    float t = GameTime * 1200.0;
    float u = texCoord0.x;
    float v = texCoord0.y;
    vec2 per = vec2(6.0, 4.0);

    // Two counter-drifting turbulence curtains wrapped around the tube,
    // scrolling downward in domain space = energy flowing UP the column.
    float curtainA = fbm2(vec2(u * 6.0, v * 7.0 - t * 0.4), per);
    float curtainB = fbm2(vec2(u * 6.0 + 2.5, v * 9.0 - t * 0.65), per);

    // Ridged streamers: bright filaments on the curtain's midline, modulated
    // by the second curtain so they braid instead of banding.
    float streamer = pow(1.0 - abs(2.0 * curtainA - 1.0), 3.0);
    streamer *= 0.6 + 0.8 * curtainB;

    // Electric flicker: a per-interval gate (8 re-rolls per second) dips the
    // whole column, plus a fast strobe shimmer (2*pi*200/1200 per second)
    // riding the filaments.
    float gate = 0.72 + 0.28 * hash11(floor(t * 8.0) * 0.113);
    float strobe = 0.85 + 0.15 * sin(t * 1.0471976 + v * 12.5663706);

    // Soft column body so the beam never fully vanishes between filaments.
    float body = 0.30 + 0.25 * curtainB;

    float pattern = clamp((body + 1.15 * streamer) * gate * strobe, 0.0, 1.5);

    // Recolor-safe: the palette rides vertexColor.rgb (the owner /color
    // override replaces it wholesale); hot filaments lift toward white.
    vec3 rgb = vertexColor.rgb * (0.55 + 0.85 * pattern);
    rgb = mix(rgb, vec3(1.0), 0.35 * smoothstep(0.85, 1.4, pattern));

    float alpha = vertexColor.a * clamp(pattern, 0.0, 1.0);
    vec4 color = vec4(clamp(rgb, 0.0, 1.0), alpha);
    if (color.a < 0.01) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>

// Beam surface shader beam_helix -- two counter-phased helical stripes winding
// up the column around a steady core glow, with rising sparkle streamers.
// HAND-WRITTEN (not generated; one shader per BeamStyle). Follows the frozen
// bubble fragment contract: fog+globals imports only, no custom uniforms or
// textures, GameTime-only animation with day-quantized speeds (every helix /
// streamer sin speed is an integer multiple of 2*pi/1200), integer
// u-harmonics across the tube seam, recolor-safe output (rgb multiplies
// vertexColor.rgb, final alpha = vertexColor.a * pattern), discard < 0.01,
// apply_fog last. Rendered with the additive LIGHTNING blend, so alpha is
// intensity. u = angle fraction, v = height fraction (the outer glow shell
// flips v, so its helices twist the opposite way -- a braided read).

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

    // Two counter-phased double helices: integer harmonic 2 around the tube
    // (seam-safe), 5 turns over the column, rotating at 2*pi*160/1200 per
    // second in opposite senses. pow sharpens the stripes.
    float helixA = pow(0.5 + 0.5 * sin(u * 12.5663706 + v * 31.4159265 - t * 0.8377580), 5.0);
    float helixB = pow(0.5 + 0.5 * sin(u * 12.5663706 - v * 31.4159265 + t * 0.8377580 + 3.1415927), 5.0);

    // Rising sparkle streamers: hash-cell glints climbing the column (cell
    // rows advance 0.5/s = 600 rows/day, ids hashed modulo 24, and 600 is an
    // integer multiple of 24... 600 = 25*24, so the daily wrap re-rolls
    // nothing); 8 cells around the tube wrap the seam exactly.
    vec2 cell = vec2(floor(u * 8.0), floor(v * 16.0 - t * 0.5));
    float glint = hash11(mod(cell.x, 8.0) * 7.0 + mod(cell.y, 24.0) * 0.173);
    float streamer = step(0.8, glint) * (0.5 + 0.5 * sin(t * 0.8377580 + glint * 39.0));

    // Core glow floor: the column never vanishes, and a slow swell
    // (2*pi*30/1200 per second) keeps it breathing.
    float core = 0.32 + 0.06 * sin(t * 0.1570796 + v * 12.5663706);

    float pattern = clamp(core + 1.05 * (helixA + helixB) + 0.5 * streamer, 0.0, 1.5);

    // Recolor-safe: the palette rides vertexColor.rgb; stripe crests lift white.
    vec3 rgb = vertexColor.rgb * (0.55 + 0.85 * pattern);
    rgb = mix(rgb, vec3(1.0), 0.35 * smoothstep(0.9, 1.4, pattern));

    float alpha = vertexColor.a * clamp(pattern, 0.0, 1.0);
    vec4 color = vec4(clamp(rgb, 0.0, 1.0), alpha);
    if (color.a < 0.01) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

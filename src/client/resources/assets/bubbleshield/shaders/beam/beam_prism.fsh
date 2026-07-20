#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>

// Beam surface shader beam_prism -- a calm "tractor beam": broad slow bands
// drifting up the column, a minor counter-spiral, and a thin-film-style
// per-channel dispersion so the light splits into spectral fringes.
// HAND-WRITTEN (not generated; one shader per BeamStyle). Follows the frozen
// bubble fragment contract: fog+globals imports only, no custom uniforms or
// textures, GameTime-only animation with day-quantized speeds (every sin
// speed is an integer multiple of 2*pi/1200), integer u-harmonics across the
// tube seam, recolor-safe output (the dispersion is a BOUNDED multiplier on
// the vertexColor-derived rgb, so the owner /color override stays
// authoritative; final alpha = vertexColor.a * pattern), discard < 0.01,
// apply_fog last. Rendered with the additive LIGHTNING blend, so alpha is
// intensity. u = angle fraction, v = height fraction (the outer glow shell
// flips v, so its bands sink while the core's rise).

in vec2 texCoord0;
in vec4 vertexColor;
in float sphericalVertexDistance;
in float cylindricalVertexDistance;

out vec4 fragColor;

// soap-film interference: per-RGB cosine over a pseudo optical path length
vec3 thinFilm(float thickness) {
    vec3 invLambda = vec3(1.0, 0.8065, 0.6452);
    return 0.5 + 0.5 * cos(6.2831853 * thickness * invLambda + vec3(0.0, 0.6, 1.2));
}

void main() {
    // GameTime spans one day in [0,1); t is in [0,1200) "seconds". All sin
    // speeds below are 2*pi*k/1200 (k integer), so the daily wrap is seamless.
    float t = GameTime * 1200.0;
    float u = texCoord0.x;
    float v = texCoord0.y;

    // Broad slow bands: 3 soft waves over the column height, rising at
    // 2*pi*50/1200 per second -- the calm tractor-beam body.
    float bands = 0.5 + 0.5 * sin(v * 18.8495559 - t * 0.2617994);
    bands = pow(bands, 1.5);

    // Minor counter-spiral: one integer harmonic around the tube, 2 turns
    // over the height, rotating at 2*pi*70/1200 per second the other way.
    float spiral = pow(0.5 + 0.5 * sin(u * 6.2831853 + v * 12.5663706 + t * 0.3665191), 3.0);

    // Gentle iridescent shimmer sweeping the height (2*pi*20/1200 per second).
    float sheen = 0.5 + 0.5 * sin(v * 6.2831853 + u * 6.2831853 + t * 0.1047198);

    float body = 0.34;
    float pattern = clamp(body + 0.75 * bands + 0.45 * spiral + 0.15 * sheen, 0.0, 1.5);

    // Recolor-safe composite: the palette rides vertexColor.rgb, then the
    // spectral dispersion multiplies it as a bounded (0.55..1.45) factor keyed
    // to the band phase -- fringes split into rainbow hues on any base color.
    vec3 rgb = vertexColor.rgb * (0.55 + 0.85 * pattern);
    vec3 film = thinFilm(0.35 + 0.9 * bands + 0.4 * v + 0.25 * spiral);
    rgb = rgb * (0.55 + 0.9 * film);
    rgb = mix(rgb, vec3(1.0), 0.25 * smoothstep(0.95, 1.4, pattern));

    float alpha = vertexColor.a * clamp(pattern, 0.0, 1.0);
    vec4 color = vec4(clamp(rgb, 0.0, 1.0), alpha);
    if (color.a < 0.01) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

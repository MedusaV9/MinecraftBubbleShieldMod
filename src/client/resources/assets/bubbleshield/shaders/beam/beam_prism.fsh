#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>

// Beam surface shader beam_prism -- the SOLID "tractor beam": the steadiest,
// fullest column of the four (high body floor, calm slow bands) with a
// thin-film spectral treatment: per-channel LATERAL dispersion (the R/G/B glow
// skirts are offset slightly across the beam, splitting the edges into
// spectral fringes) plus a bounded soap-film interference multiplier.
// HAND-WRITTEN (not generated; one shader per BeamStyle). Follows the frozen
// bubble fragment contract: fog+globals imports only, no custom uniforms or
// textures, GameTime-only animation with day-quantized speeds (every sin speed
// is an integer multiple of 2*pi/1200), recolor-safe output (the dispersion
// and film terms are BOUNDED per-channel weights on the vertexColor-derived
// energy, so the owner /color override stays authoritative; the final rgb is a
// HUE-PRESERVING soft clip 1-exp(-k*palette*energy) that saturates toward the
// palette's bright tint and NEVER toward white), discard < 0.01, apply_fog
// last. Rendered with the additive LIGHTNING blend on 2 crossed camera-facing
// planes: u runs ACROSS the beam width (u = 0.5 is the axis; x = 2u-1 is the
// signed cross-beam coordinate), v = height fraction with the membrane
// crossing pinned at v = 0.75 (BeamMesh.APEX_V). No angular seam on a plane.

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
    float x = 2.0 * u - 1.0;

    // Cross-section with per-channel lateral dispersion: the R and B glow
    // skirts are shifted a touch across the beam, so the column's edges split
    // into spectral fringes while the center stays palette-true.
    float xr = x - 0.10;
    float xb = x + 0.10;
    vec3 glow3 = vec3(exp(-3.0 * xr * xr), exp(-3.0 * x * x), exp(-3.0 * xb * xb));
    float glow = glow3.g;
    float core = exp(-26.0 * x * x);

    // Broad slow bands: 3 soft waves over the column height, rising at
    // 2*pi*50/1200 per second -- the calm tractor-beam body motion.
    float bands = 0.5 + 0.5 * sin(v * 18.8495559 - t * 0.2617994);
    bands = bands * bands * (0.6 + 0.4 * bands);

    // Gentle iridescent sheen sweeping the height (2*pi*20/1200 per second).
    float sheen = 0.5 + 0.5 * sin(v * 6.2831853 + x * 2.0 + t * 0.1047198);

    // Base impact flare at the projector and an apex bloom + faint ring where
    // the beam pierces the membrane (pinned at v = 0.75 by BeamMesh).
    float flare = exp(-10.0 * v) * exp(-1.8 * x * x);
    float av = v - 0.75;
    float apex = exp(-180.0 * av * av);
    float ringx = abs(x) - 0.62;
    float apexRing = apex * exp(-60.0 * ringx * ringx);

    // SOLID body: the highest floor of the four styles; bands modulate gently
    // on top instead of carving the column apart.
    vec3 energy = glow3 * (0.34 + 0.22 * bands + 0.08 * sheen)
            + vec3(core) * (0.55 + 0.15 * bands)
            + vec3(flare) * 0.8
            + vec3(apex * 0.40 * glow + apexRing * 0.35);

    // Bounded (0.55..1.45) soap-film multiplier keyed to the band phase and
    // the cross-beam position -- spectral fringes on any base palette.
    vec3 film = thinFilm(0.35 + 0.9 * bands + 0.30 * abs(x) + 0.25 * v);
    energy *= 0.55 + 0.9 * film;

    // HUE-PRESERVING soft clip: the column saturates toward the palette's
    // bright tint (zero channels never light up), never toward white.
    vec3 rgb = 1.0 - exp(-2.6 * vertexColor.rgb * energy);

    float alpha = vertexColor.a * clamp(dot(energy, vec3(0.3333333)), 0.0, 1.0);
    vec4 color = vec4(clamp(rgb, 0.0, 1.0), alpha);
    if (color.a < 0.01) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

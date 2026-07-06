#version 330

#moj_import <minecraft:globals.glsl>

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform RippleConfig {
    vec4 Primary;
    float Intensity;
    float Speed;
};

out vec4 fragColor;

void main() {
    // GameTime wraps once per day cycle (24000 ticks); scale to roughly seconds.
    float time = GameTime * 1200.0 * Speed;

    // Radial ripples emanating from the screen center; aspect-corrected so the
    // rings stay circular, fading toward the center and the far edges.
    vec2 centered = (texCoord - vec2(0.5)) * vec2(InSize.x / InSize.y, 1.0);
    float dist = length(centered);
    float ring = sin(dist * 48.0 - time * 3.0);
    float fade = smoothstep(0.05, 0.25, dist) * smoothstep(0.9, 0.5, dist);

    // Displace the sample along the radial direction by the ring wave.
    vec2 dir = dist > 0.0001 ? centered / dist : vec2(0.0);
    vec2 offset = dir * ring * fade * 0.008 * Intensity;
    vec4 diffuseColor = texture(InSampler, clamp(texCoord + offset, 0.0, 1.0));

    // Crests pick up a whisper of the shield's primary color.
    float crest = smoothstep(0.5, 1.0, ring) * fade;
    vec3 outColor = mix(diffuseColor.rgb, diffuseColor.rgb * Primary.rgb, crest * 0.25 * Intensity);
    fragColor = vec4(outColor, 1.0);
}

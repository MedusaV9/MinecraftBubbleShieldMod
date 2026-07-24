#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;

out vec2 texCoord0;
out vec4 vertexColor;
out float sphericalVertexDistance;
out float cylindricalVertexDistance;
// Camera-relative world-space position (Position is already pose-transformed
// CPU-side, with the pose translated by shieldCenter - cameraPos): the camera
// sits at the origin of this space, so a fragment's view direction is
// -normalize(worldPos) and its camera distance is length(worldPos). Consumed
// by refraction-capable fragment shaders; harmlessly unused by the rest.
out vec3 worldPos;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    texCoord0 = UV0;
    vertexColor = Color;
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    worldPos = Position;
}

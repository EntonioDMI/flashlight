#version 330

// Flashlight — копия ванильного rendertype_entity_shadow.vsh + view-space позиция.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
out vec3 flViewPos;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    flViewPos = (ModelViewMat * vec4(Position, 1.0)).xyz;

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = Color;
    texCoord0 = UV0;
}

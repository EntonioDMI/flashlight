#version 330

// Flashlight — копия ванильного item.vsh (26.1.2) + view-space позиция и тинт.

#moj_import <minecraft:light.glsl>
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
out vec3 flViewPos;
out vec3 flTint;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    flViewPos = (ModelViewMat * vec4(Position, 1.0)).xyz;

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);

    vec4 shaded = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color);
    flTint = shaded.rgb;
    vertexColor = shaded * sample_lightmap(Sampler2, UV2);

    texCoord0 = UV0;
}

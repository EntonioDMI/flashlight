#version 330

// Flashlight — копия ванильного item.fsh (26.1.2) + конусный свет фонарей.
// Предметы на земле и в руках мобов освещаются лучом честно.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <flashlight:fl_lights.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in vec3 flViewPos;
in vec3 flTint;

out vec4 fragColor;

void main() {
    vec4 texColor = texture(Sampler0, texCoord0);
    vec4 color = texColor;
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif

    color *= vertexColor * ColorModulator;
    // === Flashlight: albedo (с шейдингом модели) * конусный свет ===
    vec3 flPos = mat3(FlInvView) * flViewPos;
    color.rgb += texColor.rgb * flTint * flashlightLight(flPos);
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

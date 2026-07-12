#version 330

// Flashlight — копия ванильного rendertype_entity_shadow.fsh + растворение:
// в луче фонаря ванильный кружок-тень моба исчезает (наш свет «пересвечивает»
// его, как настоящий), в темноте остаётся ванильным.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <flashlight:fl_lights.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in vec3 flViewPos;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, clamp(texCoord0, 0.0, 1.0));
    color *= vertexColor * ColorModulator;

    // === Flashlight: наш свет растворяет ванильную тень ===
    vec3 flPos = mat3(FlInvView) * flViewPos;
    float lightAmount = dot(flashlightLight(flPos), vec3(0.3333));
    color.a *= clamp(1.0 - lightAmount * 1.6, 0.0, 1.0);

    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

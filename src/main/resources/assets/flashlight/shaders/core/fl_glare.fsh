#version 330

// Flashlight — процедурный круглый ореол (без текстуры): гауссово ядро +
// экспоненциальная «юбка», как у настоящего засвета линзы. Идеально круглый
// на любом размере, дизеринг против бандинга градиента.

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec2 p = texCoord0 * 2.0 - 1.0;
    float d = length(p);
    float core = exp(-d * d * 5.5);          // яркое ядро
    float halo = exp(-d * 2.0) * 0.6;        // длинная мягкая юбка
    // Жёсткая круговая отсечка к краю квада: даже усиленный ПНВ gain'ом
    // хвост гаусса не превращается в квадрат.
    float cut = 1.0 - smoothstep(0.7, 0.98, d);
    float a = (core + halo) * cut * vertexColor.a;
    // Дизеринг: градиент 8-битного таргета без «колец».
    a += (fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453) - 0.5) / 255.0;
    fragColor = vec4(vertexColor.rgb, clamp(a, 0.0, 1.0));
}

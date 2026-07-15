#version 330

// Flashlight — прибор ночного видения: усиление света в темноте, зелёный
// фосфор (P43), зерно сенсора от времени, блум ярких источников и
// бинокулярная виньетка. GameTime приходит из ванильного Globals UBO.

#moj_import <minecraft:globals.glsl>

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

// Живой uniform (пишется каждый кадр из NvgEffect): 0 -> 1 при включении.
layout(std140) uniform NvgConfig {
    float NvgFade;
};

in vec2 texCoord;

out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

// Шум с временем: новый паттерн каждый кадр (ТВ-помехи, не «ползущая» текстура).
float hashT(vec2 p, float t) {
    return fract(sin(dot(vec3(p, t), vec3(12.9898, 78.233, 37.719))) * 43758.5453);
}

void main() {
    // Разрешение трубки: дешёвый ПНВ видит мир в ~576 строк, не в 4K.
    const float TUBE_LINES = 576.0;
    vec2 res = vec2(TUBE_LINES * OutSize.x / OutSize.y, TUBE_LINES);
    vec2 cell = floor(texCoord * res);
    vec2 uv = (cell + 0.5) / res;

    vec3 color = texture(InSampler, uv).rgb;
    float lum = dot(color, vec3(0.299, 0.587, 0.114));

    // Усиление трубки: ФИКСИРОВАННЫЙ gain, без компрессии светов. Ночь
    // становится видимой, а всё, что и так яркое — день, факел вплотную,
    // луч фонаря — честно пересвечивается, как у трубки без авто-гейтинга.
    const float GAIN = 6.5;
    float amp = pow(min(lum * GAIN, 6.0), 0.65);
    // Фосфорный floor: трубка всегда слегка светится, даже без фотонов.
    amp += 0.06;

    // Зелёный фосфор в рабочем диапазоне...
    vec3 nvg = vec3(0.10, 1.0, 0.22) * min(amp, 1.0);
    // ...а всё выше насыщения ВЫГОРАЕТ в белёсо-зелёный (естественный засвет).
    float over = clamp((amp - 1.0) * 0.9, 0.0, 1.0);
    nvg = mix(nvg, vec3(0.88, 1.0, 0.90), over);

    // ТВ-крупа по сетке трубки: паттерн ПОЛНОСТЬЮ обновляется каждый кадр,
    // как помехи при потере сигнала — еле заметная поверх картинки, гуще в темноте.
    float ticks = GameTime * 24000.0;
    float bright = clamp(amp, 0.0, 1.0);
    float grain = hashT(cell, ticks) - 0.5;
    nvg += grain * mix(0.18, 0.05, bright);
    // Горизонтальные «чёрточки» помех (2 клетки шириной), редкие и неяркие.
    float dash = step(0.955, hashT(vec2(floor(cell.x * 0.5), cell.y) + 17.0, ticks));
    nvg += dash * 0.09 * mix(1.0, 0.25, bright);
    // Сцинтилляция: редкие яркие «искры» трубки.
    float spark = step(0.9995, hashT(cell * 0.37 + 61.7, ticks));
    nvg += spark * 0.3;

    // Мягкое затемнение по углам (без «шарообразного» тубуса).
    vec2 p = texCoord * 2.0 - 1.0;
    p.x *= OutSize.x / OutSize.y;
    float vignette = smoothstep(1.9, 1.05, length(p)) * 0.35 + 0.65;

    // Плавный ввод эффекта при включении: трубка «проявляется», не щёлкает.
    vec3 raw = texture(InSampler, texCoord).rgb;
    fragColor = vec4(mix(raw, nvg * vignette, clamp(NvgFade, 0.0, 1.0)), 1.0);
}

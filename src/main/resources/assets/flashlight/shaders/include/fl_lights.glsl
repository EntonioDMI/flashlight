// Flashlight — общий блок источников и расчёт конусного света.
// Подключается во все наши фрагментные шейдеры: террейн, сущности, партиклы.
// ВНИМАНИЕ: только фрагментная стадия (dFdx внутри).

// ВНИМАНИЕ: размеры массивов (32 источника / 16 капсул) продублированы в
// FlashlightEngine.java (MAX_LIGHTS/MAX_CAPSULES + std140-упаковка UBO).
// Меняешь — меняй там же, иначе упаковка разъедется с шейдером.
layout(std140) uniform FlashLights {
    mat4 FlInvView;        // поворот камеры (view -> camera-relative world)
    vec4 FlCount;          // x = число активных источников (0..32), y = число капсул (0..16)
    vec4 FlPosRange[32];   // xyz = позиция источника (camera-relative), w = дальность
    vec4 FlDirOuter[32];   // xyz = направление луча (норм.), w = cos внешнего угла
    vec4 FlColorInner[32]; // rgb = цвет*яркость, w = cos внутреннего угла
    vec4 FlVoxel0;         // xyz = origin воксельной сетки (camera-relative), w = размер сетки N
    vec4 FlVoxel1;         // x = колонок в атласе, w = окклюзия включена (1/0)
    vec4 FlCapsuleA[16];   // сущности-окклюдеры: xyz = нижний/задний конец оси, w = радиус
    vec4 FlCapsuleB[16];   // xyz = верхний/передний конец оси капсулы
};

// Атлас воксельной окклюзии: N срезов N x N, разложенных сеткой колонок.
uniform sampler2D FlOccluder;

float flOccupied(ivec3 cell, int ni, int cols) {
    ivec2 uv = ivec2((cell.z % cols) * ni + cell.x, (cell.z / cols) * ni + cell.y);
    return texelFetch(FlOccluder, uv, 0).r;
}

// Точная DDA-трассировка по воксельной сетке (Amanatides & Woo):
// идём ровно по клеткам, которые пересекает луч — без просечек и без
// самозатенения. Клетка самой цели не судится (плиты/ступени в занятой
// клетке не чернят сами себя). Координаты — относительно origin сетки.
// 1.0 = свободно.
float flTraceRay(vec3 fromRel, vec3 toRel) {
    vec3 d = toRel - fromRel;
    float len = length(d);
    if (len < 1.0e-3) {
        return 1.0;
    }
    vec3 dir = d / len;
    // Убираем нули из направления, чтобы деления были безопасны.
    vec3 safeDir = dir + step(abs(dir), vec3(1.0e-6)) * 1.0e-5;

    // Клип отрезка по AABB сетки [0..N]^3 — маршируем только внутри неё.
    float n = FlVoxel0.w;
    vec3 invDir = 1.0 / safeDir;
    vec3 tA = (vec3(0.0) - fromRel) * invDir;
    vec3 tB = (vec3(n) - fromRel) * invDir;
    vec3 tMin3 = min(tA, tB);
    vec3 tMax3 = max(tA, tB);
    float tEnter = max(max(tMin3.x, tMin3.y), tMin3.z);
    float tExit = min(min(tMax3.x, tMax3.y), tMax3.z);

    float t = max(0.7, tEnter + 1.0e-3);        // у линзы не судим (рука)
    float tStop = min(len - 0.05, tExit - 1.0e-3);
    if (t >= tStop) {
        return 1.0;
    }

    int ni = int(n);
    int cols = int(FlVoxel1.x);
    ivec3 endCell = ivec3(floor(toRel));        // клетка цели — вне подозрений
    vec3 p = fromRel + dir * t;
    ivec3 cell = clamp(ivec3(floor(p)), ivec3(0), ivec3(ni - 1));
    ivec3 stepD = ivec3(sign(safeDir));
    vec3 tDelta = abs(invDir);
    vec3 boundary = vec3(cell) + max(vec3(stepD), vec3(0.0));
    vec3 tMax = t + (boundary - p) * invDir;

    // 96 итераций хватает: отрезок клипнут AABB сетки 48³, длинные диагонали
    // редки, а обрыв цикла = «свободно» (светлее, не чернее).
    for (int i = 0; i < 96; i++) {
        if (all(equal(cell, endCell))) {
            break;
        }
        if (flOccupied(cell, ni, cols) > 0.5) {
            return 0.0;
        }
        if (tMax.x < tMax.y && tMax.x < tMax.z) {
            t = tMax.x;
            tMax.x += tDelta.x;
            cell.x += stepD.x;
        } else if (tMax.y < tMax.z) {
            t = tMax.y;
            tMax.y += tDelta.y;
            cell.y += stepD.y;
        } else {
            t = tMax.z;
            tMax.z += tDelta.z;
            cell.z += stepD.z;
        }
        if (t >= tStop
                || any(lessThan(cell, ivec3(0)))
                || any(greaterThanEqual(cell, ivec3(ni)))) {
            break;
        }
    }
    return 1.0;
}

// Тень от капсулы-сущности: ближайшее сближение отрезка «линза -> точка»
// с осью капсулы. Полутень — PCSS (Fernando 2005): ширина растёт с
// (dReceiver - dBlocker) / dBlocker — контактная тень резкая, дальняя мягкая.
float flCapsuleShadow(vec3 ro, vec3 target, vec3 a, vec3 b, float r) {
    vec3 d1 = target - ro;   // луч света
    vec3 d2 = b - a;         // ось капсулы
    vec3 rv = ro - a;
    float l1 = dot(d1, d1);
    float l2 = max(dot(d2, d2), 1.0e-6);

    // Фрагмент на самой сущности или вплотную к ней — не самозатеняем
    // (сущность освещается конусом как раньше, «ауры» под ногами нет).
    float tf = clamp(dot(target - a, d2) / l2, 0.0, 1.0);
    if (length(target - (a + d2 * tf)) < r + 0.12) {
        return 1.0;
    }

    // Ближайшие точки двух отрезков (Ericson, Real-Time Collision Detection).
    float f = dot(d2, rv);
    float c = dot(d1, rv);
    float bb = dot(d1, d2);
    float denom = l1 * l2 - bb * bb;
    float s = denom > 1.0e-6 ? clamp((bb * f - c * l2) / denom, 0.0, 1.0) : 0.0;
    float t2 = clamp((bb * s + f) / l2, 0.0, 1.0);
    s = clamp((bb * t2 - c) / max(l1, 1.0e-6), 0.0, 1.0);

    float dca = length((ro + d1 * s) - (a + d2 * t2));
    float rayLen = sqrt(l1);
    float tRay = s * rayLen;
    // Блокер должен лежать строго между линзой и точкой.
    if (tRay < 0.3 || tRay > rayLen - 0.1) {
        return 1.0;
    }
    // Почти жёсткий край (силуэт!): PCSS-полутень зажата в узкую полосу.
    float pen = clamp(0.05 * (rayLen - tRay) / max(tRay, 0.5), 0.015, 0.1);
    // Лёгкий подсвет внутри тени: не «дыра», а тень.
    return 0.1 + 0.9 * smoothstep(r, r + pen, dca);
}

// Тень: цель выносится из поверхности по нормали (нет acne). Воксели: DDA-лучи
// с диском сэмплов у ИСТОЧНИКА (полутень как от протяжённой линзы) и LOD по
// дистанции от камеры — вблизи 5 лучей, вдали 1 (полутень там субпиксельная,
// а 4 лишних трассировки на фрагмент — главный пожиратель GPU). Капсулы
// сущностей дают резкий силуэт с PCSS-полутенью и работают НЕЗАВИСИМО от
// готовности воксельной сетки — не мигают при телепорте/смене измерения.
float flShadow(vec3 fromPos, vec3 toPos, vec3 normal) {
    // Нормаль всегда в сторону света — куда бы ни смотрела грань.
    if (dot(normal, fromPos - toPos) < 0.0) {
        normal = -normal;
    }
    vec3 target = toPos + normal * 0.12;
    vec3 seg = target - fromPos;
    float segLen = length(seg);
    if (segLen < 1.0) {
        return 1.0;
    }

    float shadow = 1.0;
    if (FlVoxel1.w > 0.5) {
        vec3 rel0 = fromPos - FlVoxel0.xyz;
        vec3 rel1 = target - FlVoxel0.xyz;

        // Interleaved gradient noise (Jimenez 2014): мелкое равномерное зерно
        // по экрану против бандинга лучей — ровнее world-hash без узора.
        float ign = fract(52.9829189 * fract(0.06711056 * gl_FragCoord.x + 0.00583715 * gl_FragCoord.y));

        float sum = flTraceRay(rel0, rel1);
        // LOD-порог размыт шумом (22..30 блоков) — граница не видна как линия.
        if (length(toPos) < 22.0 + 8.0 * ign) {
            vec3 dir = seg / segLen;
            vec3 side = normalize(cross(dir, abs(dir.y) > 0.9 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0)));
            vec3 up = cross(dir, side);
            float ang = 6.28318 * ign;
            vec2 rot = vec2(cos(ang), sin(ang));
            const float SPREAD = 0.16;   // радиус «линзы»: меньше = резче контактный край
            // Пары на РАЗНЫХ радиусах (кольцо -> диск): плавнее градиент полутени.
            vec2 o1 = rot * SPREAD;
            vec2 o2 = vec2(-rot.y, rot.x) * (SPREAD * 0.55);
            sum += flTraceRay(rel0 + side * o1.x + up * o1.y, rel1);
            sum += flTraceRay(rel0 - side * o1.x - up * o1.y, rel1);
            sum += flTraceRay(rel0 + side * o2.x + up * o2.y, rel1);
            sum += flTraceRay(rel0 - side * o2.x - up * o2.y, rel1);
            shadow = sum * 0.2;
        } else {
            shadow = sum;
        }
    }

    // Резкие силуэтные тени от сущностей (капсулы по частям тела) — всегда,
    // даже пока воксельная сетка не готова.
    int capCount = int(FlCount.y + 0.5);
    for (int j = 0; j < 16; j++) {
        if (j >= capCount) {
            break;
        }
        shadow = min(shadow, flCapsuleShadow(fromPos, target,
                FlCapsuleA[j].xyz, FlCapsuleB[j].xyz, FlCapsuleA[j].w));
    }
    return shadow;
}

// Суммарный свет всех источников в точке flPos (camera-relative world).
vec3 flashlightLight(vec3 flPos) {
    vec3 total = vec3(0.0);
    int count = int(FlCount.x + 0.5);
    // Нормаль поверхности из производных позиции (для выноса цели из блока).
    vec3 fx = dFdx(flPos);
    vec3 fy = dFdy(flPos);
    vec3 crossN = cross(fx, fy);
    float crossLen = length(crossN);
    vec3 surfaceNormal = crossLen > 1.0e-8 ? crossN / crossLen : vec3(0.0);
    for (int i = 0; i < 32; i++) {
        if (i >= count) {
            break;
        }
        vec3 toFrag = flPos - FlPosRange[i].xyz;
        float dist = length(toFrag);
        float range = FlPosRange[i].w;
        if (dist > range) {
            continue;
        }
        vec3 dir = toFrag / max(dist, 1.0e-4);
        float angular = smoothstep(FlDirOuter[i].w, FlColorInner[i].w, dot(dir, FlDirOuter[i].xyz));
        // Затухание масштабируется дальностью фонаря: дальнобойный сохраняет
        // яркость вдали, у широкого спад раньше. Плюс гашение к краю дальности.
        float k = range * 0.35;
        float distFall = (1.0 - smoothstep(range * 0.5, range, dist)) * (k / (k + dist));
        // Страховка от подсветки собственной модели у самого источника.
        distFall *= smoothstep(0.15, 0.55, dist);
        float visible = angular * distFall;
        if (visible > 0.003) {
            visible *= flShadow(FlPosRange[i].xyz, flPos, surfaceNormal);
        }
        total += FlColorInner[i].rgb * visible;
    }
    return total;
}

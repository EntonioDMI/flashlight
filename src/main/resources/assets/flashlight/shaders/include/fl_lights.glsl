// Flashlight — общий блок источников и расчёт конусного света.
// Подключается во все наши шейдеры: террейн, сущности, партиклы.

layout(std140) uniform FlashLights {
    mat4 FlInvView;        // поворот камеры (view -> camera-relative world)
    vec4 FlCount;          // x = число активных фонарей (0..4)
    vec4 FlPosRange[4];    // xyz = позиция фонаря (camera-relative), w = дальность
    vec4 FlDirOuter[4];    // xyz = направление луча (норм.), w = cos внешнего угла
    vec4 FlColorInner[4];  // rgb = цвет*яркость, w = cos внутреннего угла
    vec4 FlVoxel0;         // xyz = origin воксельной сетки (camera-relative), w = размер сетки N
    vec4 FlVoxel1;         // x = колонок в атласе, w = окклюзия включена (1/0)
    vec4 FlSpheres[8];     // сущности-окклюдеры: xyz = центр (camera-relative), w = радиус
};

// Атлас воксельной окклюзии: N срезов N x N, разложенных сеткой колонок.
uniform sampler2D FlOccluder;

float flOccupied(ivec3 cell, int ni, int cols) {
    ivec2 uv = ivec2((cell.z % cols) * ni + cell.x, (cell.z / cols) * ni + cell.y);
    return texelFetch(FlOccluder, uv, 0).r;
}

// Точная DDA-трассировка по воксельной сетке (Amanatides & Woo):
// идём ровно по клеткам, которые пересекает луч — без просечек и без
// самозатенения. Координаты — относительно origin сетки. 1.0 = свободно.
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
    vec3 p = fromRel + dir * t;
    ivec3 cell = clamp(ivec3(floor(p)), ivec3(0), ivec3(ni - 1));
    ivec3 stepD = ivec3(sign(safeDir));
    vec3 tDelta = abs(invDir);
    vec3 boundary = vec3(cell) + max(vec3(stepD), vec3(0.0));
    vec3 tMax = t + (boundary - p) * invDir;

    for (int i = 0; i < 128; i++) {
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

// Мягкая тень: цель выносится из поверхности по нормали (нет acne),
// три луча со сдвигом у ИСТОЧНИКА дают полутень как от протяжённой линзы.
float flShadow(vec3 fromPos, vec3 toPos, vec3 normal) {
    if (FlVoxel1.w < 0.5) {
        return 1.0;
    }
    // Нормаль всегда в сторону света — куда бы ни смотрела грань.
    if (dot(normal, fromPos - toPos) < 0.0) {
        normal = -normal;
    }
    vec3 target = toPos + normal * 0.6;
    if (length(target - fromPos) < 1.4) {
        return 1.0;
    }
    vec3 rel0 = fromPos - FlVoxel0.xyz;
    vec3 rel1 = target - FlVoxel0.xyz;
    vec3 dir = normalize(rel1 - rel0);
    vec3 side = normalize(cross(dir, abs(dir.y) > 0.9 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0)));
    vec3 up = cross(dir, side);
    float sum = flTraceRay(rel0, rel1);
    sum += flTraceRay(rel0 + side * 0.4, rel1);
    sum += flTraceRay(rel0 + up * 0.4, rel1);
    float shadow = sum * (1.0 / 3.0);

    // Мягкие тени от сущностей: сфера-окклюдер против отрезка «линза — точка».
    // Сферы у самой цели пропускаем — сущность не затеняет саму себя и землю
    // под ногами («злая аура»), тень падает только ПОЗАДИ неё.
    int sphereCount = int(FlCount.y + 0.5);
    vec3 ab = target - fromPos;
    float abLen2 = max(dot(ab, ab), 1.0e-4);
    for (int j = 0; j < 8; j++) {
        if (j >= sphereCount) {
            break;
        }
        vec3 c = FlSpheres[j].xyz;
        float r = FlSpheres[j].w;
        if (length(c - target) < r + 1.3) {
            continue; // точка принадлежит сущности или земле рядом — никакой «ауры»
        }
        float tSeg = clamp(dot(c - fromPos, ab) / abLen2, 0.0, 1.0);
        float d = length(fromPos + ab * tSeg - c);
        // Тень от сущности — всегда полутень (не чернит до нуля).
        shadow = min(shadow, 0.35 + 0.65 * smoothstep(r * 0.8, r * 1.5, d));
    }
    return shadow;
}

// Суммарный конусный свет всех фонарей в точке flPos (camera-relative world).
vec3 flashlightLight(vec3 flPos) {
    vec3 total = vec3(0.0);
    int count = int(FlCount.x + 0.5);
    // Нормаль поверхности из производных позиции (для выноса цели из блока).
    vec3 fx = dFdx(flPos);
    vec3 fy = dFdy(flPos);
    vec3 crossN = cross(fx, fy);
    float crossLen = length(crossN);
    vec3 surfaceNormal = crossLen > 1.0e-8 ? crossN / crossLen : vec3(0.0);
    for (int i = 0; i < 4; i++) {
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

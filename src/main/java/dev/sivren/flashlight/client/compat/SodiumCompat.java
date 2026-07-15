package dev.sivren.flashlight.client.compat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import dev.sivren.flashlight.Flashlight;
import dev.sivren.flashlight.client.FlashlightEngine;
import dev.sivren.flashlight.client.VoxelOccluder;
import dev.sivren.flashlight.mixin.client.GlBufferAccessor;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;

import org.lwjgl.opengl.GL32C;

/**
 * Совместимость с Sodium: его террейн рисуется СОБСТВЕННЫМИ GL-программами
 * (не ванильными пайплайнами), поэтому подмена пайплайнов движка v4 его не
 * видит. Вместо подмены мы патчим ИСХОДНИК шейдера Sodium при загрузке
 * (инжектим fl_lights.glsl + вызов flashlightLight) и после {@code begin()}
 * каждого прохода биндим наш UBO и атлас окклюзии прямо в его программу.
 *
 * Ключевые точки (см. mixin/client/sodium):
 * - ShaderLoader.getShaderSource — текстовая инъекция в blocks/block_layer_opaque;
 * - ShaderChunkRenderer.begin — привязка FlashLights UBO + FlOccluder.
 */
public final class SodiumCompat {

    /** Точка привязки нашего UBO: Sodium держит u_Globals на 0 — не пересекаемся. */
    private static final int UBO_BINDING = 4;
    /**
     * Текстурный юнит атласа окклюзии. GlStateManager ванили трекает юниты 0..11 —
     * берём последний трекаемый: юниты Sodium (блоки/лайтмапа) — младшие.
     */
    private static final int OCCLUDER_UNIT = 11;

    private static String flLightsSource;
    private static boolean warned = false;

    /**
     * Кэш по program id: {blockIndex, occluderLocation}. glGetUniform* — драйверные
     * запросы, незачем гонять их каждый проход каждый кадр. Инвалидация — в
     * {@link #patchShader} (Sodium пересобирает программы через перезагрузку исходников).
     */
    private static final java.util.HashMap<Integer, int[]> PROGRAM_CACHE = new java.util.HashMap<>();

    private SodiumCompat() {
    }

    /**
     * Патч исходника шейдера Sodium. Возвращает изменённый текст или {@code null},
     * если этот файл нас не интересует (или якоря не нашлись — тогда честная ваниль).
     */
    public static String patchShader(String namespace, String path, String source) {
        if (!"sodium".equals(namespace)) {
            return null;
        }
        if ("blocks/block_layer_opaque.vsh".equals(path)) {
            PROGRAM_CACHE.clear(); // программы пересобираются — старые id недействительны
            return patchVertex(source);
        }
        if ("blocks/block_layer_opaque.fsh".equals(path)) {
            return patchFragment(source);
        }
        return null;
    }

    private static String patchVertex(String source) {
        String mainAnchor = "void main() {";
        String positionAnchor = "vec3 position = _vert_position + translation;";
        if (!source.contains(mainAnchor) || !source.contains(positionAnchor)) {
            warnOnce("vsh");
            return null;
        }
        source = source.replace(mainAnchor,
                "out vec3 fl_pos;\nout vec3 fl_tint;\n\n" + mainAnchor);
        source = source.replace(positionAnchor, positionAnchor
                + "\n    fl_pos = position;"
                + "\n    fl_tint = _vert_color.rgb;");
        return source;
    }

    private static String patchFragment(String source) {
        String outAnchor = "out vec4 fragColor;";
        String colorAnchor = "color *= v_Color;";
        String fogAnchor = "fragColor = _linearFog(";
        String lights = flLightsSource();
        if (lights == null
                || !source.contains(outAnchor)
                || !source.contains(colorAnchor)
                || !source.contains(fogAnchor)) {
            warnOnce("fsh");
            return null;
        }
        // Варинги + весь fl_lights.glsl (UBO, DDA-тени, конус) после объявления выхода.
        source = source.replace(outAnchor, outAnchor
                + "\n\nin vec3 fl_pos;\nin vec3 fl_tint;\n\n" + lights);
        // Альбедо до умножения на цвет вершины (в v_Color вшита лайтмапа —
        // наш свет не должен зависеть от ванильной освещённости).
        source = source.replace(colorAnchor,
                "vec4 fl_albedo = color;\n    " + colorAnchor);
        // Конусный свет добавляется до тумана — дальний луч гаснет естественно.
        source = source.replace(fogAnchor,
                "color.rgb += fl_albedo.rgb * fl_tint * flashlightLight(fl_pos);\n    " + fogAnchor);
        return source;
    }

    /**
     * После {@code ShaderChunkRenderer.begin()}: программа Sodium уже привязана —
     * подвешиваем наш UBO и атлас окклюзии. Если якоря шейдера не сработали,
     * блока FlashLights в программе нет и мы тихо выходим.
     */
    public static void setupTerrainProgram(int programId) {
        // Локации и назначение binding-точек — состояние программы, делаем один раз.
        int[] locations = PROGRAM_CACHE.computeIfAbsent(programId, id -> {
            int blockIndex = GL32C.glGetUniformBlockIndex(id, "FlashLights");
            int occluderLocation = GL32C.glGetUniformLocation(id, "FlOccluder");
            if (blockIndex != GL32C.GL_INVALID_INDEX) {
                GL32C.glUniformBlockBinding(id, blockIndex, UBO_BINDING);
            }
            if (occluderLocation >= 0) {
                GL32C.glUniform1i(occluderLocation, OCCLUDER_UNIT);
            }
            return new int[]{blockIndex, occluderLocation};
        });
        if (locations[0] == GL32C.GL_INVALID_INDEX) {
            return;
        }
        GpuBuffer ubo = FlashlightEngine.uboForCompat();
        if (ubo == null) {
            return;
        }
        // Привязки binding-точек — глобальное GL-состояние, обновляем каждый проход.
        GL32C.glBindBufferRange(GL32C.GL_UNIFORM_BUFFER, UBO_BINDING,
                ((GlBufferAccessor) ubo).flashlight$getHandle(), 0, ubo.size());

        if (locations[1] >= 0 && VoxelOccluder.texture() instanceof GlTexture glTexture) {
            GlStateManager._activeTexture(GL32C.GL_TEXTURE0 + OCCLUDER_UNIT);
            GlStateManager._bindTexture(glTexture.glId());
            GlStateManager._activeTexture(GL32C.GL_TEXTURE0);
        }
    }

    private static String flLightsSource() {
        if (flLightsSource == null) {
            try (InputStream in = SodiumCompat.class.getResourceAsStream(
                    "/assets/flashlight/shaders/include/fl_lights.glsl")) {
                if (in != null) {
                    flLightsSource = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                Flashlight.LOGGER.error("Flashlight: не смог прочитать fl_lights.glsl для Sodium", e);
            }
        }
        return flLightsSource;
    }

    private static void warnOnce(String stage) {
        if (!warned) {
            warned = true;
            Flashlight.LOGGER.warn(
                    "Flashlight: якоря шейдера Sodium не найдены ({}) — свет фонаря на террейне "
                            + "отключён (несовместимая версия Sodium?)", stage);
        }
    }
}

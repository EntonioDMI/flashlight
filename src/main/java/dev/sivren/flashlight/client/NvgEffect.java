package dev.sivren.flashlight.client;

import java.util.Set;

import dev.sivren.flashlight.NvgItem;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;

/**
 * Зелёный экран ПНВ: post-эффект flashlight:nvg поверх мира и руки, под GUI.
 * Вызывается из GameRendererMixin сразу после рендера мира (как ванильные
 * эффекты паука/крипера).
 *
 * ПРОГРЕВ: после включения усиление lightmap растёт с нуля до максимума за
 * время звука включения (~3 c) — едет через LightmapExtractorMixin.
 * ПЕРЕСВЕТ — естественный: пост-шейдер усиливает кадр фиксированным gain,
 * поэтому день/факел/луч фонаря выгорают в белёсый сами по себе, попиксельно.
 */
public final class NvgEffect {

    private static final Identifier CHAIN_ID = Identifier.fromNamespaceAndPath("flashlight", "nvg");

    /** Длительность прогрева — под звук включения (nvg_on.ogg ~3 c). */
    private static final float WARMUP_SECONDS = 3.0f;
    /** Ввод зелёного эффекта быстрее прогрева яркости. */
    private static final float FADE_SECONDS = 1.5f;

    private static boolean wasLit = false;
    /** Накопленное время прогрева: на паузе не растёт (мир за меню заморожен). */
    private static float warmupSeconds = 0;
    private static long lastNanos = 0;
    /** Пост-чейн скомпилировался: без зелени и усиливать lightmap не надо. */
    private static boolean chainAvailable = true;

    private NvgEffect() {
    }

    /** Сброс состояния при выходе из мира. */
    public static void reset() {
        wasLit = false;
        warmupSeconds = 0;
        lastNanos = 0;
    }

    public static void render() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null
                || !NvgItem.isLit(mc.player.getItemBySlot(EquipmentSlot.HEAD))) {
            wasLit = false;
            return;
        }
        long now = System.nanoTime();
        if (!wasLit) {
            wasLit = true;
            warmupSeconds = 0;
            lastNanos = now;
        }
        float dt = Math.min((now - lastNanos) / 1.0e9f, 0.1f);
        lastNanos = now;
        if (!mc.isPaused()) {
            warmupSeconds += dt;
        }
        PostChain chain = mc.getShaderManager().getPostChain(CHAIN_ID, Set.of(PostChain.MAIN_TARGET_ID));
        chainAvailable = chain != null;
        if (chain != null) {
            writeFade(chain, effectFade());
            chain.process(mc.getMainRenderTarget(), GraphicsResourceAllocator.UNPOOLED);
        }
    }

    /** Наш перезаписываемый UBO для NvgConfig (у ванильного нет USAGE_COPY_DST). */
    private static com.mojang.blaze3d.buffers.GpuBuffer fadeBuffer;
    /** Переиспользуемый CPU-буфер записи: не аллоцируем direct-память каждый кадр. */
    private static java.nio.ByteBuffer fadeScratch;

    /**
     * Пишет NvgFade в UBO-блок NvgConfig нашего пасса: подменяет ванильный
     * статичный буфер на свой (writable) и обновляет его каждый кадр.
     */
    private static void writeFade(PostChain chain, float fade) {
        for (net.minecraft.client.renderer.PostPass pass
                : ((dev.sivren.flashlight.mixin.client.PostChainAccessor) chain).flashlight$passes()) {
            var accessor = (dev.sivren.flashlight.mixin.client.PostPassAccessor) pass;
            java.util.Map<String, com.mojang.blaze3d.buffers.GpuBuffer> uniforms =
                    accessor.flashlight$customUniforms();
            com.mojang.blaze3d.buffers.GpuBuffer existing = uniforms.get("NvgConfig");
            if (existing == null) {
                continue;
            }
            // PostPass.close() при пересоздании цепочки закрывает и наш буфер
            // (он лежит в её карте) — тогда создаём новый.
            if (fadeBuffer == null || fadeBuffer.isClosed()) {
                fadeBuffer = com.mojang.blaze3d.systems.RenderSystem.getDevice().createBuffer(
                        () -> "Flashlight NvgConfig dynamic",
                        com.mojang.blaze3d.buffers.GpuBuffer.USAGE_UNIFORM
                                | com.mojang.blaze3d.buffers.GpuBuffer.USAGE_COPY_DST,
                        (int) existing.size());
            }
            if (existing != fadeBuffer) {
                java.util.Map<String, com.mojang.blaze3d.buffers.GpuBuffer> replaced =
                        new java.util.HashMap<>(uniforms);
                replaced.put("NvgConfig", fadeBuffer);
                accessor.flashlight$setCustomUniforms(replaced);
                existing.close(); // вытеснённый ванильный буфер больше никому не нужен
            }
            int size = (int) fadeBuffer.size();
            if (fadeScratch == null || fadeScratch.capacity() < size) {
                // std140-хвост блока зануляем явно (шейдер читает только NvgFade,
                // но неопределённая память — сюрприз при расширении блока).
                fadeScratch = java.nio.ByteBuffer.allocateDirect(size)
                        .order(java.nio.ByteOrder.nativeOrder());
            }
            fadeScratch.position(0).limit(size);
            fadeScratch.putFloat(0, fade);
            com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder()
                    .writeToBuffer(fadeBuffer.slice(), fadeScratch);
        }
    }

    /** Ввод зелёного эффекта: быстрее прогрева яркости (~{@value #FADE_SECONDS} c). */
    private static float effectFade() {
        float x = Math.min(warmupSeconds / FADE_SECONDS, 1.0f);
        return x * x * (3.0f - 2.0f * x);
    }

    /**
     * Усиление lightmap (читает LightmapExtractorMixin): 0 -> 1 за прогрев.
     * Пока прибор не включён — 0. Если пост-чейн не собрался (битый ресурс-пак),
     * усиление тоже 0 — иначе получилось бы «ночное зрение без зелени».
     */
    public static float lightBoost() {
        if (!wasLit || !chainAvailable) {
            return 0;
        }
        float x = Math.min(warmupSeconds / WARMUP_SECONDS, 1.0f);
        return x * x * (3.0f - 2.0f * x);
    }
}

package dev.sivren.flashlight.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Экранное ОСЛЕПЛЕНИЕ: когда луч фонаря бьёт в глаза, экран заливает
 * тёплой вспышкой с центром в точке фонаря (сила посчитана в GlareRenderer
 * по физике конуса + трассировке). Плавно нарастает и гаснет.
 * Ореол — процедурный круг (fl_glare), рисуется поверх мира и руки, под GUI.
 */
public final class FlashBlindOverlay implements HudElement {

    // Статики: элемент один, а сброс при выходе из мира удобнее статическим
    // (иначе остаточная вспышка мигает на первом кадре после перезахода).
    private static float smooth = 0;
    private static long lastNanos = 0;

    /** Сброс при выходе из мира (DISCONNECT в FlashlightClient). */
    public static void reset() {
        smooth = 0;
        lastNanos = 0;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        // Плавность: вспышка догоняет цель (~70 мс), гаснет чуть медленнее.
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0.016f : Math.min((now - lastNanos) / 1.0e9f, 0.1f);
        lastNanos = now;
        float target = GlareRenderer.blindStrength;
        float tau = target > smooth ? 0.07f : 0.18f;
        smooth += (target - smooth) * (1.0f - (float) Math.exp(-dt / tau));

        GlareRenderer.drawBlindHalo(smooth);
    }
}

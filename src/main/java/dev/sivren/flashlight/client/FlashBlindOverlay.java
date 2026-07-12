package dev.sivren.flashlight.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * Экранное ОСЛЕПЛЕНИЕ: когда луч фонаря бьёт в глаза, экран заливает
 * тёплой вспышкой с центром в точке фонаря (сила посчитана в GlareRenderer
 * по физике конуса + трассировке). Плавно нарастает и гаснет.
 */
public final class FlashBlindOverlay implements HudElement {

    private float smooth = 0;
    private long lastNanos = 0;

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        // Плавность: вспышка догоняет цель (~70 мс), гаснет чуть медленнее.
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0.016f : Math.min((now - lastNanos) / 1.0e9f, 0.1f);
        lastNanos = now;
        float target = GlareRenderer.blindStrength;
        float tau = target > smooth ? 0.07f : 0.18f;
        smooth += (target - smooth) * (1.0f - (float) Math.exp(-dt / tau));

        if (smooth < 0.02f) {
            return;
        }
        int w = graphics.guiWidth();
        int h = graphics.guiHeight();
        int cx = (int) (GlareRenderer.blindScreenX * w);
        int cy = (int) (GlareRenderer.blindScreenY * h);

        // Ореол: растёт и наливается с силой ослепления.
        // Полная сигнатура blit: рисуем size x size, растягивая ВСЮ текстуру 64x64
        // (иначе она тайлится сеткой по экрану).
        int size = (int) (h * (0.7f + 3.2f * smooth));
        int alpha = (int) (Math.clamp(smooth * 1.5f, 0.0f, 1.0f) * 235);
        int color = (alpha << 24) | 0xFFF5DE;
        graphics.blit(RenderPipelines.GUI_TEXTURED, GlareRenderer.TEXTURE,
                cx - size / 2, cy - size / 2, 0, 0, size, size, 64, 64, 64, 64, color);

        // Почти в упор — белая пелена на весь экран.
        if (smooth > 0.55f) {
            int washAlpha = (int) ((smooth - 0.55f) / 0.45f * 160);
            graphics.fill(0, 0, w, h, (washAlpha << 24) | 0xFFF8E8);
        }
    }
}

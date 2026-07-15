package dev.sivren.flashlight.mixin.client;

import dev.sivren.flashlight.NvgItem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.world.entity.EquipmentSlot;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ПНВ реально усиливает свет: пока очки включены, lightmap осветляется как
 * от зелья ночного зрения — иначе пост-эффекту в кромешной тьме нечего
 * усиливать. Зелёный тон и зерно добавляет пост-шейдер flashlight:post/nvg.
 */
@Mixin(LightmapRenderStateExtractor.class)
public class LightmapExtractorMixin {

    @Inject(method = "extract", at = @At("TAIL"))
    private void flashlight$nvgLightAmp(LightmapRenderState state, float partialTick, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !NvgItem.isLit(mc.player.getItemBySlot(EquipmentSlot.HEAD))) {
            return;
        }
        // Буст растёт с прогревом трубки (0 -> 1 за ~3 c после включения).
        float boost = dev.sivren.flashlight.client.NvgEffect.lightBoost();
        if (boost <= state.nightVisionEffectIntensity) {
            return; // зелье ночного зрения уже светит ярче
        }
        state.nightVisionEffectIntensity = boost;
        state.needsUpdate = true;
    }
}

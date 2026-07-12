package dev.sivren.flashlight.mixin.client;

import dev.sivren.flashlight.FlashlightItem;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Анимация перезарядки от первого лица: пока на фонаре висит RELOADING,
 * игрок «прижимает» его к груди — предмет уезжает к центру экрана (чуть правее
 * центра для правой руки, зеркально для левой), луч уходит чуть левее с наклоном.
 */
@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {

    /** Плавность: 0 — обычная поза, 1 — полностью прижат. Лерпается по кадрам. */
    @Unique
    private float flashlight$reloadLerp = 0.0f;

    @Inject(
            method = "renderArmWithItem",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V",
                    shift = At.Shift.AFTER))
    private void flashlight$applyReloadPose(
            AbstractClientPlayer player, float frameInterp, float xRot, InteractionHand hand,
            float attack, ItemStack itemStack, float inverseArmHeight, PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector, int lightCoords, CallbackInfo ci) {

        boolean reloading = itemStack.getItem() instanceof FlashlightItem
                && FlashlightItem.isReloading(itemStack);

        // Плавный вход/выход из позы (~5 кадров).
        float target = reloading ? 1.0f : 0.0f;
        flashlight$reloadLerp += (target - flashlight$reloadLerp) * 0.2f;
        float t = flashlight$reloadLerp;
        if (t < 0.01f) {
            return;
        }

        HumanoidArm arm = hand == InteractionHand.MAIN_HAND
                ? player.getMainArm()
                : player.getMainArm().getOpposite();
        int invert = arm == HumanoidArm.RIGHT ? 1 : -1;

        // К центру экрана, чуть вниз и ближе к телу.
        poseStack.translate(invert * -0.28f * t, -0.08f * t, 0.18f * t);
        // Луч чуть левее (для правой руки; зеркально для левой) и с лёгким наклоном.
        poseStack.mulPose(Axis.YP.rotationDegrees(invert * 28.0f * t));
        poseStack.mulPose(Axis.ZP.rotationDegrees(invert * -12.0f * t));
    }
}

package dev.sivren.flashlight.mixin.client;

import dev.sivren.flashlight.client.FlashlightEngine;
import dev.sivren.flashlight.client.GlareRenderer;

import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;

import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Начало кадра: обновляет uniform-буфер движка фонарика.
 * После рендера мира: рисует слепящие блики линз (глубина ещё мировая).
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"))
    private void flashlight$updateEngine(DeltaTracker deltaTracker, CallbackInfo ci) {
        FlashlightEngine.update();
    }

    @Inject(
            method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZLnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;)V",
                    shift = At.Shift.AFTER))
    private void flashlight$renderGlare(
            DeltaTracker deltaTracker, CallbackInfo ci,
            @Local org.joml.Matrix4f projectionMatrix,
            @Local Matrix4fc modelViewMatrix) {
        GlareRenderer.render(projectionMatrix, modelViewMatrix);
    }
}

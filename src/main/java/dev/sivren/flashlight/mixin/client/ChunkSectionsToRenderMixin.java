package dev.sivren.flashlight.mixin.client;

import dev.sivren.flashlight.client.FlashlightEngine;
import dev.sivren.flashlight.client.VoxelOccluder;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;

import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Подменяет терраин-пайплайны на наши (с конусным светом фонарей) и привязывает
 * uniform-буфер FlashLights — тот же паттерн, что LightmapMixin в Enhanced Darkness.
 */
@Mixin(ChunkSectionsToRender.class)
public class ChunkSectionsToRenderMixin {

    @WrapOperation(
            method = "renderGroup",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"))
    private void flashlight$swapPipeline(RenderPass renderPass, RenderPipeline original, Operation<Void> op) {
        RenderPipeline replacement = FlashlightEngine.swap(original);
        if (replacement != null) {
            op.call(renderPass, replacement);
            renderPass.setUniform("FlashLights", FlashlightEngine.buffer());
            renderPass.bindTexture("FlOccluder", VoxelOccluder.textureView(), VoxelOccluder.sampler());
        } else {
            op.call(renderPass, original);
        }
    }
}

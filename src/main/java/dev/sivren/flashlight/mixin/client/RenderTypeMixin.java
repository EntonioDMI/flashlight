package dev.sivren.flashlight.mixin.client;

import dev.sivren.flashlight.client.FlashlightEngine;
import dev.sivren.flashlight.client.VoxelOccluder;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;

import net.minecraft.client.renderer.rendertype.RenderType;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Универсальная точка immediate-отрисовки: сущности, партиклы, предметы,
 * блок-сущности. Пайплайны с шейдерами core/entity и core/particle подменяются
 * на клоны с конусным светом фонарей.
 */
@Mixin(RenderType.class)
public class RenderTypeMixin {

    @WrapOperation(
            method = "draw",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"))
    private void flashlight$swapPipeline(RenderPass renderPass, RenderPipeline original, Operation<Void> op) {
        RenderPipeline replacement = FlashlightEngine.swapImmediate(original);
        if (replacement != null) {
            op.call(renderPass, replacement);
            renderPass.setUniform("FlashLights", FlashlightEngine.buffer());
            renderPass.bindTexture("FlOccluder", VoxelOccluder.textureView(), VoxelOccluder.sampler());
        } else {
            op.call(renderPass, original);
        }
    }
}

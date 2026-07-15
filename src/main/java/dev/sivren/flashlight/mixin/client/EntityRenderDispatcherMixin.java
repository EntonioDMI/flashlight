package dev.sivren.flashlight.mixin.client;

import dev.sivren.flashlight.client.FlashlightEngine;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Гасит ванильную blob-тень у сущностей в конусе фонаря: внутри её круга
 * иначе не видно нашей силуэтной тени («обрезка»). shadowRadius и shadowPieces
 * заполняются при извлечении стейта, поэтому чистим на выходе extractEntity.
 */
@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {

    @Inject(method = "extractEntity", at = @At("RETURN"))
    private <E extends Entity> void flashlight$suppressBlobShadow(
            E entity, float partialTick, CallbackInfoReturnable<EntityRenderState> cir) {
        if (!FlashlightEngine.active || !FlashlightEngine.beamTouches(entity)) {
            return;
        }
        EntityRenderState state = cir.getReturnValue();
        state.shadowRadius = 0.0f;
        state.shadowPieces.clear();
    }
}

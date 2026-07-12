package dev.sivren.flashlight.mixin.client.sodium;

import dev.sivren.flashlight.client.compat.SodiumCompat;

import net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader;

import net.minecraft.resources.Identifier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Инъекция света фонаря в шейдер террейна Sodium: правим исходник
 * blocks/block_layer_opaque при загрузке (см. {@link SodiumCompat#patchShader}).
 * Применяется только когда Sodium установлен (FlashlightMixinPlugin).
 */
@Mixin(value = ShaderLoader.class, remap = false)
public abstract class SodiumShaderLoaderMixin {

    @Inject(method = "getShaderSource", at = @At("RETURN"), cancellable = true)
    private static void flashlight$injectBeam(Identifier id, CallbackInfoReturnable<String> cir) {
        String patched = SodiumCompat.patchShader(id.getNamespace(), id.getPath(), cir.getReturnValue());
        if (patched != null) {
            cir.setReturnValue(patched);
        }
    }
}

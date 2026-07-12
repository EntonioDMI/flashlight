package dev.sivren.flashlight.mixin.client.sodium;

import dev.sivren.flashlight.client.compat.SodiumCompat;

import net.caffeinemc.mods.sodium.client.gl.shader.GlProgram;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * После begin() программа террейна Sodium уже привязана — цепляем к ней
 * наш FlashLights UBO и атлас окклюзии (raw GL, как делает сам Sodium).
 */
@Mixin(value = ShaderChunkRenderer.class, remap = false)
public abstract class SodiumChunkRendererMixin {

    @Shadow
    protected GlProgram<ChunkShaderInterface> activeProgram;

    @Inject(method = "begin", at = @At("TAIL"))
    private void flashlight$bindLights(CallbackInfo ci) {
        if (this.activeProgram != null) {
            SodiumCompat.setupTerrainProgram(this.activeProgram.handle());
        }
    }
}

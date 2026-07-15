package dev.sivren.flashlight.mixin.client;

import java.util.Map;

import com.mojang.blaze3d.buffers.GpuBuffer;

import net.minecraft.client.renderer.PostPass;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Доступ к UBO-блокам пасса (JSON "uniforms"). Ванильные буферы создаются без
 * USAGE_COPY_DST (писать в них нельзя) — подменяем карту на копию со СВОИМ
 * буфером, который обновляем каждый кадр.
 */
@Mixin(PostPass.class)
public interface PostPassAccessor {

    @Accessor("customUniforms")
    Map<String, GpuBuffer> flashlight$customUniforms();

    @org.spongepowered.asm.mixin.Mutable
    @Accessor("customUniforms")
    void flashlight$setCustomUniforms(Map<String, GpuBuffer> uniforms);
}

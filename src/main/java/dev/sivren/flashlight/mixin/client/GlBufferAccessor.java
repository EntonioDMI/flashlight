package dev.sivren.flashlight.mixin.client;

import com.mojang.blaze3d.opengl.GlBuffer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Доступ к GL-хэндлу ванильного буфера — нужен Sodium-компату (raw GL bind). */
@Mixin(GlBuffer.class)
public interface GlBufferAccessor {

    @Accessor("handle")
    int flashlight$getHandle();
}

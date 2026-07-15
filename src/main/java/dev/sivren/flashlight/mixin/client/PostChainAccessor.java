package dev.sivren.flashlight.mixin.client;

import java.util.List;

import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Доступ к пассам пост-цепочки — для живого обновления uniform'ов ПНВ. */
@Mixin(PostChain.class)
public interface PostChainAccessor {

    @Accessor("passes")
    List<PostPass> flashlight$passes();
}

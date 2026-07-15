package dev.sivren.flashlight.mixin.client;

import dev.sivren.flashlight.client.VoxelOccluder;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Изменение блока на клиенте (та же точка, что триггерит перестройку чанка) —
 * помечает Z-срез воксельного окклюдера грязным. Благодаря этому статичный мир
 * не пересканируется каждый кадр (VoxelOccluder v2).
 */
@Mixin(ClientLevel.class)
public class ClientLevelMixin {

    @Inject(method = "setBlocksDirty", at = @At("TAIL"))
    private void flashlight$occluderDirty(BlockPos pos, BlockState oldState, BlockState newState,
                                          CallbackInfo ci) {
        if (oldState != newState) {
            VoxelOccluder.markDirty(pos);
        }
    }
}

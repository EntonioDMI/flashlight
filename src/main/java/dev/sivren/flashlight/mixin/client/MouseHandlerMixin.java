package dev.sivren.flashlight.mixin.client;

import dev.sivren.flashlight.FlashlightItem;
import dev.sivren.flashlight.net.AdjustFocusPayload;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Колёсико при ВКЛЮЧЁННОМ фонарике в главной руке меняет фокус линзы
 * (вверх — дальнобойный узкий, вниз — ближний широкий) вместо смены слота.
 * С выключенным фонариком хотбар скроллится как обычно.
 */
@Mixin(MouseHandler.class)
public class MouseHandlerMixin {

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void flashlight$lensFocus(long handle, double xoffset, double yoffset, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null || yoffset == 0.0) {
            return;
        }
        ItemStack stack = mc.player.getItemInHand(InteractionHand.MAIN_HAND);
        if (stack.getItem() instanceof FlashlightItem flashlight
                && !flashlight.isWideBeam()
                && FlashlightItem.isOn(stack)) {
            ClientPlayNetworking.send(new AdjustFocusPayload(yoffset > 0 ? 1 : -1));
            ci.cancel();
        }
    }
}

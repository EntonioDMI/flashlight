package dev.sivren.flashlight;

import dev.sivren.flashlight.net.AdjustFocusPayload;
import dev.sivren.flashlight.net.ReloadFlashlightPayload;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Flashlight implements ModInitializer {

    public static final String MOD_ID = "flashlight";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ModComponents.init();
        ModItems.init();
        ReloadManager.init();
        // Движок v3: свет полностью клиентский (конус-шейдер), блоков света нет.
        // TODO: подавление спавна мобов в луче — событием спавна, не блоками.

        PayloadTypeRegistry.serverboundPlay().register(ReloadFlashlightPayload.TYPE, ReloadFlashlightPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ReloadFlashlightPayload.TYPE,
                (payload, context) -> ReloadManager.tryStartFromKey(context.player()));

        PayloadTypeRegistry.serverboundPlay().register(AdjustFocusPayload.TYPE, AdjustFocusPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(AdjustFocusPayload.TYPE, (payload, context) -> {
            ItemStack stack = context.player().getItemInHand(InteractionHand.MAIN_HAND);
            if (stack.getItem() instanceof FlashlightItem flashlight && !flashlight.isWideBeam()) {
                int old = stack.getOrDefault(ModComponents.FOCUS, 10);
                int updated = Mth.clamp(old + payload.delta(), 0, 10);
                if (updated != old) {
                    stack.set(ModComponents.FOCUS, updated);
                    context.player().level().playSound(null, context.player().blockPosition(),
                            SoundEvents.SPYGLASS_USE, SoundSource.PLAYERS, 0.3f,
                            1.5f + updated * 0.04f);
                }
            }
        });

        LOGGER.info("Flashlight: свет в конце тоннеля готов.");
    }
}

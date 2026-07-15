package dev.sivren.flashlight;

import dev.sivren.flashlight.net.AdjustFocusPayload;
import dev.sivren.flashlight.net.ReloadFlashlightPayload;
import dev.sivren.flashlight.net.ToggleNvgPayload;

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
        ModEntities.init();
        ModSounds.init();
        ModParticles.init();
        ModLoot.init();
        ReloadManager.init();
        // Движок v3: свет полностью клиентский (конус-шейдер), блоков света нет.
        // TODO: подавление спавна мобов в луче — событием спавна, не блоками.

        PayloadTypeRegistry.serverboundPlay().register(ReloadFlashlightPayload.TYPE, ReloadFlashlightPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ReloadFlashlightPayload.TYPE,
                (payload, context) -> ReloadManager.tryStartFromKey(context.player()));

        PayloadTypeRegistry.serverboundPlay().register(ToggleNvgPayload.TYPE, ToggleNvgPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ToggleNvgPayload.TYPE,
                (payload, context) -> NvgItem.toggleFromKey(context.player()));

        PayloadTypeRegistry.serverboundPlay().register(AdjustFocusPayload.TYPE, AdjustFocusPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(AdjustFocusPayload.TYPE, (payload, context) -> {
            ItemStack stack = focusTarget(context.player());
            if (stack != null) {
                int old = stack.getOrDefault(ModComponents.FOCUS, 10);
                int updated = Mth.clamp(old + payload.delta(), 0, 10);
                if (updated != old) {
                    stack.set(ModComponents.FOCUS, updated);
                    context.player().level().playSound(null, context.player().blockPosition(),
                            ModSounds.FOCUS_ZOOM, SoundSource.PLAYERS, 0.45f,
                            0.9f + updated * 0.03f);
                }
            }
        });

        LOGGER.info("Flashlight: свет в конце тоннеля готов.");
    }

    /**
     * ВКЛЮЧЁННЫЙ узкий фонарик, чей фокус крутит колёсико: главная рука
     * приоритетнее второй (ровно та же логика, что в MouseHandlerMixin на клиенте —
     * иначе клиент шлёт пакет про одну руку, а сервер крутит другую).
     */
    private static ItemStack focusTarget(net.minecraft.world.entity.player.Player player) {
        ItemStack main = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (main.getItem() instanceof FlashlightItem flashlight && !flashlight.isWideBeam()
                && FlashlightItem.isOn(main)) {
            return main;
        }
        ItemStack off = player.getItemInHand(InteractionHand.OFF_HAND);
        if (off.getItem() instanceof FlashlightItem flashlight && !flashlight.isWideBeam()
                && FlashlightItem.isOn(off)) {
            return off;
        }
        return null;
    }
}

package dev.sivren.flashlight.client;

import dev.sivren.flashlight.net.ReloadFlashlightPayload;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.KeyMapping;

import org.lwjgl.glfw.GLFW;

public final class FlashlightClient implements ClientModInitializer {

    public static KeyMapping reloadKey;

    @Override
    public void onInitializeClient() {
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("flashlight", "blind_overlay"),
                new FlashBlindOverlay());

        reloadKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.flashlight.reload",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                KeyMapping.Category.GAMEPLAY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (reloadKey.consumeClick()) {
                if (client.player != null) {
                    ClientPlayNetworking.send(ReloadFlashlightPayload.INSTANCE);
                }
            }
        });
    }
}

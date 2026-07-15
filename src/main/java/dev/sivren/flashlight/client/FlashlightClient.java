package dev.sivren.flashlight.client;

import dev.sivren.flashlight.net.ReloadFlashlightPayload;
import dev.sivren.flashlight.net.ToggleNvgPayload;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.KeyMapping;

import org.lwjgl.glfw.GLFW;

public final class FlashlightClient implements ClientModInitializer {

    public static KeyMapping reloadKey;
    public static KeyMapping nvgKey;

    /** Активные гулы больших фонарей: по игроку с горящим прожектором. */
    private static final java.util.Map<java.util.UUID, LanternHumSound> HUMS = new java.util.HashMap<>();

    @Override
    public void onInitializeClient() {
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("flashlight", "blind_overlay"),
                new FlashBlindOverlay());

        net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry.getInstance()
                .register(dev.sivren.flashlight.ModParticles.RED_SMOKE, RedSmokeParticle.Provider::new);

        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(
                dev.sivren.flashlight.ModEntities.FLARE,
                net.minecraft.client.renderer.entity.ThrownItemRenderer::new);

        reloadKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.flashlight.reload",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                KeyMapping.Category.GAMEPLAY));

        nvgKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.flashlight.nvg",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                KeyMapping.Category.GAMEPLAY));

        // Выход из мира: гасим остаточные эффекты и освобождаем GPU/нативные
        // ресурсы движка (пересоздадутся лениво при следующем входе).
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT
                .register((handler, client) -> {
                    FlashBlindOverlay.reset();
                    GlareRenderer.reset();
                    NvgEffect.reset();
                    FlashlightEngine.reset();
                    HUMS.clear();
                });

        // (клавиши и звуковые лупы — один общий тик ниже)
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (reloadKey.consumeClick()) {
                if (client.player != null) {
                    ClientPlayNetworking.send(ReloadFlashlightPayload.INSTANCE);
                }
            }
            while (nvgKey.consumeClick()) {
                if (client.player != null) {
                    ClientPlayNetworking.send(ToggleNvgPayload.INSTANCE);
                }
            }
            tickLanternHums(client);
            tickFlareSmoke(client);
        });
    }

    /**
     * Красный дым от горящих шашек: в полёте — след, на земле — столб.
     * Плотность адаптивная: вблизи густой, вдали реже (столб всё равно
     * читается, а тысячи частиц не копятся — меньше лагов).
     */
    private static void tickFlareSmoke(net.minecraft.client.Minecraft client) {
        if (client.level == null || client.isPaused()) {
            return;
        }
        net.minecraft.world.phys.Vec3 cameraPos = client.gameRenderer.getMainCamera().position();
        long tick = client.level.getGameTime();
        for (net.minecraft.world.entity.Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof dev.sivren.flashlight.FlareEntity flare)) {
                continue;
            }
            // Прореживание по дистанции: <24 блоков — каждый тик,
            // дальше — каждый 2-й/4-й (у летящей — вдвое реже).
            double distSq = flare.position().distanceToSqr(cameraPos);
            int interval = distSq < 24 * 24 ? 1 : distSq < 64 * 64 ? 2 : 4;
            if (!flare.isLanded()) {
                interval *= 2;
            }
            if ((tick + flare.getId()) % interval != 0) {
                continue;
            }
            var random = client.level.getRandom();
            client.level.addParticle(dev.sivren.flashlight.ModParticles.RED_SMOKE,
                    flare.getX() + (random.nextDouble() - 0.5) * 0.15,
                    flare.getY() + 0.2,
                    flare.getZ() + (random.nextDouble() - 0.5) * 0.15,
                    (random.nextDouble() - 0.5) * 0.02,
                    0.1 + random.nextDouble() * 0.05,
                    (random.nextDouble() - 0.5) * 0.02);
        }
    }

    /**
     * Гул больших фонарей: заводим луп каждому игроку с горящим прожектором,
     * инстанс сам глохнет (LanternHumSound.tick), здесь только чистим карту.
     */
    private static void tickLanternHums(net.minecraft.client.Minecraft client) {
        if (client.level == null || client.isPaused()) {
            if (!HUMS.isEmpty() && client.level == null) {
                HUMS.clear();
            }
            return;
        }
        for (net.minecraft.world.entity.player.Player player : client.level.players()) {
            dev.sivren.flashlight.FlashlightItem lit = FlashlightEngine.litFlashlight(player);
            if (lit == null || !lit.isWideBeam()) {
                continue;
            }
            LanternHumSound current = HUMS.get(player.getUUID());
            if (current == null || current.isStopped()) {
                LanternHumSound hum = new LanternHumSound(player);
                HUMS.put(player.getUUID(), hum);
                client.getSoundManager().play(hum);
            }
        }
        HUMS.values().removeIf(LanternHumSound::isStopped);
    }
}

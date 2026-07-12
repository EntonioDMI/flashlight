package dev.sivren.flashlight;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Перезарядка фонаря батарейкой — «reload» как у оружейных модов.
 * Старт: расходуем батарейку, вешаем RELOADING (клиент показывает прижатие к груди).
 * Через {@value #RELOAD_TICKS} тиков заряд восстанавливается до максимума.
 */
public final class ReloadManager {

    public static final int RELOAD_TICKS = 30;

    private record Pending(InteractionHand hand, long finishAt) {
    }

    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    private ReloadManager() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(ReloadManager::tick);
    }

    /** Пытается начать перезарядку фонаря в руке {@code hand}. Вызывается на сервере. */
    public static void tryStart(Player player, InteractionHand hand) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof FlashlightItem flashlight)
                || FlashlightItem.isReloading(stack)
                || FlashlightItem.charge(stack) >= flashlight.maxCharge()
                || PENDING.containsKey(player.getUUID())) {
            return;
        }
        if (!consumeBattery(player, flashlight)) {
            return;
        }
        // Пока перезаряжается — фонарь гаснет.
        stack.remove(ModComponents.ON);
        stack.set(ModComponents.RELOADING, true);
        PENDING.put(player.getUUID(),
                new Pending(hand, serverPlayer.level().getGameTime() + RELOAD_TICKS));
        player.level().playSound(null, player.blockPosition(),
                SoundEvents.ARMOR_EQUIP_GENERIC.value(), SoundSource.PLAYERS, 0.5f, 0.8f);
    }

    /** Клавиша R с клиента: перезаряжаем фонарь из главной руки, иначе из второй. */
    public static void tryStartFromKey(ServerPlayer player) {
        if (player.getItemInHand(InteractionHand.MAIN_HAND).getItem() instanceof FlashlightItem) {
            tryStart(player, InteractionHand.MAIN_HAND);
        } else if (player.getItemInHand(InteractionHand.OFF_HAND).getItem() instanceof FlashlightItem) {
            tryStart(player, InteractionHand.OFF_HAND);
        }
    }

    private static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Pending>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Pending> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            Pending pending = entry.getValue();
            ItemStack stack = player.getItemInHand(pending.hand());
            if (!(stack.getItem() instanceof FlashlightItem flashlight)
                    || !FlashlightItem.isReloading(stack)) {
                // Переключил предмет / выбросил — отмена (батарейка уже потрачена).
                it.remove();
                continue;
            }
            if (player.level().getGameTime() >= pending.finishAt()) {
                stack.remove(ModComponents.RELOADING);
                stack.set(ModComponents.CHARGE, flashlight.maxCharge());
                stack.set(ModComponents.ON, true); // щёлкнул батарейку — сразу светим
                player.level().playSound(null, player.blockPosition(),
                        SoundEvents.LEVER_CLICK, SoundSource.PLAYERS, 0.4f, 1.3f);
                it.remove();
            }
        }
    }

    private static boolean consumeBattery(Player player, FlashlightItem flashlight) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack slot = inventory.getItem(i);
            if (slot.is(flashlight.batteryItem())) {
                slot.shrink(1);
                return true;
            }
        }
        return false;
    }
}

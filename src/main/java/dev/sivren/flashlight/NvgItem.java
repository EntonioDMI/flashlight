package dev.sivren.flashlight;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import org.jspecify.annotations.Nullable;

/**
 * Прибор ночного видения. Носится на голове, включается клавишей (C2S-пейлоад).
 * Заряд тратится, пока включён и надет; на пустом заряде клавиша сразу
 * меняет батарейку AA из инвентаря. Зелёный экран рисует клиент (post-эффект).
 */
public class NvgItem extends Item {

    /** 900 секунд по 1 заряду в 2 секунды = 30 минут на батарейке AA. */
    public static final int MAX_CHARGE = 900;
    private static final int DRAIN_INTERVAL_TICKS = 40;

    public NvgItem(Properties properties) {
        super(properties);
    }

    public static boolean isOn(ItemStack stack) {
        return stack.has(ModComponents.ON);
    }

    public static int charge(ItemStack stack) {
        return stack.getOrDefault(ModComponents.CHARGE, 0);
    }

    /** Активен = включён и заряжен (эффект требует, чтобы стек был на голове). */
    public static boolean isLit(ItemStack stack) {
        return stack.getItem() instanceof NvgItem && isOn(stack) && charge(stack) > 0;
    }

    /**
     * Анти-спам тумблера: C2S-пакет ничем не ограничен, без кулдауна хакнутый
     * клиент мог бы дёргать NVG_ON/NVG_OFF каждый тик на всю округу.
     */
    private static final java.util.Map<java.util.UUID, Long> LAST_TOGGLE = new java.util.HashMap<>();
    private static final int TOGGLE_COOLDOWN_TICKS = 4;

    /** Клавиша ПНВ с клиента (key.flashlight.nvg, по умолчанию G): тумблер прибора
     *  на голове, на пустом заряде — смена батарейки AA из инвентаря. */
    public static void toggleFromKey(Player player) {
        ItemStack stack = player.getItemBySlot(EquipmentSlot.HEAD);
        if (!(stack.getItem() instanceof NvgItem)) {
            return;
        }
        long now = player.level().getGameTime();
        Long last = LAST_TOGGLE.get(player.getUUID());
        if (last != null && now - last < TOGGLE_COOLDOWN_TICKS) {
            return;
        }
        LAST_TOGGLE.put(player.getUUID(), now);
        if (isOn(stack)) {
            stack.remove(ModComponents.ON);
            play(player, ModSounds.NVG_OFF, 0.7f, 1.0f);
        } else if (charge(stack) > 0) {
            stack.set(ModComponents.ON, true);
            play(player, ModSounds.NVG_ON, 0.7f, 1.0f);
        } else if (consumeBattery(player)) {
            stack.set(ModComponents.CHARGE, MAX_CHARGE);
            stack.set(ModComponents.ON, true);
            player.level().playSound(null, player.blockPosition(),
                    SoundEvents.ARMOR_EQUIP_GENERIC.value(), SoundSource.PLAYERS, 0.5f, 1.1f);
            play(player, ModSounds.NVG_ON, 0.7f, 1.0f);
        } else {
            play(player, ModSounds.FLASHLIGHT_CLICK, 0.4f, 0.6f); // пусто и без батареек
        }
    }

    @Override
    public void inventoryTick(ItemStack stack, ServerLevel level, Entity owner, @Nullable EquipmentSlot slot) {
        if (!isOn(stack)) {
            return;
        }
        // Снятый с головы прибор гаснет — не жрёт заряд в рюкзаке.
        if (slot != EquipmentSlot.HEAD) {
            stack.remove(ModComponents.ON);
            return;
        }
        int charge = charge(stack);
        if (charge <= 0) {
            stack.remove(ModComponents.ON);
        } else if (level.getGameTime() % DRAIN_INTERVAL_TICKS == 0L
                && !(owner instanceof Player player && player.getAbilities().instabuild)) {
            // В креативе заряд не тратится.
            stack.set(ModComponents.CHARGE, charge - 1);
        }
    }

    // Индикатор заряда — как у фонарей, но фосфорно-зелёный.
    @Override
    public boolean isBarVisible(ItemStack stack) {
        return charge(stack) < MAX_CHARGE;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return Mth.clamp(Math.round(13.0f * charge(stack) / MAX_CHARGE), 0, 13);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return 0x6FE85C;
    }

    private static boolean consumeBattery(Player player) {
        if (player.getAbilities().instabuild) {
            return true; // креатив: смена батарейки бесплатная
        }
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack slot = inventory.getItem(i);
            if (slot.is(ModItems.BATTERY_AA)) {
                slot.shrink(1);
                return true;
            }
        }
        return false;
    }

    private static void play(Player player, net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
        player.level().playSound(null, player.blockPosition(), sound, SoundSource.PLAYERS, volume, pitch);
    }
}

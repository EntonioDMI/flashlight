package dev.sivren.flashlight;

import java.util.function.Supplier;

import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;

import org.jspecify.annotations.Nullable;

/**
 * Ручной фонарь. ПКМ — вкл/выкл (если есть заряд) или автоперезарядка при пустом.
 * Заряд тратится 1/сек, пока включён. Перезарядка — батарейкой через {@link ReloadManager}.
 */
public class FlashlightItem extends Item {

    private final int maxCharge;
    private final int lightLevel;
    private final int beamRange;
    private final boolean wideBeam;
    private final int drainIntervalTicks;
    private final Supplier<Item> battery;

    public FlashlightItem(Properties properties, int maxCharge, int lightLevel, int beamRange,
                          boolean wideBeam, int drainIntervalTicks, Supplier<Item> battery) {
        super(properties);
        this.maxCharge = maxCharge;
        this.lightLevel = lightLevel;
        this.beamRange = beamRange;
        this.wideBeam = wideBeam;
        this.drainIntervalTicks = drainIntervalTicks;
        this.battery = battery;
    }

    /** true — широкий прожектор (фонарь), false — узкий дальнобойный луч (фонарик). */
    public boolean isWideBeam() {
        return wideBeam;
    }

    public int maxCharge() {
        return maxCharge;
    }

    public int lightLevel() {
        return lightLevel;
    }

    public int beamRange() {
        return beamRange;
    }

    public Item batteryItem() {
        return battery.get();
    }

    public static boolean isOn(ItemStack stack) {
        return stack.has(ModComponents.ON);
    }

    public static boolean isReloading(ItemStack stack) {
        return stack.getOrDefault(ModComponents.RELOADING, false);
    }

    public static int charge(ItemStack stack) {
        return stack.getOrDefault(ModComponents.CHARGE, 0);
    }

    /** Активен = включён, заряжен и не в процессе перезарядки. */
    public static boolean isLit(ItemStack stack) {
        return stack.getItem() instanceof FlashlightItem
                && isOn(stack) && !isReloading(stack) && charge(stack) > 0;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide()) {
            if (isReloading(stack)) {
                if (ReloadManager.isPending(player)) {
                    return InteractionResult.CONSUME; // честная перезарядка идёт
                }
                // Самолечение: флаг завис без активной перезарядки (старый баг
                // со свапом слота) — снимаем и работаем как обычный тумблер.
                stack.remove(ModComponents.RELOADING);
            }
            if (isOn(stack)) {
                stack.remove(ModComponents.ON);
                click(level, player, 0.9f);
            } else if (charge(stack) > 0) {
                stack.set(ModComponents.ON, true);
                click(level, player, 1.2f);
            } else {
                // Пустой: пробуем сразу перезарядить батарейкой из инвентаря.
                ReloadManager.tryStart(player, hand);
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void inventoryTick(ItemStack stack, ServerLevel level, Entity owner, @Nullable EquipmentSlot slot) {
        if (!isOn(stack)) {
            return;
        }
        int charge = charge(stack);
        if (charge <= 0) {
            stack.remove(ModComponents.ON);
        } else if (level.getGameTime() % drainIntervalTicks == 0L
                && !(owner instanceof Player player && player.getAbilities().instabuild)) {
            // В креативе заряд не тратится.
            stack.set(ModComponents.CHARGE, charge - 1);
        }
    }

    // Индикатор заряда — как полоска прочности, но жёлтая.
    @Override
    public boolean isBarVisible(ItemStack stack) {
        return charge(stack) < maxCharge;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return Mth.clamp(Math.round(13.0f * charge(stack) / maxCharge), 0, 13);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return 0xFFD75E;
    }

    private static void click(Level level, Player player, float pitch) {
        level.playSound(null, player.blockPosition(), ModSounds.FLASHLIGHT_CLICK, SoundSource.PLAYERS, 0.5f, pitch);
    }
}

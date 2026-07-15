package dev.sivren.flashlight;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.level.Level;

/**
 * Сигнальная шашка: зажимаешь ПКМ (зарядка как у лука), отпускаешь — летит
 * по дуге до ~35 блоков, падает и горит {@value #BURN_SECONDS} секунд красным
 * светом с столбом дыма. Одноразовая, потушить нельзя — как ИРЛ.
 */
public class FlareItem extends Item {

    public static final int BURN_SECONDS = 60;

    /** Скорость броска на полной зарядке (~35 блоков по дуге). */
    private static final float MAX_THROW_VELOCITY = 2.1f;

    public FlareItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        player.startUsingItem(hand);
        return InteractionResult.CONSUME;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return 72000;
    }

    @Override
    public ItemUseAnimation getUseAnimation(ItemStack stack) {
        return ItemUseAnimation.BOW;
    }

    @Override
    public boolean releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
        if (!(entity instanceof Player player)) {
            return false;
        }
        int chargeTicks = getUseDuration(stack, entity) - timeLeft;
        float power = net.minecraft.world.item.BowItem.getPowerForTime(chargeTicks); // кривая лука
        if (power < 0.1f) {
            return false; // слишком короткий тап — не бросаем
        }
        if (!level.isClientSide()) {
            FlareEntity flare = new FlareEntity(level, player);
            flare.shootFromRotation(player, player.getXRot(), player.getYRot(),
                    -8.0f, (0.5f + 1.6f * power) * MAX_THROW_VELOCITY / 2.1f, 0.5f);
            level.addFreshEntity(flare);
            level.playSound(null, player.blockPosition(),
                    SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 0.6f, 1.3f);
        }
        stack.consume(1, player);
        return true;
    }
}

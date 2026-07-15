package dev.sivren.flashlight;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Брошенная горящая шашка: летит по дуге, падает и ЛЕЖИТ, догорая
 * ({@link FlareItem#BURN_SECONDS} c от броска). Красный точечный свет даёт
 * движок фонарика (FlashlightEngine сканирует эти сущности), дым — клиент.
 */
public class FlareEntity extends ThrowableItemProjectile {

    public static final int BURN_TICKS = FlareItem.BURN_SECONDS * 20;

    /**
     * Синхронизируется с клиентом: без этого клиентская копия лежащей шашки
     * продолжает «падать» (сервер не шлёт движение неподвижной сущности),
     * а плотность дыма (isLanded в FlashlightClient) считается неверно.
     */
    private static final EntityDataAccessor<Boolean> DATA_LANDED =
            SynchedEntityData.defineId(FlareEntity.class, EntityDataSerializers.BOOLEAN);

    private int burnTicks = BURN_TICKS;

    public FlareEntity(EntityType<? extends FlareEntity> type, Level level) {
        super(type, level);
    }

    public FlareEntity(Level level, LivingEntity owner) {
        super(ModEntities.FLARE, owner, level, new ItemStack(ModItems.SIGNAL_FLARE));
    }

    @Override
    protected Item getDefaultItem() {
        return ModItems.SIGNAL_FLARE;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_LANDED, false);
    }

    public boolean isLanded() {
        return entityData.get(DATA_LANDED);
    }

    private void setLanded(boolean landed) {
        entityData.set(DATA_LANDED, landed);
        // Без гравитации обе стороны держат её на месте между пакетами.
        setNoGravity(landed);
    }

    @Override
    public void tick() {
        if (!isLanded()) {
            super.tick();
        }
        if (!level().isClientSide() && --burnTicks <= 0) {
            discard(); // догорела
        }
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        super.onHitBlock(result);
        setLanded(true);
        setDeltaMovement(Vec3.ZERO);
        Vec3 hit = result.getLocation();
        setPos(hit.x, hit.y + 0.02, hit.z);
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        // Об моба — отскакивает, урона нет. На излёте (застряла в хитбоксе)
        // не дребезжит: слабый импульс не отражаем, шашка просто падает дальше.
        Vec3 velocity = getDeltaMovement();
        if (velocity.lengthSqr() > 0.01) {
            setDeltaMovement(velocity.scale(-0.25));
        }
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putInt("BurnTicks", burnTicks);
        output.putBoolean("Landed", isLanded());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        burnTicks = input.getIntOr("BurnTicks", BURN_TICKS);
        setLanded(input.getBooleanOr("Landed", false));
    }
}

package dev.sivren.flashlight;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/** Сущности мода. */
public final class ModEntities {

    private static final ResourceKey<EntityType<?>> FLARE_KEY = ResourceKey.create(Registries.ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(Flashlight.MOD_ID, "signal_flare"));

    /** Брошенная горящая сигнальная шашка. */
    public static final EntityType<FlareEntity> FLARE = Registry.register(
            BuiltInRegistries.ENTITY_TYPE, FLARE_KEY,
            EntityType.Builder.<FlareEntity>of(FlareEntity::new, MobCategory.MISC)
                    .sized(0.25f, 0.25f)
                    .clientTrackingRange(16) // сигналка должна быть видна издалека
                    .updateInterval(10)
                    .build(FLARE_KEY));

    private ModEntities() {
    }

    public static void init() {
        // Регистрация — в статических инициализаторах выше.
    }
}

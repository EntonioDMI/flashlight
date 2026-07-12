package dev.sivren.flashlight;

import com.mojang.serialization.Codec;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;

/**
 * Data-компоненты мода. Все синхронизируются с клиентом штатной сетью ItemStack
 * (дельта от прототипа предмета), кастомные пакеты для них не нужны.
 */
public final class ModComponents {

    /** Текущий заряд фонаря в секундах. ignoreSwapAnimation — чтобы рука не дёргалась каждую секунду. */
    public static final DataComponentType<Integer> CHARGE = DataComponentType.<Integer>builder()
            .persistent(Codec.INT)
            .networkSynchronized(ByteBufCodecs.VAR_INT)
            .ignoreSwapAnimation()
            .build();

    /**
     * Включён ли фонарь. ВАЖНО: компонент либо присутствует (true), либо ОТСУТСТВУЕТ —
     * item-модель выбирается через has_component в items/flashlight.json.
     */
    public static final DataComponentType<Boolean> ON = DataComponentType.<Boolean>builder()
            .persistent(Codec.BOOL)
            .networkSynchronized(ByteBufCodecs.BOOL)
            .ignoreSwapAnimation()
            .build();

    /** Идёт перезарядка (для клиентской анимации прижатия к груди). */
    public static final DataComponentType<Boolean> RELOADING = DataComponentType.<Boolean>builder()
            .persistent(Codec.BOOL)
            .networkSynchronized(ByteBufCodecs.BOOL)
            .ignoreSwapAnimation()
            .build();

    /**
     * Фокус линзы фонарика 0..10: 10 — максимально узкий дальнобойный луч,
     * 0 — широкий ближний (~60% ширины прожектора). Меняется колёсиком.
     */
    public static final DataComponentType<Integer> FOCUS = DataComponentType.<Integer>builder()
            .persistent(Codec.INT)
            .networkSynchronized(ByteBufCodecs.VAR_INT)
            .ignoreSwapAnimation()
            .build();

    private ModComponents() {
    }

    public static void init() {
        register("charge", CHARGE);
        register("on", ON);
        register("reloading", RELOADING);
        register("focus", FOCUS);
    }

    private static void register(String path, DataComponentType<?> type) {
        Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
                Identifier.fromNamespaceAndPath(Flashlight.MOD_ID, path), type);
    }
}

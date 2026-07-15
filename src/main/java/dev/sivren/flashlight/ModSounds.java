package dev.sivren.flashlight;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/** Звуки мода (см. assets/flashlight/sounds.json). */
public final class ModSounds {

    /** Включение ПНВ: писк разгона трубки. */
    public static final SoundEvent NVG_ON = register("nvg_on");
    /** Выключение ПНВ. */
    public static final SoundEvent NVG_OFF = register("nvg_off");
    /** Универсальный клик кнопки фонаря (вкл/выкл/перезаряжен). */
    public static final SoundEvent FLASHLIGHT_CLICK = register("flashlight_click");
    /** Тик кольца фокуса линзы (колёсико). */
    public static final SoundEvent FOCUS_ZOOM = register("focus_zoom");
    /** Гул работающего большого фонаря (луп, клиент). */
    public static final SoundEvent LANTERN_LOOP = register("lantern_loop");

    private ModSounds() {
    }

    public static void init() {
        // Регистрация — в статических инициализаторах выше.
    }

    private static SoundEvent register(String path) {
        Identifier id = Identifier.fromNamespaceAndPath(Flashlight.MOD_ID, path);
        return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
    }
}

package dev.sivren.flashlight;

import java.util.function.Function;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;

/** Регистрация предметов мода. */
public final class ModItems {

    /**
     * Фонарик-универсал: фокус линзы колёсиком (25 блоков широкий ↔ 110 узкий).
     * Экономный: 1 заряд за 2 секунды — 30 минут на дешёвой AA.
     */
    public static final Item FLASHLIGHT = register("flashlight",
            props -> new FlashlightItem(props, 900, 13, 110, false, 40, () -> ModItems.BATTERY_AA),
            new Item.Properties()
                    .stacksTo(1)
                    .component(ModComponents.CHARGE, 0)
                    .component(ModComponents.FOCUS, 10));

    /**
     * Фонарь-«прожектор»: широкий ~40°, дальность 55. Прожорливее (1 заряд/1.5с),
     * но мощный аккумулятор тянет 45 минут.
     */
    public static final Item WORK_LANTERN = register("work_lantern",
            props -> new FlashlightItem(props, 1800, 15, 55, true, 30, () -> ModItems.BATTERY_PACK),
            new Item.Properties()
                    .stacksTo(1)
                    .component(ModComponents.CHARGE, 0));

    /** Пальчиковая батарейка — расходник для фонарика. */
    public static final Item BATTERY_AA = register("battery_aa", Item::new,
            new Item.Properties().stacksTo(16));

    /** Аккумулятор — расходник для рабочего фонаря. */
    public static final Item BATTERY_PACK = register("battery_pack", Item::new,
            new Item.Properties().stacksTo(16));

    /** Прибор ночного видения — шлем (надевается ПКМ), тумблер клавишей, ест AA. */
    public static final Item NVG = register("nvg", NvgItem::new,
            new Item.Properties()
                    .stacksTo(1)
                    .component(ModComponents.CHARGE, 0)
                    .equippable(EquipmentSlot.HEAD));

    /** Сигнальная шашка: ПКМ поджигает, минута красного света и дыма, одноразовая. */
    public static final Item SIGNAL_FLARE = register("signal_flare", FlareItem::new,
            new Item.Properties().stacksTo(16));

    private ModItems() {
    }

    public static void init() {
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(output -> {
            output.accept(FLASHLIGHT);
            output.accept(WORK_LANTERN);
            output.accept(BATTERY_AA);
            output.accept(BATTERY_PACK);
            output.accept(NVG);
            output.accept(SIGNAL_FLARE);
        });
    }

    private static Item register(String path, Function<Item.Properties, Item> factory, Item.Properties properties) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM,
                Identifier.fromNamespaceAndPath(Flashlight.MOD_ID, path));
        Item item = factory.apply(properties.setId(key));
        return Registry.register(BuiltInRegistries.ITEM, key, item);
    }
}

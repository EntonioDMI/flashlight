package dev.sivren.flashlight;

import java.util.Set;

import net.fabricmc.fabric.api.loot.v3.LootTableEvents;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.EmptyLootItem;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraft.world.level.storage.loot.providers.number.UniformGenerator;

/**
 * Лут в мире: тактические фонарики и батарейки AA — по деревням,
 * рабочие фонари и аккумуляторы — по данжам. ПНВ в лут не кладём (только крафт).
 */
public final class ModLoot {

    private static final Set<ResourceKey<LootTable>> VILLAGES = Set.of(
            BuiltInLootTables.VILLAGE_WEAPONSMITH,
            BuiltInLootTables.VILLAGE_TOOLSMITH,
            BuiltInLootTables.VILLAGE_PLAINS_HOUSE,
            BuiltInLootTables.VILLAGE_DESERT_HOUSE,
            BuiltInLootTables.VILLAGE_SAVANNA_HOUSE,
            BuiltInLootTables.VILLAGE_SNOWY_HOUSE,
            BuiltInLootTables.VILLAGE_TAIGA_HOUSE);

    private static final Set<ResourceKey<LootTable>> DUNGEONS = Set.of(
            BuiltInLootTables.SIMPLE_DUNGEON,
            BuiltInLootTables.ABANDONED_MINESHAFT,
            BuiltInLootTables.STRONGHOLD_CORRIDOR,
            BuiltInLootTables.STRONGHOLD_CROSSING);

    private ModLoot() {
    }

    public static void init() {
        LootTableEvents.MODIFY.register((key, tableBuilder, source, registries) -> {
            if (!source.isBuiltin()) {
                return;
            }
            if (VILLAGES.contains(key)) {
                // Деревни: чаще тактический фонарик + пальчиковые батарейки.
                tableBuilder.withPool(LootPool.lootPool()
                        .setRolls(ConstantValue.exactly(1))
                        .add(EmptyLootItem.emptyItem().setWeight(6))
                        .add(LootItem.lootTableItem(ModItems.FLASHLIGHT).setWeight(3))
                        .add(LootItem.lootTableItem(ModItems.BATTERY_AA).setWeight(4)
                                .apply(SetItemCountFunction.setCount(UniformGenerator.between(1, 3)))));
            } else if (DUNGEONS.contains(key)) {
                // Данжи: рабочий фонарь + аккумуляторы.
                tableBuilder.withPool(LootPool.lootPool()
                        .setRolls(ConstantValue.exactly(1))
                        .add(EmptyLootItem.emptyItem().setWeight(5))
                        .add(LootItem.lootTableItem(ModItems.WORK_LANTERN).setWeight(3))
                        .add(LootItem.lootTableItem(ModItems.BATTERY_PACK).setWeight(3)
                                .apply(SetItemCountFunction.setCount(UniformGenerator.between(1, 2)))));
            }
        });
    }
}

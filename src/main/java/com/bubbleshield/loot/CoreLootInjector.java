package com.bubbleshield.loot;

import com.bubbleshield.registry.ModItems;

import net.fabricmc.fabric.api.loot.v3.LootTableEvents;

import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.EmptyLootItem;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraft.world.level.storage.loot.providers.number.UniformGenerator;

/**
 * Injects mod items into structure chest loot tables via extra weighted pools —
 * TWO per matching chest (the existing table content is untouched):
 * <ul>
 * <li>End City treasure + Ancient City: Resonant Core at 1-in-10 (weight 1 vs 9 empty).</li>
 * <li>End City treasure: Aegis Core at 1-in-20 (weight 1 vs 19 empty) — the tier-3
 * endgame core guards the End City's top-floor treasure room (C8).</li>
 * <li>Ancient City: Patch Kit x1-2 at 1-in-8 (weight 1 vs 7 empty) — field-repair
 * supplies in the deep dark (C8).</li>
 * </ul>
 */
public final class CoreLootInjector {
	private CoreLootInjector() {
	}

	public static void register() {
		LootTableEvents.MODIFY.register((key, tableBuilder, source, registries) -> {
			if (BuiltInLootTables.END_CITY_TREASURE.equals(key) || BuiltInLootTables.ANCIENT_CITY.equals(key)) {
				tableBuilder.withPool(LootPool.lootPool()
					.setRolls(ConstantValue.exactly(1.0F))
					.add(LootItem.lootTableItem(ModItems.RESONANT_CORE).setWeight(1))
					.add(EmptyLootItem.emptyItem().setWeight(9)));
			}

			if (BuiltInLootTables.END_CITY_TREASURE.equals(key)) {
				tableBuilder.withPool(LootPool.lootPool()
					.setRolls(ConstantValue.exactly(1.0F))
					.add(LootItem.lootTableItem(ModItems.AEGIS_CORE).setWeight(1))
					.add(EmptyLootItem.emptyItem().setWeight(19)));
			}

			if (BuiltInLootTables.ANCIENT_CITY.equals(key)) {
				tableBuilder.withPool(LootPool.lootPool()
					.setRolls(ConstantValue.exactly(1.0F))
					.add(LootItem.lootTableItem(ModItems.PATCH_KIT).setWeight(1)
						.apply(SetItemCountFunction.setCount(UniformGenerator.between(1.0F, 2.0F))))
					.add(EmptyLootItem.emptyItem().setWeight(7)));
			}
		});
	}
}

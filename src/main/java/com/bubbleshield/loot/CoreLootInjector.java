package com.bubbleshield.loot;

import com.bubbleshield.registry.ModItems;

import net.fabricmc.fabric.api.loot.v3.LootTableEvents;

import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.EmptyLootItem;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;

/**
 * Injects a Resonant Core chance into two structure chest loot tables: End City
 * treasure and Ancient City chests each get one extra pool with a 1-in-10 chance
 * (weight 1 core vs weight 9 empty) per chest. Every other table is untouched.
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
		});
	}
}

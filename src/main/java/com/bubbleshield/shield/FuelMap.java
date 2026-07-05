package com.bubbleshield.shield;

import java.util.Map;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Static mapping of items to the number of fuel-seconds they provide to a shield projector.
 */
public final class FuelMap {
	private static final Map<Item, Integer> FUEL_SECONDS = Map.of(
		Items.COAL, 80,
		Items.CHARCOAL, 80,
		Items.COAL_BLOCK, 800,
		Items.BLAZE_ROD, 120,
		Items.LAVA_BUCKET, 1000
	);

	private FuelMap() {
	}

	/**
	 * @return fuel-seconds provided by a single item of this type, or 0 if it is not a fuel.
	 */
	public static int fuelSeconds(Item item) {
		return FUEL_SECONDS.getOrDefault(item, 0);
	}

	/**
	 * @return fuel-seconds provided by the whole stack, or 0 if it is not a fuel.
	 */
	public static int fuelSeconds(ItemStack stack) {
		return stack.isEmpty() ? 0 : fuelSeconds(stack.getItem()) * stack.getCount();
	}
}

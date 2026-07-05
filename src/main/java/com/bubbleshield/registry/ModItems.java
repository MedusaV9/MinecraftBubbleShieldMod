package com.bubbleshield.registry;

import com.bubbleshield.BubbleShield;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;

public final class ModItems {
	public static final ResourceKey<Item> BUBBLE_SHIELD_PROJECTOR_KEY = ResourceKey.create(Registries.ITEM, BubbleShield.id("bubble_shield_projector"));

	public static final Item BUBBLE_SHIELD_PROJECTOR = Registry.register(
		BuiltInRegistries.ITEM,
		BUBBLE_SHIELD_PROJECTOR_KEY,
		new BlockItem(
			ModBlocks.BUBBLE_SHIELD_PROJECTOR,
			new Item.Properties()
				.useBlockDescriptionPrefix()
				.setId(BUBBLE_SHIELD_PROJECTOR_KEY)
		)
	);

	private ModItems() {
	}

	public static void init() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS)
			.register(output -> output.accept(BUBBLE_SHIELD_PROJECTOR));
	}
}

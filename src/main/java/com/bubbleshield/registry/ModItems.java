package com.bubbleshield.registry;

import java.util.function.Consumer;

import com.bubbleshield.BubbleShield;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

public final class ModItems {
	public static final ResourceKey<Item> BUBBLE_SHIELD_PROJECTOR_KEY = ResourceKey.create(Registries.ITEM, BubbleShield.id("bubble_shield_projector"));
	public static final ResourceKey<Item> RESONANT_CORE_KEY = ResourceKey.create(Registries.ITEM, BubbleShield.id("resonant_core"));
	public static final ResourceKey<Item> PRISMATIC_CORE_KEY = ResourceKey.create(Registries.ITEM, BubbleShield.id("prismatic_core"));
	public static final ResourceKey<Item> AEGIS_CORE_KEY = ResourceKey.create(Registries.ITEM, BubbleShield.id("aegis_core"));
	public static final ResourceKey<Item> FLUX_CAPACITOR_KEY = ResourceKey.create(Registries.ITEM, BubbleShield.id("flux_capacitor"));
	public static final ResourceKey<Item> PATCH_KIT_KEY = ResourceKey.create(Registries.ITEM, BubbleShield.id("patch_kit"));
	public static final ResourceKey<Item> REINFORCED_PLATING_KEY = ResourceKey.create(Registries.ITEM, BubbleShield.id("reinforced_plating"));
	public static final ResourceKey<Item> BLAST_WARD_KEY = ResourceKey.create(Registries.ITEM, BubbleShield.id("blast_ward"));

	/**
	 * An item whose hover tooltip carries one static translatable line —
	 * {@code <descriptionId>.tooltip} in gray — used by the upgrade cores and the
	 * flux capacitor to state their exact current numbers (HP base, regen/pulse,
	 * cooldown, DR) right on the item. The EN+DE lang parity gametest covers the
	 * {@code .tooltip} keys like every other key.
	 */
	private static final class TooltipItem extends Item {
		TooltipItem(Item.Properties properties) {
			super(properties);
		}

		@Override
		public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
			builder.accept(Component.translatable(this.getDescriptionId() + ".tooltip").withStyle(ChatFormatting.GRAY));
		}
	}

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

	/** Tier-1 upgrade core: 400 base HP, 25% damage resistance, faster regen, 10-min break cooldown. */
	public static final Item RESONANT_CORE = Registry.register(
		BuiltInRegistries.ITEM,
		RESONANT_CORE_KEY,
		new TooltipItem(
			new Item.Properties()
				.stacksTo(1)
				.setId(RESONANT_CORE_KEY)
		)
	);

	/** Tier-2 upgrade core: 700 base HP, 40% damage resistance, faster regen, 6-min break cooldown. */
	public static final Item PRISMATIC_CORE = Registry.register(
		BuiltInRegistries.ITEM,
		PRISMATIC_CORE_KEY,
		new TooltipItem(
			new Item.Properties()
				.stacksTo(1)
				.setId(PRISMATIC_CORE_KEY)
		)
	);

	/** Tier-3 upgrade core: 1200 base HP, 50% damage resistance, fastest regen, 3-min break cooldown. */
	public static final Item AEGIS_CORE = Registry.register(
		BuiltInRegistries.ITEM,
		AEGIS_CORE_KEY,
		new TooltipItem(
			new Item.Properties()
				.stacksTo(1)
				.setId(AEGIS_CORE_KEY)
		)
	);

	/**
	 * Slot-2 upgrade: while installed, the active shield's passive drain halves and
	 * tier regeneration pulses no longer burn the extra fuel-second.
	 */
	public static final Item FLUX_CAPACITOR = Registry.register(
		BuiltInRegistries.ITEM,
		FLUX_CAPACITOR_KEY,
		new TooltipItem(
			new Item.Properties()
				.stacksTo(1)
				.setId(FLUX_CAPACITOR_KEY)
		)
	);

	/**
	 * C3 repair consumable: right-click an ACTIVE owned/whitelisted projector to
	 * restore 150 shield HP (capped at max), or a broken (cooling-down) one to cut
	 * the remaining break cooldown by 20% of the tier's full cooldown.
	 */
	public static final Item PATCH_KIT = Registry.register(
		BuiltInRegistries.ITEM,
		PATCH_KIT_KEY,
		new TooltipItem(
			new Item.Properties()
				.stacksTo(16)
				.setId(PATCH_KIT_KEY)
		)
	);

	/**
	 * Augment-slot defense module: while socketed, every shield hit gains 30%
	 * plating damage resistance, stacking multiplicatively with the tier DR under
	 * the 70% combined cap. Mutually exclusive with the blast ward (one augment slot).
	 */
	public static final Item REINFORCED_PLATING = Registry.register(
		BuiltInRegistries.ITEM,
		REINFORCED_PLATING_KEY,
		new TooltipItem(
			new Item.Properties()
				.stacksTo(1)
				.setId(REINFORCED_PLATING_KEY)
		)
	);

	/**
	 * Augment-slot defense module: while socketed, intercepted EXPLOSIVE projectiles
	 * (fireballs, wither skulls, wind charges) deal 60% less shield damage, applied
	 * to the raw damage BEFORE the tier/plating DR pipeline. Mutually exclusive with
	 * the reinforced plating (one augment slot).
	 */
	public static final Item BLAST_WARD = Registry.register(
		BuiltInRegistries.ITEM,
		BLAST_WARD_KEY,
		new TooltipItem(
			new Item.Properties()
				.stacksTo(1)
				.setId(BLAST_WARD_KEY)
		)
	);

	private ModItems() {
	}

	public static void init() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS)
			.register(output -> {
				output.accept(BUBBLE_SHIELD_PROJECTOR);
				output.accept(RESONANT_CORE);
				output.accept(PRISMATIC_CORE);
				output.accept(AEGIS_CORE);
				output.accept(FLUX_CAPACITOR);
				output.accept(PATCH_KIT);
				output.accept(REINFORCED_PLATING);
				output.accept(BLAST_WARD);
			});
	}
}

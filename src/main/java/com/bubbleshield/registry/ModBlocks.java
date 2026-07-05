package com.bubbleshield.registry;

import com.bubbleshield.BubbleShield;
import com.bubbleshield.block.BubbleShieldBlock;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class ModBlocks {
	public static final ResourceKey<Block> BUBBLE_SHIELD_PROJECTOR_KEY = ResourceKey.create(Registries.BLOCK, BubbleShield.id("bubble_shield_projector"));

	public static final Block BUBBLE_SHIELD_PROJECTOR = Registry.register(
		BuiltInRegistries.BLOCK,
		BUBBLE_SHIELD_PROJECTOR_KEY,
		new BubbleShieldBlock(
			BlockBehaviour.Properties.of()
				.strength(3.5F)
				.requiresCorrectToolForDrops()
				.setId(BUBBLE_SHIELD_PROJECTOR_KEY)
		)
	);

	private ModBlocks() {
	}

	public static void init() {
		// Static initializers run registration.
	}
}

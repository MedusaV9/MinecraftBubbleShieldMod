package com.bubbleshield.registry;

import java.util.Set;

import com.bubbleshield.BubbleShield;
import com.bubbleshield.block.BubbleShieldBlockEntity;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class ModBlockEntities {
	public static final BlockEntityType<BubbleShieldBlockEntity> BUBBLE_SHIELD_PROJECTOR = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE,
		ResourceKey.create(Registries.BLOCK_ENTITY_TYPE, BubbleShield.id("bubble_shield_projector")),
		new BlockEntityType<>(BubbleShieldBlockEntity::new, Set.of(ModBlocks.BUBBLE_SHIELD_PROJECTOR))
	);

	private ModBlockEntities() {
	}

	public static void init() {
		// Static initializers run registration.
	}
}

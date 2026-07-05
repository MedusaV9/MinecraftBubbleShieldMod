package com.bubbleshield.registry;

import com.bubbleshield.BubbleShield;
import com.bubbleshield.menu.BubbleShieldMenu;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;

public final class ModMenus {
	/**
	 * Extended menu type that syncs the projector's BlockPos to the client when the menu opens,
	 * so the client screen can address settings packets to the right block.
	 */
	public static final ExtendedMenuType<BubbleShieldMenu, BlockPos> BUBBLE_SHIELD = Registry.register(
		BuiltInRegistries.MENU,
		ResourceKey.create(Registries.MENU, BubbleShield.id("bubble_shield")),
		new ExtendedMenuType<>(BubbleShieldMenu::new, BlockPos.STREAM_CODEC)
	);

	private ModMenus() {
	}

	public static void init() {
		// Static initializers run registration.
	}
}

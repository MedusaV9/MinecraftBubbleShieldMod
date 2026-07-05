package com.bubbleshield;

import com.bubbleshield.net.ServerNet;
import com.bubbleshield.net.ShieldPayloads;
import com.bubbleshield.registry.ModBlockEntities;
import com.bubbleshield.registry.ModBlocks;
import com.bubbleshield.registry.ModItems;
import com.bubbleshield.registry.ModMenus;

import net.fabricmc.api.ModInitializer;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BubbleShield implements ModInitializer {
	public static final String MOD_ID = "bubbleshield";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Hello from Bubble Shield!");

		ModBlocks.init();
		ModItems.init();
		ModBlockEntities.init();
		ModMenus.init();

		ShieldPayloads.registerTypes();
		ServerNet.register();
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}

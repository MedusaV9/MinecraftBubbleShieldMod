package com.bubbleshield.client;

import com.bubbleshield.client.gui.BubbleShieldScreen;
import com.bubbleshield.registry.ModMenus;

import net.fabricmc.api.ClientModInitializer;

import net.minecraft.client.gui.screens.MenuScreens;

public class BubbleShieldClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientShieldManager.register();
		MenuScreens.register(ModMenus.BUBBLE_SHIELD, BubbleShieldScreen::new);
	}
}

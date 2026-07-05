package com.bubbleshield.client;

import com.bubbleshield.client.gui.BubbleShieldScreen;
import com.bubbleshield.client.render.ShieldPipelines;
import com.bubbleshield.client.render.ShieldRenderer;
import com.bubbleshield.registry.ModMenus;

import net.fabricmc.api.ClientModInitializer;

import net.minecraft.client.gui.screens.MenuScreens;

public class BubbleShieldClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientShieldManager.register();
		ShieldPipelines.bootstrap();
		ShieldRenderer.register();
		MenuScreens.register(ModMenus.BUBBLE_SHIELD, BubbleShieldScreen::new);
	}
}

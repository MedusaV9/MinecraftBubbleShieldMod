package com.bubbleshield.client;

import com.bubbleshield.BubbleShield;
import com.bubbleshield.client.fx.ScreenEffectManager;
import com.bubbleshield.client.gui.BubbleShieldScreen;
import com.bubbleshield.client.hud.ShieldHudElement;
import com.bubbleshield.client.render.ShieldPipelines;
import com.bubbleshield.client.render.ShieldRenderer;
import com.bubbleshield.registry.ModMenus;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import net.minecraft.client.gui.screens.MenuScreens;

public class BubbleShieldClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientShieldManager.register();
		ShieldPipelines.bootstrap();
		ShieldRenderer.register();
		ScreenEffectManager.register();
		HudElementRegistry.addLast(BubbleShield.id("shield_status"), new ShieldHudElement());
		MenuScreens.register(ModMenus.BUBBLE_SHIELD, BubbleShieldScreen::new);
	}
}

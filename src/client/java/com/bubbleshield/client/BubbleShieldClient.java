package com.bubbleshield.client;

import com.bubbleshield.BubbleShield;
import com.bubbleshield.client.fx.ImpactFxManager;
import com.bubbleshield.client.fx.ScreenEffectManager;
import com.bubbleshield.client.gui.BubbleShieldScreen;
import com.bubbleshield.client.hud.ShieldFlashElement;
import com.bubbleshield.client.hud.ShieldHudElement;
import com.bubbleshield.client.render.InteriorPipelines;
import com.bubbleshield.client.render.InteriorRenderer;
import com.bubbleshield.client.render.SceneCopy;
import com.bubbleshield.client.render.ShieldPipelines;
import com.bubbleshield.client.render.ShieldRenderer;
import com.bubbleshield.registry.ModMenus;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import net.minecraft.client.gui.screens.MenuScreens;

public class BubbleShieldClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Config first: FlashIntensity and the interior density must be live
		// before any renderer consults them.
		BubbleShieldClientConfig.load();
		ClientShieldManager.register();
		ShieldPipelines.bootstrap();
		InteriorPipelines.bootstrap();
		ShieldRenderer.register();
		InteriorRenderer.register();
		SceneCopy.register();
		ScreenEffectManager.register();
		ImpactFxManager.register();
		HudElementRegistry.addLast(BubbleShield.id("shield_status"), new ShieldHudElement());
		// The contact flash draws after (over) the status text, matching addLast order.
		HudElementRegistry.addLast(BubbleShield.id("shield_flash"), new ShieldFlashElement());
		MenuScreens.register(ModMenus.BUBBLE_SHIELD, BubbleShieldScreen::new);
	}
}

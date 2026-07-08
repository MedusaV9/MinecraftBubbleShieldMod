package com.bubbleshield.client.hud;

import com.bubbleshield.client.ClientShieldManager;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.EffectRegistry;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Top-center HUD status shown while the local player stands inside an active bubble
 * shield: the effect name, a 100px health bar tinted with the effect's primary color,
 * and the shield tier (when upgraded with a core).
 *
 * <p>Registered via Fabric's {@code HudElementRegistry.addLast}, so it renders after
 * the vanilla HUD layers and is hidden together with them (F1).
 */
public final class ShieldHudElement implements HudElement {
	private static final int TOP_MARGIN = 4;
	private static final int BAR_WIDTH = 100;
	private static final int BAR_HEIGHT = 5;
	private static final int BAR_BACKGROUND = 0xE0101010;
	private static final int TEXT_COLOR = 0xFFFFFFFF;

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return;
		}

		ClientShieldManager.ClientShield shield = ClientShieldManager.findSurroundingShield(mc);
		if (shield == null) {
			return;
		}

		EffectDefinition def = EffectRegistry.get(shield.effectId());
		int centerX = graphics.guiWidth() / 2;
		int y = TOP_MARGIN;

		// The owner-set custom name takes precedence; unset shields show the effect name.
		Component title = shield.customName().isEmpty()
				? Component.translatable(def.nameKey())
				: Component.literal(shield.customName());
		graphics.centeredText(mc.font, title, centerX, y, TEXT_COLOR);
		y += mc.font.lineHeight + 2;

		// 100px health bar: dark backdrop (1px frame) + primary-colored fill.
		int barX = centerX - BAR_WIDTH / 2;
		int fill = Mth.clamp(Math.round(BAR_WIDTH * shield.healthFrac()), 0, BAR_WIDTH);
		graphics.fill(barX - 1, y - 1, barX + BAR_WIDTH + 1, y + BAR_HEIGHT + 1, BAR_BACKGROUND);
		if (fill > 0) {
			graphics.fill(barX, y, barX + fill, y + BAR_HEIGHT, def.argbPrimary());
		}
		y += BAR_HEIGHT + 3;

		if (shield.tier() > 0) {
			graphics.centeredText(mc.font, Component.translatable("gui.bubbleshield.tier", shield.tier()), centerX, y, TEXT_COLOR);
		}
	}
}

package com.bubbleshield.client.hud;

import com.bubbleshield.client.ClientShieldManager;
import com.bubbleshield.client.mixin.BossHealthOverlayAccessor;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Top-center HUD status shown while the local player stands inside an active bubble
 * shield: the shield tier (when upgraded with a core).
 *
 * <p>The shield's name and health are deliberately NOT drawn here: the server-side
 * boss bar (v3) already shows both — name above a health-progress bar — for exactly
 * the same "inside an active shield" condition, so drawing them again would overlap
 * the vanilla boss-bar stack. Only the tier, which the boss bar does not carry, is
 * rendered, and it is positioned directly below the boss-bar stack (whose rows start
 * at y = 12 and step 19px per bar, per {@code BossHealthOverlay.extractRenderState}).
 *
 * <p>Registered via Fabric's {@code HudElementRegistry.addLast}, so it renders after
 * the vanilla HUD layers and is hidden together with them (F1).
 */
public final class ShieldHudElement implements HudElement {
	/** First boss-bar row: name text at y = 12 - 9, bar at y = 12 (see BossHealthOverlay). */
	private static final int BOSS_BAR_STACK_TOP = 12;
	/** Vertical distance between two boss-bar rows (10px gap + 9px name line). */
	private static final int BOSS_BAR_ROW_STEP = 10 + 9;
	private static final int TEXT_COLOR = 0xFFFFFFFF;

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return;
		}

		ClientShieldManager.ClientShield shield = ClientShieldManager.findSurroundingShield(mc);
		if (shield == null || shield.tier() <= 0) {
			return;
		}

		int centerX = graphics.guiWidth() / 2;
		int y = bossBarStackBottom(mc, graphics.guiHeight());
		graphics.centeredText(mc.font, Component.translatable("gui.bubbleshield.tier", shield.tier()), centerX, y, TEXT_COLOR);
	}

	/**
	 * The y coordinate of the first free row below the vanilla boss-bar stack,
	 * mirroring {@code BossHealthOverlay.extractRenderState}'s layout loop: rows start
	 * at yOffset = 12 (name at yOffset - 9, 5px bar at yOffset), advance 19px per bar
	 * and stop once yOffset passes a third of the screen height. The returned value is
	 * where the NEXT row's name line would start, so the tier text can never collide
	 * with any rendered bar (ours or another boss's). With no bars at all this floors
	 * at the stack top (y = 12) rather than drifting into the very top edge.
	 */
	private static int bossBarStackBottom(Minecraft mc, int guiHeight) {
		int events = ((BossHealthOverlayAccessor) mc.gui.hud.getBossOverlay()).bubbleshield$events().size();
		int yOffset = BOSS_BAR_STACK_TOP;
		for (int i = 0; i < events; i++) {
			yOffset += BOSS_BAR_ROW_STEP;
			if (yOffset >= guiHeight / 3) {
				break;
			}
		}

		return Math.max(yOffset - 9, BOSS_BAR_STACK_TOP);
	}
}

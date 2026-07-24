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
 * shield: the shield tier (when upgraded with a core) and the absolute health as
 * "HP cur/max" (when the sync carried a known max health).
 *
 * <p>The shield's NAME is deliberately NOT drawn here: the server-side boss bar (v3)
 * already shows it for exactly the same "inside an active shield" condition. The
 * boss bar's health is only a fraction bar though, so the absolute HP line (fed by
 * the synced {@code maxHealth}) adds real information rather than duplicating it.
 * Both lines are positioned directly below the boss-bar stack (whose rows start at
 * y = 12 and step 19px per bar, per {@code BossHealthOverlay.extractRenderState}).
 *
 * <p>Registered via Fabric's {@code HudElementRegistry.addLast}, so it renders after
 * the vanilla HUD layers and is hidden together with them (F1).
 */
public final class ShieldHudElement implements HudElement {
	/** First boss-bar row: name text at y = 12 - 9, bar at y = 12 (see BossHealthOverlay). */
	private static final int BOSS_BAR_STACK_TOP = 12;
	/** Vertical distance between two boss-bar rows (10px gap + 9px name line). */
	private static final int BOSS_BAR_ROW_STEP = 10 + 9;
	/** Vertical distance between the HUD's own text lines (9px font + 1px gap). */
	private static final int LINE_STEP = 10;
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

		int centerX = graphics.guiWidth() / 2;
		int y = bossBarStackBottom(mc, graphics.guiHeight());
		if (shield.tier() > 0) {
			graphics.centeredText(mc.font, Component.translatable("gui.bubbleshield.tier", shield.tier()), centerX, y, TEXT_COLOR);
			y += LINE_STEP;
		}

		// Absolute "HP cur/max" from the synced max health; 0 means an old/unknown
		// snapshot, where the boss bar's fraction display remains the only source.
		if (shield.maxHealth() > 0.0F) {
			int max = Math.round(shield.maxHealth());
			int current = Math.round(shield.healthFrac() * shield.maxHealth());
			graphics.centeredText(mc.font, Component.translatable("gui.bubbleshield.hud.health", current, max), centerX, y, TEXT_COLOR);
		}
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

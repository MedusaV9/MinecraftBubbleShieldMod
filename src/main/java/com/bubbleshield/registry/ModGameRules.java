package com.bubbleshield.registry;

import com.bubbleshield.BubbleShield;

import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;

import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;

/**
 * Custom server game rules, registered through the Fabric game-rule API.
 */
public final class ModGameRules {
	public static final int MIN_STRENGTH_PERCENT = 10;
	public static final int MAX_STRENGTH_PERCENT = 500;
	public static final int DEFAULT_STRENGTH_PERCENT = 100;

	/**
	 * Global shield strength percent (10..500, default 100): scales every shield's
	 * max health (see {@code ShieldLogic.maxHealthFor}). Registered as
	 * {@code bubbleshield:strength} — game rule ids are {@code Identifier}s in this
	 * Minecraft version, so the whole id must be lowercase snake_case; set it with
	 * {@code /gamerule bubbleshield:strength <percent>}.
	 */
	public static final GameRule<Integer> STRENGTH = GameRuleBuilder.forInteger(DEFAULT_STRENGTH_PERCENT)
			.range(MIN_STRENGTH_PERCENT, MAX_STRENGTH_PERCENT)
			.category(GameRuleCategory.MISC)
			.buildAndRegister(BubbleShield.id("strength"));

	private ModGameRules() {
	}

	public static void init() {
		// Forces the static registration above to run during mod initialization.
	}
}

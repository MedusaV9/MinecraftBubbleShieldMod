package com.bubbleshield.gametest;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.menu.BubbleShieldMenu;
import com.bubbleshield.registry.ModBlocks;
import com.bubbleshield.registry.ModGameRules;
import com.bubbleshield.registry.ModItems;
import com.bubbleshield.shield.ShieldLogic;
import com.bubbleshield.shield.ShieldMode;
import com.bubbleshield.shield.ShieldState;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;

/**
 * Coverage for the WP3 "strength core" balance overhaul: the size/tier/strength
 * scaled max-health model, the tier damage-resistance pipeline, the 60% shrink
 * plateau, the combat-gated regen model, the per-tier break cooldown table, the
 * diameter-scaled drain units, the {@code bubbleshield:strength} gamerule and the
 * tier-3 aegis core.
 */
public class StrengthGameTests {
	/**
	 * A dedicated (but otherwise vanilla-default) test environment,
	 * {@code data/bubbleshield/test_environment/strength.json}, keeping this class
	 * out of the shared default batch (see ColorGameTests.ISOLATED_ENVIRONMENT for
	 * the full batching rationale).
	 */
	private static final String ISOLATED_ENVIRONMENT = "bubbleshield:strength";
	/**
	 * {@code data/bubbleshield/test_environment/strength_rule.json}: a
	 * {@code minecraft:game_rules} environment setting {@code bubbleshield:strength}
	 * to 200 for its batch (the vanilla runner restores the previous value on
	 * teardown). Game rules are level-global, so mutating one mid-test would leak
	 * into parallel tests; a dedicated environment applies it batch-wide instead.
	 */
	private static final String RULE_ENVIRONMENT = "bubbleshield:strength_rule";
	/**
	 * {@code data/bubbleshield/test_environment/strength_drain.json}: a private
	 * batch for the diameter-160 drain-units test. A bubble that large reaches
	 * several structures over in a shared batch grid (structures sit ~45 blocks
	 * apart) and would intercept other tests' projectiles, so it must run alone
	 * (and deactivate before succeeding).
	 */
	private static final String DRAIN_ENVIRONMENT = "bubbleshield:strength_drain";
	private static final BlockPos PROJECTOR_POS = new BlockPos(4, 2, 4);
	private static final int PLENTY_OF_FUEL = 600;
	private static final float TOLERANCE = 0.01F;

	private static BubbleShieldBlockEntity placeProjector(GameTestHelper helper, float targetRadius) {
		helper.setBlock(PROJECTOR_POS, ModBlocks.BUBBLE_SHIELD_PROJECTOR);
		BubbleShieldBlockEntity be = helper.getBlockEntity(PROJECTOR_POS, BubbleShieldBlockEntity.class);
		be.getShieldState().targetRadius = targetRadius;
		return be;
	}

	/**
	 * (a) The canonical max-health model, pinned at the spec's example points:
	 * {@code clamp(round(BASE_HP[tier] * (0.5 + diameter/64) * strength/100), 50, 8000)}.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void maxHealthModelTable(GameTestHelper helper) {
		helper.assertTrue(ShieldLogic.maxHealthFor(0, 16.0F, 100) == 200.0F, "T0 D32 should be 200");
		helper.assertTrue(ShieldLogic.maxHealthFor(0, 4.0F, 100) == 125.0F, "T0 D8 should be 125");
		helper.assertTrue(ShieldLogic.maxHealthFor(1, 4.0F, 100) == 250.0F, "T1 D8 should be 250");
		helper.assertTrue(ShieldLogic.maxHealthFor(2, 4.0F, 100) == 438.0F, "T2 D8 should round 437.5 up to 438");
		helper.assertTrue(ShieldLogic.maxHealthFor(2, 16.0F, 100) == 700.0F, "T2 D32 should be 700");
		helper.assertTrue(ShieldLogic.maxHealthFor(3, 4.0F, 100) == 750.0F, "T3 D8 should be 750");
		helper.assertTrue(ShieldLogic.maxHealthFor(3, 100.0F, 100) == 4350.0F, "T3 D200 should be 4350");

		// Clamps: the floor at 50 and the ceiling at 8000.
		helper.assertTrue(ShieldLogic.maxHealthFor(0, 4.0F, 10) == 50.0F, "a 10% strength T0 D8 shield should clamp up to 50");
		helper.assertTrue(ShieldLogic.maxHealthFor(3, 100.0F, 500) == 8000.0F, "a 500% strength T3 D200 shield should clamp down to 8000");

		// Hostile tier indexes clamp into the table instead of throwing.
		helper.assertTrue(
				ShieldLogic.maxHealthFor(99, 4.0F, 100) == ShieldLogic.maxHealthFor(3, 4.0F, 100),
				"an out-of-range tier should clamp to the top of the table");
		helper.assertTrue(
				ShieldLogic.maxHealthFor(-1, 4.0F, 100) == ShieldLogic.maxHealthFor(0, 4.0F, 100),
				"a negative tier should clamp to the bottom of the table");
		helper.succeed();
	}

	/**
	 * (b) Max health is size-scaled and recomputes when the diameter changes,
	 * always preserving the health FRACTION: a 50%-damaged diameter-32 shield
	 * resized to diameter 8 lands at 50% of the new max.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 100, padding = 16)
	public void resizePreservesHealthFraction(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 16.0F);
		ShieldState state = be.getShieldState();
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.runAfterDelay(2, () -> {
			helper.assertTrue(state.maxHealth == 200.0F, "tier 0 at diameter 32 should have maxHealth 200, got " + state.maxHealth);
			// 100 raw at tier 0 (no DR) leaves exactly 50%; the hit also opens the
			// combat gate, so tier 0 cannot regenerate between the two checks.
			be.applyShieldDamage(100.0F);
			helper.assertTrue(state.health == 100.0F, "damaged health should be 100, got " + state.health);

			state.targetRadius = 4.0F;
			helper.runAfterDelay(2, () -> {
				helper.assertTrue(state.maxHealth == 125.0F, "resizing to diameter 8 should recompute maxHealth to 125, got " + state.maxHealth);
				helper.assertTrue(state.health == 62.5F, "the 50% health fraction must survive the resize (62.5), got " + state.health);
				helper.assertTrue(
						be.getMenuData().get(BubbleShieldMenu.DATA_MAX_HEALTH) == 125,
						"the DATA_MAX_HEALTH menu slot should mirror the recompute");
				helper.succeed();
			});
		});
	}

	/**
	 * (c) The shrink plateau: at 60%+ health the bubble holds its FULL target
	 * radius; below that it shrinks proportionally ({@code radius * frac/0.6}),
	 * floored at {@link ShieldLogic#MIN_RADIUS}; the ECO cap applies after. Pure.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void shrinkPlateauHoldsRadius(GameTestHelper helper) {
		ShieldState state = new ShieldState();
		state.active = true;
		state.targetRadius = 10.0F;
		state.maxHealth = 100.0F;

		state.health = 100.0F;
		helper.assertTrue(ShieldLogic.currentRadius(state) == 10.0F, "full health should hold the full radius");
		state.health = 70.0F;
		helper.assertTrue(ShieldLogic.currentRadius(state) == 10.0F, "70% health is on the plateau: still full radius");
		state.health = 60.0F;
		helper.assertTrue(ShieldLogic.currentRadius(state) == 10.0F, "exactly 60% health is still on the plateau");
		state.health = 50.0F;
		helper.assertTrue(
				Math.abs(ShieldLogic.currentRadius(state) - 10.0F * (0.5F / 0.6F)) < 0.001F,
				"50% health should shrink to 10 x (0.5/0.6), got " + ShieldLogic.currentRadius(state));
		state.health = 30.0F;
		helper.assertTrue(
				Math.abs(ShieldLogic.currentRadius(state) - 5.0F) < 0.001F,
				"30% health should shrink to half the radius, got " + ShieldLogic.currentRadius(state));
		state.health = 2.0F;
		helper.assertTrue(
				ShieldLogic.currentRadius(state) == ShieldLogic.MIN_RADIUS,
				"very low health should floor at MIN_RADIUS, got " + ShieldLogic.currentRadius(state));

		// ECO caps AFTER the plateau math.
		state.mode = ShieldMode.ECO;
		state.health = 70.0F;
		helper.assertTrue(
				ShieldLogic.currentRadius(state) == 10.0F * ShieldLogic.ECO_RADIUS_FACTOR,
				"ECO at full plateau radius should cap at 7.5, got " + ShieldLogic.currentRadius(state));
		state.health = 50.0F;
		helper.assertTrue(
				Math.abs(ShieldLogic.currentRadius(state) - 10.0F * (0.5F / 0.6F) * ShieldLogic.ECO_RADIUS_FACTOR) < 0.001F,
				"ECO should scale the shrunk radius by 0.75, got " + ShieldLogic.currentRadius(state));

		state.active = false;
		helper.assertTrue(ShieldLogic.currentRadius(state) == 0.0F, "an inactive shield has radius 0");
		helper.succeed();
	}

	/**
	 * (d) The damage table and the single DR pipeline: raw projectile damage is
	 * pinned (arrow 3, trident 4, fireball 10, thrown 1) and {@code appliedDamage}
	 * discounts by tier DR {0, 25%, 40%, 50%}, stacks plating multiplicatively
	 * capped at 70% combined, and halves under last-stand. Pure.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void damageTableAndTierDr(GameTestHelper helper) {
		helper.assertTrue(ShieldLogic.PROJECTILE_DAMAGE == 3.0F, "arrow raw damage should be 3");
		helper.assertTrue(ShieldLogic.TRIDENT_DAMAGE == 4.0F, "trident raw damage should be 4");
		helper.assertTrue(ShieldLogic.HURTING_PROJECTILE_DAMAGE == 10.0F, "fireball/wither-skull/wind-charge raw damage should be 10");
		helper.assertTrue(ShieldLogic.THROWN_DAMAGE == 1.0F, "thrown/shulker-bullet raw damage should be 1");

		// Exact per-arrow deltas by tier: 3.0, 2.25, 1.8, 1.5.
		float[] expectedPerArrow = {3.0F, 2.25F, 1.8F, 1.5F};
		for (int tier = 0; tier <= 3; tier++) {
			float applied = ShieldLogic.appliedDamage(ShieldLogic.PROJECTILE_DAMAGE, tier, 0.0F, false);
			helper.assertTrue(
					Math.abs(applied - expectedPerArrow[tier]) < 0.001F,
					"an arrow into a tier-" + tier + " shield should apply " + expectedPerArrow[tier] + ", got " + applied);
		}

		// A fireball into a tier-3 shield: 10 raw -> 5 applied.
		helper.assertTrue(
				Math.abs(ShieldLogic.appliedDamage(10.0F, 3, 0.0F, false) - 5.0F) < 0.001F,
				"a fireball into a tier-3 shield should apply 5");

		// Plating stacks multiplicatively with tier DR, capped at 70% combined:
		// tier 3 (50%) + plating 50% -> 75% uncapped -> 70% -> 3.0 of 10.
		helper.assertTrue(
				Math.abs(ShieldLogic.appliedDamage(10.0F, 3, 0.5F, false) - 3.0F) < 0.001F,
				"combined DR should cap at 70%");

		// Last-stand halves what remains (wired to false everywhere for now).
		helper.assertTrue(
				Math.abs(ShieldLogic.appliedDamage(10.0F, 0, 0.0F, true) - 5.0F) < 0.001F,
				"last-stand should halve the applied damage");

		// Hostile tier indexes clamp instead of throwing.
		helper.assertTrue(
				ShieldLogic.appliedDamage(3.0F, 99, 0.0F, false) == ShieldLogic.appliedDamage(3.0F, 3, 0.0F, false),
				"an out-of-range tier should clamp to the top of the DR table");
		helper.succeed();
	}

	/**
	 * (d') The DR pipeline end to end: an intercepted arrow costs a tier-3 aegis
	 * shield exactly 1.5 health (3 raw, 50% tier DR) through the live interception
	 * path, not just the pure helper.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void aegisShieldResistsArrow(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 8.0F);
		ShieldState state = be.getShieldState();
		be.getCoreContainer().setItem(0, new ItemStack(ModItems.AEGIS_CORE));
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		// Same spawn geometry as ShieldGameTests.arrowDamagesShield; the first-tick
		// refresh (tier 3 at diameter 16: 1200 x 0.75 = 900) lands well before the
		// arrow reaches the boundary.
		Arrow arrow = helper.spawn(EntityTypes.ARROW, new Vec3(4.5, 14.5, 4.5));
		arrow.setDeltaMovement(0.0, -1.5, 0.0);

		helper.succeedWhen(() -> {
			helper.assertTrue(state.health < state.maxHealth, "the aegis shield should take damage from the intercepted arrow");
			helper.assertTrue(state.maxHealth == 900.0F, "tier 3 at diameter 16 should have maxHealth 900, got " + state.maxHealth);
			helper.assertTrue(
					Math.abs(state.health - 898.5F) <= TOLERANCE,
					"tier 3 DR should cut the 3-damage arrow to exactly 1.5, health is " + state.health);
		});
	}

	/**
	 * (e) The break cooldown table {15, 10, 6, 3} minutes by tier — pure, plus a
	 * live tier-3 break starting exactly the 3-minute (3600-tick) cooldown.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 100, padding = 16)
	public void breakCooldownScalesWithTier(GameTestHelper helper) {
		helper.assertTrue(ShieldLogic.breakCooldownTicks(0) == 18000L, "tier 0 break cooldown should be 15 min (18000 ticks)");
		helper.assertTrue(ShieldLogic.breakCooldownTicks(1) == 12000L, "tier 1 break cooldown should be 10 min (12000 ticks)");
		helper.assertTrue(ShieldLogic.breakCooldownTicks(2) == 7200L, "tier 2 break cooldown should be 6 min (7200 ticks)");
		helper.assertTrue(ShieldLogic.breakCooldownTicks(3) == 3600L, "tier 3 break cooldown should be 3 min (3600 ticks)");
		helper.assertTrue(ShieldLogic.breakCooldownTicks(-1) == 18000L, "a negative tier should clamp to the tier-0 cooldown");
		helper.assertTrue(ShieldLogic.breakCooldownTicks(99) == 3600L, "an out-of-range tier should clamp to the tier-3 cooldown");

		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		be.getCoreContainer().setItem(0, new ItemStack(ModItems.AEGIS_CORE));
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.runAfterDelay(2, () -> {
			helper.assertTrue(state.maxHealth == 750.0F, "tier 3 at diameter 8 should have maxHealth 750, got " + state.maxHealth);
			be.applyShieldDamage(100000.0F);
			helper.assertTrue(!state.active, "overkill damage should break the shield");
			helper.assertTrue(state.health == 750.0F, "health should be restored for the next activation, got " + state.health);
			long remaining = state.cooldownUntil - helper.getLevel().getGameTime();
			helper.assertTrue(
					remaining == 3600L,
					"a tier-3 break should start exactly the 3600-tick cooldown, got " + remaining);
			helper.succeed();
		});
	}

	/**
	 * (f) The tier-0 combat gate: no regen pulse at all while the shield was hit
	 * less than 200 ticks ago, then the normal 1.0-per-pulse rate resumes (tier 0
	 * gets no out-of-combat x3 — 1.0 already IS its out-of-combat rate).
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 400, padding = 16)
	public void tierZeroCombatGatedRegen(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.startSequence()
				.thenExecuteAfter(2, () -> {
					helper.assertTrue(state.maxHealth == 125.0F, "tier 0 at diameter 8 should have maxHealth 125, got " + state.maxHealth);
					be.applyShieldDamage(30.0F);
					helper.assertTrue(state.health == 95.0F, "damaged health should be 95, got " + state.health);
				})
				// 190 ticks cover four 40-tick pulse boundaries, all inside the
				// 200-tick combat gate: tier 0 must not heal a single point.
				.thenExecuteAfter(190, () -> helper.assertTrue(
						state.health == 95.0F,
						"tier 0 must not regenerate while in combat, health is " + state.health))
				.thenWaitUntil(() -> helper.assertTrue(
						state.health > 95.0F, "tier 0 should regenerate once the combat gate expires"))
				.thenExecute(() -> helper.assertTrue(
						Math.abs(state.health - 96.0F) <= TOLERANCE,
						"the first out-of-combat tier-0 pulse should heal exactly 1.0, health is " + state.health))
				.thenSucceed();
	}

	/**
	 * (g) Tiers 1-3 pulse at their base rate in combat and x3 out of combat:
	 * a tier-1 shield heals 3.0 per pulse right after being hit and 9.0 per pulse
	 * once the 200-tick combat gate has expired.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 400, padding = 16)
	public void tierOneOutOfCombatRegenTriples(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		be.getCoreContainer().setItem(0, new ItemStack(ModItems.RESONANT_CORE));
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		float[] before = new float[1];
		helper.startSequence()
				.thenExecuteAfter(2, () -> {
					helper.assertTrue(state.maxHealth == 250.0F, "tier 1 at diameter 8 should have maxHealth 250, got " + state.maxHealth);
					// 60 raw is 45 after tier 1's 25% DR; the hit opens the combat gate.
					be.applyShieldDamage(60.0F);
					helper.assertTrue(state.health == 205.0F, "damaged health should be 205, got " + state.health);
					before[0] = state.health;
				})
				// The poll runs every tick and pulses are 40 apart: the first passing
				// check observes exactly ONE (in-combat) pulse.
				.thenWaitUntil(() -> helper.assertTrue(
						state.health > before[0], "tier 1 should keep regenerating in combat"))
				.thenExecute(() -> {
					float healed = state.health - before[0];
					helper.assertTrue(
							Math.abs(healed - 3.0F) <= TOLERANCE,
							"an in-combat tier-1 pulse should heal exactly 3.0, got " + healed);
				})
				// Wait out the combat gate, snapshot BETWEEN pulses, then observe the
				// next (now out-of-combat) pulse healing exactly 3.0 x 3.
				.thenWaitUntil(() -> helper.assertTrue(
						!ShieldLogic.inCombat(state, helper.getLevel().getGameTime()),
						"waiting for the combat gate to expire"))
				.thenExecute(() -> before[0] = state.health)
				.thenWaitUntil(() -> helper.assertTrue(
						state.health > before[0], "tier 1 should regenerate out of combat"))
				.thenExecute(() -> {
					float healed = state.health - before[0];
					helper.assertTrue(
							Math.abs(healed - 9.0F) <= TOLERANCE,
							"an out-of-combat tier-1 pulse should heal 3.0 x 3 = 9.0, got " + healed);
				})
				.thenSucceed();
	}

	/**
	 * (h) Drain units scale with the diameter — {@code max(1, round(diameter/50))}
	 * pure at the thresholds, plus a live diameter-160 bubble burning exactly 3
	 * fuel-seconds per 20-tick drain event (and the GUI drain slot reporting it).
	 * Runs alone in {@link #DRAIN_ENVIRONMENT} and deactivates before success.
	 */
	@GameTest(environment = DRAIN_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void drainUnitsScaleWithDiameter(GameTestHelper helper) {
		helper.assertTrue(ShieldLogic.drainUnits(4.0F) == 1, "diameter 8 should burn 1 fuel-second per drain");
		helper.assertTrue(ShieldLogic.drainUnits(37.0F) == 1, "diameter 74 should burn 1 (round(1.48))");
		helper.assertTrue(ShieldLogic.drainUnits(37.5F) == 2, "diameter 75 should burn 2 (round(1.5))");
		helper.assertTrue(ShieldLogic.drainUnits(62.0F) == 2, "diameter 124 should burn 2 (round(2.48))");
		helper.assertTrue(ShieldLogic.drainUnits(62.5F) == 3, "diameter 125 should burn 3 (round(2.5))");
		helper.assertTrue(ShieldLogic.drainUnits(87.0F) == 3, "diameter 174 should burn 3 (round(3.48))");
		helper.assertTrue(ShieldLogic.drainUnits(87.5F) == 4, "diameter 175 should burn 4 (round(3.5))");
		helper.assertTrue(ShieldLogic.drainUnits(100.0F) == 4, "diameter 200 should burn 4 (round(4.0))");

		BubbleShieldBlockEntity be = placeProjector(helper, 80.0F);
		ShieldState state = be.getShieldState();
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "the diameter-160 shield should activate");

		int fuelStart = state.fuelSeconds;
		helper.onEachTick(() -> {
			if (state.fuelSeconds == fuelStart) {
				return; // waiting for the first 20-tick drain event
			}

			// The GUI baseline while active: 3 units per 20 ticks = 9/min -> x10 = 1800.
			int drainSlot = be.getMenuData().get(BubbleShieldMenu.DATA_DRAIN_PER_MIN_X10);
			int burned = fuelStart - state.fuelSeconds;
			// Deactivate FIRST: a diameter-160 bubble must never outlive its test.
			be.setActive(false);
			helper.assertTrue(
					burned == 3,
					"a diameter-160 drain event should burn exactly 3 fuel-seconds, burned " + burned);
			helper.assertTrue(
					drainSlot == 1800,
					"DATA_DRAIN_PER_MIN_X10 should report 3 units per 20 ticks as 1800, got " + drainSlot);
			helper.succeed();
		});
	}

	/**
	 * (i) The {@code bubbleshield:strength} gamerule feeds the max-health model:
	 * in the {@link #RULE_ENVIRONMENT} batch (rule set to 200 by the environment
	 * definition, restored on teardown) a tier-0 diameter-32 shield doubles to 400,
	 * and the DATA_STRENGTH_PERCENT menu slot mirrors the rule.
	 */
	@GameTest(environment = RULE_ENVIRONMENT, maxTicks = 100, padding = 16)
	public void strengthGameruleScalesMaxHealth(GameTestHelper helper) {
		helper.assertTrue(
				helper.getLevel().getGameRules().get(ModGameRules.STRENGTH) == 200,
				"the strength_rule environment should set bubbleshield:strength to 200");
		helper.assertTrue(ShieldLogic.maxHealthFor(0, 16.0F, 200) == 400.0F, "T0 D32 at 200% should be 400");

		BubbleShieldBlockEntity be = placeProjector(helper, 16.0F);
		ShieldState state = be.getShieldState();
		helper.runAfterDelay(2, () -> {
			helper.assertTrue(
					state.maxHealth == 400.0F,
					"at strength 200% a tier-0 diameter-32 shield should have maxHealth 400, got " + state.maxHealth);
			helper.assertTrue(state.health == 400.0F, "the full-health fraction should scale along, got " + state.health);
			helper.assertTrue(
					be.getMenuData().get(BubbleShieldMenu.DATA_STRENGTH_PERCENT) == 200,
					"DATA_STRENGTH_PERCENT should mirror the gamerule");
			helper.succeed();
		});
	}

	/**
	 * (j) The aegis core: its shaped recipe ships as authored (ENE/NCN/ENE from
	 * echo shards, netherite ingots and a prismatic core) and the projector menu
	 * accepts it in the core slot (mayPlace + quickMove routing), deriving tier 3.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, padding = 16)
	public void aegisRecipeAndCoreSlotAcceptance(GameTestHelper helper) {
		JsonObject recipe = readJsonResource(helper, "/data/bubbleshield/recipe/aegis_core.json");
		helper.assertTrue(
				"minecraft:crafting_shaped".equals(recipe.get("type").getAsString()),
				"the aegis recipe should be minecraft:crafting_shaped");
		helper.assertTrue(
				"bubbleshield:aegis_core".equals(recipe.getAsJsonObject("result").get("id").getAsString()),
				"the aegis recipe should produce bubbleshield:aegis_core");
		JsonObject key = recipe.getAsJsonObject("key");
		helper.assertTrue("minecraft:echo_shard".equals(key.get("E").getAsString()), "key E should be echo shards");
		helper.assertTrue("minecraft:netherite_ingot".equals(key.get("N").getAsString()), "key N should be netherite ingots");
		helper.assertTrue("bubbleshield:prismatic_core".equals(key.get("C").getAsString()), "key C should be the prismatic core");
		JsonArray pattern = recipe.getAsJsonArray("pattern");
		helper.assertTrue(
				pattern != null && pattern.size() == 3
						&& "ENE".equals(pattern.get(0).getAsString())
						&& "NCN".equals(pattern.get(1).getAsString())
						&& "ENE".equals(pattern.get(2).getAsString()),
				"the aegis recipe pattern should be ENE/NCN/ENE");

		// Menu acceptance end to end, mirroring CapacitorGameTests.quickMoveRoutesCapacitor.
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ServerPlayer player = MockPlayers.createUniqueMockPlayer(helper);
		try {
			Vec3 center = Vec3.atCenterOf(helper.absolutePos(PROJECTOR_POS));
			player.snapTo(center.x + 1.5, center.y - 0.5, center.z);
			helper.useBlock(PROJECTOR_POS, player);
			helper.assertTrue(player.containerMenu instanceof BubbleShieldMenu, "using the projector should open a BubbleShieldMenu");
			BubbleShieldMenu menu = (BubbleShieldMenu) player.containerMenu;
			helper.assertTrue(
					menu.getSlot(BubbleShieldMenu.CORE_SLOT).mayPlace(new ItemStack(ModItems.AEGIS_CORE)),
					"the core slot should accept an aegis core");

			// Hotbar slots 0..2 map to menu slots 31..33 (4 projector slots + 27 inventory).
			int hotbarStart = 4 + 27;
			player.getInventory().setItem(0, new ItemStack(ModItems.AEGIS_CORE));
			menu.quickMoveStack(player, hotbarStart);
			helper.assertTrue(
					menu.getSlot(BubbleShieldMenu.CORE_SLOT).getItem().is(ModItems.AEGIS_CORE),
					"quickMove should route the aegis core into the core slot");
			helper.assertTrue(be.tier() == 3, "an installed aegis core should derive tier 3");
		} finally {
			MockPlayers.removeMockPlayer(helper, player);
		}

		helper.succeed();
	}

	/**
	 * (k) Migration: a legacy save from the flat {@code 100 * (1 + tier)} model
	 * loads verbatim, then the first tick recomputes the canonical max health and
	 * maps the OLD health fraction onto it (50/100 -> 100/200), never the absolute
	 * value.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 100, padding = 16)
	public void legacySaveMigratesByFraction(GameTestHelper helper) {
		helper.setBlock(PROJECTOR_POS, ModBlocks.BUBBLE_SHIELD_PROJECTOR);
		BubbleShieldBlockEntity be = helper.getBlockEntity(PROJECTOR_POS, BubbleShieldBlockEntity.class);
		ShieldState state = be.getShieldState();

		var registries = helper.getLevel().registryAccess();
		CompoundTag tag = be.saveCustomOnly(registries);
		tag.putFloat("max_health", 100.0F);
		tag.putFloat("health", 50.0F);
		be.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
		helper.assertTrue(
				state.maxHealth == 100.0F && state.health == 50.0F,
				"the legacy health values should load verbatim before the first tick");

		helper.runAfterDelay(2, () -> {
			helper.assertTrue(
					state.maxHealth == 200.0F,
					"the first tick must recompute the legacy max_health to the tier-0 diameter-32 value 200, got " + state.maxHealth);
			helper.assertTrue(
					state.health == 100.0F,
					"the legacy 50% fraction must map onto the new max (100/200), got " + state.health);
			helper.succeed();
		});
	}

	private static JsonObject readJsonResource(GameTestHelper helper, String path) {
		try (InputStream in = StrengthGameTests.class.getResourceAsStream(path)) {
			helper.assertTrue(in != null, "missing data pack resource: " + path);
			return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (Exception e) {
			throw helper.assertionException("failed to read/parse " + path + ": " + e);
		}
	}
}

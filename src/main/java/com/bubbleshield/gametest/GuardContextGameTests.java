package com.bubbleshield.gametest;

import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.effect.ContextModifier;
import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.ContextProfile;
import com.bubbleshield.effect.EffectRegistry;
import com.bubbleshield.effect.GuardStyle;
import com.bubbleshield.registry.ModBlocks;
import com.bubbleshield.shield.ShieldLogic;
import com.bubbleshield.shield.ShieldState;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.phys.Vec3;

/**
 * Coverage for milestone M3: guard styles retaliating against expelled players and
 * context profiles modulating the inside-behavior tick.
 */
public class GuardContextGameTests {
	private static final BlockPos PROJECTOR_POS = new BlockPos(4, 2, 4);
	private static final int PLENTY_OF_FUEL = 600;

	private static BubbleShieldBlockEntity placeProjector(GameTestHelper helper, float targetRadius) {
		helper.setBlock(PROJECTOR_POS, ModBlocks.BUBBLE_SHIELD_PROJECTOR);
		BubbleShieldBlockEntity be = helper.getBlockEntity(PROJECTOR_POS, BubbleShieldBlockEntity.class);
		be.getShieldState().targetRadius = targetRadius;
		return be;
	}

	@GameTest(padding = 16)
	public void guardStyleApplies(GameTestHelper helper) {
		helper.assertTrue(EffectRegistry.get(21).guard() == GuardStyle.SLOW, "effect 21 should have guard style SLOW");
		helper.assertTrue(EffectRegistry.get(8).guard() == GuardStyle.DARK, "effect 8 should have guard style DARK");

		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);

		assertGuardApplies(helper, be, 21, MobEffects.SLOWNESS, "Slowness (guard SLOW)");
		be.setActive(false);
		assertGuardApplies(helper, be, 8, MobEffects.DARKNESS, "Darkness (guard DARK)");
		helper.succeed();
	}

	/**
	 * Activates the shield with the given effect, drops a blocked mock player inside the
	 * boundary and runs the ServerLevel-aware barrier pass ({@code expelBlockedPlayers},
	 * which is also what {@code serverTick} mirrors): the player must be pushed out AND
	 * be retaliated against with the effect's guard-style mob effect.
	 */
	private static void assertGuardApplies(GameTestHelper helper, BubbleShieldBlockEntity be, int effectId, Holder<MobEffect> guardEffect, String label) {
		be.getShieldState().effectId = effectId;
		helper.assertTrue(be.tryActivate(), "shield should activate for effect " + effectId);

		ShieldState state = be.getShieldState();
		Vec3 center = Vec3.atCenterOf(helper.absolutePos(PROJECTOR_POS));
		double radius = be.currentRadius();
		Vec3 outside = center.add(radius + 1.5, 0.0, 0.0);
		Vec3 inside = center.add(radius - 0.5, 0.0, 0.0);

		// An in-level mock ServerPlayer (same pattern as whitelistBlocksAndAdmits, but
		// registered in the level so the barrier's getEntitiesOfClass query finds it).
		// The uniquely-named mock player is not whitelisted.
		ServerPlayer stranger = MockPlayers.createUniqueMockPlayer(helper);
		try {
			stranger.snapTo(inside.x, inside.y, inside.z);
			stranger.xo = outside.x;
			stranger.yo = outside.y;
			stranger.zo = outside.z;

			helper.assertTrue(
					ShieldLogic.expelBlockedPlayers(helper.getLevel(), helper.absolutePos(PROJECTOR_POS), state),
					"the barrier pass should push the stranger crossing into effect " + effectId);
			helper.assertTrue(
					stranger.position().distanceTo(center) > radius,
					"the stranger should end up outside the shield of effect " + effectId);
			helper.assertTrue(
					stranger.hasEffect(guardEffect),
					"the expelled player should have " + label + " after effect " + effectId + " retaliated");
		} finally {
			MockPlayers.removeMockPlayer(helper, stranger);
		}
	}

	@GameTest
	public void contextModifierPure(GameTestHelper helper) {
		ContextState neutral = new ContextState(1.0F, 1, false, false);

		// NONE: always neutral, regardless of inputs.
		helper.assertTrue(neutral.equals(ContextModifier.compute(ContextProfile.NONE, true, true, 5, 0.1F)), "NONE should always be neutral");

		// NIGHT_BLOOM: doubled particle counts at night only.
		helper.assertTrue(
				ContextModifier.compute(ContextProfile.NIGHT_BLOOM, true, false, 0, 1.0F).countMult() == 2.0F,
				"NIGHT_BLOOM should double counts at night");
		helper.assertTrue(
				neutral.equals(ContextModifier.compute(ContextProfile.NIGHT_BLOOM, false, false, 0, 1.0F)),
				"NIGHT_BLOOM should be neutral by day");

		// STORM_CHARGED: extra sparks while raining only.
		helper.assertTrue(
				ContextModifier.compute(ContextProfile.STORM_CHARGED, false, true, 0, 1.0F).extraSparks(),
				"STORM_CHARGED should add sparks in the rain");
		helper.assertTrue(
				neutral.equals(ContextModifier.compute(ContextProfile.STORM_CHARGED, false, false, 0, 1.0F)),
				"STORM_CHARGED should be neutral in clear weather");

		// CROWD_SCALE: 1 + 0.5 per player inside, capped at x3.
		helper.assertTrue(
				ContextModifier.compute(ContextProfile.CROWD_SCALE, false, false, 0, 1.0F).countMult() == 1.0F,
				"CROWD_SCALE with an empty bubble should be x1");
		helper.assertTrue(
				ContextModifier.compute(ContextProfile.CROWD_SCALE, false, false, 2, 1.0F).countMult() == 2.0F,
				"CROWD_SCALE with 2 players should be x2");
		helper.assertTrue(
				ContextModifier.compute(ContextProfile.CROWD_SCALE, false, false, 10, 1.0F).countMult() == 3.0F,
				"CROWD_SCALE should cap at x3");

		// LOW_HEALTH_FRENZY: halved throttle period below half health.
		ContextState frenzy = ContextModifier.compute(ContextProfile.LOW_HEALTH_FRENZY, false, false, 0, 0.4F);
		helper.assertTrue(frenzy.periodDivisor() == 2, "LOW_HEALTH_FRENZY should halve the period below half health");
		helper.assertTrue(frenzy.effectiveThrottle(10L) == 5L, "divisor 2 should turn the %10 throttle into %5");
		helper.assertTrue(frenzy.effectiveThrottle(1L) == 1L, "the effective throttle must never reach 0");
		helper.assertTrue(
				neutral.equals(ContextModifier.compute(ContextProfile.LOW_HEALTH_FRENZY, false, false, 0, 0.9F)),
				"LOW_HEALTH_FRENZY should be neutral at high health");

		// HEALTH_HUE: secondary color below half health.
		helper.assertTrue(
				ContextModifier.compute(ContextProfile.HEALTH_HUE, false, false, 0, 0.4F).useSecondaryColor(),
				"HEALTH_HUE should switch to the secondary color below half health");
		helper.assertTrue(
				neutral.equals(ContextModifier.compute(ContextProfile.HEALTH_HUE, false, false, 0, 0.6F)),
				"HEALTH_HUE should be neutral at high health");

		// periodDivisor can never be 0 (canonical constructor floors it at 1).
		helper.assertTrue(new ContextState(1.0F, 0, false, false).periodDivisor() == 1, "a zero periodDivisor must be floored to 1");
		helper.succeed();
	}

	@GameTest(maxTicks = 200, padding = 16)
	public void stormChargedIntegration(GameTestHelper helper) {
		helper.assertTrue(EffectRegistry.get(55).context() == ContextProfile.STORM_CHARGED, "effect 55 should have context STORM_CHARGED");

		WeatherData weather = helper.getLevel().getServer().getWeatherData();
		boolean wasRaining = weather.isRaining();
		int previousRainTime = weather.getRainTime();

		BubbleShieldBlockEntity be = placeProjector(helper, 6.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		be.getShieldState().effectId = 55;

		weather.setRaining(true);
		weather.setRainTime(6000);
		try {
			helper.assertTrue(be.tryActivate(), "shield should activate");
		} catch (Throwable t) {
			weather.setRaining(wasRaining);
			weather.setRainTime(previousRainTime);
			throw t;
		}

		// 30 ticks let the level's rain level ramp past the isRaining() threshold, so
		// the STORM_CHARGED extra-sparks path runs. Reaching the delayed check without
		// an exception (and with the shield still up) proves it ran cleanly.
		helper.runAfterDelay(30, () -> {
			try {
				helper.assertTrue(be.getShieldState().active, "a storm-charged shield should stay active in the rain");
				helper.succeed();
			} finally {
				weather.setRaining(wasRaining);
				weather.setRainTime(previousRainTime);
			}
		});
	}
}

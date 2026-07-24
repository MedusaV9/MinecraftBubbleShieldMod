package com.bubbleshield.gametest;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.menu.BubbleShieldMenu;
import com.bubbleshield.registry.ModBlocks;
import com.bubbleshield.registry.ModItems;
import com.bubbleshield.shield.ShieldLogic;
import com.bubbleshield.shield.ShieldState;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * Coverage for WP4 "Recovery &amp; repair": the A7 emergency revive (fuel-paid
 * break-cooldown skip) and the C3 patch kit (heal an active shield / shorten a
 * break cooldown), plus the patch kit's recipe and registration.
 */
public class RecoveryGameTests {
	/**
	 * A dedicated (but otherwise vanilla-default) test environment,
	 * {@code data/bubbleshield/test_environment/recovery.json}, keeping this class
	 * out of the shared default batch (see ColorGameTests.ISOLATED_ENVIRONMENT for
	 * the full batching rationale).
	 */
	private static final String ISOLATED_ENVIRONMENT = "bubbleshield:recovery";
	private static final BlockPos PROJECTOR_POS = new BlockPos(4, 2, 4);
	private static final int PLENTY_OF_FUEL = 1000;

	private static BubbleShieldBlockEntity placeProjector(GameTestHelper helper, float targetRadius) {
		helper.setBlock(PROJECTOR_POS, ModBlocks.BUBBLE_SHIELD_PROJECTOR);
		BubbleShieldBlockEntity be = helper.getBlockEntity(PROJECTOR_POS, BubbleShieldBlockEntity.class);
		be.getShieldState().targetRadius = targetRadius;
		return be;
	}

	/** Parks the mock next to the projector (menu stillValid range) and hands it {@code count} patch kits. */
	private static void armWithPatchKits(GameTestHelper helper, ServerPlayer player, int count) {
		Vec3 center = Vec3.atCenterOf(helper.absolutePos(PROJECTOR_POS));
		player.snapTo(center.x + 1.5, center.y - 0.5, center.z);
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.PATCH_KIT, count));
	}

	/**
	 * (a) The A7 emergency revive: with a running break cooldown and at least the
	 * tier-scaled fee (tier 0: 400) stored, the OWNER's revive bypasses the
	 * cooldown, charges exactly the fee and reactivates the shield at 50% of its
	 * max health. Fix 3a: the cooldown WINDOW keeps running in the background
	 * (cooldownUntil is bypassed for the activation, not cleared) and the
	 * once-per-window flag is set.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 100, padding = 16)
	public void reviveSkipsCooldownForFuel(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		ServerPlayer owner = MockPlayers.createUniqueMockPlayer(helper);
		be.setOwner(owner);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.runAfterDelay(2, () -> {
			helper.assertTrue(state.maxHealth == 125.0F, "tier 0 at diameter 8 should have maxHealth 125, got " + state.maxHealth);
			be.applyShieldDamage(100000.0F);
			long gameTime = helper.getLevel().getGameTime();
			helper.assertTrue(!state.active && state.cooldownUntil > gameTime, "overkill damage should break the shield into a cooldown");
			helper.assertTrue(!be.tryActivate(), "the plain activation must still refuse during the cooldown");
			int fuelBefore = state.fuelSeconds;
			helper.assertTrue(fuelBefore >= ShieldLogic.reviveFuelCost(0), "the test setup should leave at least the revive fee stored");

			helper.assertTrue(be.tryEmergencyRevive(owner), "the owner's revive should succeed with the fee affordable");
			helper.assertTrue(state.active, "the revived shield should be active");
			helper.assertTrue(
					state.fuelSeconds == fuelBefore - ShieldLogic.reviveFuelCost(0),
					"the tier-0 revive should charge exactly 400 fuel-seconds, fuel went " + fuelBefore + " -> " + state.fuelSeconds);
			helper.assertTrue(state.cooldownUntil > gameTime,
					"fix 3a: the cooldown window must keep running in the background across the revive");
			helper.assertTrue(state.revivedThisCooldown, "the revive must set the once-per-window flag");
			helper.assertTrue(
					state.health == 0.5F * state.maxHealth,
					"the revived shield should restart at 50% of max health (62.5), got " + state.health);
			helper.succeed();
		});
	}

	/**
	 * (b) The revive is refused when fewer fuel-seconds than the tier-scaled fee
	 * (tier 0: 400) are stored: the shield stays broken, the cooldown keeps
	 * running and no fuel is charged.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 100, padding = 16)
	public void reviveRefusedWhenFuelBelowCost(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		ServerPlayer owner = MockPlayers.createUniqueMockPlayer(helper);
		be.setOwner(owner);
		be.addFuelSeconds(ShieldLogic.reviveFuelCost(0) - 1);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.runAfterDelay(2, () -> {
			be.applyShieldDamage(100000.0F);
			long gameTime = helper.getLevel().getGameTime();
			int fuelBefore = state.fuelSeconds;
			helper.assertTrue(fuelBefore == ShieldLogic.reviveFuelCost(0) - 1, "the setup should leave 399 fuel-seconds, got " + fuelBefore);

			helper.assertTrue(!be.tryEmergencyRevive(owner), "the revive must refuse below the 400 fuel-second fee");
			helper.assertTrue(!state.active, "the shield must stay inactive after the refused revive");
			helper.assertTrue(state.cooldownUntil > gameTime, "the cooldown must keep running after the refused revive");
			helper.assertTrue(state.fuelSeconds == fuelBefore, "a refused revive must not charge any fuel");
			helper.succeed();
		});
	}

	/**
	 * (b2, fix 3b) The revive fee scales with the shield tier: the pure table is
	 * {@code 400 + 200 * tier} (400/600/800/1000, out-of-range tiers clamped),
	 * and a live tier-3 revive charges exactly 1000 fuel-seconds.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 100, padding = 16)
	public void reviveCostScalesWithTier(GameTestHelper helper) {
		helper.assertTrue(ShieldLogic.reviveFuelCost(0) == 400, "tier 0 revive should cost 400");
		helper.assertTrue(ShieldLogic.reviveFuelCost(1) == 600, "tier 1 revive should cost 600");
		helper.assertTrue(ShieldLogic.reviveFuelCost(2) == 800, "tier 2 revive should cost 800");
		helper.assertTrue(ShieldLogic.reviveFuelCost(3) == 1000, "tier 3 revive should cost 1000");
		helper.assertTrue(ShieldLogic.reviveFuelCost(-1) == 400 && ShieldLogic.reviveFuelCost(99) == 1000,
				"out-of-range tiers should clamp into the 400..1000 fee table");

		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		ServerPlayer owner = MockPlayers.createUniqueMockPlayer(helper);
		be.setOwner(owner);
		be.getCoreContainer().setItem(0, new ItemStack(ModItems.AEGIS_CORE));
		be.addFuelSeconds(3000);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.runAfterDelay(2, () -> {
			be.applyShieldDamage(1000000.0F);
			helper.assertTrue(!state.active, "overkill damage should break the tier-3 shield");
			int fuelBefore = state.fuelSeconds;

			helper.assertTrue(be.tryEmergencyRevive(owner), "the tier-3 revive should succeed with 3000 fuel stored");
			helper.assertTrue(state.fuelSeconds == fuelBefore - 1000,
					"the tier-3 revive should charge exactly 1000 fuel-seconds, fuel went " + fuelBefore + " -> " + state.fuelSeconds);
			helper.succeed();
		});
	}

	/**
	 * (b3, fix 3a) At most ONE revive per cooldown window: after a successful
	 * revive the window keeps running in the background, and even a deliberate
	 * deactivation inside it cannot buy a second revive — the server refuses and
	 * charges nothing, and the menu's {@code DATA_REVIVE_AVAILABLE} slot mirrors
	 * the refusal for the GUI's Revive button face.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 100, padding = 16)
	public void reviveOncePerCooldownWindow(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		ServerPlayer owner = MockPlayers.createUniqueMockPlayer(helper);
		be.setOwner(owner);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.runAfterDelay(2, () -> {
			be.applyShieldDamage(100000.0F);
			long gameTime = helper.getLevel().getGameTime();
			helper.assertTrue(be.getMenuData().get(BubbleShieldMenu.DATA_REVIVE_AVAILABLE) == 1,
					"a fresh break window should report the revive as available");
			helper.assertTrue(be.tryEmergencyRevive(owner), "the first revive in the window should succeed");
			helper.assertTrue(be.getMenuData().get(BubbleShieldMenu.DATA_REVIVE_AVAILABLE) == 0,
					"an active (revived) shield must not report the revive as available");

			// Deliberately power down INSIDE the still-running window: the flag
			// (not the active state) must block the second revive.
			be.setActive(false);
			helper.assertTrue(!state.active && state.cooldownUntil > gameTime,
					"the setup should leave an inactive shield inside the original window");
			int fuelBefore = state.fuelSeconds;
			helper.assertTrue(be.getMenuData().get(BubbleShieldMenu.DATA_REVIVE_AVAILABLE) == 0,
					"a spent window must not report the revive as available");
			helper.assertTrue(!be.tryEmergencyRevive(owner), "the second revive in the same window must be refused");
			helper.assertTrue(state.fuelSeconds == fuelBefore, "the refused second revive must not charge any fuel");
			helper.assertTrue(!state.active, "the refused second revive must not activate the shield");
			helper.succeed();
		});
	}

	/**
	 * (b4, fix 3c) The revive is refused while fewer than
	 * {@link ShieldLogic#MIN_REVIVE_COOLDOWN_TICKS} (200) cooldown ticks remain —
	 * paying hundreds of fuel-seconds to skip under 10 s is a rounding trap, not
	 * a rescue — and accepted at exactly the threshold.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 100, padding = 16)
	public void reviveRefusedForTinyRemainder(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		ServerPlayer owner = MockPlayers.createUniqueMockPlayer(helper);
		be.setOwner(owner);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.runAfterDelay(2, () -> {
			be.applyShieldDamage(100000.0F);
			long gameTime = helper.getLevel().getGameTime();
			int fuelBefore = state.fuelSeconds;

			state.cooldownUntil = gameTime + ShieldLogic.MIN_REVIVE_COOLDOWN_TICKS - 1;
			helper.assertTrue(be.getMenuData().get(BubbleShieldMenu.DATA_REVIVE_AVAILABLE) == 0,
					"a 199-tick remainder must not report the revive as available");
			helper.assertTrue(!be.tryEmergencyRevive(owner), "a 199-tick remainder must refuse the revive");
			helper.assertTrue(state.fuelSeconds == fuelBefore && !state.active,
					"the tiny-remainder refusal must not charge fuel or activate");

			state.cooldownUntil = gameTime + ShieldLogic.MIN_REVIVE_COOLDOWN_TICKS;
			helper.assertTrue(be.tryEmergencyRevive(owner), "a 200-tick remainder should accept the revive");
			helper.assertTrue(state.fuelSeconds == fuelBefore - ShieldLogic.reviveFuelCost(0),
					"the accepted revive should charge the tier-0 fee");
			helper.succeed();
		});
	}

	/**
	 * (b5, fix 3d) Live fuel top-ups clamp the stored total to the same
	 * {@link ShieldState#MAX_LOADED_FUEL_SECONDS} (100000) cap the NBT load
	 * enforces, so repeated additions can never park an overflow-prone total.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, padding = 16)
	public void liveFuelTopUpsClampToCap(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();

		be.addFuelSeconds(ShieldState.MAX_LOADED_FUEL_SECONDS - 1);
		helper.assertTrue(state.fuelSeconds == ShieldState.MAX_LOADED_FUEL_SECONDS - 1,
				"a below-cap top-up should store unclamped, got " + state.fuelSeconds);

		be.addFuelSeconds(50000);
		helper.assertTrue(state.fuelSeconds == ShieldState.MAX_LOADED_FUEL_SECONDS,
				"an over-cap top-up must clamp to 100000, got " + state.fuelSeconds);

		be.addFuelSeconds(Integer.MAX_VALUE);
		helper.assertTrue(state.fuelSeconds == ShieldState.MAX_LOADED_FUEL_SECONDS,
				"an Integer.MAX_VALUE top-up must not overflow past the cap, got " + state.fuelSeconds);
		helper.succeed();
	}

	/**
	 * (c) The revive is owner-gated exactly like the other mutating requests: a
	 * non-owner is refused (no state change), then the owner succeeds.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 100, padding = 16)
	public void reviveOwnerGated(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		ServerPlayer owner = MockPlayers.createUniqueMockPlayer(helper);
		ServerPlayer stranger = MockPlayers.createUniqueMockPlayer(helper);
		be.setOwner(owner);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.runAfterDelay(2, () -> {
			be.applyShieldDamage(100000.0F);
			long gameTime = helper.getLevel().getGameTime();
			int fuelBefore = state.fuelSeconds;

			helper.assertTrue(!be.tryEmergencyRevive(stranger), "a non-owner's revive must be refused");
			helper.assertTrue(!state.active && state.cooldownUntil > gameTime && state.fuelSeconds == fuelBefore,
					"a refused non-owner revive must not change any state");

			helper.assertTrue(be.tryEmergencyRevive(owner), "the owner's revive should succeed after the stranger's refusal");
			helper.assertTrue(state.active, "the shield should be active after the owner's revive");
			helper.succeed();
		});
	}

	/**
	 * (d) The revive only bypasses the COOLDOWN failure: without a running
	 * cooldown (inactive-with-fuel, or already active) it refuses and charges
	 * nothing — plain activation stays the only path there.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, padding = 16)
	public void reviveRequiresRunningCooldown(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		ServerPlayer owner = MockPlayers.createUniqueMockPlayer(helper);
		be.setOwner(owner);
		be.addFuelSeconds(PLENTY_OF_FUEL);

		helper.assertTrue(!be.tryEmergencyRevive(owner), "an inactive shield without a cooldown has nothing to revive");
		helper.assertTrue(state.fuelSeconds == PLENTY_OF_FUEL && !state.active, "the refused revive must not touch fuel or activate");

		helper.assertTrue(be.tryActivate(), "the plain activation should succeed instead");
		helper.assertTrue(!be.tryEmergencyRevive(owner), "an already-active shield must refuse the revive");
		helper.assertTrue(state.fuelSeconds == PLENTY_OF_FUEL, "the refused revive on an active shield must not charge fuel");
		helper.succeed();
	}

	/**
	 * (e) The patch kit on an ACTIVE shield: the first use restores exactly 150 HP
	 * and consumes one kit; the second use caps at max health (healing the last 10)
	 * and is still consumed because it healed at least 1 HP.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 100, padding = 16)
	public void patchKitHealsAndCapsAtMax(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 16.0F);
		ShieldState state = be.getShieldState();
		// SURVIVAL: fix 11 routes consumption through ItemStack.consume, which
		// no-ops for creative (instabuild) players — the default creative mock
		// would keep its kits and break every consumption assert below.
		ServerPlayer owner = MockPlayers.createCapturingMockPlayer(helper, GameType.SURVIVAL).player();
		be.setOwner(owner);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.runAfterDelay(2, () -> {
			helper.assertTrue(state.maxHealth == 200.0F, "tier 0 at diameter 32 should have maxHealth 200, got " + state.maxHealth);
			// 160 raw at tier 0 (no DR) leaves 40 HP; the hit also opens the combat
			// gate, so tier 0 cannot regenerate between the checks below.
			be.applyShieldDamage(160.0F);
			helper.assertTrue(state.health == 40.0F, "damaged health should be 40, got " + state.health);

			armWithPatchKits(helper, owner, 2);
			helper.useBlock(PROJECTOR_POS, owner);
			helper.assertTrue(state.health == 190.0F, "the first kit should heal exactly 150 (40 -> 190), got " + state.health);
			helper.assertTrue(owner.getMainHandItem().getCount() == 1, "the first (effective) kit use should consume one kit");

			helper.useBlock(PROJECTOR_POS, owner);
			helper.assertTrue(state.health == 200.0F, "the second kit should cap at max health (190 -> 200), got " + state.health);
			helper.assertTrue(owner.getMainHandItem().getCount() == 0, "healing the last 10 HP still consumes the kit (>= 1 HP healed)");
			helper.succeed();
		});
	}

	/**
	 * (f) The patch kit at full health is a no-op: nothing to heal means no kit is
	 * consumed — and the interaction falls through to the regular menu open, so a
	 * kit-holding owner is never locked out of the GUI.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 100, padding = 16)
	public void patchKitFullHealthNoOpOpensMenu(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		// SURVIVAL keeps the "not consumed" assert non-vacuous (fix 11: creative
		// players never consume kits, no-op or not).
		ServerPlayer owner = MockPlayers.createCapturingMockPlayer(helper, GameType.SURVIVAL).player();
		be.setOwner(owner);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.runAfterDelay(2, () -> {
			helper.assertTrue(state.health == state.maxHealth, "the shield should be at full health");
			armWithPatchKits(helper, owner, 1);
			helper.useBlock(PROJECTOR_POS, owner);
			helper.assertTrue(state.health == state.maxHealth, "a full-health shield must not change");
			helper.assertTrue(owner.getMainHandItem().getCount() == 1, "a no-op kit use must not be consumed");
			helper.assertTrue(owner.containerMenu instanceof BubbleShieldMenu,
					"the no-op kit interaction should fall through to the menu open");
			helper.succeed();
		});
	}

	/**
	 * (g) The patch kit on a broken shield cuts the REMAINING cooldown by 20% of
	 * the FULL cooldown this break started (tier 0: 3600 of 18000 ticks), stacks
	 * across uses, floors at 1 remaining tick, and is only consumed when it
	 * reduced something.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 100, padding = 16)
	public void patchKitCutsBreakCooldown(GameTestHelper helper) {
		// The 20% reduction is pure and exact (all table values divide by 5). A
		// state WITHOUT a break-time snapshot (0: legacy save / no break yet)
		// falls back to the given tier's table value...
		ShieldState fresh = new ShieldState();
		helper.assertTrue(ShieldLogic.patchKitCooldownReduction(fresh, 0) == 3600L, "tier 0 fallback reduction should be 3600 ticks");
		helper.assertTrue(ShieldLogic.patchKitCooldownReduction(fresh, 1) == 2400L, "tier 1 fallback reduction should be 2400 ticks");
		helper.assertTrue(ShieldLogic.patchKitCooldownReduction(fresh, 2) == 1440L, "tier 2 fallback reduction should be 1440 ticks");
		helper.assertTrue(ShieldLogic.patchKitCooldownReduction(fresh, 3) == 720L, "tier 3 fallback reduction should be 720 ticks");
		// ... and fix 2: a present snapshot WINS over the current tier, whatever it is.
		fresh.breakCooldownTotalTicks = 18000L;
		helper.assertTrue(ShieldLogic.patchKitCooldownReduction(fresh, 3) == 3600L,
				"the break-time snapshot must pin the reduction regardless of the current tier");

		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		// SURVIVAL: the consumption asserts below need ItemStack.consume to
		// actually shrink the stack (fix 11 exempts creative players).
		ServerPlayer owner = MockPlayers.createCapturingMockPlayer(helper, GameType.SURVIVAL).player();
		be.setOwner(owner);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.runAfterDelay(2, () -> {
			be.applyShieldDamage(100000.0F);
			long gameTime = helper.getLevel().getGameTime();
			helper.assertTrue(state.cooldownUntil == gameTime + 18000L, "a tier-0 break should start the full 18000-tick cooldown");

			armWithPatchKits(helper, owner, 3);
			helper.useBlock(PROJECTOR_POS, owner);
			helper.assertTrue(state.cooldownUntil == gameTime + 14400L,
					"the first kit should cut the remaining cooldown by exactly 3600, got " + (state.cooldownUntil - gameTime));
			helper.assertTrue(owner.getMainHandItem().getCount() == 2, "the first cooldown cut should consume one kit");

			helper.useBlock(PROJECTOR_POS, owner);
			helper.assertTrue(state.cooldownUntil == gameTime + 10800L,
					"the second kit should stack another 3600 cut, got " + (state.cooldownUntil - gameTime));
			helper.assertTrue(owner.getMainHandItem().getCount() == 1, "the second cooldown cut should consume one kit");
			helper.assertTrue(!state.active, "cooldown cuts must not activate the shield");

			// The floor: a remaining cooldown shorter than the reduction drops to
			// exactly 1 tick (consumed: it reduced something)...
			state.cooldownUntil = gameTime + 100L;
			helper.useBlock(PROJECTOR_POS, owner);
			helper.assertTrue(state.cooldownUntil == gameTime + 1L,
					"a 100-tick remainder should floor at 1 remaining tick, got " + (state.cooldownUntil - gameTime));
			helper.assertTrue(owner.getMainHandItem().getCount() == 0, "the floored cut still consumed a kit (it reduced 99 ticks)");

			// ... and a further use has nothing left to reduce, so it must not consume.
			armWithPatchKits(helper, owner, 1);
			helper.useBlock(PROJECTOR_POS, owner);
			helper.assertTrue(state.cooldownUntil == gameTime + 1L, "at the 1-tick floor nothing may be reduced");
			helper.assertTrue(owner.getMainHandItem().getCount() == 1, "a kit that reduced nothing must not be consumed");
			helper.succeed();
		});
	}

	/**
	 * (g2, fix 2) Core-swap exploit kill: the per-kit cooldown reduction is
	 * pinned to the cooldown SNAPSHOTTED at break time. A tier-0 break (18000
	 * ticks, 3600/kit) keeps its 3600 reduction even after an aegis core (tier 3,
	 * whose own table value would be 720) is socketed mid-cooldown.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 100, padding = 16)
	public void patchKitReductionPinnedAcrossCoreSwap(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		// SURVIVAL: the consumption assert needs ItemStack.consume to shrink (fix 11).
		ServerPlayer owner = MockPlayers.createCapturingMockPlayer(helper, GameType.SURVIVAL).player();
		be.setOwner(owner);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.runAfterDelay(2, () -> {
			be.applyShieldDamage(100000.0F);
			long gameTime = helper.getLevel().getGameTime();
			helper.assertTrue(state.cooldownUntil == gameTime + 18000L, "a tier-0 break should start the full 18000-tick cooldown");
			helper.assertTrue(state.breakCooldownTotalTicks == 18000L,
					"the break should snapshot its full cooldown, got " + state.breakCooldownTotalTicks);

			// The exploit setup: swap in the tier-3 core AFTER the tier-0 break.
			be.getCoreContainer().setItem(0, new ItemStack(ModItems.AEGIS_CORE));

			armWithPatchKits(helper, owner, 1);
			helper.useBlock(PROJECTOR_POS, owner);
			helper.assertTrue(state.cooldownUntil == gameTime + 14400L,
					"the kit must keep cutting the SNAPSHOTTED 3600 (not tier 3's 720), got " + (state.cooldownUntil - gameTime));
			helper.assertTrue(owner.getMainHandItem().getCount() == 0, "the effective cut should consume the kit");
			helper.succeed();
		});
	}

	/**
	 * (g3, fix 11) A creative-mode player's patch kit is NOT consumed (the
	 * standard {@code ItemStack.consume} player-aware pattern), while the heal
	 * still applies once.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 100, padding = 16)
	public void patchKitCreativeAppliesWithoutConsuming(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 16.0F);
		ShieldState state = be.getShieldState();
		ServerPlayer owner = MockPlayers.createUniqueMockPlayer(helper);
		be.setOwner(owner);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.runAfterDelay(2, () -> {
			helper.assertTrue(state.maxHealth == 200.0F, "tier 0 at diameter 32 should have maxHealth 200, got " + state.maxHealth);
			be.applyShieldDamage(160.0F);
			helper.assertTrue(state.health == 40.0F, "damaged health should be 40, got " + state.health);

			// Creative semantics: ItemStack.consume keys off hasInfiniteMaterials()
			// = abilities.instabuild, exactly what GameType.CREATIVE grants a real
			// player on mode change.
			owner.getAbilities().instabuild = true;
			armWithPatchKits(helper, owner, 1);
			helper.useBlock(PROJECTOR_POS, owner);
			helper.assertTrue(state.health == 190.0F, "the creative kit should still heal exactly 150 (40 -> 190), got " + state.health);
			helper.assertTrue(owner.getMainHandItem().getCount() == 1, "a creative-mode kit use must not be consumed");
			helper.succeed();
		});
	}

	/**
	 * (h) The patch kit mirrors the barrier's subject rule: a non-whitelisted
	 * stranger gets no effect and keeps the kit, a whitelisted (non-owner) friend
	 * may patch.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 100, padding = 16)
	public void patchKitStrangerNoOpWhitelistedAllowed(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		ServerPlayer owner = MockPlayers.createUniqueMockPlayer(helper);
		// SURVIVAL: the stranger's kit-kept/kit-consumed asserts rely on real
		// ItemStack.consume semantics (fix 11 exempts creative players).
		ServerPlayer stranger = MockPlayers.createCapturingMockPlayer(helper, GameType.SURVIVAL).player();
		be.setOwner(owner);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.runAfterDelay(2, () -> {
			be.applyShieldDamage(100000.0F);
			long gameTime = helper.getLevel().getGameTime();
			long cooldownBefore = state.cooldownUntil;
			helper.assertTrue(cooldownBefore > gameTime, "the break should start a cooldown");

			armWithPatchKits(helper, stranger, 1);
			helper.useBlock(PROJECTOR_POS, stranger);
			helper.assertTrue(state.cooldownUntil == cooldownBefore, "a stranger's kit must not touch the cooldown");
			helper.assertTrue(stranger.getMainHandItem().getCount() == 1, "a stranger's kit must not be consumed");

			// Whitelisting the same player flips the decision — no ownership needed.
			state.whitelistUuids.add(stranger.getUUID());
			helper.useBlock(PROJECTOR_POS, stranger);
			helper.assertTrue(state.cooldownUntil == cooldownBefore - ShieldLogic.patchKitCooldownReduction(state, 0),
					"a whitelisted friend's kit should cut the cooldown");
			helper.assertTrue(stranger.getMainHandItem().getCount() == 0, "the friend's effective kit use should be consumed");
			helper.succeed();
		});
	}

	/**
	 * (i) The patch kit ships as authored: registered under
	 * {@code bubbleshield:patch_kit} with a max stack of 16, its shapeless recipe
	 * (2x amethyst shard + slime ball + copper ingot -&gt; 2 kits) parses, and the
	 * item-definition/model assets exist on the classpath.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void patchKitRecipeAndRegistration(GameTestHelper helper) {
		helper.assertTrue(
				BuiltInRegistries.ITEM.getValue(Identifier.parse("bubbleshield:patch_kit")) == ModItems.PATCH_KIT,
				"bubbleshield:patch_kit should resolve to the registered item");
		helper.assertTrue(new ItemStack(ModItems.PATCH_KIT).getMaxStackSize() == 16, "the patch kit should stack to 16");

		JsonObject recipe = readJsonResource(helper, "/data/bubbleshield/recipe/patch_kit.json");
		helper.assertTrue(
				"minecraft:crafting_shapeless".equals(recipe.get("type").getAsString()),
				"the patch kit recipe should be minecraft:crafting_shapeless");
		JsonObject result = recipe.getAsJsonObject("result");
		helper.assertTrue("bubbleshield:patch_kit".equals(result.get("id").getAsString()), "the recipe should produce patch kits");
		helper.assertTrue(result.get("count").getAsInt() == 2, "the recipe should produce 2 kits");

		JsonArray ingredients = recipe.getAsJsonArray("ingredients");
		helper.assertTrue(ingredients != null && ingredients.size() == 4, "the recipe should list exactly 4 ingredients");
		int shards = 0;
		int slime = 0;
		int copper = 0;
		for (int i = 0; i < ingredients.size(); i++) {
			switch (ingredients.get(i).getAsString()) {
				case "minecraft:amethyst_shard" -> shards++;
				case "minecraft:slime_ball" -> slime++;
				case "minecraft:copper_ingot" -> copper++;
				default -> throw helper.assertionException("unexpected ingredient: " + ingredients.get(i).getAsString());
			}
		}

		helper.assertTrue(shards == 2 && slime == 1 && copper == 1,
				"the recipe should take 2 amethyst shards, 1 slime ball and 1 copper ingot");

		// The item definition points at the mod model, which reuses the vanilla
		// phantom-membrane sprite (same pattern as the cores' nether-star reuse).
		JsonObject itemDefinition = readJsonResource(helper, "/assets/bubbleshield/items/patch_kit.json");
		helper.assertTrue(
				"bubbleshield:item/patch_kit".equals(itemDefinition.getAsJsonObject("model").get("model").getAsString()),
				"the item definition should reference bubbleshield:item/patch_kit");
		JsonObject model = readJsonResource(helper, "/assets/bubbleshield/models/item/patch_kit.json");
		helper.assertTrue(
				"minecraft:item/phantom_membrane".equals(model.getAsJsonObject("textures").get("layer0").getAsString()),
				"the model should reuse the vanilla phantom membrane sprite");
		helper.succeed();
	}

	private static JsonObject readJsonResource(GameTestHelper helper, String path) {
		try (InputStream in = RecoveryGameTests.class.getResourceAsStream(path)) {
			helper.assertTrue(in != null, "missing data pack resource: " + path);
			return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (Exception e) {
			throw helper.assertionException("failed to read/parse " + path + ": " + e);
		}
	}
}

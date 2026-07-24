package com.bubbleshield.gametest;

import java.util.List;
import java.util.UUID;

import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.registry.ModBlocks;
import com.bubbleshield.registry.ModItems;
import com.bubbleshield.shield.ShieldLinking;
import com.bubbleshield.shield.ShieldLogic;
import com.bubbleshield.shield.ShieldShape;
import com.bubbleshield.shield.ShieldState;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Coverage for milestone V6: the resonance link between same-owner active shields
 * with overlapping spheres — the even projectile damage split across linked shields
 * and the pure {@link ShieldLinking#findLinked} geometry/owner rules.
 */
public class LinkingGameTests {
	/**
	 * A dedicated (but otherwise vanilla-default) test environment,
	 * {@code data/bubbleshield/test_environment/linking.json}. The vanilla runner
	 * batches tests by environment (50 per batch, ticked in parallel); keeping this
	 * class out of the shared default batch avoids reshuffling the pre-existing suite
	 * (see ColorGameTests.ISOLATED_ENVIRONMENT for the full story; the historical
	 * "test-mock-player" PlayerList collisions are gone now that in-level mocks are
	 * uniquely named via {@link MockPlayers}).
	 */
	private static final String ISOLATED_ENVIRONMENT = "bubbleshield:linking";
	/**
	 * A 39x8x8 empty arena ({@code data/bubbleshield/gametest/structure/linking_arena.snbt})
	 * wide enough for two projectors 10 or 30 blocks apart. Height 8 keeps the
	 * radius-8 boundary crossing (relative y=10.5) above the framework's barrier
	 * ceiling, the same trick as ShieldGameTests.arrowDamagesShield.
	 */
	private static final String ARENA_STRUCTURE = "bubbleshield:linking_arena";
	private static final BlockPos SHIELD_A_POS = new BlockPos(4, 2, 4);
	/** 10 blocks east of A: overlaps A (10 < 8 + 8). */
	private static final BlockPos SHIELD_B_NEAR_POS = new BlockPos(14, 2, 4);
	/** 30 blocks east of A: disjoint from A (30 >= 8 + 8). */
	private static final BlockPos SHIELD_B_FAR_POS = new BlockPos(34, 2, 4);
	private static final float RADIUS = 8.0F;
	private static final int PLENTY_OF_FUEL = 600;
	private static final float TOLERANCE = 0.01F;
	/** Tier-0 max health at RADIUS 8 (diameter 16): 200 * (0.5 + 16/64) = 150. */
	private static final float T0_MAX_HEALTH = ShieldLogic.maxHealthFor(0, RADIUS, 100);

	private static BubbleShieldBlockEntity placeProjector(GameTestHelper helper, BlockPos pos, UUID owner) {
		helper.setBlock(pos, ModBlocks.BUBBLE_SHIELD_PROJECTOR);
		BubbleShieldBlockEntity be = helper.getBlockEntity(pos, BubbleShieldBlockEntity.class);
		be.getShieldState().targetRadius = RADIUS;
		be.getShieldState().ownerUuid = owner;
		be.addFuelSeconds(PLENTY_OF_FUEL);
		return be;
	}

	/**
	 * Copies the arrow-spawn pattern from ShieldGameTests.arrowDamagesShield: spawned
	 * 12 blocks above shield A's projector (staying in the structure's own force-loaded
	 * chunk column), shooting straight down into shield A. The interception discards
	 * the arrow, so no entity cleanup is needed.
	 */
	private static void shootArrowIntoShieldA(GameTestHelper helper) {
		Arrow arrow = helper.spawn(EntityTypes.ARROW, new Vec3(4.5, 14.5, 4.5));
		arrow.setDeltaMovement(0.0, -1.5, 0.0);
	}

	/** (a) Same owner, overlapping: the arrow's damage is split evenly across both shields. */
	@GameTest(environment = ISOLATED_ENVIRONMENT, structure = ARENA_STRUCTURE, maxTicks = 200, padding = 16)
	public void linkedShieldsSplitDamage(GameTestHelper helper) {
		UUID owner = UUID.randomUUID();
		BubbleShieldBlockEntity shieldA = placeProjector(helper, SHIELD_A_POS, owner);
		BubbleShieldBlockEntity shieldB = placeProjector(helper, SHIELD_B_NEAR_POS, owner);
		helper.assertTrue(shieldA.tryActivate(), "shield A should activate");
		helper.assertTrue(shieldB.tryActivate(), "shield B should activate");
		helper.assertTrue(
				ShieldLinking.findLinked(shieldA, List.of(shieldA, shieldB)).size() == 2,
				"the two overlapping same-owner shields should be linked");

		shootArrowIntoShieldA(helper);

		ShieldState stateA = shieldA.getShieldState();
		ShieldState stateB = shieldB.getShieldState();
		// Tier 0 has no DR, so each shield loses exactly its raw half of the split.
		float expected = T0_MAX_HEALTH - ShieldLogic.PROJECTILE_DAMAGE / 2.0F;
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(stateA.health < stateA.maxHealth, "shield A should take damage from the intercepted arrow"))
				// A few extra ticks so a non-discarded arrow (or double application by
				// the partner's own tick) would be caught by the exact-value asserts.
				.thenExecuteAfter(10, () -> {
					helper.assertTrue(
							Math.abs(stateA.health - expected) <= TOLERANCE,
							"the hit shield should lose exactly half the arrow damage, health is " + stateA.health);
					helper.assertTrue(
							Math.abs(stateB.health - expected) <= TOLERANCE,
							"the linked partner should lose the other half, health is " + stateB.health);
				})
				.thenSucceed();
	}

	/** (b) Different owners, overlapping: no link, only the hit shield loses health. */
	@GameTest(environment = ISOLATED_ENVIRONMENT, structure = ARENA_STRUCTURE, maxTicks = 200, padding = 16)
	public void differentOwnersDoNotLink(GameTestHelper helper) {
		BubbleShieldBlockEntity shieldA = placeProjector(helper, SHIELD_A_POS, UUID.randomUUID());
		BubbleShieldBlockEntity shieldB = placeProjector(helper, SHIELD_B_NEAR_POS, UUID.randomUUID());
		helper.assertTrue(shieldA.tryActivate(), "shield A should activate");
		helper.assertTrue(shieldB.tryActivate(), "shield B should activate");
		helper.assertTrue(
				ShieldLinking.findLinked(shieldA, List.of(shieldA, shieldB)).size() == 1,
				"overlapping shields with different owners must not link");

		shootArrowIntoShieldA(helper);

		ShieldState stateA = shieldA.getShieldState();
		ShieldState stateB = shieldB.getShieldState();
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(stateA.health < stateA.maxHealth, "shield A should take damage from the intercepted arrow"))
				.thenExecuteAfter(10, () -> {
					helper.assertTrue(
							stateA.health == stateA.maxHealth - ShieldLogic.PROJECTILE_DAMAGE,
							"the hit shield should take the full arrow damage, health is " + stateA.health);
					helper.assertTrue(
							stateB.health == stateB.maxHealth,
							"the other owner's shield must be untouched, health is " + stateB.health);
				})
				.thenSucceed();
	}

	/** (c) Same owner but disjoint spheres (30 apart, radius 8 each): independent shields. */
	@GameTest(environment = ISOLATED_ENVIRONMENT, structure = ARENA_STRUCTURE, maxTicks = 200, padding = 16)
	public void nonOverlappingShieldsAreIndependent(GameTestHelper helper) {
		UUID owner = UUID.randomUUID();
		BubbleShieldBlockEntity shieldA = placeProjector(helper, SHIELD_A_POS, owner);
		BubbleShieldBlockEntity shieldB = placeProjector(helper, SHIELD_B_FAR_POS, owner);
		helper.assertTrue(shieldA.tryActivate(), "shield A should activate");
		helper.assertTrue(shieldB.tryActivate(), "shield B should activate");
		helper.assertTrue(
				ShieldLinking.findLinked(shieldA, List.of(shieldA, shieldB)).size() == 1,
				"same-owner shields with disjoint spheres must not link");

		shootArrowIntoShieldA(helper);

		ShieldState stateA = shieldA.getShieldState();
		ShieldState stateB = shieldB.getShieldState();
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(stateA.health < stateA.maxHealth, "shield A should take damage from the intercepted arrow"))
				.thenExecuteAfter(10, () -> {
					helper.assertTrue(
							stateA.health == stateA.maxHealth - ShieldLogic.PROJECTILE_DAMAGE,
							"the hit shield should take the full arrow damage, health is " + stateA.health);
					helper.assertTrue(
							stateB.health == stateB.maxHealth,
							"the far same-owner shield must be untouched, health is " + stateB.health);
				})
				.thenSucceed();
	}

	/**
	 * (a') Two overlapping linked shields must intercept the SAME projectile only
	 * ONCE. A DEFLECTED projectile (unlike a discarded arrow) keeps existing with its
	 * stale prev-tick position, so before the prev-position neutralization both
	 * shields' ticks saw the same outside->inside crossing in one server tick and
	 * each ran its own damage split (double damage, and the double-reversed fireball
	 * kept flying inward). The fireball drops on the exact x-midplane between the two
	 * projectors, so by symmetry it crosses BOTH shield boundaries on the SAME tick —
	 * the worst case. Total damage must be HURTING_PROJECTILE_DAMAGE, split once.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, structure = ARENA_STRUCTURE, maxTicks = 200, padding = 16)
	public void linkedShieldsInterceptFireballOnce(GameTestHelper helper) {
		UUID owner = UUID.randomUUID();
		BubbleShieldBlockEntity shieldA = placeProjector(helper, SHIELD_A_POS, owner);
		// 2 blocks east of A: heavily overlapping, and the boundary crossing on the
		// midplane (x offset 1 from each center) sits at relative y ~10.44 — safely
		// above the framework's barrier ceiling at the structure top (y 8).
		BubbleShieldBlockEntity shieldB = placeProjector(helper, SHIELD_A_POS.east(2), owner);
		helper.assertTrue(shieldA.tryActivate(), "shield A should activate");
		helper.assertTrue(shieldB.tryActivate(), "shield B should activate");
		helper.assertTrue(
				ShieldLinking.findLinked(shieldA, List.of(shieldA, shieldB)).size() == 2,
				"the two overlapping same-owner shields should be linked");

		// On the x-midplane between the centers (4.5 and 6.5): equidistant from both,
		// falling slowly enough (0.75/tick) that the first inside sample stays above
		// the barrier ceiling. Deflection keeps the fireball alive (never absorbed).
		SmallFireball fireball = helper.spawn(EntityTypes.SMALL_FIREBALL, new Vec3(5.5, 14.5, 4.5));
		fireball.setDeltaMovement(0.0, -0.75, 0.0);

		ShieldState stateA = shieldA.getShieldState();
		ShieldState stateB = shieldB.getShieldState();
		float expected = T0_MAX_HEALTH - ShieldLogic.HURTING_PROJECTILE_DAMAGE / 2.0F;
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(stateA.health < stateA.maxHealth, "shield A should take damage from the intercepted fireball"))
				// Extra ticks so a second interception (by the partner's tick on the
				// same crossing) would be caught by the exact-value asserts.
				.thenExecuteAfter(10, () -> {
					helper.assertTrue(
							Math.abs(stateA.health - expected) <= TOLERANCE,
							"one interception split once: shield A should lose exactly "
									+ ShieldLogic.HURTING_PROJECTILE_DAMAGE / 2.0F + ", health is " + stateA.health);
					helper.assertTrue(
							Math.abs(stateB.health - expected) <= TOLERANCE,
							"one interception split once: shield B should lose exactly "
									+ ShieldLogic.HURTING_PROJECTILE_DAMAGE / 2.0F + ", health is " + stateB.health);
					float totalDamage = (stateA.maxHealth - stateA.health) + (stateB.maxHealth - stateB.health);
					helper.assertTrue(
							Math.abs(totalDamage - ShieldLogic.HURTING_PROJECTILE_DAMAGE) <= TOLERANCE,
							"the total damage across both linked shields must be " + ShieldLogic.HURTING_PROJECTILE_DAMAGE
									+ " (split once, not twice), got " + totalDamage);
					helper.assertTrue(fireball.isAlive(), "the deflected fireball must stay alive (not be absorbed)");
					helper.assertTrue(
							fireball.getDeltaMovement().y > 0.0,
							"the fireball must be neutralized outbound (single REVERSE deflection), not re-reversed inward");
				})
				.thenSucceed();
	}

	/**
	 * (a'') D7b cache consistency: a 10-arrow SAME-TICK volley into shield A of a
	 * linked pair must split the total damage exactly evenly. The per-tick link
	 * cache resolves the partner set once per shield tick (instead of once per
	 * intercepted projectile), so every hit of the volley must use the same
	 * partner set: 10 arrows x {@link ShieldLogic#PROJECTILE_DAMAGE} = 30 total,
	 * exactly 15 per shield (both tier 0, so no DR discounts the raw shares).
	 *
	 * <p>Geometry: the whole volley must land within ONE interception loop, because
	 * the framework's barrier ceiling caps the arena at the structure top — an arrow
	 * that gets stuck on that ceiling BETWEEN two boundary radii (prev and current
	 * both inside/both outside) would never register as crossing in. Shield A is
	 * widened to radius 12 (post-volley health fraction 160/175 stays above the 60%
	 * shrink plateau, so the boundary holds at 12) and the arrows fall a whole
	 * 6 blocks on their first move tick: prev sits at distance ~12.3 (outside the
	 * boundary) and current sticks on the ceiling at distance ~6.5 (well inside),
	 * so every arrow of the volley crosses in on the same shield tick.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, structure = ARENA_STRUCTURE, maxTicks = 200, padding = 16)
	public void linkedVolleySplitsEvenly(GameTestHelper helper) {
		UUID owner = UUID.randomUUID();
		BubbleShieldBlockEntity shieldA = placeProjector(helper, SHIELD_A_POS, owner);
		BubbleShieldBlockEntity shieldB = placeProjector(helper, SHIELD_B_NEAR_POS, owner);
		// Widen A so the boundary stays above the ceiling-stuck arrow positions for
		// the whole volley (see the geometry note in the javadoc). Still linked to B
		// throughout: both stay on the shrink plateau (12 + 8 > 10).
		shieldA.getShieldState().targetRadius = 12.0F;
		helper.assertTrue(shieldA.tryActivate(), "shield A should activate");
		helper.assertTrue(shieldB.tryActivate(), "shield B should activate");

		ShieldState stateA = shieldA.getShieldState();
		ShieldState stateB = shieldB.getShieldState();
		// A is radius 12 (max 175), B radius 8 (max 150); each loses 15 raw (T0: no DR).
		int volley = 10;
		float halfVolley = volley * ShieldLogic.PROJECTILE_DAMAGE / 2.0F;
		float expectedA = ShieldLogic.maxHealthFor(0, 12.0F, 100) - halfVolley;
		float expectedB = T0_MAX_HEALTH - halfVolley;
		helper.startSequence()
				// Let BOTH first-tick max-health refreshes land before firing: the
				// volley crosses on its very first move tick, and a split share
				// applied to B before ITS refresh would be taken against the stale
				// default max and then rescaled by the fraction-preserving refresh.
				.thenExecuteAfter(2, () -> {
					helper.assertTrue(stateA.maxHealth == expectedA + halfVolley,
							"shield A should refresh to maxHealth 175, got " + stateA.maxHealth);
					helper.assertTrue(stateB.maxHealth == expectedB + halfVolley,
							"shield B should refresh to maxHealth 150, got " + stateB.maxHealth);

					// x-fanned around the column so the arrows are distinct entities;
					// all spawn just above A's radius-12 top (center y 2.5 + 12 = 14.5)
					// and dive 6 blocks on their first move tick, landing stuck on the
					// barrier ceiling INSIDE the final boundary, so the whole volley
					// intercepts in one loop.
					for (int i = 0; i < volley; i++) {
						Arrow arrow = helper.spawn(EntityTypes.ARROW, new Vec3(4.05 + 0.1 * i, 14.8, 4.5));
						arrow.setDeltaMovement(0.0, -6.0, 0.0);
					}
				})
				.thenWaitUntil(() -> helper.assertTrue(
						stateA.health <= expectedA + TOLERANCE,
						"all 10 arrows should be absorbed by shield A, health is " + stateA.health))
				// Extra ticks so a stray un-discarded arrow or an uneven split would
				// be caught by the exact-value asserts.
				.thenExecuteAfter(10, () -> {
					helper.assertTrue(
							Math.abs(stateA.health - expectedA) <= TOLERANCE,
							"shield A should lose exactly half the volley damage (15), health is " + stateA.health);
					helper.assertTrue(
							Math.abs(stateB.health - expectedB) <= TOLERANCE,
							"shield B should lose the other half (15), health is " + stateB.health);
					helper.assertTrue(
							stateA.maxHealth - stateA.health == stateB.maxHealth - stateB.health,
							"the volley split must stay exactly even, A=" + stateA.health + " B=" + stateB.health);
				})
				.thenSucceed();
	}

	/**
	 * (a''') The linked regen bonus: a tier-1 shield with at least one resonance-linked
	 * partner heals {@link ShieldLogic#regenPerPulse regenPerPulse(1)} x
	 * {@link ShieldLogic#LINKED_REGEN_MULTIPLIER} = 3.75 per pulse; after the partner
	 * deactivates (link gone) the very next pulse is back to the base 3.0. Both
	 * observed pulses land inside the 200-tick combat gate opened by the damage, so
	 * the out-of-combat x3 never applies here.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, structure = ARENA_STRUCTURE, maxTicks = 200, padding = 16)
	public void linkedRegenHealsFaster(GameTestHelper helper) {
		UUID owner = UUID.randomUUID();
		BubbleShieldBlockEntity shieldA = placeProjector(helper, SHIELD_A_POS, owner);
		BubbleShieldBlockEntity shieldB = placeProjector(helper, SHIELD_B_NEAR_POS, owner);
		shieldA.getCoreContainer().setItem(0, new ItemStack(ModItems.RESONANT_CORE));
		helper.assertTrue(shieldA.tryActivate(), "shield A should activate");
		helper.assertTrue(shieldB.tryActivate(), "shield B should activate");

		ShieldState stateA = shieldA.getShieldState();
		float[] healthBefore = new float[1];
		float t1MaxHealth = ShieldLogic.maxHealthFor(1, RADIUS, 100);
		float basePulse = ShieldLogic.regenPerPulse(1);
		float linkedPulse = basePulse * ShieldLogic.LINKED_REGEN_MULTIPLIER;
		helper.startSequence()
				// Let the core refresh land (maxHealth 300), then damage A: 60 raw is
				// 45 after tier 1's 25% DR, so health 255/300 = 85% — on the shrink
				// plateau, so the radius holds at 8 and A stays linked (8 + 8 > 10).
				.thenExecuteAfter(2, () -> {
					helper.assertTrue(stateA.maxHealth == t1MaxHealth,
							"the resonant core should raise max health to " + t1MaxHealth + ", got " + stateA.maxHealth);
					shieldA.applyShieldDamage(60.0F);
					healthBefore[0] = stateA.health;
					helper.assertTrue(
							ShieldLinking.findLinked(shieldA, List.of(shieldA, shieldB)).size() == 2,
							"the damaged shield should still be linked to its partner");
				})
				// The poll runs every tick and pulses are 40 ticks apart, so the first
				// passing check observes exactly ONE pulse.
				.thenWaitUntil(() -> helper.assertTrue(
						stateA.health > healthBefore[0], "the linked tier-1 shield should regenerate"))
				.thenExecute(() -> {
					float healed = stateA.health - healthBefore[0];
					helper.assertTrue(
							Math.abs(healed - linkedPulse) <= TOLERANCE,
							"a linked regen pulse should heal " + linkedPulse + ", got " + healed);

					// Unlink: with the partner inactive, the next pulse (next tick's
					// cache resolution) drops back to the base rate.
					shieldB.setActive(false);
					healthBefore[0] = stateA.health;
				})
				.thenWaitUntil(() -> helper.assertTrue(
						stateA.health > healthBefore[0], "the unlinked shield should keep regenerating"))
				.thenExecute(() -> {
					float healed = stateA.health - healthBefore[0];
					helper.assertTrue(
							Math.abs(healed - basePulse) <= TOLERANCE,
							"an unlinked regen pulse should heal the base " + basePulse + ", got " + healed);
				})
				.thenSucceed();
	}

	/**
	 * A free-standing (never level-attached, so never tracked in LOADED_SHIELDS)
	 * block entity for pure findLinked geometry checks.
	 */
	private static BubbleShieldBlockEntity standalone(BlockPos pos, float targetRadius, UUID owner, boolean active) {
		BubbleShieldBlockEntity be = new BubbleShieldBlockEntity(pos, ModBlocks.BUBBLE_SHIELD_PROJECTOR.defaultBlockState());
		ShieldState state = be.getShieldState();
		state.targetRadius = targetRadius;
		state.ownerUuid = owner;
		state.active = active;
		return be;
	}

	/** (d) Pure findLinked geometry: overlap strictness, owner/active gating, current radii, order. */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void findLinkedGeometry(GameTestHelper helper) {
		UUID owner = UUID.randomUUID();
		BlockPos originPos = new BlockPos(0, 64, 0);
		BubbleShieldBlockEntity origin = standalone(originPos, 8.0F, owner, true);

		// Overlap is strict: touching spheres (distance == r1 + r2) do NOT link.
		BubbleShieldBlockEntity overlapping = standalone(originPos.east(10), 8.0F, owner, true);
		BubbleShieldBlockEntity touching = standalone(originPos.east(16), 8.0F, owner, true);
		BubbleShieldBlockEntity disjoint = standalone(originPos.east(30), 8.0F, owner, true);
		helper.assertTrue(
				ShieldLinking.findLinked(origin, List.of(overlapping, touching, disjoint)).equals(List.of(origin, overlapping)),
				"only the strictly overlapping shield should link (touching/disjoint excluded)");

		// Both shields must be active, and owners must match and be non-null.
		BubbleShieldBlockEntity inactive = standalone(originPos.east(10), 8.0F, owner, false);
		BubbleShieldBlockEntity stranger = standalone(originPos.east(10), 8.0F, UUID.randomUUID(), true);
		BubbleShieldBlockEntity ownerless = standalone(originPos.east(10), 8.0F, null, true);
		helper.assertTrue(
				ShieldLinking.findLinked(origin, List.of(inactive, stranger, ownerless)).equals(List.of(origin)),
				"inactive, different-owner and ownerless shields must not link");

		BubbleShieldBlockEntity ownerlessOrigin = standalone(originPos, 8.0F, null, true);
		BubbleShieldBlockEntity ownerlessTwin = standalone(originPos.east(1), 8.0F, null, true);
		helper.assertTrue(
				ShieldLinking.findLinked(ownerlessOrigin, List.of(ownerlessTwin)).equals(List.of(ownerlessOrigin)),
				"two ownerless shields must not link even at distance 1");
		BubbleShieldBlockEntity inactiveOrigin = standalone(originPos, 8.0F, owner, false);
		helper.assertTrue(
				ShieldLinking.findLinked(inactiveOrigin, List.of(overlapping)).equals(List.of(inactiveOrigin)),
				"an inactive origin links to nothing");

		// Linking uses the CURRENT (health-shrunk) radii, not the target radii.
		// 30% health is below the 60% shrink plateau: currentRadius = max(4, 8 * 0.3/0.6) = 4.
		BubbleShieldBlockEntity shrunk = standalone(originPos.east(13), 8.0F, owner, true);
		shrunk.getShieldState().health = 30.0F;
		helper.assertTrue(
				ShieldLinking.findLinked(origin, List.of(shrunk)).equals(List.of(origin)),
				"a health-shrunk partner (current radius 4) at distance 13 must not link (8 + 4 < 13)");
		BubbleShieldBlockEntity shrunkNear = standalone(originPos.east(11), 8.0F, owner, true);
		shrunkNear.getShieldState().health = 30.0F;
		helper.assertTrue(
				ShieldLinking.findLinked(origin, List.of(shrunkNear)).equals(List.of(origin, shrunkNear)),
				"the same shrunk partner at distance 11 should link (8 + 4 > 11)");

		// Linking uses radii ONLY — shape is ignored. Two DOME shields whose sphere
		// volumes overlap purely below the center plane (where a dome renders no
		// surface and admits everything) still resonate: the link is a property of
		// the projectors' spheres, not of the rendered shell.
		BubbleShieldBlockEntity domeOrigin = standalone(originPos, 8.0F, owner, true);
		domeOrigin.getShieldState().shape = ShieldShape.DOME;
		BubbleShieldBlockEntity domeBelow = standalone(originPos.below(10), 8.0F, owner, true);
		domeBelow.getShieldState().shape = ShieldShape.DOME;
		helper.assertTrue(
				ShieldLinking.findLinked(domeOrigin, List.of(domeBelow)).equals(List.of(domeBelow, domeOrigin)),
				"dome shields link by their full sphere radii, even through the dome's open underside");

		// Not transitive: C overlaps B but not the origin, so it is not in origin's set.
		BubbleShieldBlockEntity chained = standalone(originPos.east(20), 8.0F, owner, true);
		helper.assertTrue(
				ShieldLinking.findLinked(origin, List.of(overlapping, chained)).equals(List.of(origin, overlapping)),
				"linking is not transitive: only direct overlap with the origin counts");
		helper.assertTrue(
				ShieldLinking.findLinked(overlapping, List.of(origin, chained)).size() == 3,
				"the middle shield of a chain links to both neighbors");

		// Deterministic order: sorted by BlockPos.asLong(), origin included, regardless
		// of candidate order or duplicates of the origin in the candidate collection.
		BubbleShieldBlockEntity west = standalone(originPos.west(9), 8.0F, owner, true);
		List<BubbleShieldBlockEntity> expected = List.of(west, origin, overlapping);
		helper.assertTrue(
				west.getBlockPos().asLong() < originPos.asLong() && originPos.asLong() < overlapping.getBlockPos().asLong(),
				"the expected order fixture should be ascending by BlockPos.asLong()");
		helper.assertTrue(
				ShieldLinking.findLinked(origin, List.of(overlapping, origin, west)).equals(expected),
				"the link set should be sorted by BlockPos.asLong() and deduplicate the origin");
		helper.assertTrue(
				ShieldLinking.findLinked(origin, List.of(west, overlapping)).equals(expected),
				"candidate order must not change the result");
		helper.succeed();
	}
}

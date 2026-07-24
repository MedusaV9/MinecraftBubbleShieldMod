package com.bubbleshield.gametest;

import java.util.List;

import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.menu.BubbleShieldMenu;
import com.bubbleshield.registry.ModBlocks;
import com.bubbleshield.registry.ModItems;
import com.bubbleshield.shield.ShieldLogic;
import com.bubbleshield.shield.ShieldMode;
import com.bubbleshield.shield.ShieldState;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;

/**
 * Coverage for WP6 "combat behaviors &amp; siege alarm": the A5 hostile-mob
 * barrier (mode matrix DEFENSE/PULSE/ECO, activation expulsion, boss exemption),
 * the B2 arrow riposte (tier gate, shield-owner re-attribution, exact post-DR
 * cost), B3 last stand (halved damage, doubled drain), the B5 break shockwave
 * nova, and B6 (threat census slot, siege-alarm comparator override, threat log,
 * boss-bar suffix).
 */
public class CombatGameTests {
	/**
	 * A dedicated (but otherwise vanilla-default) test environment,
	 * {@code data/bubbleshield/test_environment/combat.json}: the vanilla runner
	 * batches tests by environment (50 per batch, ticked in parallel), and adding
	 * this class to the shared default batch would push the pre-existing suite
	 * past 50 and reshuffle which tests overlap in time (see ColorGameTests for
	 * the full rationale).
	 */
	private static final String ISOLATED_ENVIRONMENT = "bubbleshield:combat";
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
	 * Spawns a frozen (no-AI, persistence-required) zombie: it stays exactly where
	 * it was placed so the inside/outside classification is deterministic, and the
	 * iron helmet absorbs daylight sun-burn ticks (same recipe as ModeGameTests).
	 */
	private static Zombie spawnFrozenZombie(GameTestHelper helper, Vec3 pos) {
		Zombie zombie = helper.spawn(EntityTypes.ZOMBIE, pos);
		zombie.setNoAi(true);
		zombie.setPersistenceRequired();
		zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
		return zombie;
	}

	/** Reads the projector's comparator (analog output) signal through the BlockState wrapper. */
	private static int analogSignal(GameTestHelper helper) {
		BlockPos absolute = helper.absolutePos(PROJECTOR_POS);
		return helper.getLevel().getBlockState(absolute).getAnalogOutputSignal(helper.getLevel(), absolute, Direction.NORTH);
	}

	/** Absolute shield center (the projector block's center). */
	private static Vec3 shieldCenter(GameTestHelper helper) {
		return Vec3.atCenterOf(helper.absolutePos(PROJECTOR_POS));
	}

	private static double distanceFromCenter(GameTestHelper helper, Zombie zombie) {
		return zombie.position().distanceTo(shieldCenter(helper));
	}

	/**
	 * Arrow spawn point for this class's radius-4 shields. Unlike the radius-8
	 * arrow tests elsewhere (which spawn at y = 14.5, above the test enclosure,
	 * and intercept at their y = 10.5 boundary), a radius-4 bubble's boundary
	 * (y = 6.5) sits BELOW the enclosure's solid ceiling (~y = 9) — an arrow
	 * dropped from above would embed in the ceiling and never reach the shield.
	 * Spawning just under the ceiling at y = 7.5 (distance 5 from the center,
	 * still OUTSIDE the bubble) gives the dive one clean outside-to-inside
	 * boundary crossing on its first moved tick.
	 */
	private static Vec3 arrowSpawnAboveBoundary() {
		return new Vec3(4.5, 7.5, 4.5);
	}

	/**
	 * (A5) DEFENSE expulsion-on-activation: a monster already standing inside when
	 * the shield rises is expelled once, synchronously with the activation (the
	 * monster counterpart of expelBlockedPlayers).
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 100, padding = 16)
	public void defenseExpelsZombieOnActivation(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);

		Zombie zombie = spawnFrozenZombie(helper, new Vec3(4.5, 4.5, 4.5));
		helper.assertTrue(distanceFromCenter(helper, zombie) < 4.0, "the zombie should start inside the future bubble");

		// tryActivate runs expelBlockedMonsters synchronously (DEFENSE is the default mode).
		helper.assertTrue(be.tryActivate(), "shield should activate");
		double distance = distanceFromCenter(helper, zombie);
		try {
			helper.assertTrue(
					distance > 4.0,
					"a DEFENSE activation should expel the zombie standing inside, distance is " + distance);
		} finally {
			zombie.discard();
		}

		helper.succeed();
	}

	/**
	 * (A5/fix 6) The hostile partition is {@code Mob && Enemy}, not Monster: a
	 * slime — a hostile {@link net.minecraft.world.entity.monster.Enemy} on the
	 * AbstractCubeMob branch that the old Monster partition MISSED entirely — is
	 * expelled by the per-tick DEFENSE barrier exactly like a zombie. Frozen
	 * (NoAI/persistent) like the zombie helper for deterministic geometry.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 100, padding = 16)
	public void defenseBarrierExpelsSlimeInside(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		Slime slime = helper.spawn(EntityTypes.SLIME, new Vec3(4.5, 4.5, 4.5));
		slime.setNoAi(true);
		slime.setPersistenceRequired();
		helper.runAfterDelay(5, () -> {
			double distance = slime.position().distanceTo(shieldCenter(helper));
			try {
				helper.assertTrue(
						distance > 4.0,
						"the per-tick DEFENSE barrier should expel a slime inside (Mob && Enemy partition), distance is " + distance);
			} finally {
				slime.discard();
			}

			helper.succeed();
		});
	}

	/**
	 * (A5) The per-tick DEFENSE barrier: a monster that ends up inside a running
	 * DEFENSE shield (spawned in, here) is expelled by the next barrier pass, so
	 * hostile mobs can never take up residence inside.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 100, padding = 16)
	public void defenseBarrierExpelsZombieInside(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		// Spawned AFTER activation: only the per-tick barrier can expel it.
		Zombie zombie = spawnFrozenZombie(helper, new Vec3(4.5, 4.5, 4.5));
		helper.runAfterDelay(5, () -> {
			double distance = distanceFromCenter(helper, zombie);
			try {
				helper.assertTrue(
						distance > 4.0,
						"the per-tick DEFENSE barrier should expel a zombie inside, distance is " + distance);
			} finally {
				zombie.discard();
			}

			helper.succeed();
		});
	}

	/**
	 * (A5) PULSE blocks NEW entry only: a monster parked inside is never expelled
	 * (it is the pulse zap's prey, live loop and pure call agree), while an
	 * outside-to-inside crossing since the previous tick is pushed back out.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 150, padding = 16)
	public void pulseBlocksOnlyNewEntry(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.getShieldState().mode = ShieldMode.PULSE;
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		Zombie parked = spawnFrozenZombie(helper, new Vec3(4.5, 3.5, 4.5));
		helper.runAfterDelay(30, () -> {
			Vec3 center = shieldCenter(helper);
			try {
				// The live loop ran ~30 barrier passes: the parked zombie must not move.
				double distance = distanceFromCenter(helper, parked);
				helper.assertTrue(
						distance < 4.0,
						"PULSE must not expel a monster already inside, distance is " + distance);

				// Pure crossing check: previous tick outside, current position inside.
				Zombie crossing = spawnFrozenZombie(helper, new Vec3(4.5, 9.5, 4.5));
				try {
					crossing.xo = crossing.getX();
					crossing.yo = crossing.getY();
					crossing.zo = crossing.getZ();
					crossing.setPos(center.x, center.y + 1.0, center.z);
					boolean pushed = ShieldLogic.applyMobBarrier(center, 4.0, be.getShieldState(), crossing, false);
					double crossingDistance = crossing.position().distanceTo(center);
					helper.assertTrue(
							pushed && crossingDistance > 4.0,
							"an outside-to-inside crossing must be pushed back out in PULSE, distance is " + crossingDistance);
				} finally {
					crossing.discard();
				}

				// Pure parked check: previous tick already inside -> left alone.
				helper.assertTrue(
						!ShieldLogic.applyMobBarrier(center, 4.0, be.getShieldState(), parked, false),
						"a monster whose previous position was already inside must not be pushed in PULSE");
			} finally {
				parked.discard();
			}

			helper.succeed();
		});
	}

	/**
	 * (A5) ECO repels no mobs — documented efficiency trade-off: a monster inside
	 * an ECO bubble is neither expelled nor zapped (there is no pulse in ECO).
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void ecoLetsMobsThrough(GameTestHelper helper) {
		helper.assertTrue(!ShieldLogic.mobBarrierBlocksEntry(ShieldMode.ECO), "ECO must not block mob entry");
		helper.assertTrue(ShieldLogic.mobBarrierBlocksEntry(ShieldMode.DEFENSE) && ShieldLogic.mobBarrierBlocksEntry(ShieldMode.PULSE),
				"DEFENSE and PULSE must block mob entry");
		helper.assertTrue(ShieldLogic.mobBarrierExpelsInside(ShieldMode.DEFENSE), "only DEFENSE expels inside monsters");
		helper.assertTrue(!ShieldLogic.mobBarrierExpelsInside(ShieldMode.PULSE) && !ShieldLogic.mobBarrierExpelsInside(ShieldMode.ECO),
				"PULSE and ECO must not expel inside monsters");

		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.getShieldState().mode = ShieldMode.ECO;
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");
		// ECO's x0.75 would give 3, but the MIN_RADIUS floor is applied after all
		// multipliers (fix 10), so the bubble holds 4; park the zombie well inside.
		Zombie zombie = spawnFrozenZombie(helper, new Vec3(4.5, 3.0, 4.5));
		float startHealth = zombie.getHealth();

		// 70 ticks cover at least one would-be 60-tick pulse boundary.
		helper.runAfterDelay(70, () -> {
			double distance = distanceFromCenter(helper, zombie);
			float health = zombie.getHealth();
			try {
				helper.assertTrue(distance < 3.0, "ECO must leave the inside zombie in place, distance is " + distance);
				helper.assertTrue(health == startHealth, "ECO has no pulse zap; the zombie must be unhurt, health is " + health);
			} finally {
				zombie.discard();
			}

			helper.succeed();
		});
	}

	/**
	 * (A5) Boss exemption: the wither and the ender dragon are never
	 * teleport-expelled (teleport-fragile bosses; they still take pulse/nova
	 * DAMAGE), while a plain zombie in the same spot is. The dragon check uses a
	 * constructed (never-spawned) instance — since the fix-6 {@code Mob && Enemy}
	 * partition the dragon DOES reach the barrier through the combined scan, so
	 * this exemption is load-bearing for it.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 100, padding = 16)
	public void bossesAreBarrierExempt(GameTestHelper helper) {
		EnderDragon dragon = new EnderDragon(EntityTypes.ENDER_DRAGON, helper.getLevel());
		helper.assertTrue(ShieldLogic.isBarrierExemptBoss(dragon), "the ender dragon should be barrier-exempt");

		WitherBoss wither = helper.spawn(EntityTypes.WITHER, new Vec3(5.5, 2.5, 4.5));
		wither.setNoAi(true);
		Zombie zombie = spawnFrozenZombie(helper, new Vec3(5.5, 2.5, 4.5));
		helper.assertTrue(ShieldLogic.isBarrierExemptBoss(wither), "the wither should be barrier-exempt");
		helper.assertTrue(!ShieldLogic.isBarrierExemptBoss(zombie), "a zombie is not a barrier-exempt boss");

		// Same synthetic DEFENSE bubble (radius 4 around the structure spot), same
		// inside position: the wither is skipped, the zombie is expelled.
		ShieldState state = new ShieldState();
		state.active = true;
		state.targetRadius = 4.0F;
		Vec3 center = helper.absoluteVec(new Vec3(4.5, 2.5, 4.5));
		try {
			Vec3 witherBefore = wither.position();
			helper.assertTrue(
					!ShieldLogic.applyMobBarrier(center, 4.0, state, wither, true),
					"the barrier must refuse to teleport-expel a wither");
			helper.assertTrue(wither.position().equals(witherBefore), "the exempt wither must not move");

			helper.assertTrue(
					ShieldLogic.applyMobBarrier(center, 4.0, state, zombie, true),
					"the same barrier call must expel a plain zombie");
			double distance = zombie.position().distanceTo(center);
			helper.assertTrue(distance > 4.0, "the expelled zombie should end up outside, distance is " + distance);
		} finally {
			wither.discard();
			zombie.discard();
		}

		helper.succeed();
	}

	/**
	 * (B2) The riposte tier gate: a tier-0 shield ABSORBS (discards) an arrow from
	 * a non-whitelisted shooter — no reflection — for the exact undiscounted 3.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void riposteTierZeroAbsorbsArrow(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		ServerPlayer owner = MockPlayers.createUniqueMockPlayer(helper);
		be.setOwner(owner);
		ServerPlayer shooter = MockPlayers.createUniqueMockPlayer(helper);
		Vec3 center = shieldCenter(helper);
		// Outside the bubble, off the arrow's downward path.
		shooter.snapTo(center.x - 2.5, center.y + 9.5, center.z);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		// Same dive as ShieldGameTests.arrowDamagesShield, but spawned under this
		// arena's ceiling and carrying a resolvable non-whitelisted shooter.
		Arrow arrow = helper.spawn(EntityTypes.ARROW, arrowSpawnAboveBoundary());
		arrow.setOwner(shooter);
		arrow.setDeltaMovement(0.0, -1.5, 0.0);

		helper.succeedWhen(() -> {
			helper.assertTrue(state.maxHealth == 125.0F, "tier 0 at diameter 8 should have maxHealth 125, got " + state.maxHealth);
			helper.assertTrue(
					Math.abs(state.health - 122.0F) <= TOLERANCE,
					"tier 0 should absorb the arrow for the exact 3 (no DR), health is " + state.health);
			helper.assertTrue(arrow.isRemoved(), "tier 0 must ABSORB the arrow (no riposte below tier 1)");
		});
	}

	/**
	 * (B2) The riposte at tier 1: the intercepted arrow survives, is re-owned to
	 * the SHIELD owner (so the original shooter is a valid target and kill credit
	 * goes to the defender), flies back toward its shooter at the deterministic
	 * {@link ShieldLogic#RIPOSTE_SPEED} with pickup DISALLOWED (fix 9d: no free
	 * arrow fountain at the shooter's feet) — while the shield still pays the
	 * exact post-DR price (3 raw x 0.75 = 2.25).
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void riposteTierOneReflectsAtShooter(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		be.getCoreContainer().setItem(0, new ItemStack(ModItems.RESONANT_CORE));
		ServerPlayer owner = MockPlayers.createUniqueMockPlayer(helper);
		be.setOwner(owner);
		ServerPlayer shooter = MockPlayers.createUniqueMockPlayer(helper);
		Vec3 center = shieldCenter(helper);
		// Outside the bubble and BELOW the interception point, so the reflected
		// arrow arcs down into this structure's own floor, never toward a
		// concurrently running neighbor test.
		shooter.snapTo(center.x - 5.5, center.y + 1.0, center.z);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		Arrow arrow = helper.spawn(EntityTypes.ARROW, arrowSpawnAboveBoundary());
		arrow.setOwner(shooter);
		arrow.setDeltaMovement(0.0, -1.5, 0.0);

		helper.succeedWhen(() -> {
			helper.assertTrue(state.maxHealth == 250.0F, "tier 1 at diameter 8 should have maxHealth 250, got " + state.maxHealth);
			helper.assertTrue(
					Math.abs(state.health - 247.75F) <= TOLERANCE,
					"the riposte must still cost the exact post-DR 2.25, health is " + state.health);
			helper.assertTrue(!arrow.isRemoved(), "tier 1 must REFLECT the arrow, not absorb it");
			helper.assertTrue(arrow.getOwner() == owner, "the reflected arrow must be re-owned to the shield owner");
			helper.assertTrue(arrow.pickup == AbstractArrow.Pickup.DISALLOWED,
					"the reflected arrow's pickup must be DISALLOWED (not farmable), got " + arrow.pickup);
			Vec3 toShooter = shooter.position().subtract(arrow.position());
			helper.assertTrue(
					arrow.getDeltaMovement().dot(toShooter) > 0.0,
					"the reflected arrow should be moving back toward its shooter");
			// This callback runs later on the SAME server tick as the block
			// entity's interception (gametests tick after level ticking), so on
			// the first passing evaluation the re-aimed velocity is still the
			// exact riposte launch vector — before drag/gravity touch it.
			double speed = arrow.getDeltaMovement().length();
			helper.assertTrue(Math.abs(speed - ShieldLogic.RIPOSTE_SPEED) <= 0.02,
					"the reflected arrow should launch at RIPOSTE_SPEED " + ShieldLogic.RIPOSTE_SPEED + ", got " + speed);
			// Keep the reflected arrow inside this structure (batch hygiene).
			arrow.discard();
		});
	}

	/**
	 * (B2 / fix 9e) The OWNERLESS-arrow branch at tier 1: the riposte gate needs a
	 * resolvable shooter, so a dispenser-style arrow with no owner is plain
	 * ABSORBED — removed, no reflection, no owner re-attribution (and no NPE on
	 * the null shooter) — for the exact post-DR 2.25 (3 raw x 0.75), and with no
	 * shooter there is nothing to append to the threat log.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void tierOneDispenserArrowIsAbsorbed(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		be.getCoreContainer().setItem(0, new ItemStack(ModItems.RESONANT_CORE));
		ServerPlayer owner = MockPlayers.createUniqueMockPlayer(helper);
		be.setOwner(owner);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		// No setOwner call: the dispenser-fired (ownerless) case.
		Arrow arrow = helper.spawn(EntityTypes.ARROW, arrowSpawnAboveBoundary());
		arrow.setDeltaMovement(0.0, -1.5, 0.0);

		helper.succeedWhen(() -> {
			helper.assertTrue(state.maxHealth == 250.0F, "tier 1 at diameter 8 should have maxHealth 250, got " + state.maxHealth);
			helper.assertTrue(
					Math.abs(state.health - 247.75F) <= TOLERANCE,
					"the ownerless arrow must cost the exact post-DR 2.25, health is " + state.health);
			helper.assertTrue(arrow.isRemoved(),
					"an ownerless arrow must be ABSORBED even at tier 1 (the riposte needs a resolvable shooter)");
			helper.assertTrue(state.threatLog().isEmpty(),
					"no resolvable shooter means nothing lands in the threat log, got " + state.threatLog().size() + " entries");
		});
	}

	/**
	 * (B6b / fix 9f) The siege-alarm rate limit around {@link ShieldLogic#triggerAlarm}:
	 * an alarm EVENT at T opens the comparator override for EXACTLY
	 * {@link ShieldLogic#ALARM_WINDOW_TICKS} real ticks (15 at T+99, back to the
	 * health-based value at T+100), re-triggers up to T+299 are refused without
	 * touching the window, and the boundary re-trigger at exactly T+300
	 * ({@link ShieldLogic#ALARM_REARM_TICKS}) fires a fresh event.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 250, padding = 16)
	public void alarmRearmRateLimit(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		long[] firstEvent = new long[1];
		helper.startSequence()
				.thenExecuteAfter(2, () -> {
					// 75/125 = 60% -> the health-based comparator reads 9, so the
					// 15-override is unambiguous (a FULL shield would read 15 anyway).
					be.applyShieldDamage(50.0F);
					helper.assertTrue(state.health == 75.0F, "damaged health should be 75, got " + state.health);

					long now = helper.getLevel().getGameTime();
					firstEvent[0] = now;
					helper.assertTrue(
							ShieldLogic.triggerAlarm(helper.getLevel(), helper.absolutePos(PROJECTOR_POS), state, now, 4.0),
							"the first alarm event must fire");
					helper.assertTrue(state.alarmUntilGameTime == now + ShieldLogic.ALARM_WINDOW_TICKS,
							"the event must open exactly the " + ShieldLogic.ALARM_WINDOW_TICKS + "-tick window");
					helper.assertTrue(analogSignal(helper) == 15,
							"the open window must override the comparator to 15, got " + analogSignal(helper));

					// Rate limit: same-tick and boundary-minus-one re-triggers are
					// refused and must not touch the open window.
					helper.assertTrue(
							!ShieldLogic.triggerAlarm(helper.getLevel(), helper.absolutePos(PROJECTOR_POS), state, now, 4.0),
							"an immediate re-trigger must be rate-limited");
					helper.assertTrue(
							!ShieldLogic.triggerAlarm(helper.getLevel(), helper.absolutePos(PROJECTOR_POS), state,
									now + ShieldLogic.ALARM_REARM_TICKS - 1, 4.0),
							"a re-trigger " + (ShieldLogic.ALARM_REARM_TICKS - 1) + " ticks after the event must still be rate-limited");
					helper.assertTrue(state.alarmUntilGameTime == now + ShieldLogic.ALARM_WINDOW_TICKS,
							"refused re-triggers must leave the window untouched");
				})
				// Real-time window edge: still alarmed (15) on the window's last tick ...
				.thenExecuteAfter(ShieldLogic.ALARM_WINDOW_TICKS - 1, () -> {
					long now = helper.getLevel().getGameTime();
					helper.assertTrue(now == firstEvent[0] + ShieldLogic.ALARM_WINDOW_TICKS - 1,
							"sequence drift: expected T+" + (ShieldLogic.ALARM_WINDOW_TICKS - 1) + ", at T+" + (now - firstEvent[0]));
					helper.assertTrue(state.isAlarmed(now), "T+99 is the window's last alarmed tick");
					helper.assertTrue(analogSignal(helper) == 15,
							"the comparator must still read 15 on the window's last tick, got " + analogSignal(helper));
				})
				// ... and back to the health-based 9 EXACTLY at T+100.
				.thenExecuteAfter(1, () -> {
					long now = helper.getLevel().getGameTime();
					helper.assertTrue(now == firstEvent[0] + ShieldLogic.ALARM_WINDOW_TICKS,
							"sequence drift: expected T+" + ShieldLogic.ALARM_WINDOW_TICKS + ", at T+" + (now - firstEvent[0]));
					helper.assertTrue(!state.isAlarmed(now), "the window must close exactly at T+100");
					helper.assertTrue(analogSignal(helper) == 9,
							"75/125 reads the health-based 9 after the window, got " + analogSignal(helper));

					// The rearm boundary itself, pinned via the pure gameTime
					// parameter: exactly ALARM_REARM_TICKS after the first event a
					// new event fires and opens its own full window.
					helper.assertTrue(
							ShieldLogic.triggerAlarm(helper.getLevel(), helper.absolutePos(PROJECTOR_POS), state,
									firstEvent[0] + ShieldLogic.ALARM_REARM_TICKS, 4.0),
							"a re-trigger exactly " + ShieldLogic.ALARM_REARM_TICKS + " ticks after the event must fire");
					helper.assertTrue(
							state.alarmUntilGameTime == firstEvent[0] + ShieldLogic.ALARM_REARM_TICKS + ShieldLogic.ALARM_WINDOW_TICKS,
							"the second event must open its own full window");
				})
				.thenSucceed();
	}

	/**
	 * (B6a / fix 9i) The census identity matrix: with the OWNER, a WHITELISTED
	 * friend and a STRANGER player all parked inside the census ring
	 * (radius + 8 = 12), plus one hostile INSIDE the ring and one OUTSIDE it, the
	 * count settles at exactly 2 — the stranger and the in-ring hostile. The
	 * owner, the friend and the out-of-ring hostile never register, across
	 * multiple census passes.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void threatCensusIdentityMatrix(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);

		ServerPlayer owner = MockPlayers.createUniqueMockPlayer(helper);
		be.setOwner(owner);
		ServerPlayer friend = MockPlayers.createUniqueMockPlayer(helper);
		be.whitelistAdd(helper.getLevel().getServer(), friend.getGameProfile().name());
		ServerPlayer stranger = MockPlayers.createUniqueMockPlayer(helper);

		Vec3 center = shieldCenter(helper);
		// All three players outside the radius-4 bubble (no barrier pushes to
		// blur the geometry) but well inside the 12-block census ring.
		owner.snapTo(center.x + 6.0, center.y, center.z);
		friend.snapTo(center.x - 6.0, center.y, center.z);
		stranger.snapTo(center.x, center.y, center.z + 6.0);

		// One hostile inside the ring (7 < 12) ...
		Zombie inRing = spawnFrozenZombie(helper, new Vec3(4.5, 9.5, 4.5));
		// ... and one OUTSIDE it: 13 blocks above the center beats the ring while
		// staying inside this test's own padded cell; no-gravity because it hangs
		// in the open air above the arena ceiling.
		Zombie outOfRing = spawnFrozenZombie(helper, new Vec3(4.5, 15.5, 4.5));
		outOfRing.setNoGravity(true);

		helper.assertTrue(be.tryActivate(), "shield should activate");
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(
						be.threatCount() == 2 && be.getMenuData().get(BubbleShieldMenu.DATA_THREAT_COUNT) == 2,
						"the census must count exactly the stranger + the in-ring hostile (2), got " + be.threatCount()))
				// Two more once-per-second census passes: the count must HOLD at 2
				// (the owner, the friend and the far hostile never join it).
				.thenExecuteAfter(2 * ShieldLogic.THREAT_CENSUS_PERIOD_TICKS + 2, () -> {
					try {
						helper.assertTrue(be.threatCount() == 2,
								"the identity matrix must stay at exactly 2 across later censuses, got " + be.threatCount());
					} finally {
						inRing.discard();
						outOfRing.discard();
					}
				})
				.thenSucceed();
	}

	/**
	 * (B3) Last stand below 25% health: applied damage is halved AFTER the DR cap
	 * (the hit that drops the shield below the threshold is itself full-priced)
	 * and the passive drain burns double units over the same interval.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 150, padding = 16)
	public void lastStandHalvesDamageAndDoublesDrain(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		int[] fuelBefore = new int[1];
		helper.startSequence()
				.thenExecuteAfter(2, () -> {
					helper.assertTrue(state.maxHealth == 125.0F, "tier 0 at diameter 8 should have maxHealth 125, got " + state.maxHealth);
					helper.assertTrue(
							ShieldLogic.drainUnits(4.0F, true) == 2 * ShieldLogic.drainUnits(4.0F, false),
							"last stand must double the drain units");
					helper.assertTrue(!ShieldLogic.isLastStand(state), "a full-health shield is not in last stand");

					// Evaluated BEFORE the hit (125/125): the plunge itself is full-priced.
					be.applyShieldDamage(100.0F);
					helper.assertTrue(state.health == 25.0F, "the full-priced plunge should leave 25, got " + state.health);
					helper.assertTrue(ShieldLogic.isLastStand(state), "25/125 = 20% is below the 25% last-stand threshold");

					// Now in last stand: 10 raw (tier 0, no DR) is halved to 5.
					be.applyShieldDamage(10.0F);
					helper.assertTrue(
							Math.abs(state.health - 20.0F) <= TOLERANCE,
							"last stand should halve the applied damage (25 - 5 = 20), got " + state.health);
					fuelBefore[0] = state.fuelSeconds;
				})
				// 40 ticks = exactly two 20-tick drain events; each burns
				// drainUnits(4, lastStand=true) = 2. Tier 0 is combat-gated (hit
				// above), so no regen pulse can burn a masking extra fuel-second.
				.thenExecuteAfter(40, () -> {
					int used = fuelBefore[0] - state.fuelSeconds;
					helper.assertTrue(
							used == 4,
							"last-stand drain should burn 2 events x 2 units = 4 over 40 ticks, used " + used);
				})
				.thenSucceed();
	}

	/**
	 * (B5) The break shockwave nova: at the moment the shield breaks (here via the
	 * direct applyShieldDamage path, which routes through the same shared break
	 * routine as interception breaks), every hostile mob ({@code Mob && Enemy},
	 * fix 6) inside the pre-break radius takes exactly 8 magic damage, while a
	 * whitelisted player standing inside is untouched — and (fix 6) a
	 * CUSTOM-NAMED hostile is skipped by the nova only: a nametagged "pet" zombie
	 * in the blast zone stays unhurt.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 100, padding = 16)
	public void breakNovaDamagesMonstersSparesPlayers(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		// ECO: no mob barrier and no pulse zap, so the zombie sits inside unharmed
		// until the break — the nova is the only thing that can hurt it.
		state.mode = ShieldMode.ECO;
		ServerPlayer owner = MockPlayers.createCapturingMockPlayer(helper, GameType.SURVIVAL).player();
		be.setOwner(owner);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		Vec3 center = shieldCenter(helper);
		// ECO radius floors at MIN_RADIUS 4 (fix 10): park everyone well inside it.
		owner.snapTo(center.x + 1.0, center.y, center.z);
		Zombie zombie = spawnFrozenZombie(helper, new Vec3(4.5, 3.5, 4.5));
		// Deterministic magic-damage math: no randomly rolled (possibly
		// Protection-enchanted) spawn gear beyond the fixed plain helmet.
		zombie.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
		zombie.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY);
		zombie.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
		zombie.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		zombie.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
		// Fix 6: a custom-named "pet" hostile in the same blast zone is skipped
		// by the nova (and by the nova ONLY).
		Zombie namedPet = spawnFrozenZombie(helper, new Vec3(3.5, 3.5, 4.5));
		namedPet.setCustomName(Component.literal("Chomper"));

		helper.runAfterDelay(5, () -> {
			float zombieStart = zombie.getHealth();
			float petStart = namedPet.getHealth();
			float ownerStart = owner.getHealth();
			double distance = distanceFromCenter(helper, zombie);
			double petDistance = distanceFromCenter(helper, namedPet);
			try {
				helper.assertTrue(state.active, "the shield should still be active before the overkill hit");
				helper.assertTrue(distance <= be.currentRadius(), "the zombie should sit inside the pre-break radius");
				helper.assertTrue(petDistance <= be.currentRadius(), "the named zombie should sit inside the pre-break radius");

				be.applyShieldDamage(100000.0F);
				helper.assertTrue(!state.active, "the overkill hit should break the shield");
				helper.assertTrue(
						Math.abs(zombie.getHealth() - (zombieStart - ShieldLogic.NOVA_DAMAGE)) <= TOLERANCE,
						"the break nova should deal exactly 8 magic damage to the inside zombie, health went "
								+ zombieStart + " -> " + zombie.getHealth());
				helper.assertTrue(
						namedPet.getHealth() == petStart,
						"the nova must skip custom-named hostiles, pet health went " + petStart + " -> " + namedPet.getHealth());
				helper.assertTrue(
						owner.getHealth() == ownerStart,
						"the nova must never hurt players, owner health went " + ownerStart + " -> " + owner.getHealth());
			} finally {
				zombie.discard();
				namedPet.discard();
			}

			helper.succeed();
		});
	}

	/**
	 * (B6a) DATA_THREAT_COUNT: the once-per-second census counts a hostile monster
	 * inside radius + 8, drops back to 0 when it is gone, and any deactivation
	 * clears a stale count immediately.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 300, padding = 16)
	public void threatCountTracksAndClears(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");
		helper.assertTrue(be.threatCount() == 0, "a freshly activated shield with no threats should report 0");

		// Outside the bubble (7 > 4) but inside the census ring (7 < 4 + 8).
		Zombie zombie = spawnFrozenZombie(helper, new Vec3(4.5, 9.5, 4.5));
		Zombie[] second = new Zombie[1];
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(
						be.threatCount() == 1 && be.getMenuData().get(BubbleShieldMenu.DATA_THREAT_COUNT) == 1,
						"the census should count the ring zombie via the slot, got " + be.threatCount()))
				.thenExecute(zombie::discard)
				.thenWaitUntil(() -> helper.assertTrue(
						be.threatCount() == 0,
						"the census should drop to 0 after the zombie is gone, got " + be.threatCount()))
				.thenExecute(() -> second[0] = spawnFrozenZombie(helper, new Vec3(4.5, 9.5, 4.5)))
				.thenWaitUntil(() -> helper.assertTrue(
						be.threatCount() == 1,
						"the census should count the second zombie, got " + be.threatCount()))
				.thenExecute(() -> be.setActive(false))
				.thenExecuteAfter(3, () -> {
					try {
						helper.assertTrue(
								be.threatCount() == 0 && be.getMenuData().get(BubbleShieldMenu.DATA_THREAT_COUNT) == 0,
								"deactivation must clear the stale threat count, got " + be.threatCount());
					} finally {
						second[0].discard();
					}
				})
				.thenSucceed();
	}

	/**
	 * (B6b) The siege-alarm comparator override: direct applyShieldDamage never
	 * alarms (the comparator stays health-based, keeping redstone health readouts
	 * deterministic), a REAL projectile interception overrides the output to 15
	 * for the 100-tick window, and afterwards it reverts to the health value.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 300, padding = 16)
	public void alarmOverridesComparatorAfterInterception(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.startSequence()
				.thenExecuteAfter(2, () -> {
					helper.assertTrue(state.maxHealth == 125.0F, "tier 0 at diameter 8 should have maxHealth 125, got " + state.maxHealth);
					// The direct path: damage lands, NO alarm (75/125 = 60% -> 9).
					be.applyShieldDamage(50.0F);
					helper.assertTrue(state.health == 75.0F, "damaged health should be 75, got " + state.health);
					helper.assertTrue(
							!state.isAlarmed(helper.getLevel().getGameTime()),
							"direct applyShieldDamage must never trigger the siege alarm");
					helper.assertTrue(analogSignal(helper) == 9, "without an alarm the comparator is health-based (9), got " + analogSignal(helper));

					// A real interception: an ownerless arrow diving into the bubble.
					Arrow arrow = helper.spawn(EntityTypes.ARROW, arrowSpawnAboveBoundary());
					arrow.setDeltaMovement(0.0, -1.5, 0.0);
				})
				.thenWaitUntil(() -> helper.assertTrue(state.health == 72.0F, "waiting for the arrow interception (75 - 3)"))
				.thenExecute(() -> {
					helper.assertTrue(
							state.isAlarmed(helper.getLevel().getGameTime()),
							"a projectile interception must open the alarm window");
					helper.assertTrue(analogSignal(helper) == 15, "the alarm window must override the comparator to 15, got " + analogSignal(helper));
				})
				// 72/125 = 57.6% -> round(8.64) = 9 once the window has passed.
				.thenExecuteAfter(ShieldLogic.ALARM_WINDOW_TICKS + 10, () -> {
					helper.assertTrue(
							!state.isAlarmed(helper.getLevel().getGameTime()),
							"the alarm window should have expired");
					helper.assertTrue(analogSignal(helper) == 9, "after the window the comparator is health-based again (9), got " + analogSignal(helper));
				})
				.thenSucceed();
	}

	/**
	 * (B6c) The threat log: an interception with a resolvable shooter appends
	 * {name, post-DR damage, gameTime}; the ring buffer clamps at 8 (oldest
	 * dropped), sanitizes hostile input, and round-trips through NBT.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void threatLogRecordsShooterAndClamps(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		ServerPlayer shooter = MockPlayers.createUniqueMockPlayer(helper);
		Vec3 center = shieldCenter(helper);
		shooter.snapTo(center.x - 2.5, center.y + 9.5, center.z);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");
		helper.assertTrue(state.threatLog().isEmpty(), "the threat log starts empty");

		Arrow arrow = helper.spawn(EntityTypes.ARROW, arrowSpawnAboveBoundary());
		arrow.setOwner(shooter);
		arrow.setDeltaMovement(0.0, -1.5, 0.0);

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(!state.threatLog().isEmpty(), "waiting for the interception to log the shooter"))
				.thenExecute(() -> {
					List<ShieldState.ThreatLogEntry> log = state.threatLog();
					helper.assertTrue(log.size() == 1, "exactly one interception should be logged, got " + log.size());
					ShieldState.ThreatLogEntry entry = log.get(0);
					helper.assertTrue(
							entry.attackerName().equals(shooter.getGameProfile().name()),
							"the logged attacker should be the shooter, got '" + entry.attackerName() + "'");
					helper.assertTrue(
							Math.abs(entry.damage() - 3.0F) <= TOLERANCE,
							"the logged damage is this shield's post-DR share (tier 0: 3.0), got " + entry.damage());
					helper.assertTrue(entry.gameTime() > 0L, "the logged game time should be positive");

					// Pure ring-buffer semantics: 10 appends keep the LAST 8.
					ShieldState pure = new ShieldState();
					for (int i = 0; i < 10; i++) {
						pure.recordThreat("raider" + i, 1.0F, 100L + i);
					}
					helper.assertTrue(
							pure.threatLog().size() == ShieldState.THREAT_LOG_MAX,
							"the log must clamp at 8 entries, got " + pure.threatLog().size());
					helper.assertTrue(
							pure.threatLog().get(0).attackerName().equals("raider2")
									&& pure.threatLog().get(7).attackerName().equals("raider9"),
							"the ring buffer must drop the OLDEST entries");

					// Sanitization: names cap at 16 chars, damage/gameTime clamp at 0,
					// and a name that sanitizes to empty is dropped entirely.
					pure.recordThreat("ABCDEFGHIJKLMNOPQRST", -5.0F, -3L);
					ShieldState.ThreatLogEntry sanitized = pure.threatLog().get(ShieldState.THREAT_LOG_MAX - 1);
					helper.assertTrue(
							sanitized.attackerName().equals("ABCDEFGHIJKLMNOP") && sanitized.damage() == 0.0F && sanitized.gameTime() == 0L,
							"a hostile entry must be sanitized on append, got '" + sanitized.attackerName() + "' / "
									+ sanitized.damage() + " / " + sanitized.gameTime());
					int sizeBefore = pure.threatLog().size();
					pure.recordThreat("   ", 1.0F, 1L);
					helper.assertTrue(pure.threatLog().size() == sizeBefore, "a name sanitizing to empty must be dropped");

					// NBT round trip through the block entity save/load path.
					var registries = helper.getLevel().registryAccess();
					CompoundTag tag = be.saveCustomOnly(registries);
					BubbleShieldBlockEntity loaded = new BubbleShieldBlockEntity(helper.absolutePos(PROJECTOR_POS), be.getBlockState());
					loaded.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
					List<ShieldState.ThreatLogEntry> reloaded = loaded.getShieldState().threatLog();
					helper.assertTrue(
							reloaded.size() == 1 && reloaded.get(0).attackerName().equals(shooter.getGameProfile().name()),
							"the threat log should round-trip through NBT");
				})
				.thenSucceed();
	}

	/**
	 * (B6 boss bar) While the alarm window is open the boss bar name carries the
	 * localized UNDER ATTACK suffix, and it reverts to the plain name once the
	 * window expires.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 300, padding = 16)
	public void bossBarAlarmSuffixAppearsAndReverts(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		be.setCustomName("Home Base");
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		// E7: the boss bar name always carries the health readout quantized to 5%
		// steps; one 3-damage arrow on the 125-HP shield (97.6%) still quantizes
		// to 100%, so the percent part stays stable across this whole sequence and
		// only the alarm suffix comes and goes ("<name> · NN% — UNDER ATTACK!").
		String namedWithPercent = "Home Base \u00b7 100%";
		helper.startSequence()
				.thenExecuteAfter(2, () -> {
					ServerBossEvent event = be.getBossEvent();
					helper.assertTrue(event != null, "an active shield should have created its boss event");
					helper.assertTrue(
							namedWithPercent.equals(event.getName().getString()),
							"before the alarm the boss bar shows name + percent, got '" + event.getName().getString() + "'");

					Arrow arrow = helper.spawn(EntityTypes.ARROW, arrowSpawnAboveBoundary());
					arrow.setDeltaMovement(0.0, -1.5, 0.0);
				})
				.thenWaitUntil(() -> helper.assertTrue(state.health < state.maxHealth, "waiting for the arrow interception"))
				.thenExecuteAfter(2, () -> {
					helper.assertTrue(
							state.isAlarmed(helper.getLevel().getGameTime()),
							"the interception should have opened the alarm window");
					String name = be.getBossEvent().getName().getString();
					helper.assertTrue(
							name.startsWith(namedWithPercent) && !name.equals(namedWithPercent),
							"the alarmed boss bar should append the UNDER ATTACK suffix after the percent, got '" + name + "'");
				})
				.thenExecuteAfter(ShieldLogic.ALARM_WINDOW_TICKS + 10, () -> {
					helper.assertTrue(
							!state.isAlarmed(helper.getLevel().getGameTime()),
							"the alarm window should have expired");
					String name = be.getBossEvent().getName().getString();
					helper.assertTrue(
							namedWithPercent.equals(name),
							"the suffix must revert with the window, got '" + name + "'");
				})
				.thenSucceed();
	}
}

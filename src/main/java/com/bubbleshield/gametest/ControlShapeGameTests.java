package com.bubbleshield.gametest;

import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.registry.ModBlocks;
import com.bubbleshield.shield.ShieldGeometry;
import com.bubbleshield.shield.ShieldLogic;
import com.bubbleshield.shield.ShieldShape;
import com.bubbleshield.shield.ShieldState;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Coverage for milestone M5: comparator output, redstone edge control,
 * projectile-type-specific interactions and the dome shield shape.
 */
public class ControlShapeGameTests {
	private static final BlockPos PROJECTOR_POS = new BlockPos(4, 2, 4);
	private static final int PLENTY_OF_FUEL = 600;

	private static BubbleShieldBlockEntity placeProjector(GameTestHelper helper, float targetRadius) {
		helper.setBlock(PROJECTOR_POS, ModBlocks.BUBBLE_SHIELD_PROJECTOR);
		BubbleShieldBlockEntity be = helper.getBlockEntity(PROJECTOR_POS, BubbleShieldBlockEntity.class);
		be.getShieldState().targetRadius = targetRadius;
		return be;
	}

	/** Reads the projector's comparator (analog output) signal through the BlockState wrapper. */
	private static int analogSignal(GameTestHelper helper) {
		BlockPos absolute = helper.absolutePos(PROJECTOR_POS);
		return helper.getLevel().getBlockState(absolute).getAnalogOutputSignal(helper.getLevel(), absolute, Direction.NORTH);
	}

	@GameTest(padding = 16)
	public void comparatorTracksHealth(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.assertTrue(analogSignal(helper) == 15, "an active full-health shield should output 15, got " + analogSignal(helper));

		be.applyShieldDamage(50.0F);
		helper.assertTrue(state.health == 50.0F, "damaged health should be 50, got " + state.health);
		helper.assertTrue(analogSignal(helper) == 8, "an active half-health shield should output round(7.5) = 8, got " + analogSignal(helper));

		be.setActive(false);
		int expected = Math.min(15, state.fuelSeconds / 200);
		helper.assertTrue(
				analogSignal(helper) == expected,
				"an inactive shield should output the fuel-based value " + expected + ", got " + analogSignal(helper));
		helper.succeed();
	}

	@GameTest(maxTicks = 100, padding = 16)
	public void redstoneActivates(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(!be.getShieldState().active, "shield should start inactive");

		// Placing a redstone block next to the projector fires neighborChanged (setBlock
		// uses UPDATE_ALL flags): a rising edge that must activate the fueled shield.
		BlockPos redstonePos = PROJECTOR_POS.east();
		helper.setBlock(redstonePos, Blocks.REDSTONE_BLOCK);
		helper.runAfterDelay(2, () -> {
			helper.assertTrue(be.isPowered(), "the projector should observe the redstone block");
			helper.assertTrue(be.getShieldState().active, "a rising redstone edge should activate the fueled shield");

			// Removing the redstone block is a falling edge that must deactivate.
			helper.setBlock(redstonePos, Blocks.AIR);
			helper.runAfterDelay(2, () -> {
				helper.assertTrue(!be.isPowered(), "the projector should observe the redstone block being removed");
				helper.assertTrue(!be.getShieldState().active, "a falling redstone edge should deactivate the shield");
				helper.succeed();
			});
		});
	}

	/**
	 * A projector placed NEXT TO an already-powered block must seed powered=true without
	 * acting, so a later unrelated neighbor update is not misread as a rising edge
	 * (pre-fix, it spuriously activated the fueled shield).
	 */
	@GameTest(maxTicks = 100, padding = 16)
	public void poweredSeededAtPlacement(GameTestHelper helper) {
		BlockPos redstonePos = PROJECTOR_POS.east();
		helper.setBlock(redstonePos, Blocks.REDSTONE_BLOCK);

		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);

		helper.runAfterDelay(2, () -> {
			helper.assertTrue(be.isPowered(), "the projector should seed powered=true from the pre-existing signal");
			helper.assertTrue(!be.getShieldState().active, "seeding the powered flag must not activate the shield");

			// An unrelated neighbor update while the signal is steady: no edge, no activation.
			helper.setBlock(PROJECTOR_POS.west(), Blocks.STONE);
			helper.runAfterDelay(2, () -> {
				helper.assertTrue(!be.getShieldState().active, "an unrelated neighbor update must not fake a rising edge");
				helper.assertTrue(be.isPowered(), "the powered flag should still reflect the steady signal");

				// The edge behaviour is intact: removing the signal is a falling edge (no-op
				// on an inactive shield), re-adding it is a genuine rising edge that activates.
				helper.setBlock(redstonePos, Blocks.AIR);
				helper.runAfterDelay(2, () -> {
					helper.assertTrue(!be.isPowered(), "removing the redstone block should be observed as unpowered");
					helper.setBlock(redstonePos, Blocks.REDSTONE_BLOCK);
					helper.runAfterDelay(2, () -> {
						helper.assertTrue(be.getShieldState().active, "a genuine rising edge should still activate the fueled shield");
						helper.succeed();
					});
				});
			});
		});
	}

	@GameTest(maxTicks = 200, padding = 16)
	public void fireballDeflected(GameTestHelper helper) {
		// Radius 8 puts the boundary crossing (relative y=10.5) in the open air above the
		// barrier shell the framework encases the 8x8x8 structure in; a smaller radius
		// would have projectiles hit that barrier ceiling before reaching the shield.
		BubbleShieldBlockEntity be = placeProjector(helper, 8.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		// Block center (relative) is at (4.5, 2.5, 4.5). Spawn the fireball above the
		// boundary inside the structure's own chunk column (only those chunks
		// entity-tick during a game test), moving straight down-inward.
		SmallFireball fireball = helper.spawn(EntityTypes.SMALL_FIREBALL, new Vec3(4.5, 14.5, 4.5));
		fireball.setDeltaMovement(0.0, -1.5, 0.0);

		ShieldState state = be.getShieldState();
		Vec3 center = Vec3.atCenterOf(helper.absolutePos(PROJECTOR_POS));
		helper.succeedWhen(() -> {
			helper.assertTrue(fireball.isAlive(), "the deflected fireball must stay alive (not be absorbed)");
			helper.assertTrue(
					state.health == state.maxHealth - ShieldLogic.HURTING_PROJECTILE_DAMAGE,
					"deflecting a fireball should cost exactly " + ShieldLogic.HURTING_PROJECTILE_DAMAGE + " shield health, health is " + state.health);
			Vec3 away = fireball.position().subtract(center);
			helper.assertTrue(
					fireball.getDeltaMovement().dot(away) > 0.0,
					"the deflected fireball should move away from the shield center");
		});
	}

	@GameTest(maxTicks = 200, padding = 16)
	public void tridentDeflected(GameTestHelper helper) {
		// Radius 8: see fireballDeflected for the barrier-ceiling reasoning.
		BubbleShieldBlockEntity be = placeProjector(helper, 8.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		// ThrownTrident extends AbstractArrow: this proves the classification matches the
		// trident branch (deflect + 4) before the generic arrow absorb branch (discard + 5).
		ThrownTrident trident = helper.spawn(EntityTypes.TRIDENT, new Vec3(4.5, 14.5, 4.5));
		trident.setDeltaMovement(0.0, -1.5, 0.0);

		ShieldState state = be.getShieldState();
		Vec3 center = Vec3.atCenterOf(helper.absolutePos(PROJECTOR_POS));
		helper.succeedWhen(() -> {
			helper.assertTrue(trident.isAlive(), "the deflected trident must stay alive (not be absorbed)");
			// Gravity can drop a deflected trident back in for another deflection, so
			// assert at-least-one 4-damage hit instead of an exact final value.
			helper.assertTrue(
					state.health <= state.maxHealth - ShieldLogic.TRIDENT_DAMAGE,
					"deflecting a trident should cost " + ShieldLogic.TRIDENT_DAMAGE + " shield health per hit, health is " + state.health);
			Vec3 away = trident.position().subtract(center);
			helper.assertTrue(
					trident.getDeltaMovement().dot(away) > 0.0,
					"the deflected trident should move away from the shield center");
		});
	}

	@GameTest(maxTicks = 200, padding = 16)
	public void snowballAbsorbed(GameTestHelper helper) {
		// Radius 8: see fireballDeflected for the barrier-ceiling reasoning.
		BubbleShieldBlockEntity be = placeProjector(helper, 8.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		Snowball snowball = helper.spawn(EntityTypes.SNOWBALL, new Vec3(4.5, 14.5, 4.5));
		snowball.setDeltaMovement(0.0, -1.5, 0.0);

		ShieldState state = be.getShieldState();
		helper.succeedWhen(() -> {
			helper.assertTrue(snowball.isRemoved(), "an intercepted snowball should be absorbed (discarded)");
			helper.assertTrue(
					state.health == state.maxHealth - ShieldLogic.THROWN_DAMAGE,
					"absorbing a snowball should cost exactly " + ShieldLogic.THROWN_DAMAGE + " shield health, health is " + state.health);
		});
	}

	@GameTest(padding = 16)
	public void domeGeometry(GameTestHelper helper) {
		// Pure geometry: a dome only contains points at or above the center plane.
		Vec3 center = new Vec3(0.0, 10.0, 0.0);
		helper.assertTrue(ShieldGeometry.isInside(ShieldShape.DOME, center, 6.0, new Vec3(0.0, 12.0, 0.0)), "a point above the center should be inside the dome");
		helper.assertTrue(!ShieldGeometry.isInside(ShieldShape.DOME, center, 6.0, new Vec3(0.0, 8.0, 0.0)), "a point below the center should be outside the dome");
		helper.assertTrue(ShieldGeometry.isInside(ShieldShape.SPHERE, center, 6.0, new Vec3(0.0, 8.0, 0.0)), "the same point below the center should be inside the sphere");
		helper.assertTrue(!ShieldGeometry.isInside(ShieldShape.DOME, center, 6.0, new Vec3(0.0, 17.0, 0.0)), "a point above the center but past the radius should be outside");
		helper.assertTrue(
				ShieldGeometry.crossedInto(ShieldShape.DOME, center, 6.0, new Vec3(3.0, 9.0, 0.0), new Vec3(3.0, 11.0, 0.0)),
				"rising through the dome's bottom plane should count as crossing in");
		helper.assertTrue(
				!ShieldGeometry.crossedInto(ShieldShape.DOME, center, 6.0, new Vec3(0.0, 12.0, 0.0), new Vec3(1.0, 12.0, 0.0)),
				"movement fully inside the dome should not count as crossing in");
		helper.assertTrue(
				!ShieldGeometry.crossedInto(ShieldShape.SPHERE, center, 6.0, new Vec3(0.0, 12.0, 0.0), new Vec3(0.0, 20.0, 0.0)),
				"leaving the shield should not count as crossing in");

		// Integration: a dome-shaped barrier ignores blocked players below the center
		// plane (even within the sphere radius) but expels them above it.
		BubbleShieldBlockEntity be = placeProjector(helper, 6.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		be.getShieldState().shape = ShieldShape.DOME;
		helper.assertTrue(be.tryActivate(), "shield should activate");

		ShieldState state = be.getShieldState();
		Vec3 shieldCenter = Vec3.atCenterOf(helper.absolutePos(PROJECTOR_POS));
		double radius = be.currentRadius();

		Player stranger = helper.makeMockPlayer(GameType.SURVIVAL);
		Vec3 below = shieldCenter.add(4.0, -1.0, 0.0);
		stranger.snapTo(below.x, below.y, below.z);
		helper.assertTrue(
				!ShieldLogic.applyPlayerBarrier(shieldCenter, radius, state, stranger),
				"a player below the dome's center plane should not be pushed");
		helper.assertTrue(stranger.position().equals(below), "the below-plane player should not have moved");

		Vec3 above = shieldCenter.add(4.0, 1.0, 0.0);
		stranger.snapTo(above.x, above.y, above.z);
		helper.assertTrue(
				ShieldLogic.applyPlayerBarrier(shieldCenter, radius, state, stranger),
				"the same player above the dome's center plane should be pushed");
		helper.assertTrue(
				!ShieldGeometry.isInside(ShieldShape.DOME, shieldCenter, radius, stranger.position()),
				"the pushed player should end up outside the dome");
		helper.succeed();
	}

	/** Keeps the compiler honest about the classification order in {@link ShieldLogic}. */
	@GameTest
	public void projectileTaxonomy(GameTestHelper helper) {
		helper.assertTrue(
				net.minecraft.world.entity.projectile.arrow.AbstractArrow.class.isAssignableFrom(ThrownTrident.class),
				"ThrownTrident must extend AbstractArrow (classification order relies on it)");
		helper.assertTrue(
				Projectile.class.isAssignableFrom(SmallFireball.class),
				"SmallFireball must be a Projectile");
		helper.succeed();
	}
}

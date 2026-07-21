package com.bubbleshield.gametest;

import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.effect.behaviors.BehaviorSupport;
import com.bubbleshield.menu.BubbleShieldMenu;
import com.bubbleshield.registry.ModBlocks;
import com.bubbleshield.shield.ShieldGeometry;
import com.bubbleshield.shield.ShieldLogic;
import com.bubbleshield.shield.ShieldShape;
import com.bubbleshield.shield.ShieldState;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * Coverage for the four post-DOME shield shapes (CYLINDER, CUBE, DIAMOND, RING):
 * the exact inscribed containment math in {@link ShieldGeometry} and its
 * load-bearing subset-of-the-ball invariant, the shape-aware
 * {@link BehaviorSupport#containPoint} projections (containment, idempotence and
 * the identity-instance guarantee that keeps legacy sphere/dome emissions
 * byte-identical), NBT ordinal round-trip + clamp hardening, the player-barrier
 * integration per shape (including the RING's deliberately passable hole and the
 * open space below a CYLINDER's bottom cap), and the settings cycle through all
 * six ordinals.
 */
public class ShapeGameTests {
	/**
	 * A dedicated (but otherwise vanilla-default) test environment,
	 * {@code data/bubbleshield/test_environment/shapes.json}: the vanilla runner
	 * batches 50 tests per environment and ticks a batch in parallel, so new test
	 * classes get their own batch instead of growing (and reshuffling) the shared
	 * default one — see AGENTS.md and {@code ColorGameTests.ISOLATED_ENVIRONMENT}.
	 */
	private static final String ISOLATED_ENVIRONMENT = "bubbleshield:shapes";
	private static final BlockPos PROJECTOR_POS = new BlockPos(4, 2, 4);
	private static final int PLENTY_OF_FUEL = 600;
	private static final ShieldShape[] NEW_SHAPES = {ShieldShape.CYLINDER, ShieldShape.CUBE, ShieldShape.DIAMOND, ShieldShape.RING};

	private static BubbleShieldBlockEntity placeProjector(GameTestHelper helper, float targetRadius) {
		helper.setBlock(PROJECTOR_POS, ModBlocks.BUBBLE_SHIELD_PROJECTOR);
		BubbleShieldBlockEntity be = helper.getBlockEntity(PROJECTOR_POS, BubbleShieldBlockEntity.class);
		be.getShieldState().targetRadius = targetRadius;
		return be;
	}

	/**
	 * (a) Pure geometry per shape: hand-picked inside/outside boundary points for
	 * the exact inscribed dimensions (cylinder 0.6r/±0.8r, cube r/sqrt(3), diamond
	 * L1 <= r, torus R = 0.7r / a = 0.3r incl. the passable hole), the RING's
	 * crossing behaviour, and the subset-of-the-ball property over seeded random
	 * samples — the invariant every radius-parameterized system (search AABB,
	 * barrier pushback, interception, renderer cull) silently relies on.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void shapeGeometryProperties(GameTestHelper helper) {
		Vec3 c = new Vec3(0.0, 50.0, 0.0);
		double r = 10.0;

		// CYLINDER: horizontal radius 6, half-height 8 (3-4-5: the rim corner sits
		// exactly on the sphere).
		helper.assertTrue(ShieldGeometry.isInside(ShieldShape.CYLINDER, c, r, c.add(5.9, 7.9, 0.0)),
				"a point just inside the cylinder rim corner should be inside");
		helper.assertTrue(ShieldGeometry.isInside(ShieldShape.CYLINDER, c, r, c.add(6.0, 8.0, 0.0)),
				"the exact cylinder rim corner (0.6r, 0.8r) should be inside (boundary included)");
		helper.assertTrue(!ShieldGeometry.isInside(ShieldShape.CYLINDER, c, r, c.add(6.1, 0.0, 0.0)),
				"a point past the cylinder's horizontal radius should be outside");
		helper.assertTrue(!ShieldGeometry.isInside(ShieldShape.CYLINDER, c, r, c.add(0.0, 8.1, 0.0)),
				"a point above the cylinder's top cap should be outside");
		helper.assertTrue(!ShieldGeometry.isInside(ShieldShape.CYLINDER, c, r, c.add(7.0, 0.0, 0.0)),
				"a point inside the sphere but past 0.6r horizontally should be outside the cylinder");

		// CUBE: half-extent 10/sqrt(3) ~ 5.7735.
		double cubeHalf = ShieldGeometry.CUBE_HALF_EXTENT_FRAC * r;
		helper.assertTrue(ShieldGeometry.isInside(ShieldShape.CUBE, c, r, c.add(5.7, 5.7, 5.7)),
				"a point just inside the cube corner should be inside");
		helper.assertTrue(ShieldGeometry.isInside(ShieldShape.CUBE, c, r, c.add(cubeHalf, cubeHalf, cubeHalf)),
				"the exact cube corner (r/sqrt(3) each axis) should be inside (boundary included)");
		helper.assertTrue(!ShieldGeometry.isInside(ShieldShape.CUBE, c, r, c.add(5.8, 0.0, 0.0)),
				"a point past the cube's half-extent on one axis should be outside");
		helper.assertTrue(!ShieldGeometry.isInside(ShieldShape.CUBE, c, r, c.add(0.0, 0.0, 6.0)),
				"a point inside the sphere but past r/sqrt(3) should be outside the cube");

		// DIAMOND: the L1 ball of radius 10.
		helper.assertTrue(ShieldGeometry.isInside(ShieldShape.DIAMOND, c, r, c.add(3.0, 3.0, 3.0)),
				"an L1-9 point should be inside the diamond");
		helper.assertTrue(ShieldGeometry.isInside(ShieldShape.DIAMOND, c, r, c.add(10.0, 0.0, 0.0)),
				"the octahedron vertex (r on one axis) should be inside (boundary included)");
		helper.assertTrue(!ShieldGeometry.isInside(ShieldShape.DIAMOND, c, r, c.add(4.0, 4.0, 4.0)),
				"an L1-12 point should be outside the diamond even though it is inside the sphere");

		// RING: torus R = 7, a = 3 — with the hole (center and axis OUTSIDE).
		helper.assertTrue(ShieldGeometry.isInside(ShieldShape.RING, c, r, c.add(7.0, 0.0, 0.0)),
				"a point on the ring circle should be inside");
		helper.assertTrue(ShieldGeometry.isInside(ShieldShape.RING, c, r, c.add(10.0, 0.0, 0.0)),
				"the torus outer equator (R + a = r) should be inside (boundary included)");
		helper.assertTrue(ShieldGeometry.isInside(ShieldShape.RING, c, r, c.add(4.1, 0.0, 0.0)),
				"a point just outside the inner hole radius (R - a) should be inside the tube");
		helper.assertTrue(!ShieldGeometry.isInside(ShieldShape.RING, c, r, c.add(3.9, 0.0, 0.0)),
				"a point just inside the inner hole radius should be outside (the hole is open)");
		helper.assertTrue(!ShieldGeometry.isInside(ShieldShape.RING, c, r, c),
				"the RING's center must be OUTSIDE (the hole is a documented feature)");
		helper.assertTrue(!ShieldGeometry.isInside(ShieldShape.RING, c, r, c.add(0.0, 2.0, 0.0)),
				"the RING's vertical axis must be outside too");
		helper.assertTrue(!ShieldGeometry.isInside(ShieldShape.RING, c, r, c.add(7.0, 3.1, 0.0)),
				"a point above the tube should be outside");

		// RING crossing behaviour: a drop straight down the axis NEVER crosses in
		// (so projectiles through the hole are never intercepted), while a drop
		// onto the tube does.
		helper.assertTrue(!ShieldGeometry.crossedInto(ShieldShape.RING, c, r, c.add(0.0, 10.0, 0.0), c.add(0.0, -5.0, 0.0)),
				"falling down the RING's axis must never count as crossing in");
		helper.assertTrue(ShieldGeometry.crossedInto(ShieldShape.RING, c, r, c.add(7.0, 10.0, 0.0), c.add(7.0, 0.0, 0.0)),
				"falling onto the RING's tube must count as crossing in");

		// Anti-tunneling: a single-tick segment that passes CLEAN THROUGH the tube
		// (both endpoints outside — tube top 3, bottom -3 at rho = 7) must still
		// count as an inward crossing (the subsampled sweep), while the axis drop
		// above stays false (every subsample is in the hole) and a fast segment
		// that never touches the tube stays false too.
		helper.assertTrue(ShieldGeometry.crossedInto(ShieldShape.RING, c, r, c.add(7.0, 4.0, 0.0), c.add(7.0, -4.0, 0.0)),
				"a fast projectile tunneling straight through the RING tube in one tick must count as crossing in");
		helper.assertTrue(!ShieldGeometry.crossedInto(ShieldShape.RING, c, r, c.add(0.0, 14.0, 0.0), c.add(0.0, -14.0, 0.0)),
				"a fast fall down the RING's axis must STILL never count as crossing in (hole identity)");
		helper.assertTrue(!ShieldGeometry.crossedInto(ShieldShape.RING, c, r, c.add(20.0, 4.0, 0.0), c.add(20.0, -4.0, 0.0)),
				"a fast segment far outside the tube must not count as crossing in");

		// The subset-of-the-ball property, sampled: isInside(shape) implies the
		// point is within the shield radius of the center, for every shape.
		RandomSource random = RandomSource.create(0xB0BB1E5L);
		for (ShieldShape shape : ShieldShape.values()) {
			int inside = 0;
			for (int i = 0; i < 4000; i++) {
				Vec3 p = c.add(
						(random.nextDouble() * 2.4 - 1.2) * r,
						(random.nextDouble() * 2.4 - 1.2) * r,
						(random.nextDouble() * 2.4 - 1.2) * r);
				if (ShieldGeometry.isInside(shape, c, r, p)) {
					inside++;
					helper.assertTrue(p.distanceTo(c) <= r * (1.0 + 1.0e-9),
							shape + " violates the subset-of-the-ball invariant at " + p
									+ " (distance " + p.distanceTo(c) + " > radius " + r + ")");
				}
			}

			helper.assertTrue(inside > 0, "the sample should hit the inside of " + shape + " at least once");
		}

		helper.succeed();
	}

	/**
	 * (b) The shape-aware {@link BehaviorSupport#containPoint} contract for every
	 * shape: the result always satisfies {@code ShieldGeometry.isInside}, the
	 * projection is idempotent, already-contained points come back as the SAME
	 * {@link Vec3} instance (what keeps legacy sphere/dome emissions
	 * byte-identical), and hand-picked outside points land on their exact
	 * per-shape projections.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void containPointProperties(GameTestHelper helper) {
		Vec3 c = new Vec3(100.5, 64.5, -200.5);
		double r = 10.0;
		double f = BehaviorSupport.MAX_DIST_FRAC;

		// Random property pass: the result is always contained, and the projection
		// is (near-)idempotent — a re-projection may re-touch a boundary point by
		// an ulp when the recomputed distance rounds up, so idempotence is asserted
		// by distance while the strict identity-instance guarantee is asserted on
		// clearly-inside points below.
		RandomSource random = RandomSource.create(0x5EEDL);
		for (ShieldShape shape : ShieldShape.values()) {
			for (int i = 0; i < 500; i++) {
				Vec3 p = c.add(
						(random.nextDouble() * 3.0 - 1.5) * r,
						(random.nextDouble() * 3.0 - 1.5) * r,
						(random.nextDouble() * 3.0 - 1.5) * r);
				Vec3 contained = BehaviorSupport.containPoint(shape, c, r, p);
				helper.assertTrue(ShieldGeometry.isInside(shape, c, r, contained),
						shape + " containPoint result " + contained + " for " + p + " must satisfy isInside");
				Vec3 again = BehaviorSupport.containPoint(shape, c, r, contained);
				helper.assertTrue(again.distanceTo(contained) < 1.0e-9,
						shape + " containPoint must be idempotent for " + p + ": " + contained + " -> " + again);
			}
		}

		// Identity instances for clearly-inside points, per shape.
		Vec3 nearCenter = c.add(0.5, 0.5, 0.5);
		for (ShieldShape shape : new ShieldShape[] {ShieldShape.SPHERE, ShieldShape.DOME, ShieldShape.CYLINDER, ShieldShape.CUBE, ShieldShape.DIAMOND}) {
			helper.assertTrue(BehaviorSupport.containPoint(shape, c, r, nearCenter) == nearCenter,
					shape + " must return the SAME instance for an inside point");
		}

		Vec3 onRing = c.add(ShieldGeometry.RING_MAJOR_FRAC * r, 0.0, 0.0);
		helper.assertTrue(BehaviorSupport.containPoint(ShieldShape.RING, c, r, onRing) == onRing,
				"RING must return the SAME instance for a point on the ring circle");

		// Exact projections for hand-picked outside points.
		double eps = 1.0e-9;

		// CYLINDER: dy clamps to +-0.8 * 0.98r; (dx, dz) rescales onto 0.6 * 0.98r.
		Vec3 aboveCap = BehaviorSupport.containPoint(ShieldShape.CYLINDER, c, r, c.add(0.0, 20.0, 0.0));
		helper.assertTrue(aboveCap.distanceTo(c.add(0.0, ShieldGeometry.CYLINDER_HALF_HEIGHT_FRAC * r * f, 0.0)) < eps,
				"CYLINDER should clamp a point above the cap onto +0.784r, got " + aboveCap);
		Vec3 pastWall = BehaviorSupport.containPoint(ShieldShape.CYLINDER, c, r, c.add(12.0, 0.0, 0.0));
		helper.assertTrue(pastWall.distanceTo(c.add(ShieldGeometry.CYLINDER_RADIUS_FRAC * r * f, 0.0, 0.0)) < eps,
				"CYLINDER should rescale a point past the wall onto 0.588r, got " + pastWall);

		// CUBE: per-axis clamp to +-0.98r / sqrt(3); untouched axes stay put.
		double cubeMax = ShieldGeometry.CUBE_HALF_EXTENT_FRAC * r * f;
		Vec3 cubeProjected = BehaviorSupport.containPoint(ShieldShape.CUBE, c, r, c.add(10.0, -20.0, 3.0));
		helper.assertTrue(cubeProjected.distanceTo(c.add(cubeMax, -cubeMax, 3.0)) < eps,
				"CUBE should clamp each out-of-range axis independently, got " + cubeProjected);

		// DIAMOND: direction-preserving rescale by 0.98r / L1.
		Vec3 diamondProjected = BehaviorSupport.containPoint(ShieldShape.DIAMOND, c, r, c.add(10.0, 10.0, 0.0));
		helper.assertTrue(diamondProjected.distanceTo(c.add(4.9, 4.9, 0.0)) < eps,
				"DIAMOND should rescale an L1-20 offset onto L1 = 9.8, got " + diamondProjected);

		// RING: the shield center projects toward the +x ring point (axis rule)
		// and stops at tube distance 0.98 * 0.3r = 2.94.
		Vec3 ringFromCenter = BehaviorSupport.containPoint(ShieldShape.RING, c, r, c);
		double major = ShieldGeometry.RING_MAJOR_FRAC * r;
		double tube = ShieldGeometry.RING_MINOR_FRAC * r * f;
		helper.assertTrue(ringFromCenter.distanceTo(c.add(major - tube, 0.0, 0.0)) < eps,
				"RING should pull the center to (R - 0.294r, 0, 0) relative, got " + ringFromCenter);
		// A generic off-axis point: pulled straight toward its nearest ring point.
		Vec3 outsideTube = c.add(0.0, 5.0, 14.0);
		Vec3 ringProjected = BehaviorSupport.containPoint(ShieldShape.RING, c, r, outsideTube);
		Vec3 nearestRingPoint = c.add(0.0, 0.0, major);
		Vec3 expected = nearestRingPoint.add(outsideTube.subtract(nearestRingPoint).scale(tube / outsideTube.distanceTo(nearestRingPoint)));
		helper.assertTrue(ringProjected.distanceTo(expected) < eps,
				"RING should pull an outside point onto tube distance 0.294r toward its nearest ring point, got "
						+ ringProjected + " expected " + expected);

		// DOME keeps its legacy two-step projection (clamp the half-space, then the
		// sphere rescale) — the below-plane point lands on the plane.
		Vec3 belowPlane = BehaviorSupport.containPoint(ShieldShape.DOME, c, r, c.add(1.0, -3.0, 0.0));
		helper.assertTrue(belowPlane.distanceTo(c.add(1.0, 0.0, 0.0)) < eps,
				"DOME should clamp a below-plane point up to the center plane, got " + belowPlane);
		helper.succeed();
	}

	/**
	 * (c) Every shape ordinal round-trips through {@link ShieldState} NBT save/load,
	 * and tampered ordinals (99, -1 — /data or NBT editors) clamp back to SPHERE via
	 * {@code byOrdinal}, the same hardening as mode/beam.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void shapeNbtRoundTripAndClamp(GameTestHelper helper) {
		var registries = helper.getLevel().registryAccess();

		for (ShieldShape shape : ShieldShape.values()) {
			ShieldState original = new ShieldState();
			original.shape = shape;
			TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
			original.save(output);
			CompoundTag tag = output.buildResult();
			helper.assertTrue(tag.contains("shape"), "saved shield NBT should include shape");

			ShieldState loaded = new ShieldState();
			loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
			helper.assertTrue(loaded.shape == shape,
					"shape should round-trip through NBT, expected " + shape + " got " + loaded.shape);
		}

		// Legacy saves (no key) default to ordinal 0 = SPHERE.
		ShieldState legacy = new ShieldState();
		legacy.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, new CompoundTag()));
		helper.assertTrue(legacy.shape == ShieldShape.SPHERE,
				"NBT without shape should load as SPHERE, got " + legacy.shape);

		for (int invalid : new int[] {99, -1, ShieldShape.values().length}) {
			TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
			new ShieldState().save(output);
			CompoundTag tag = output.buildResult();
			tag.putInt("shape", invalid);
			ShieldState tampered = new ShieldState();
			tampered.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
			helper.assertTrue(tampered.shape == ShieldShape.SPHERE,
					"a tampered shape ordinal (" + invalid + ") must clamp to SPHERE on load, got " + tampered.shape);
		}

		helper.succeed();
	}

	/**
	 * (d) Barrier integration per shape (the {@code domeGeometry} pattern): a
	 * non-whitelisted survival player inside a CYLINDER / CUBE / DIAMOND / RING
	 * tube is pushed out (and really ends up outside), while a player in the
	 * RING's hole or below the CYLINDER's bottom cap is NOT pushed — those open
	 * regions are the shapes' documented gameplay identity.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, padding = 16)
	public void barrierExpelsPerShape(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 6.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		be.getShieldState().shape = ShieldShape.CYLINDER;
		helper.assertTrue(be.tryActivate(), "shield should activate");

		ShieldState state = be.getShieldState();
		Vec3 center = Vec3.atCenterOf(helper.absolutePos(PROJECTOR_POS));
		double radius = be.currentRadius();
		Player stranger = helper.makeMockPlayer(GameType.SURVIVAL);

		// Inside points per shape (radius 6: cylinder 3.6/4.8, cube half 3.46,
		// diamond L1 6, torus R 4.2 / a 1.8).
		record ShapeCase(ShieldShape shape, Vec3 insideOffset) {
		}

		for (ShapeCase testCase : new ShapeCase[] {
				new ShapeCase(ShieldShape.CYLINDER, new Vec3(2.0, 0.5, 0.0)),
				new ShapeCase(ShieldShape.CUBE, new Vec3(2.0, 1.0, 1.0)),
				new ShapeCase(ShieldShape.DIAMOND, new Vec3(1.5, 1.5, 1.0)),
				new ShapeCase(ShieldShape.RING, new Vec3(4.2, 0.5, 0.0))}) {
			state.shape = testCase.shape();
			Vec3 inside = center.add(testCase.insideOffset());
			helper.assertTrue(ShieldGeometry.isInside(testCase.shape(), center, radius, inside),
					testCase.shape() + " test point should start inside");
			stranger.snapTo(inside.x, inside.y, inside.z);
			helper.assertTrue(ShieldLogic.applyPlayerBarrier(center, radius, state, stranger),
					"a blocked player inside a " + testCase.shape() + " should be pushed");
			helper.assertTrue(!ShieldGeometry.isInside(testCase.shape(), center, radius, stranger.position()),
					"the pushed player should end up outside the " + testCase.shape());
		}

		// The RING's hole is passable: standing on the axis is NOT inside, so the
		// barrier must leave the player alone.
		state.shape = ShieldShape.RING;
		Vec3 inHole = center.add(0.3, 0.0, 0.0);
		stranger.snapTo(inHole.x, inHole.y, inHole.z);
		helper.assertTrue(!ShieldLogic.applyPlayerBarrier(center, radius, state, stranger),
				"a player in the RING's central hole must not be pushed");
		helper.assertTrue(stranger.position().equals(inHole), "the in-hole player should not have moved");

		// Below a CYLINDER's bottom cap (within the sphere radius) is open space.
		state.shape = ShieldShape.CYLINDER;
		Vec3 belowCap = center.add(0.5, -5.0, 0.0);
		stranger.snapTo(belowCap.x, belowCap.y, belowCap.z);
		helper.assertTrue(!ShieldLogic.applyPlayerBarrier(center, radius, state, stranger),
				"a player below the CYLINDER's bottom cap must not be pushed");
		helper.assertTrue(stranger.position().equals(belowCap), "the below-cap player should not have moved");
		helper.succeed();
	}

	/**
	 * (f) End-to-end anti-tunneling: a FAST arrow (~4 blocks/tick, faster than the
	 * small RING's 2.4-block tube diameter at radius 4) dropped straight onto the
	 * tube crosses it entirely within one tick — endpoint sampling alone would
	 * never see it inside. The subsampled {@link ShieldGeometry#crossedInto} must
	 * intercept it anyway: the arrow is absorbed (discarded) and the shield takes
	 * damage.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void fastArrowCannotTunnelThroughRing(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		be.getShieldState().shape = ShieldShape.RING;
		helper.assertTrue(be.tryActivate(), "shield should activate");

		// Center (relative) is (4.5, 2.5, 4.5); the radius-4 RING's tube circle is
		// at rho = 2.8, so the tube spans y 1.3..3.7 at (7.3, _, 4.5). Spawn just
		// above the tube and fall at ~4 blocks/tick: one move tick goes from above
		// the tube (outside) to below it (outside), straddling the whole tube.
		Arrow arrow = helper.spawn(EntityTypes.ARROW, new Vec3(7.3, 4.7, 4.5));
		arrow.setDeltaMovement(0.0, -4.0, 0.0);

		ShieldState state = be.getShieldState();
		helper.succeedWhen(() -> {
			helper.assertTrue(arrow.isRemoved(), "the tunneling arrow should have been absorbed (discarded)");
			helper.assertTrue(state.health < state.maxHealth, "the shield should take damage from the intercepted fast arrow");
		});
	}

	/**
	 * (e) The settings path accepts every shape ordinal: state and the DATA_SHAPE
	 * menu slot agree after each set, hostile ordinals clamp via {@code byOrdinal},
	 * and the ServerNet-mirror clamp expression maps any payload value into the
	 * (now larger) valid range.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void settingsCycleThroughAllShapes(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 6.0F);

		for (ShieldShape shape : ShieldShape.values()) {
			be.setSettings(16, 0, shape.ordinal(), 0, false, 0);
			helper.assertTrue(be.getShieldState().shape == shape,
					"setSettings should store " + shape + ", got " + be.getShieldState().shape);
			helper.assertTrue(be.getMenuData().get(BubbleShieldMenu.DATA_SHAPE) == shape.ordinal(),
					"DATA_SHAPE should mirror the stored " + shape + " ordinal");
		}

		// A hostile ordinal fed straight into the setter clamps to SPHERE (byOrdinal).
		be.setSettings(16, 0, 99, 0, false, 0);
		helper.assertTrue(be.getShieldState().shape == ShieldShape.SPHERE,
				"setSettings(99) should clamp the shape to SPHERE, got " + be.getShieldState().shape);

		// The receiver-side clamp expression (mirrored from ServerNet's SetSettings
		// handler) auto-adapts to the grown enum: 99 maps to the last shape, -7 to SPHERE.
		int max = ShieldShape.values().length - 1;
		helper.assertTrue(Mth.clamp(99, 0, max) == max && ShieldShape.byOrdinal(Mth.clamp(99, 0, max)) == ShieldShape.RING,
				"the receiver clamp should map 99 to the last shape (RING)");
		helper.assertTrue(Mth.clamp(-7, 0, max) == 0 && ShieldShape.byOrdinal(Mth.clamp(-7, 0, max)) == ShieldShape.SPHERE,
				"the receiver clamp should map negatives to SPHERE");
		helper.assertTrue(NEW_SHAPES.length == 4 && ShieldShape.values().length == 6,
				"the shape enum should have exactly the six documented values");
		helper.succeed();
	}
}

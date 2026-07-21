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
 * Coverage for the eight post-DOME shield shapes (CYLINDER, CUBE, DIAMOND, RING,
 * PYRAMID, LENS, HOURGLASS, STAR): the exact inscribed containment math in
 * {@link ShieldGeometry} and its load-bearing subset-of-the-ball invariant, the
 * shape-aware {@link BehaviorSupport#containPoint} projections (containment,
 * idempotence and the identity-instance guarantee that keeps legacy sphere/dome
 * emissions byte-identical), NBT ordinal round-trip + clamp hardening, the
 * player-barrier integration per shape (including the RING's deliberately
 * passable hole, the open space below a CYLINDER's bottom cap and the
 * HOURGLASS's pinched-open waist), and the settings cycle through all ten
 * ordinals.
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
	private static final ShieldShape[] NEW_SHAPES = {
			ShieldShape.CYLINDER, ShieldShape.CUBE, ShieldShape.DIAMOND, ShieldShape.RING,
			ShieldShape.PYRAMID, ShieldShape.LENS, ShieldShape.HOURGLASS, ShieldShape.STAR};

	private static BubbleShieldBlockEntity placeProjector(GameTestHelper helper, float targetRadius) {
		helper.setBlock(PROJECTOR_POS, ModBlocks.BUBBLE_SHIELD_PROJECTOR);
		BubbleShieldBlockEntity be = helper.getBlockEntity(PROJECTOR_POS, BubbleShieldBlockEntity.class);
		be.getShieldState().targetRadius = targetRadius;
		return be;
	}

	/**
	 * (a) Pure geometry per shape: hand-picked inside/outside boundary points for
	 * the exact inscribed dimensions (cylinder 0.6r/±0.8r, cube r/sqrt(3), diamond
	 * L1 <= r, torus R = 0.7r / a = 0.3r incl. the passable hole, pyramid apex
	 * +0.9r / base -0.5r / half-extent 0.6r, lens oblate spheroid r x 0.45r,
	 * hourglass double cone ±0.8r / 0.55r incl. the passable waist, star prism
	 * ±0.55r / R(theta) in [0.30r, 0.80r] incl. the shallow lobe gaps), the
	 * RING's/HOURGLASS's/STAR's crossing behaviour, and the subset-of-the-ball
	 * property over seeded random samples — the invariant every
	 * radius-parameterized system (search AABB, barrier pushback, interception,
	 * renderer cull) silently relies on.
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

		// PYRAMID: apex +9, base plane -5, base half-extent 6, taper 6*(9-dy)/14.
		helper.assertTrue(ShieldGeometry.isInside(ShieldShape.PYRAMID, c, r, c.add(2.9, 2.0, 0.0)),
				"a point just inside the pyramid taper at dy=2 (limit 3.0) should be inside");
		helper.assertTrue(ShieldGeometry.isInside(ShieldShape.PYRAMID, c, r, c.add(0.0, 9.0, 0.0)),
				"the exact pyramid apex (+0.9r) should be inside (boundary included)");
		helper.assertTrue(ShieldGeometry.isInside(ShieldShape.PYRAMID, c, r, c.add(6.0, -5.0, 6.0)),
				"the exact pyramid base corner (0.6r, -0.5r, 0.6r) should be inside (boundary included)");
		helper.assertTrue(!ShieldGeometry.isInside(ShieldShape.PYRAMID, c, r, c.add(0.0, 9.1, 0.0)),
				"a point above the pyramid apex should be outside");
		helper.assertTrue(!ShieldGeometry.isInside(ShieldShape.PYRAMID, c, r, c.add(3.1, 2.0, 0.0)),
				"a point past the pyramid taper at dy=2 should be outside even though it is inside the sphere");
		helper.assertTrue(!ShieldGeometry.isInside(ShieldShape.PYRAMID, c, r, c.add(0.0, -5.1, 0.0)),
				"a point below the pyramid base plane should be outside");

		// LENS: oblate spheroid, semi-axes r horizontally and 0.45r vertically.
		helper.assertTrue(ShieldGeometry.isInside(ShieldShape.LENS, c, r, c.add(9.9, 0.0, 0.0)),
				"a point just inside the lens equator should be inside");
		helper.assertTrue(ShieldGeometry.isInside(ShieldShape.LENS, c, r, c.add(10.0, 0.0, 0.0)),
				"the exact lens equator (r) should be inside (boundary included)");
		helper.assertTrue(ShieldGeometry.isInside(ShieldShape.LENS, c, r, c.add(0.0, 4.5, 0.0)),
				"the exact lens pole (0.45r) should be inside (boundary included)");
		helper.assertTrue(!ShieldGeometry.isInside(ShieldShape.LENS, c, r, c.add(0.0, 4.6, 0.0)),
				"a point above the lens pole should be outside");
		helper.assertTrue(ShieldGeometry.isInside(ShieldShape.LENS, c, r, c.add(7.0, 3.1, 0.0)),
				"a mid-latitude point just inside the lens surface should be inside");
		helper.assertTrue(!ShieldGeometry.isInside(ShieldShape.LENS, c, r, c.add(7.1, 3.2, 0.0)),
				"a point just past the lens surface should be outside even though it is inside the sphere");

		// HOURGLASS: two cones tip-to-tip, band ±8, taper 0.55r*|dy|/(0.8r).
		helper.assertTrue(ShieldGeometry.isInside(ShieldShape.HOURGLASS, c, r, c.add(5.4, 7.9, 0.0)),
				"a point just inside the upper cone near its cap should be inside");
		helper.assertTrue(ShieldGeometry.isInside(ShieldShape.HOURGLASS, c, r, c.add(5.5, 8.0, 0.0)),
				"the exact hourglass rim corner (0.55r, 0.8r) should be inside (boundary included)");
		helper.assertTrue(ShieldGeometry.isInside(ShieldShape.HOURGLASS, c, r, c.add(3.0, -6.0, 0.0)),
				"a point inside the lower cone should be inside");
		helper.assertTrue(ShieldGeometry.isInside(ShieldShape.HOURGLASS, c, r, c),
				"the exact center (the cones' shared tip) should be inside (boundary included)");
		helper.assertTrue(!ShieldGeometry.isInside(ShieldShape.HOURGLASS, c, r, c.add(0.0, 8.1, 0.0)),
				"a point above the hourglass cap should be outside");
		helper.assertTrue(!ShieldGeometry.isInside(ShieldShape.HOURGLASS, c, r, c.add(1.0, 0.0, 0.0)),
				"an off-axis point on the waist plane must be OUTSIDE (the pinched waist is open)");
		helper.assertTrue(!ShieldGeometry.isInside(ShieldShape.HOURGLASS, c, r, c.add(2.0, 1.0, 0.0)),
				"a point past the taper near the waist should be outside even though it is inside the sphere");

		// HOURGLASS crossing behaviour: skimming horizontally past the pinched
		// waist never crosses in (every subsample stays outside the cones), while
		// a fast drop through a cone does.
		helper.assertTrue(!ShieldGeometry.crossedInto(ShieldShape.HOURGLASS, c, r, c.add(-5.0, 1.0, 1.0), c.add(5.0, 1.0, 1.0)),
				"a fast horizontal segment skimming past the hourglass waist must not count as crossing in");
		helper.assertTrue(ShieldGeometry.crossedInto(ShieldShape.HOURGLASS, c, r, c.add(3.0, 10.0, 0.0), c.add(3.0, -1.0, 0.0)),
				"a fast drop straight through the upper cone must count as crossing in");

		// STAR: six-lobed prism, band ±5.5, R(theta) = 5.5 + 2.5*cos(6*theta)
		// (8 toward a lobe at theta=0, 3 in the gap at theta=30 degrees).
		double gapCos = Math.cos(Math.PI / 6.0);
		double gapSin = Math.sin(Math.PI / 6.0);
		helper.assertTrue(ShieldGeometry.isInside(ShieldShape.STAR, c, r, c.add(7.9, 0.0, 0.0)),
				"a point just inside a star lobe tip should be inside");
		helper.assertTrue(ShieldGeometry.isInside(ShieldShape.STAR, c, r, c.add(8.0, 0.0, 0.0)),
				"the exact star lobe tip (0.8r) should be inside (boundary included)");
		helper.assertTrue(!ShieldGeometry.isInside(ShieldShape.STAR, c, r, c.add(8.1, 0.0, 0.0)),
				"a point past a star lobe tip should be outside");
		helper.assertTrue(ShieldGeometry.isInside(ShieldShape.STAR, c, r, c.add(2.9 * gapCos, 0.0, 2.9 * gapSin)),
				"a point just inside the shallow lobe gap (limit 0.3r) should be inside");
		helper.assertTrue(!ShieldGeometry.isInside(ShieldShape.STAR, c, r, c.add(3.1 * gapCos, 0.0, 3.1 * gapSin)),
				"a point just past the lobe gap radius should be outside even though it is inside the sphere");
		helper.assertTrue(ShieldGeometry.isInside(ShieldShape.STAR, c, r, c.add(2.0, 5.4, 0.0)),
				"a point just below the star prism's top cap should be inside");
		helper.assertTrue(!ShieldGeometry.isInside(ShieldShape.STAR, c, r, c.add(2.0, 5.6, 0.0)),
				"a point above the star prism's top cap should be outside");

		// STAR crossing behaviour: a fast horizontal chord through the shallow gap
		// (both endpoints outside R(30deg) = 3) still crosses the interior and must
		// be caught by the subsampled sweep.
		helper.assertTrue(ShieldGeometry.crossedInto(ShieldShape.STAR, c, r,
						c.add(5.0 * gapCos, 0.0, 5.0 * gapSin), c.add(-5.0 * gapCos, 0.0, -5.0 * gapSin)),
				"a fast chord through the star's shallow gap must count as crossing in");

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
		for (ShieldShape shape : new ShieldShape[] {ShieldShape.SPHERE, ShieldShape.DOME, ShieldShape.CYLINDER,
				ShieldShape.CUBE, ShieldShape.DIAMOND, ShieldShape.PYRAMID, ShieldShape.LENS, ShieldShape.STAR}) {
			helper.assertTrue(BehaviorSupport.containPoint(shape, c, r, nearCenter) == nearCenter,
					shape + " must return the SAME instance for an inside point");
		}

		Vec3 onRing = c.add(ShieldGeometry.RING_MAJOR_FRAC * r, 0.0, 0.0);
		helper.assertTrue(BehaviorSupport.containPoint(ShieldShape.RING, c, r, onRing) == onRing,
				"RING must return the SAME instance for a point on the ring circle");

		// The HOURGLASS excludes nearCenter (waist taper 0.6875 * 0.5 < rho), so
		// its identity point sits deep inside the upper cone instead.
		Vec3 inUpperCone = c.add(0.5, 4.0, 0.5);
		helper.assertTrue(BehaviorSupport.containPoint(ShieldShape.HOURGLASS, c, r, inUpperCone) == inUpperCone,
				"HOURGLASS must return the SAME instance for a point inside the upper cone");

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

		// PYRAMID: dy clamps into the 0.98-scaled band [-0.5, +0.9] * 0.98r; the
		// apex taper is zero, and a side point clamps per-axis onto the taper at
		// its (unchanged) height.
		Vec3 aboveApex = BehaviorSupport.containPoint(ShieldShape.PYRAMID, c, r, c.add(0.0, 20.0, 0.0));
		helper.assertTrue(aboveApex.distanceTo(c.add(0.0, ShieldGeometry.PYRAMID_APEX_FRAC * r * f, 0.0)) < eps,
				"PYRAMID should clamp a point above the apex onto +0.882r, got " + aboveApex);
		double taperAtCenter = ShieldGeometry.pyramidTaper(r * f, 0.0);
		Vec3 pastFace = BehaviorSupport.containPoint(ShieldShape.PYRAMID, c, r, c.add(10.0, 0.0, 0.0));
		helper.assertTrue(pastFace.distanceTo(c.add(taperAtCenter, 0.0, 0.0)) < eps,
				"PYRAMID should clamp a side point onto the taper at its height, got " + pastFace);

		// LENS: homogeneous rescale by 0.98 / norm — the pole projection lands on
		// 0.45 * 0.98r, and an oblique breach preserves its direction.
		Vec3 abovePole = BehaviorSupport.containPoint(ShieldShape.LENS, c, r, c.add(0.0, 20.0, 0.0));
		helper.assertTrue(abovePole.distanceTo(c.add(0.0, ShieldGeometry.LENS_HALF_HEIGHT_FRAC * r * f, 0.0)) < eps,
				"LENS should rescale a point above the pole onto 0.441r, got " + abovePole);
		double lensB = ShieldGeometry.LENS_HALF_HEIGHT_FRAC * r;
		double lensNorm = Math.sqrt((12.0 * 12.0) / (r * r) + (6.0 * 6.0) / (lensB * lensB));
		Vec3 lensProjected = BehaviorSupport.containPoint(ShieldShape.LENS, c, r, c.add(12.0, 6.0, 0.0));
		helper.assertTrue(lensProjected.distanceTo(c.add(12.0 * f / lensNorm, 6.0 * f / lensNorm, 0.0)) < eps,
				"LENS should rescale an oblique breach by 0.98/norm, got " + lensProjected);

		// HOURGLASS: dy clamps to ±0.8 * 0.98r (rho already under the cap taper
		// stays put), and a waist-ward point rescales onto 0.98 * the taper.
		Vec3 aboveCone = BehaviorSupport.containPoint(ShieldShape.HOURGLASS, c, r, c.add(5.0, 20.0, 0.0));
		helper.assertTrue(aboveCone.distanceTo(c.add(5.0, ShieldGeometry.HOURGLASS_HALF_HEIGHT_FRAC * r * f, 0.0)) < eps,
				"HOURGLASS should clamp a point above the cap onto +0.784r keeping its rho, got " + aboveCone);
		double waistTaper = ShieldGeometry.hourglassTaper(r, 4.0) * f;
		Vec3 pastCone = BehaviorSupport.containPoint(ShieldShape.HOURGLASS, c, r, c.add(10.0, 4.0, 0.0));
		helper.assertTrue(pastCone.distanceTo(c.add(waistTaper, 4.0, 0.0)) < eps,
				"HOURGLASS should rescale a point past the cone onto 0.98 * taper(dy), got " + pastCone);

		// STAR: (dx, dz) rescales onto 0.98 * R(theta) — 0.8r toward a lobe,
		// 0.3r into the shallow gap — with theta preserved.
		Vec3 pastLobe = BehaviorSupport.containPoint(ShieldShape.STAR, c, r, c.add(20.0, 0.0, 0.0));
		helper.assertTrue(pastLobe.distanceTo(c.add(0.8 * r * f, 0.0, 0.0)) < eps,
				"STAR should rescale a lobe-direction breach onto 0.784r, got " + pastLobe);
		double gapCos = Math.cos(Math.PI / 6.0);
		double gapSin = Math.sin(Math.PI / 6.0);
		double gapRadius = 0.3 * r * f;
		Vec3 pastGap = BehaviorSupport.containPoint(ShieldShape.STAR, c, r, c.add(20.0 * gapCos, 0.0, 20.0 * gapSin));
		helper.assertTrue(pastGap.distanceTo(c.add(gapRadius * gapCos, 0.0, gapRadius * gapSin)) < eps,
				"STAR should rescale a gap-direction breach onto 0.294r along the same theta, got " + pastGap);

		// Fly-toward dip safety at the minimum shield radius 4: the default
		// vertical mid-dip anchor (center + (0, 0.6, 0)) and its FLY_TOWARD_DIP
		// undershoot (center - (0, 0.6, 0)) must be contained for every new shape,
		// which is what lets flyTowardAnchor keep its default path for them.
		Vec3 smallCenter = new Vec3(-40.5, 80.5, 77.5);
		double smallRadius = 4.0;
		Vec3 anchor = smallCenter.add(0.0, BehaviorSupport.FLY_TOWARD_DIP * 0.5, 0.0);
		Vec3 dipped = anchor.subtract(0.0, BehaviorSupport.FLY_TOWARD_DIP, 0.0);
		for (ShieldShape shape : new ShieldShape[] {ShieldShape.PYRAMID, ShieldShape.LENS, ShieldShape.HOURGLASS, ShieldShape.STAR}) {
			helper.assertTrue(BehaviorSupport.isContained(shape, smallCenter, smallRadius, anchor),
					shape + " must contain the default fly-toward anchor at radius 4");
			helper.assertTrue(BehaviorSupport.isContained(shape, smallCenter, smallRadius, dipped),
					shape + " must contain the fly-toward anchor's dip undershoot at radius 4");
		}

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
	 * tube / PYRAMID / LENS / HOURGLASS cone / STAR lobe is pushed out (and
	 * really ends up outside), while a player in the RING's hole, below the
	 * CYLINDER's bottom cap or beside the HOURGLASS's pinched waist is NOT
	 * pushed — those open regions are the shapes' documented gameplay identity.
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
		// diamond L1 6, torus R 4.2 / a 1.8, pyramid taper 2.1 at dy=0.5, lens
		// 6 x 2.7, hourglass taper 2.06 at dy=3, star lobe 4.8 at theta=0).
		record ShapeCase(ShieldShape shape, Vec3 insideOffset) {
		}

		for (ShapeCase testCase : new ShapeCase[] {
				new ShapeCase(ShieldShape.CYLINDER, new Vec3(2.0, 0.5, 0.0)),
				new ShapeCase(ShieldShape.CUBE, new Vec3(2.0, 1.0, 1.0)),
				new ShapeCase(ShieldShape.DIAMOND, new Vec3(1.5, 1.5, 1.0)),
				new ShapeCase(ShieldShape.RING, new Vec3(4.2, 0.5, 0.0)),
				new ShapeCase(ShieldShape.PYRAMID, new Vec3(1.5, 0.5, 0.0)),
				new ShapeCase(ShieldShape.LENS, new Vec3(2.0, 1.0, 0.0)),
				new ShapeCase(ShieldShape.HOURGLASS, new Vec3(1.5, 3.0, 0.0)),
				new ShapeCase(ShieldShape.STAR, new Vec3(3.0, 0.5, 0.0))}) {
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

		// The HOURGLASS's pinched waist is open: an off-axis point on the center
		// plane (taper 0 there) is NOT inside, so the barrier must leave the
		// player alone — the waist's documented passable-region identity.
		state.shape = ShieldShape.HOURGLASS;
		Vec3 besideWaist = center.add(1.5, 0.0, 0.0);
		stranger.snapTo(besideWaist.x, besideWaist.y, besideWaist.z);
		helper.assertTrue(!ShieldLogic.applyPlayerBarrier(center, radius, state, stranger),
				"a player beside the HOURGLASS's pinched waist must not be pushed");
		helper.assertTrue(stranger.position().equals(besideWaist), "the beside-waist player should not have moved");
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
	 * (f2) End-to-end anti-tunneling through a STAR lobe gap: at radius 4 the gap
	 * direction (theta = 30 degrees) is only R = 0.3r = 1.2 blocks, so the whole
	 * 2.4-block chord through the center fits inside one ~5 blocks/tick segment —
	 * both endpoints land outside and endpoint sampling alone would never see the
	 * arrow inside. The subsampled {@link ShieldGeometry#crossedInto} must
	 * intercept it anyway: the arrow is absorbed and the shield takes damage.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void fastArrowCannotTunnelThroughStarGap(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		be.getShieldState().shape = ShieldShape.STAR;
		helper.assertTrue(be.tryActivate(), "shield should activate");

		// Center (relative) is (4.5, 2.5, 4.5). Fly along the gap azimuth at
		// dy = +1.5 (inside the ±2.2 band, above the projector block): from
		// rho = 3.3 on one side, one ~5-block tick ends at rho ~ 1.7 on the
		// opposite side — outside R = 1.2 at both ends, straddling the whole gap
		// chord, with interior subsamples at rho ~ 0.3 and ~0.7 (inside).
		double gapCos = Math.cos(Math.PI / 6.0);
		double gapSin = Math.sin(Math.PI / 6.0);
		Arrow arrow = helper.spawn(EntityTypes.ARROW, new Vec3(4.5 + 3.3 * gapCos, 4.0, 4.5 + 3.3 * gapSin));
		arrow.setDeltaMovement(-5.0 * gapCos, 0.0, -5.0 * gapSin);

		ShieldState state = be.getShieldState();
		helper.succeedWhen(() -> {
			helper.assertTrue(arrow.isRemoved(), "the gap-tunneling arrow should have been absorbed (discarded)");
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
		helper.assertTrue(Mth.clamp(99, 0, max) == max && ShieldShape.byOrdinal(Mth.clamp(99, 0, max)) == ShieldShape.STAR,
				"the receiver clamp should map 99 to the last shape (STAR)");
		helper.assertTrue(Mth.clamp(-7, 0, max) == 0 && ShieldShape.byOrdinal(Mth.clamp(-7, 0, max)) == ShieldShape.SPHERE,
				"the receiver clamp should map negatives to SPHERE");
		helper.assertTrue(NEW_SHAPES.length == 8 && ShieldShape.values().length == 10,
				"the shape enum should have exactly the ten documented values");
		helper.succeed();
	}
}

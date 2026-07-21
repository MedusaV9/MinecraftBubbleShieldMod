package com.bubbleshield.shield;

import net.minecraft.world.phys.Vec3;

/**
 * Pure shape-aware containment math shared by the barrier, the expel pass and
 * projectile interception, so every server-side check agrees on what "inside the
 * shield" means for each {@link ShieldShape}.
 *
 * <p><b>The load-bearing invariant — every shape is a SUBSET of the closed ball of
 * radius {@code r}:</b> a point inside any shape is always within {@code r} of the
 * center. This is what keeps ALL existing radius-parameterized code correct without
 * modification: the {@code 2r + 8} search AABB in {@code ShieldLogic.serverTick}
 * covers every shape's volume, the barrier pushback to horizontal distance
 * {@code r + PUSHBACK_MARGIN} lands outside every shape (max horizontal half-extent
 * is at most {@code r}, the same guarantee the DOME already relies on), projectile
 * interception via {@link #crossedInto} is shape-generic, and boss-bar membership,
 * crowd counting, resonance overlap and the renderer cull all stay valid.
 * Verified per shape: cylinder rim corner {@code sqrt(0.36 + 0.64) r = r}; cube
 * corner {@code sqrt(3) * r / sqrt(3) = r}; octahedron {@code L2 <= L1 <= r};
 * torus maximum {@code R + a = (0.7 + 0.3) r = r}. A property gametest
 * ({@code ShapeGameTests.shapeGeometryProperties}) samples every shape so a future
 * shape can never silently break the invariant.
 *
 * <p><b>{@link ShieldShape#RING} is deliberately holey:</b> the center (and the whole
 * vertical axis) is OUTSIDE the shield. Players stand in the hole un-expelled,
 * projectiles flying through the hole are never intercepted (no inward boundary
 * crossing), and the projector block itself is not "inside". This is the ring's
 * documented gameplay identity — gametested, not a bug to fix.
 */
public final class ShieldGeometry {
	/** CYLINDER: horizontal radius as a fraction of the shield radius (3-4-5 inscribed: 0.6). */
	public static final double CYLINDER_RADIUS_FRAC = 0.6;
	/** CYLINDER: half-height as a fraction of the shield radius (3-4-5 inscribed: 0.8). */
	public static final double CYLINDER_HALF_HEIGHT_FRAC = 0.8;
	/** CUBE: half-extent as a fraction of the shield radius (inscribed box: 1/sqrt(3)). */
	public static final double CUBE_HALF_EXTENT_FRAC = 1.0 / Math.sqrt(3.0);
	/** RING: torus major (ring) radius as a fraction of the shield radius. */
	public static final double RING_MAJOR_FRAC = 0.7;
	/** RING: torus minor (tube) radius as a fraction of the shield radius. */
	public static final double RING_MINOR_FRAC = 0.3;

	private ShieldGeometry() {
	}

	/**
	 * @return true when {@code pos} lies inside the shield of the given shape.
	 * A {@link ShieldShape#DOME} only contains points at or above the center's Y
	 * plane; see the class javadoc for the exact volume of every other shape and
	 * the subset-of-the-ball invariant they all satisfy.
	 */
	public static boolean isInside(ShieldShape shape, Vec3 center, double radius, Vec3 pos) {
		double dx = pos.x - center.x;
		double dy = pos.y - center.y;
		double dz = pos.z - center.z;
		return switch (shape) {
			// SPHERE/DOME keep the pre-shapes code path byte-equivalent (same
			// distanceTo comparison first) so float behavior is unchanged.
			case SPHERE -> !(pos.distanceTo(center) > radius);
			case DOME -> !(pos.distanceTo(center) > radius) && pos.y >= center.y;
			case CYLINDER -> Math.sqrt(dx * dx + dz * dz) <= CYLINDER_RADIUS_FRAC * radius
					&& Math.abs(dy) <= CYLINDER_HALF_HEIGHT_FRAC * radius;
			case CUBE -> Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) <= CUBE_HALF_EXTENT_FRAC * radius;
			case DIAMOND -> Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <= radius;
			case RING -> {
				double rho = Math.sqrt(dx * dx + dz * dz);
				double ringDist = rho - RING_MAJOR_FRAC * radius;
				yield Math.sqrt(ringDist * ringDist + dy * dy) <= RING_MINOR_FRAC * radius;
			}
		};
	}

	/**
	 * Subsample step (blocks) for the anti-tunneling sweep in {@link #crossedInto}:
	 * fine enough that no fast projectile can skip across a thin feature (the RING
	 * tube is >= {@code 2 * 0.3 * 4 = 2.4} blocks thick at the minimum shield
	 * radius) while staying cheap (a couple of extra {@link #isInside} calls for
	 * the fastest vanilla projectiles at ~3-5 blocks/tick).
	 */
	private static final double CROSSING_SAMPLE_STEP = 1.0;

	/**
	 * @return true when a movement from {@code prev} to {@code cur} crossed the shield
	 * boundary inward (outside before, inside now — or passed clean THROUGH the
	 * volume within one tick, see below). Outward or fully-inside movement
	 * never triggers, which is what keeps deflected projectiles from being re-intercepted.
	 * Shape-generic: it only consults {@link #isInside}, so it works unchanged for
	 * every shape — including the RING, whose central hole counts as "outside" on
	 * both ends (a projectile falling straight down the axis never crosses in).
	 *
	 * <p><b>Anti-tunneling:</b> the endpoint pair alone misses a fast projectile
	 * whose single-tick segment enters AND exits a thin feature (the RING tube is
	 * only {@code 0.6 r} thick — 2.4 blocks at the minimum radius — and the
	 * DIAMOND tips taper to nothing; a crossbow bolt moves ~3.15 blocks/tick).
	 * When both endpoints are outside a shape with a thin minimum feature and the
	 * segment is long enough to have tunneled ({@code length > minFeature}), the
	 * segment interior is subsampled at ~{@value #CROSSING_SAMPLE_STEP}-block
	 * steps and any inside sample counts as an inward crossing. Slow projectiles
	 * (segment {@code <= minFeature}) keep the cheap two-endpoint test. The fat
	 * shapes' minimum feature (see {@link #minFeatureThickness}) exceeds every
	 * vanilla projectile's per-tick travel at the minimum shield radius 4, so
	 * their fast path is unchanged in practice.
	 */
	public static boolean crossedInto(ShieldShape shape, Vec3 center, double radius, Vec3 prev, Vec3 cur) {
		if (isInside(shape, center, radius, prev)) {
			return false;
		}

		if (isInside(shape, center, radius, cur)) {
			return true;
		}

		// Both endpoints outside: only a segment longer than the shape's thinnest
		// feature can have passed clean through it in one tick.
		double length = cur.distanceTo(prev);
		if (length <= minFeatureThickness(shape, radius)) {
			return false;
		}

		int steps = (int) Math.ceil(length / CROSSING_SAMPLE_STEP);
		for (int i = 1; i < steps; i++) {
			if (isInside(shape, center, radius, prev.lerp(cur, (double) i / steps))) {
				return true;
			}
		}

		return false;
	}

	/**
	 * The thinnest feature of the shape's volume (blocks): the smallest thickness a
	 * straight segment must span to pass clean through the volume somewhere. This
	 * gates the anti-tunneling subsampling in {@link #crossedInto} — segments no
	 * longer than this cannot have tunneled. RING: the tube diameter
	 * {@code 2 * 0.3 r}. DIAMOND: the tips taper to zero thickness, so every
	 * fast-moving segment near a tip is a candidate (conservative 0). The convex
	 * fat shapes use their smallest full-width chord through the deep interior;
	 * grazing chords shorter than that only clip the outermost
	 * {@code CROSSING_SAMPLE_STEP}-deep sliver, which the subsampling would not
	 * reliably see anyway and which never protects the inhabitants.
	 */
	private static double minFeatureThickness(ShieldShape shape, double radius) {
		return switch (shape) {
			case SPHERE, DOME, CUBE, CYLINDER -> radius; // conservative: < the true min width (2r, 2r/sqrt(3), 1.2r)
			case DIAMOND -> 0.0;
			case RING -> 2.0 * RING_MINOR_FRAC * radius;
		};
	}
}

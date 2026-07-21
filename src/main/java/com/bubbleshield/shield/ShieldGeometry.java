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
 * torus maximum {@code R + a = (0.7 + 0.3) r = r}; pyramid base corner
 * {@code sqrt(0.36 + 0.25 + 0.36) r ~ 0.985 r}; lens equator exactly {@code r}
 * (the oblate norm dominates the Euclidean one); hourglass rim corner
 * {@code sqrt(0.3025 + 0.64) r ~ 0.971 r}; star lobe-tip rim corner
 * {@code sqrt(0.64 + 0.3025) r ~ 0.971 r}. A property gametest
 * ({@code ShapeGameTests.shapeGeometryProperties}) samples every shape so a future
 * shape can never silently break the invariant.
 *
 * <p><b>{@link ShieldShape#RING} is deliberately holey:</b> the center (and the whole
 * vertical axis) is OUTSIDE the shield. Players stand in the hole un-expelled,
 * projectiles flying through the hole are never intercepted (no inward boundary
 * crossing), and the projector block itself is not "inside". This is the ring's
 * documented gameplay identity — gametested, not a bug to fix. The
 * {@link ShieldShape#HOURGLASS} waist is the same kind of documented open region:
 * the two cones taper to a point at the center plane, so everything off-axis near
 * {@code dy = 0} is passable (only the exact axis point is "inside" there).
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
	/** PYRAMID: apex height above the center as a fraction of the shield radius. */
	public static final double PYRAMID_APEX_FRAC = 0.9;
	/** PYRAMID: base-plane depth below the center as a fraction of the shield radius. */
	public static final double PYRAMID_BASE_FRAC = 0.5;
	/** PYRAMID: square-base half-extent as a fraction of the shield radius (worst corner ~0.985r). */
	public static final double PYRAMID_BASE_HALF_EXTENT_FRAC = 0.6;
	/** LENS: vertical semi-axis of the oblate spheroid as a fraction of the shield radius. */
	public static final double LENS_HALF_HEIGHT_FRAC = 0.45;
	/** HOURGLASS: half-height of the tip-to-tip double cone as a fraction of the shield radius. */
	public static final double HOURGLASS_HALF_HEIGHT_FRAC = 0.8;
	/** HOURGLASS: cone base radius (at {@code |dy| = 0.8r}) as a fraction of the shield radius (rim corner ~0.971r). */
	public static final double HOURGLASS_MAX_RADIUS_FRAC = 0.55;
	/** STAR: half-height of the six-lobed star prism as a fraction of the shield radius. */
	public static final double STAR_HALF_HEIGHT_FRAC = 0.55;
	/** STAR: mean lobe radius as a fraction of the shield radius. */
	public static final double STAR_RADIUS_MID_FRAC = 0.55;
	/** STAR: lobe radius wave amplitude as a fraction of the shield radius (R in [0.30r, 0.80r]). */
	public static final double STAR_RADIUS_WAVE_FRAC = 0.25;
	/** STAR: number of lobes (the angular frequency of the radius wave). */
	public static final int STAR_LOBES = 6;

	private ShieldGeometry() {
	}

	/**
	 * PYRAMID: the square cross-section's half-extent at height {@code dy} above the
	 * center — {@code 0.6 r * (0.9 r - dy) / (1.4 r)}, i.e. the linear taper from the
	 * full {@code 0.6 r} at the base plane ({@code dy = -0.5 r}) to zero at the apex
	 * ({@code dy = +0.9 r}). Linear in {@code radius}, so evaluating it with a
	 * 0.98-scaled radius yields the 0.98-scaled pyramid's taper (used by
	 * {@code BehaviorSupport.containPoint}).
	 */
	public static double pyramidTaper(double radius, double dy) {
		return PYRAMID_BASE_HALF_EXTENT_FRAC * radius * (PYRAMID_APEX_FRAC * radius - dy)
				/ ((PYRAMID_APEX_FRAC + PYRAMID_BASE_FRAC) * radius);
	}

	/**
	 * HOURGLASS: the maximum horizontal distance from the axis at height {@code dy} —
	 * {@code 0.55 r * |dy| / (0.8 r)}, the double cone's linear taper from zero at the
	 * waist ({@code dy = 0}) to the full {@code 0.55 r} at the caps
	 * ({@code |dy| = 0.8 r}). The slope is radius-independent (both {@code r}s cancel),
	 * so only the height band scales with the radius.
	 */
	public static double hourglassTaper(double radius, double dy) {
		return HOURGLASS_MAX_RADIUS_FRAC * radius * Math.abs(dy) / (HOURGLASS_HALF_HEIGHT_FRAC * radius);
	}

	/**
	 * STAR: the six-lobed radius bound {@code r * (0.55 + 0.25 * cos(6 * theta))} for
	 * the horizontal direction of {@code (dx, dz)} ({@code theta = atan2(dz, dx)}),
	 * ranging over {@code [0.30 r, 0.80 r]}. Theta — and therefore this bound — is
	 * invariant under any positive rescale of {@code (dx, dz)}, which is what makes
	 * {@code BehaviorSupport.containPoint}'s horizontal projection idempotent.
	 */
	public static double starRadius(double radius, double dx, double dz) {
		double theta = Math.atan2(dz, dx);
		return radius * (STAR_RADIUS_MID_FRAC + STAR_RADIUS_WAVE_FRAC * Math.cos(STAR_LOBES * theta));
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
			case PYRAMID -> dy >= -PYRAMID_BASE_FRAC * radius && dy <= PYRAMID_APEX_FRAC * radius
					&& Math.max(Math.abs(dx), Math.abs(dz)) <= pyramidTaper(radius, dy);
			case LENS -> {
				double b = LENS_HALF_HEIGHT_FRAC * radius;
				yield (dx * dx + dz * dz) / (radius * radius) + (dy * dy) / (b * b) <= 1.0;
			}
			case HOURGLASS -> Math.abs(dy) <= HOURGLASS_HALF_HEIGHT_FRAC * radius
					&& Math.sqrt(dx * dx + dz * dz) <= hourglassTaper(radius, dy);
			case STAR -> Math.abs(dy) <= STAR_HALF_HEIGHT_FRAC * radius
					&& Math.sqrt(dx * dx + dz * dz) <= starRadius(radius, dx, dz);
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
	 * fast-moving segment near a tip is a candidate (conservative 0); PYRAMID
	 * (apex), HOURGLASS (waist and cone rims) and STAR (lobe tips) taper to zero
	 * the same way. LENS: the flat side's smallest full-width chord is bounded
	 * below by the {@code 0.9 r} polar diameter (conservative {@code 0.45 r}).
	 * The convex fat shapes use their smallest full-width chord through the deep
	 * interior; grazing chords shorter than that only clip the outermost
	 * {@code CROSSING_SAMPLE_STEP}-deep sliver, which the subsampling would not
	 * reliably see anyway and which never protects the inhabitants.
	 */
	private static double minFeatureThickness(ShieldShape shape, double radius) {
		return switch (shape) {
			case SPHERE, DOME, CUBE, CYLINDER -> radius; // conservative: < the true min width (2r, 2r/sqrt(3), 1.2r)
			case DIAMOND, PYRAMID, HOURGLASS, STAR -> 0.0; // tips/waist/lobe edges taper to zero thickness
			case RING -> 2.0 * RING_MINOR_FRAC * radius;
			case LENS -> LENS_HALF_HEIGHT_FRAC * radius; // conservative: < the 0.9r polar diameter
		};
	}
}

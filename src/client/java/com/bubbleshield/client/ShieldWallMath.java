package com.bubbleshield.client;

import com.bubbleshield.client.ClientShieldManager.ClientShield;
import com.bubbleshield.shield.ShieldGeometry;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.world.phys.Vec3;

/**
 * Shape-aware wall math shared by the client fx trackers ({@code ContactFlash},
 * {@code ProximityHum}, {@code ApertureTracker}) and the renderer's impact-wave
 * centering: the old spherical approximation {@code |dist(center) - r|} is
 * exact for the SPHERE but off by up to {@code (1 - 1/sqrt(3)) * r} for the
 * sub-ball shapes (a D=200 CUBE face sits 42 blocks inside the bounding
 * sphere), which pushed the contact flash, the proximity hum and the aperture
 * hysteresis out of range entirely on large shaped shields.
 *
 * <p>{@link #boundaryDistanceAlong} finds the OUTERMOST boundary crossing along
 * a radial ray with a coarse {@value #SCAN_STEPS}-sample bracket plus
 * {@value #BISECT_STEPS} bisection steps over {@link ShieldGeometry#isInside}
 * (resolution {@code radius / 2048} — 0.05 blocks on the largest shield); rays
 * that never enter the shape (the RING's axis hole, the DOME's underside, the
 * HOURGLASS waist plane) fall back to the bounding-sphere radius, matching the
 * legacy approximation there. {@link #wallDistance} refines the spherical
 * distance with that radial crossing, but only within
 * {@code radius + }{@value #REFINE_MARGIN} of the center — beyond every
 * fx trigger range the cheap spherical distance is already a safe
 * "far away" answer.
 */
public final class ShieldWallMath {
	/** Beyond bounding sphere + this margin (blocks) the spherical distance is returned unrefined. */
	public static final double REFINE_MARGIN = 8.0;
	/** Coarse samples bracketing the outermost inside-to-outside crossing along the ray. */
	private static final int SCAN_STEPS = 8;
	/** Bisection iterations refining the bracketed crossing. */
	private static final int BISECT_STEPS = 8;

	private ShieldWallMath() {
	}

	/** {@link #wallDistance(ShieldShape, Vec3, double, Vec3)} over a synced replica. */
	public static double wallDistance(ClientShield shield, Vec3 pos) {
		return wallDistance(ShieldShape.byOrdinal(shield.shape()), Vec3.atCenterOf(shield.pos()),
				shield.currentRadius(), pos);
	}

	/**
	 * The approximate distance (blocks) from {@code pos} to the shield's wall:
	 * exact-spherical for the SPHERE, refined along the radial direction through
	 * {@code pos} for every other shape (see the class javadoc for the fallbacks).
	 */
	public static double wallDistance(ShieldShape shape, Vec3 center, double radius, Vec3 pos) {
		Vec3 fromCenter = pos.subtract(center);
		double dist = fromCenter.length();
		double spherical = Math.abs(dist - radius);
		if (shape == ShieldShape.SPHERE || dist > radius + REFINE_MARGIN || dist < 1.0e-4) {
			return spherical;
		}

		return Math.abs(dist - boundaryDistanceAlong(shape, center, radius, fromCenter.scale(1.0 / dist)));
	}

	/**
	 * The distance along {@code dirUnit} from {@code center} to the shape's
	 * OUTERMOST boundary crossing, in {@code (0, radius]}. Exactly {@code radius}
	 * for the SPHERE (and any direction whose ball-boundary point is still inside
	 * the shape — DOME above the equator, LENS on it, DIAMOND on the axes);
	 * {@code radius} as the documented fallback when the ray never enters the
	 * shape at all.
	 */
	public static double boundaryDistanceAlong(ShieldShape shape, Vec3 center, double radius, Vec3 dirUnit) {
		if (shape == ShieldShape.SPHERE
				|| ShieldGeometry.isInside(shape, center, radius, center.add(dirUnit.scale(radius)))) {
			return radius;
		}

		// Bracket the outermost crossing: scan from the rim inward for the first
		// inside sample (handles the RING, whose center is outside the shape).
		double lo = -1.0;
		double hi = radius;
		for (int i = SCAN_STEPS - 1; i >= 0; i--) {
			double t = radius * i / SCAN_STEPS;
			if (ShieldGeometry.isInside(shape, center, radius, center.add(dirUnit.scale(t)))) {
				lo = t;
				break;
			}

			hi = t;
		}

		if (lo < 0.0) {
			// The ray never enters the shape (ring hole, dome underside): the
			// bounding sphere is the only sensible wall to report.
			return radius;
		}

		for (int i = 0; i < BISECT_STEPS; i++) {
			double mid = (lo + hi) * 0.5;
			if (ShieldGeometry.isInside(shape, center, radius, center.add(dirUnit.scale(mid)))) {
				lo = mid;
			} else {
				hi = mid;
			}
		}

		return (lo + hi) * 0.5;
	}
}

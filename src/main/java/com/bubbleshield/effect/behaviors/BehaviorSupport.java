package com.bubbleshield.effect.behaviors;

import java.util.Set;

import com.bubbleshield.shield.ShieldGeometry;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Shared server-side helpers for inside behaviors.
 *
 * <p><b>Containment:</b> every ABSOLUTE particle emission position that can end up
 * beyond {@link #MAX_DIST_FRAC} (0.98) of the shield radius must be routed through
 * {@link #containPoint(Vec3, double, Vec3)} (or its {@link ShieldShape}-aware
 * overload) so nothing ever renders outside the bubble wall. The sphere variant
 * rescales an outside point back toward the center onto {@code 0.98 * radius};
 * the shape-aware variant projects the point into the 0.98-scaled volume of the
 * given shape (mirroring {@link ShieldGeometry#isInside}'s per-shape math, see the
 * exact projections on {@link #containPoint(ShieldShape, Vec3, double, Vec3)}).
 * Points that are already contained are returned UNCHANGED (the same {@link Vec3}
 * instance), so legacy sphere/dome emissions that were always inside stay
 * byte-identical.
 *
 * <p><b>Air safety:</b> {@link #AIR_UNSAFE_PARTICLES} lists the vanilla particle
 * types that self-remove on their first client tick outside water (verified
 * against the 26.2 client sources: BubbleParticle, BubbleColumnUpParticle and
 * WaterCurrentDownParticle all {@code remove()} when not in a
 * {@code FluidTags.WATER} state). They are invisible in an air-filled bubble
 * (a one-frame flicker at most), so no inside behavior may emit them;
 * {@code EffectCatalogGameTests} scans every behavior's emissions against this
 * deny-list. BUBBLE_POP and SPLASH are the air-safe stand-ins.
 */
public final class BehaviorSupport {
	/** Contained emissions stay within this fraction of the radius (inside the shell). */
	public static final double MAX_DIST_FRAC = 0.98;

	/**
	 * Particles that self-remove outside water and therefore may never be emitted
	 * by an inside behavior (the bubble interior is air).
	 */
	public static final Set<ParticleType<?>> AIR_UNSAFE_PARTICLES = Set.of(
			ParticleTypes.BUBBLE,
			ParticleTypes.BUBBLE_COLUMN_UP,
			ParticleTypes.CURRENT_DOWN);

	private BehaviorSupport() {
	}

	/**
	 * Contains an absolute emission point inside the spherical shell: a point
	 * farther than {@code 0.98 * radius} from {@code center} is rescaled toward
	 * the center onto that distance; a point already inside is returned as-is
	 * (identical instance, no floating-point drift).
	 */
	public static Vec3 containPoint(Vec3 center, double radius, Vec3 point) {
		double dx = point.x - center.x;
		double dy = point.y - center.y;
		double dz = point.z - center.z;
		double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
		double maxDist = radius * MAX_DIST_FRAC;
		if (dist <= maxDist) {
			return point;
		}

		double scale = maxDist / dist;
		return new Vec3(center.x + dx * scale, center.y + dy * scale, center.z + dz * scale);
	}

	/**
	 * Shape-aware containment: projects the point into the {@link #MAX_DIST_FRAC}
	 * (0.98)-scaled volume of the given shape, mirroring the per-shape math of
	 * {@link ShieldGeometry#isInside}:
	 * <ul>
	 *   <li>SPHERE — exactly {@link #containPoint(Vec3, double, Vec3)} (unchanged
	 *       legacy path: rescale onto {@code 0.98 r} toward the center);</li>
	 *   <li>DOME — clamp into the dome half-space ({@code y >= center.y}), then the
	 *       sphere rescale (unchanged legacy path);</li>
	 *   <li>CYLINDER — clamp {@code dy} into {@code ±0.8 * 0.98 r}, then rescale
	 *       {@code (dx, dz)} onto {@code 0.6 * 0.98 r} if horizontally outside;</li>
	 *   <li>CUBE — per-axis clamp into {@code ±0.98 r / sqrt(3)};</li>
	 *   <li>DIAMOND — scale the whole offset by {@code 0.98 r / L1} when the L1
	 *       norm exceeds {@code 0.98 r} (direction-preserving);</li>
	 *   <li>RING — pull the point toward the nearest ring-circle point
	 *       {@code q = center + 0.7 r * unit(dx, 0, dz)} (unit +x when on the axis)
	 *       until the tube distance is {@code 0.98 * 0.3 r}. A point at tube
	 *       distance 0 sits ON the ring circle (inside), so the projection never
	 *       divides by zero.</li>
	 * </ul>
	 *
	 * <p>Contract (gametested in {@code ShapeGameTests.containPointProperties}):
	 * the result always satisfies {@code ShieldGeometry.isInside(shape, ...)}, the
	 * projection is idempotent, and an already-contained point is returned as the
	 * SAME {@link Vec3} instance — which is what keeps every legacy sphere/dome
	 * emission byte-identical.
	 */
	public static Vec3 containPoint(ShieldShape shape, Vec3 center, double radius, Vec3 point) {
		switch (shape) {
			case SPHERE:
				return containPoint(center, radius, point);
			case DOME:
				if (point.y < center.y) {
					point = new Vec3(point.x, center.y, point.z);
				}

				return containPoint(center, radius, point);
			case CYLINDER: {
				double dx = point.x - center.x;
				double dy = point.y - center.y;
				double dz = point.z - center.z;
				double maxHalfHeight = ShieldGeometry.CYLINDER_HALF_HEIGHT_FRAC * radius * MAX_DIST_FRAC;
				double maxRho = ShieldGeometry.CYLINDER_RADIUS_FRAC * radius * MAX_DIST_FRAC;
				double clampedDy = Math.clamp(dy, -maxHalfHeight, maxHalfHeight);
				double rho = Math.sqrt(dx * dx + dz * dz);
				if (clampedDy == dy && rho <= maxRho) {
					return point;
				}

				// rho > maxRho implies rho > 0, so the horizontal rescale is safe.
				double scale = rho > maxRho ? maxRho / rho : 1.0;
				return new Vec3(center.x + dx * scale, center.y + clampedDy, center.z + dz * scale);
			}
			case CUBE: {
				double dx = point.x - center.x;
				double dy = point.y - center.y;
				double dz = point.z - center.z;
				double maxExtent = ShieldGeometry.CUBE_HALF_EXTENT_FRAC * radius * MAX_DIST_FRAC;
				double clampedDx = Math.clamp(dx, -maxExtent, maxExtent);
				double clampedDy = Math.clamp(dy, -maxExtent, maxExtent);
				double clampedDz = Math.clamp(dz, -maxExtent, maxExtent);
				if (clampedDx == dx && clampedDy == dy && clampedDz == dz) {
					return point;
				}

				return new Vec3(center.x + clampedDx, center.y + clampedDy, center.z + clampedDz);
			}
			case DIAMOND: {
				double dx = point.x - center.x;
				double dy = point.y - center.y;
				double dz = point.z - center.z;
				double l1 = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
				double maxDist = radius * MAX_DIST_FRAC;
				if (l1 <= maxDist) {
					return point;
				}

				double scale = maxDist / l1;
				return new Vec3(center.x + dx * scale, center.y + dy * scale, center.z + dz * scale);
			}
			case RING: {
				double dx = point.x - center.x;
				double dy = point.y - center.y;
				double dz = point.z - center.z;
				double major = ShieldGeometry.RING_MAJOR_FRAC * radius;
				double maxTube = ShieldGeometry.RING_MINOR_FRAC * radius * MAX_DIST_FRAC;
				double rho = Math.sqrt(dx * dx + dz * dz);
				// The nearest point on the ring circle; unit +x when on the axis.
				double qx;
				double qz;
				if (rho < 1.0e-6) {
					qx = center.x + major;
					qz = center.z;
				} else {
					qx = center.x + dx * (major / rho);
					qz = center.z + dz * (major / rho);
				}

				double tx = point.x - qx;
				double tz = point.z - qz;
				double tubeDist = Math.sqrt(tx * tx + dy * dy + tz * tz);
				if (tubeDist <= maxTube) {
					return point;
				}

				// tubeDist > maxTube >= 0: never a division by zero.
				double scale = maxTube / tubeDist;
				return new Vec3(qx + tx * scale, center.y + dy * scale, qz + tz * scale);
			}
		}

		return containPoint(center, radius, point);
	}

	/**
	 * Emits one {@code sendParticles} call at the contained emission position
	 * (shape-aware, see {@link #containPoint(ShieldShape, Vec3, double, Vec3)}).
	 * The count/spread/speed arguments pass through untouched, so the vanilla
	 * count=0 "fly towards" packet form works too.
	 */
	public static void sendContained(ServerLevel level, ParticleOptions particle, ShieldShape shape, Vec3 center, double radius,
			double x, double y, double z, int count, double xDist, double yDist, double zDist, double speed) {
		Vec3 point = containPoint(shape, center, radius, new Vec3(x, y, z));
		level.sendParticles(particle, true, false, point.x, point.y, point.z, count, xDist, yDist, zDist, speed);
	}

	/**
	 * SplitMix64 finalizer over an arbitrary composite seed. The stateless ghost
	 * behaviors derive all apparition anchors (dart endpoints, watcher slots,
	 * walker waypoints...) from this so one shared behavior instance serves every
	 * shield deterministically, with no fields and no cleanup.
	 */
	public static long mix(long seed) {
		long z = seed + 0x9E3779B97F4A7C15L;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	/** A uniform double in {@code [0, 1)} derived from {@link #mix}. */
	public static double hash01(long seed) {
		return (mix(seed) >>> 11) * 0x1.0p-53;
	}
}

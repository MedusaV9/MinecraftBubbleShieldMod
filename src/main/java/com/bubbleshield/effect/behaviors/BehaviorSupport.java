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
 * <p><b>Containment:</b> every particle emission must be routed through
 * {@link #sendContained} (count&gt;0 spread packets and generic count=0 packets) or
 * {@link #sendFlyToward} (the ENCHANT/NAUTILUS/VAULT_CONNECTION count=0 fly-toward
 * form) so nothing ever RENDERS outside the bubble wall — not just the packet
 * origin, the whole client-side trajectory:
 * <ul>
 *   <li>count&gt;0: the vanilla client spawns each particle at
 *       {@code origin + nextGaussian() * dist} per axis (unbounded tails), so
 *       {@link #sendContained} first contains the origin and then checks the whole
 *       {@code origin ± SPREAD_SIGMAS * dist} box; when it does not fit, the
 *       Gaussian jitter is generated SERVER-side instead (same distribution,
 *       every sample projected through {@link #containPoint}) and sent as
 *       zero-spread single-particle packets, preserving the authored look;</li>
 *   <li>count=0 fly-toward: {@link #sendFlyToward} keeps the spawn point, the
 *       destination AND the destination's built-in {@link #FLY_TOWARD_DIP}
 *       undershoot inside the shell;</li>
 *   <li>non-convex shapes (RING/HOURGLASS/STAR): endpoint containment alone is
 *       not enough there — the straight client-side path between two contained
 *       endpoints can bow OUTSIDE the volume (across the RING hole, through the
 *       HOURGLASS waist or a STAR inter-lobe gap) — so the count=0 velocity
 *       segment and the whole fly-toward trajectory (lerp plus the
 *       {@code 1.2 * t^4} dip) are additionally subsampled at
 *       {@link #TRAJECTORY_SAMPLES}-based fractions and pulled toward a safe
 *       anchor until EVERY sample is contained. The convex shapes
 *       (SPHERE/DOME/CYLINDER/CUBE/DIAMOND/PYRAMID/LENS) keep the endpoint-only
 *       fast path: their 0.98-scaled volumes are convex and every rendered
 *       trajectory point is a convex combination of the already-checked
 *       endpoints, so those emissions stay byte-identical.</li>
 * </ul>
 *
 * <p>The primitive is {@link #containPoint(Vec3, double, Vec3)} (or its
 * {@link ShieldShape}-aware overload). The sphere variant rescales an outside
 * point back toward the center onto {@code 0.98 * radius}; the shape-aware
 * variant projects the point into the 0.98-scaled volume of the given shape
 * (mirroring {@link ShieldGeometry#isInside}'s per-shape math, see the exact
 * projections on {@link #containPoint(ShieldShape, Vec3, double, Vec3)}).
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
	 * How many standard deviations of a count&gt;0 packet's Gaussian position spread
	 * must stay inside the shell. The vanilla client spawns each particle at
	 * {@code origin + nextGaussian() * dist} per axis (verified in 26.2
	 * {@code ClientPacketListener.handleParticleEvent}), i.e. the dx/dy/dz packet
	 * args are STDDEVS with unbounded tails — so "contained" is defined as the
	 * whole {@code origin ± SPREAD_SIGMAS * stddev} box fitting inside the
	 * {@link #MAX_DIST_FRAC} volume (&gt;98.7% of samples per axis; the capture
	 * matrix gametest reconstructs exactly this bound from every sent packet).
	 */
	public static final double SPREAD_SIGMAS = 2.5;

	/**
	 * The vanilla {@code FlyTowardsPositionParticle} y-dip: the particle spawns at
	 * {@code packetPos + offset}, lerps back to {@code packetPos} and additionally
	 * subtracts {@code 1.2 * ((age/lifetime)^4)} blocks of Y on the way, ending
	 * exactly 1.2 blocks BELOW the packet position (verified in the 26.2 client
	 * sources: {@code this.y = yStart + yd * pos - pp * 1.2F}). Fly-toward
	 * destinations must therefore keep {@code target - (0, 1.2, 0)} inside too.
	 */
	public static final double FLY_TOWARD_DIP = 1.2;

	/**
	 * The particle types backed by {@code FlyTowardsPositionParticle} in 26.2
	 * (EnchantProvider / NautilusProvider / VaultConnectionProvider): their
	 * count=0 packet form spawns at {@code target + offset} and lands at
	 * {@code target.y - }{@link #FLY_TOWARD_DIP}. Emit them via
	 * {@link #sendFlyToward} so both ends of the trajectory stay contained.
	 */
	public static final Set<ParticleType<?>> FLY_TOWARD_PARTICLES = Set.of(
			ParticleTypes.ENCHANT,
			ParticleTypes.NAUTILUS,
			ParticleTypes.VAULT_CONNECTION);

	/**
	 * Particles that self-remove outside water and therefore may never be emitted
	 * by an inside behavior (the bubble interior is air).
	 */
	public static final Set<ParticleType<?>> AIR_UNSAFE_PARTICLES = Set.of(
			ParticleTypes.BUBBLE,
			ParticleTypes.BUBBLE_COLUMN_UP,
			ParticleTypes.CURRENT_DOWN);

	/**
	 * Base subdivision count for the rendered-trajectory containment checks on the
	 * NON-CONVEX shapes (RING/HOURGLASS/STAR): the count=0 velocity segment and
	 * the fly-toward lerp-plus-dip curve are sampled at {@code t = i / steps}
	 * where {@code steps} is always a MULTIPLE of this constant (finer for longer
	 * trajectories, never coarser — see {@code trajectorySteps}). The capture
	 * matrix gametest reconstructs trajectories at exactly the
	 * {@code i / TRAJECTORY_SAMPLES} fractions, which are therefore always a
	 * subset of the server-checked samples: a trajectory the server accepted can
	 * never fail the test's reconstruction.
	 */
	public static final int TRAJECTORY_SAMPLES = 16;

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
	 *       divides by zero;</li>
	 *   <li>PYRAMID — clamp {@code dy} into the 0.98-scaled
	 *       {@code [-0.5 r, +0.9 r]} band, then per-axis clamp {@code (dx, dz)}
	 *       into the 0.98-scaled pyramid's taper at that height
	 *       ({@link ShieldGeometry#pyramidTaper} evaluated with {@code 0.98 r});</li>
	 *   <li>LENS — homogeneous rescale of the whole offset by {@code 0.98 / norm}
	 *       when the oblate norm {@code sqrt((dx^2 + dz^2) / r^2 + dy^2 / (0.45 r)^2)}
	 *       exceeds 0.98 (direction-preserving, like DIAMOND);</li>
	 *   <li>HOURGLASS — clamp {@code dy} into {@code ±0.8 * 0.98 r}, then rescale
	 *       {@code (dx, dz)} onto {@code 0.98 *} the cone taper at that height
	 *       ({@link ShieldGeometry#hourglassTaper}; the taper's slope is
	 *       radius-invariant, so the 0.98 must multiply the taper itself — like the
	 *       CYLINDER's {@code 0.6 * 0.98 r} — to keep a real margin under the
	 *       {@code isInside} bound; near the waist this pulls the point onto the
	 *       axis, the only inside region there);</li>
	 *   <li>STAR — clamp {@code dy} into {@code ±0.55 * 0.98 r}, then rescale
	 *       {@code (dx, dz)} onto {@code 0.98 * R(theta)}
	 *       ({@link ShieldGeometry#starRadius}; theta is invariant under the
	 *       positive rescale, so the projection is idempotent, and
	 *       {@code R(theta) >= 0.30 * 0.98 r > 0} keeps the division safe).</li>
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
			case PYRAMID: {
				double dx = point.x - center.x;
				double dy = point.y - center.y;
				double dz = point.z - center.z;
				// The 0.98-scaled pyramid is exactly the pyramid of radius 0.98r
				// (all its dimensions are linear in the radius).
				double rs = radius * MAX_DIST_FRAC;
				double clampedDy = Math.clamp(dy, -ShieldGeometry.PYRAMID_BASE_FRAC * rs, ShieldGeometry.PYRAMID_APEX_FRAC * rs);
				double taper = ShieldGeometry.pyramidTaper(rs, clampedDy);
				double clampedDx = Math.clamp(dx, -taper, taper);
				double clampedDz = Math.clamp(dz, -taper, taper);
				if (clampedDx == dx && clampedDy == dy && clampedDz == dz) {
					return point;
				}

				return new Vec3(center.x + clampedDx, center.y + clampedDy, center.z + clampedDz);
			}
			case LENS: {
				double dx = point.x - center.x;
				double dy = point.y - center.y;
				double dz = point.z - center.z;
				double b = ShieldGeometry.LENS_HALF_HEIGHT_FRAC * radius;
				double norm = Math.sqrt((dx * dx + dz * dz) / (radius * radius) + (dy * dy) / (b * b));
				if (norm <= MAX_DIST_FRAC) {
					return point;
				}

				// norm > 0.98 > 0: never a division by zero.
				double scale = MAX_DIST_FRAC / norm;
				return new Vec3(center.x + dx * scale, center.y + dy * scale, center.z + dz * scale);
			}
			case HOURGLASS: {
				double dx = point.x - center.x;
				double dy = point.y - center.y;
				double dz = point.z - center.z;
				double maxHalfHeight = ShieldGeometry.HOURGLASS_HALF_HEIGHT_FRAC * radius * MAX_DIST_FRAC;
				double clampedDy = Math.clamp(dy, -maxHalfHeight, maxHalfHeight);
				// The taper's slope is radius-invariant (hourglassTaper(0.98r, dy)
				// == hourglassTaper(r, dy)), so the 0.98 must multiply the taper
				// itself — like CYLINDER's 0.6 * 0.98r — to keep a real margin
				// under the isInside bound.
				double maxRho = ShieldGeometry.hourglassTaper(radius, clampedDy) * MAX_DIST_FRAC;
				double rho = Math.sqrt(dx * dx + dz * dz);
				if (clampedDy == dy && rho <= maxRho) {
					return point;
				}

				// rho > maxRho >= 0 implies rho > 0, so the horizontal rescale is
				// safe; near the waist (maxRho ~ 0) it pulls the point onto the axis.
				double scale = rho > maxRho ? maxRho / rho : 1.0;
				return new Vec3(center.x + dx * scale, center.y + clampedDy, center.z + dz * scale);
			}
			case STAR: {
				double dx = point.x - center.x;
				double dy = point.y - center.y;
				double dz = point.z - center.z;
				double rs = radius * MAX_DIST_FRAC;
				double maxHalfHeight = ShieldGeometry.STAR_HALF_HEIGHT_FRAC * rs;
				double clampedDy = Math.clamp(dy, -maxHalfHeight, maxHalfHeight);
				// Theta (and so the lobe bound) is invariant under the positive
				// horizontal rescale, which makes the projection idempotent.
				double maxRho = ShieldGeometry.starRadius(rs, dx, dz);
				double rho = Math.sqrt(dx * dx + dz * dz);
				if (clampedDy == dy && rho <= maxRho) {
					return point;
				}

				// maxRho >= 0.30 * 0.98r > 0, so rho > maxRho implies rho > 0.
				double scale = rho > maxRho ? maxRho / rho : 1.0;
				return new Vec3(center.x + dx * scale, center.y + clampedDy, center.z + dz * scale);
			}
		}

		return containPoint(center, radius, point);
	}

	/** @return true when the point already satisfies the {@link #MAX_DIST_FRAC} volume (identity contract of containPoint). */
	public static boolean isContained(ShieldShape shape, Vec3 center, double radius, Vec3 point) {
		return containPoint(shape, center, radius, point) == point;
	}

	/**
	 * The shapes whose (0.98-scaled) volume is NOT convex: a straight segment
	 * between two contained points can leave the volume (across the RING's
	 * central hole, through the HOURGLASS's pinched waist, or over a STAR
	 * inter-lobe gap), so rendered trajectories there need subsampled checks
	 * instead of the endpoint-only convex fast path.
	 */
	private static boolean isNonConvex(ShieldShape shape) {
		return shape == ShieldShape.RING || shape == ShieldShape.HOURGLASS || shape == ShieldShape.STAR;
	}

	/**
	 * Subdivision count for a rendered trajectory of the given length: always a
	 * multiple of {@link #TRAJECTORY_SAMPLES} (so the capture matrix's
	 * {@code i / TRAJECTORY_SAMPLES} reconstruction points are a subset of the
	 * checked samples), refined for long trajectories to keep the sample spacing
	 * at or under half a block.
	 */
	private static int trajectorySteps(double length) {
		return TRAJECTORY_SAMPLES * Math.max(1, (int) Math.ceil(length / (0.5 * TRAJECTORY_SAMPLES)));
	}

	/**
	 * Whether every subsample of the straight segment {@code from -> to} is
	 * contained (the count=0 velocity/displacement form's rendered path). Both
	 * endpoints are included in the sweep.
	 */
	private static boolean segmentContained(ShieldShape shape, Vec3 center, double radius, Vec3 from, Vec3 to) {
		int steps = trajectorySteps(from.distanceTo(to));
		for (int i = 0; i <= steps; i++) {
			if (!isContained(shape, center, radius, from.lerp(to, (double) i / steps))) {
				return false;
			}
		}

		return true;
	}

	/**
	 * Whether the destination and the ENTIRE reconstructed fly-toward trajectory
	 * are contained: the vanilla {@code FlyTowardsPositionParticle} at lifetime
	 * fraction {@code t} sits at {@code lerp(spawn, dest, t) - (0, 1.2 * t^4, 0)}
	 * (linear lerp from the spawn point to the packet position plus the
	 * {@link #FLY_TOWARD_DIP} undershoot), so that exact curve is subsampled.
	 */
	private static boolean flyTowardTrajectoryContained(ShieldShape shape, Vec3 center, double radius, Vec3 spawn, Vec3 dest) {
		if (!isContained(shape, center, radius, dest)) {
			return false;
		}

		int steps = trajectorySteps(spawn.distanceTo(dest) + FLY_TOWARD_DIP);
		for (int i = 0; i <= steps; i++) {
			double t = (double) i / steps;
			Vec3 point = spawn.lerp(dest, t).subtract(0.0, FLY_TOWARD_DIP * t * t * t * t, 0.0);
			if (!isContained(shape, center, radius, point)) {
				return false;
			}
		}

		return true;
	}

	/**
	 * Whether the whole axis-aligned box {@code origin ± (hx, hy, hz)} fits inside
	 * the {@link #MAX_DIST_FRAC} volume of the shape. This is the k-sigma spread
	 * bound {@link #sendContained} enforces with {@code h = SPREAD_SIGMAS * dist}:
	 * exact worst-corner math for SPHERE/DOME/CYLINDER/CUBE/DIAMOND, and the
	 * (conservative) circumscribed-ball Lipschitz bound for the non-convex RING
	 * (torus tube distance is 1-Lipschitz, so {@code tubeDist(origin) + |h|} is a
	 * safe upper bound for every point of the box). PYRAMID uses exact worst-corner
	 * math with the taper evaluated at the box TOP (the tightest slice, since the
	 * taper shrinks toward the apex); LENS uses the oblate-norm Lipschitz bound
	 * {@code norm(origin) + |h| / (0.45 r) <= 0.98} (the norm's steepest axis is Y
	 * with slope {@code 1 / (0.45 r)}); HOURGLASS conservatively requires the box
	 * to sit inside ONE cone, bounded by the taper at the box's smallest
	 * {@code |dy|} (the pinch-nearest slice); STAR conservatively uses the
	 * inscribed cylinder of the minimum lobe radius {@code 0.30 * 0.98 r}.
	 */
	public static boolean spreadFits(ShieldShape shape, Vec3 center, double radius, Vec3 origin, double hx, double hy, double hz) {
		double ax = Math.abs(origin.x - center.x);
		double ay = Math.abs(origin.y - center.y);
		double az = Math.abs(origin.z - center.z);
		hx = Math.abs(hx);
		hy = Math.abs(hy);
		hz = Math.abs(hz);
		double maxDist = radius * MAX_DIST_FRAC;
		switch (shape) {
			case SPHERE:
			case DOME: {
				double cx = ax + hx;
				double cy = ay + hy;
				double cz = az + hz;
				boolean sphereOk = Math.sqrt(cx * cx + cy * cy + cz * cz) <= maxDist;
				// DOME additionally: the box bottom may not cross the base plane.
				return sphereOk && (shape == ShieldShape.SPHERE || origin.y - hy >= center.y);
			}
			case CYLINDER: {
				double cx = ax + hx;
				double cz = az + hz;
				return Math.sqrt(cx * cx + cz * cz) <= ShieldGeometry.CYLINDER_RADIUS_FRAC * maxDist
						&& ay + hy <= ShieldGeometry.CYLINDER_HALF_HEIGHT_FRAC * maxDist;
			}
			case CUBE: {
				double maxExtent = ShieldGeometry.CUBE_HALF_EXTENT_FRAC * maxDist;
				return ax + hx <= maxExtent && ay + hy <= maxExtent && az + hz <= maxExtent;
			}
			case DIAMOND:
				return (ax + hx) + (ay + hy) + (az + hz) <= maxDist;
			case RING: {
				double dx = origin.x - center.x;
				double dz = origin.z - center.z;
				double rho = Math.sqrt(dx * dx + dz * dz);
				double ringDist = rho - ShieldGeometry.RING_MAJOR_FRAC * radius;
				double dy = origin.y - center.y;
				double tubeDist = Math.sqrt(ringDist * ringDist + dy * dy);
				return tubeDist + Math.sqrt(hx * hx + hy * hy + hz * hz)
						<= ShieldGeometry.RING_MINOR_FRAC * radius * MAX_DIST_FRAC;
			}
			case PYRAMID: {
				// Exact worst-corner: the signed dy band, then the taper at the box
				// TOP — the tightest cross-section, since the taper shrinks upward.
				double dy = origin.y - center.y;
				if (dy - hy < -ShieldGeometry.PYRAMID_BASE_FRAC * maxDist
						|| dy + hy > ShieldGeometry.PYRAMID_APEX_FRAC * maxDist) {
					return false;
				}

				double taper = ShieldGeometry.pyramidTaper(maxDist, dy + hy);
				return ax + hx <= taper && az + hz <= taper;
			}
			case LENS: {
				// The oblate norm is Lipschitz with constant 1 / (0.45 r) (its
				// steepest axis is Y), so norm(origin) + |h| / (0.45 r) bounds the
				// norm of every point of the box.
				double dx = origin.x - center.x;
				double dy = origin.y - center.y;
				double dz = origin.z - center.z;
				double b = ShieldGeometry.LENS_HALF_HEIGHT_FRAC * radius;
				double norm = Math.sqrt((dx * dx + dz * dz) / (radius * radius) + (dy * dy) / (b * b));
				return norm + Math.sqrt(hx * hx + hy * hy + hz * hz) / b <= MAX_DIST_FRAC;
			}
			case HOURGLASS: {
				// Conservative inner bound: the whole box must sit inside ONE cone,
				// so the taper at its smallest |dy| (the pinch-nearest slice) must
				// cover the box's full horizontal reach. A box straddling the waist
				// gets a zero taper and only fits when it has no horizontal extent.
				double cx = ax + hx;
				double cz = az + hz;
				double minAbsDy = Math.max(0.0, ay - hy);
				// The taper slope is radius-invariant, so the 0.98 margin must
				// multiply the taper itself (mirrors the containPoint case).
				return ay + hy <= ShieldGeometry.HOURGLASS_HALF_HEIGHT_FRAC * maxDist
						&& Math.sqrt(cx * cx + cz * cz) <= ShieldGeometry.hourglassTaper(radius, minAbsDy) * MAX_DIST_FRAC;
			}
			case STAR: {
				// Conservative inner-radius bound: the star prism always contains
				// the inscribed cylinder of the minimum lobe radius 0.30 * 0.98r.
				double cx = ax + hx;
				double cz = az + hz;
				double innerRho = (ShieldGeometry.STAR_RADIUS_MID_FRAC - ShieldGeometry.STAR_RADIUS_WAVE_FRAC) * maxDist;
				return ay + hy <= ShieldGeometry.STAR_HALF_HEIGHT_FRAC * maxDist
						&& Math.sqrt(cx * cx + cz * cz) <= innerRho;
			}
		}

		return false;
	}

	/**
	 * Emits a contained particle emission (shape-aware). This is trajectory-safe,
	 * not just origin-safe:
	 *
	 * <ul>
	 *   <li><b>count&gt;0:</b> the origin is contained first; when the whole
	 *       {@code origin ± SPREAD_SIGMAS * dist} Gaussian-spread box also fits
	 *       (see {@link #spreadFits}), the single packet passes through UNCHANGED —
	 *       the legacy path, byte-identical for emissions that were always safe.
	 *       Otherwise the Gaussian jitter is generated server-side with the same
	 *       per-axis stddevs (identical distribution), every sample is projected
	 *       through {@link #containPoint}, and {@code count} zero-spread
	 *       single-particle packets are sent — the client can no longer add
	 *       unbounded tails, and the authored volume-filling look is preserved
	 *       instead of being shrunk. The per-particle velocity semantics
	 *       ({@code nextGaussian() * speed} per axis) are untouched in both paths.</li>
	 *   <li><b>count=0:</b> the vanilla single-particle form spawns at the origin
	 *       with {@code speed * dist} as its velocity/displacement; the origin is
	 *       contained and the displacement END POINT is contained too (the dist
	 *       args are rescaled onto the projected end point). ENCHANT/NAUTILUS/
	 *       VAULT_CONNECTION must use {@link #sendFlyToward} instead, which also
	 *       covers their built-in {@link #FLY_TOWARD_DIP} undershoot.</li>
	 * </ul>
	 */
	public static void sendContained(ServerLevel level, ParticleOptions particle, ShieldShape shape, Vec3 center, double radius,
			double x, double y, double z, int count, double xDist, double yDist, double zDist, double speed) {
		if (FLY_TOWARD_PARTICLES.contains(particle.getType())) {
			// FlyTowardsPositionParticle types are dip-hazardous in EVERY packet
			// form; reproduce the client's per-particle distribution server-side
			// and route each one through the fully-checked fly-toward path.
			// count=0: the destination is (x, y, z) and speed * dist is the spawn
			// offset. count>0: each particle's destination is origin + Gaussian
			// jitter and its spawn offset is Gaussian * speed per axis.
			if (count == 0) {
				sendFlyToward(level, particle, shape, center, radius, new Vec3(x, y, z),
						new Vec3(speed * xDist, speed * yDist, speed * zDist));
				return;
			}

			for (int i = 0; i < count; i++) {
				Vec3 target = new Vec3(
						x + level.getRandom().nextGaussian() * xDist,
						y + level.getRandom().nextGaussian() * yDist,
						z + level.getRandom().nextGaussian() * zDist);
				Vec3 offset = new Vec3(
						level.getRandom().nextGaussian() * speed,
						level.getRandom().nextGaussian() * speed,
						level.getRandom().nextGaussian() * speed);
				sendFlyToward(level, particle, shape, center, radius, target, offset);
			}

			return;
		}

		Vec3 origin = containPoint(shape, center, radius, new Vec3(x, y, z));
		if (count == 0) {
			// Velocity/displacement form: keep the far end of speed * dist inside.
			Vec3 end = containPoint(shape, center, radius,
					origin.add(speed * xDist, speed * yDist, speed * zDist));
			if (isNonConvex(shape) && !segmentContained(shape, center, radius, origin, end)) {
				// On RING/HOURGLASS/STAR the straight client path between the two
				// contained endpoints can bow outside (hole/waist/lobe gap): pull
				// the far end back toward the contained origin (the trivially safe
				// anchor of this segment) until every subsample of the rendered
				// segment is inside. Already-safe segments passed the check above
				// untouched, so those emissions stay byte-identical.
				Vec3 lo = origin;
				Vec3 hi = end;
				for (int i = 0; i < 16; i++) {
					Vec3 mid = lo.lerp(hi, 0.5);
					if (segmentContained(shape, center, radius, origin, mid)) {
						lo = mid;
					} else {
						hi = mid;
					}
				}

				end = lo;
			}

			double sx = speed == 0.0 ? 0.0 : (end.x - origin.x) / speed;
			double sy = speed == 0.0 ? 0.0 : (end.y - origin.y) / speed;
			double sz = speed == 0.0 ? 0.0 : (end.z - origin.z) / speed;
			level.sendParticles(particle, true, false, origin.x, origin.y, origin.z, 0, sx, sy, sz, speed);
			return;
		}

		if (spreadFits(shape, center, radius, origin,
				SPREAD_SIGMAS * xDist, SPREAD_SIGMAS * yDist, SPREAD_SIGMAS * zDist)) {
			level.sendParticles(particle, true, false, origin.x, origin.y, origin.z, count, xDist, yDist, zDist, speed);
			return;
		}

		for (int i = 0; i < count; i++) {
			Vec3 sample = containPoint(shape, center, radius, origin.add(
					level.getRandom().nextGaussian() * xDist,
					level.getRandom().nextGaussian() * yDist,
					level.getRandom().nextGaussian() * zDist));
			level.sendParticles(particle, true, false, sample.x, sample.y, sample.z, 1, 0.0, 0.0, 0.0, speed);
		}
	}

	/**
	 * Emits one count=0 fly-toward particle (ENCHANT/NAUTILUS/VAULT_CONNECTION,
	 * the {@code FlyTowardsPositionParticle} types) whose ENTIRE client trajectory
	 * stays inside the shell: the spawn point ({@code target + spawnOffset}), the
	 * destination AND the destination's built-in {@link #FLY_TOWARD_DIP} Y
	 * undershoot (the particle lands exactly 1.2 blocks BELOW the packet position).
	 *
	 * <p>The destination is settled by bisecting from a per-shape safe anchor
	 * (which trivially satisfies both constraints) toward the requested target for
	 * the farthest point that keeps both the target and its dip inside — the
	 * result is ALWAYS valid, direction-preserving, and identical to the request
	 * whenever the request was already safe. The spawn offset is then re-anchored
	 * on the settled destination and contained as well.
	 *
	 * <p>On the non-convex RING/HOURGLASS/STAR the endpoint checks are not enough
	 * (the straight spawn-to-destination path, and the dip curve under it, can bow
	 * outside through the hole/waist/lobe gap), so the whole reconstructed
	 * trajectory is additionally subsampled ({@code flyTowardTrajectoryContained})
	 * and, when any sample escapes, the emission is pulled toward the safe anchor:
	 * at pull fraction {@code s} the destination is {@code anchor.lerp(settled, s)}
	 * and the spawn offset scales by {@code s}, so {@code s = 0} degenerates to
	 * the anchor's own dip curve — on the axis (or on the ring circle), contained
	 * at every sample — and {@code s = 1} is the requested emission. Emissions
	 * whose full trajectory was already contained pass through unchanged.
	 */
	public static void sendFlyToward(ServerLevel level, ParticleOptions particle, ShieldShape shape, Vec3 center, double radius,
			Vec3 target, Vec3 spawnOffset) {
		Vec3 settled = containPoint(shape, center, radius, target);
		if (!flyTowardSafe(shape, center, radius, settled)) {
			// Bisect between the always-safe anchor (lo) and the requested point
			// (hi): lo stays safe throughout, so the result is guaranteed valid
			// even for the non-convex RING.
			Vec3 lo = flyTowardAnchor(shape, center, radius, settled);
			Vec3 hi = settled;
			for (int i = 0; i < 16; i++) {
				Vec3 mid = lo.lerp(hi, 0.5);
				if (flyTowardSafe(shape, center, radius, mid)) {
					lo = mid;
				} else {
					hi = mid;
				}
			}

			settled = lo;
		}

		Vec3 spawn = containPoint(shape, center, radius, settled.add(spawnOffset));
		if (isNonConvex(shape) && !flyTowardTrajectoryContained(shape, center, radius, spawn, settled)) {
			// The rendered path escapes the non-convex volume between its checked
			// endpoints: bisect the pull fraction s toward the safe anchor for the
			// largest s whose ENTIRE sampled trajectory stays contained.
			Vec3 anchor = flyTowardAnchor(shape, center, radius, settled);
			double lo = 0.0;
			double hi = 1.0;
			for (int i = 0; i < 16; i++) {
				double mid = (lo + hi) * 0.5;
				Vec3 midDest = anchor.lerp(settled, mid);
				Vec3 midSpawn = containPoint(shape, center, radius, midDest.add(spawnOffset.scale(mid)));
				if (flyTowardTrajectoryContained(shape, center, radius, midSpawn, midDest)) {
					lo = mid;
				} else {
					hi = mid;
				}
			}

			settled = anchor.lerp(settled, lo);
			spawn = containPoint(shape, center, radius, settled.add(spawnOffset.scale(lo)));
		}

		level.sendParticles(particle, true, false, settled.x, settled.y, settled.z, 0,
				spawn.x - settled.x, spawn.y - settled.y, spawn.z - settled.z, 1.0);
	}

	/** Both fly-toward constraints: the destination and its {@link #FLY_TOWARD_DIP} undershoot are inside. */
	private static boolean flyTowardSafe(ShieldShape shape, Vec3 center, double radius, Vec3 target) {
		return isContained(shape, center, radius, target)
				&& isContained(shape, center, radius, target.subtract(0.0, FLY_TOWARD_DIP, 0.0));
	}

	/**
	 * A destination that trivially satisfies both fly-toward constraints (itself
	 * and its {@link #FLY_TOWARD_DIP} undershoot inside) for every shape at the
	 * minimum shield radius 4: the vertical mid-dip point over the center — or,
	 * for the holey RING, over the nearest ring-circle point so the emission keeps
	 * its direction. PYRAMID/LENS/HOURGLASS/STAR ride the default vertical
	 * mid-dip anchor: the whole {@code center ± (0, 0.6, 0)} segment lies on the
	 * axis, which is inside all four 0.98-scaled volumes at radius 4 (PYRAMID band
	 * [-1.96, 3.528]; LENS norm 0.333; the HOURGLASS axis is inside both cones for
	 * any in-band |dy| — including through the pinched waist; STAR band ±2.156) —
	 * gametested in {@code ShapeGameTests.containPointProperties}.
	 */
	private static Vec3 flyTowardAnchor(ShieldShape shape, Vec3 center, double radius, Vec3 near) {
		if (shape == ShieldShape.RING) {
			double dx = near.x - center.x;
			double dz = near.z - center.z;
			double rho = Math.sqrt(dx * dx + dz * dz);
			double major = ShieldGeometry.RING_MAJOR_FRAC * radius;
			double qx = rho < 1.0e-6 ? center.x + major : center.x + dx * (major / rho);
			double qz = rho < 1.0e-6 ? center.z : center.z + dz * (major / rho);
			return new Vec3(qx, center.y + FLY_TOWARD_DIP * 0.5, qz);
		}

		// The DOME floor sits at center.y, so the dipped point must not go below it.
		double lift = shape == ShieldShape.DOME ? FLY_TOWARD_DIP : FLY_TOWARD_DIP * 0.5;
		return new Vec3(center.x, center.y + lift, center.z);
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

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
 * the shape-aware variant additionally clamps the point into the DOME half-space
 * ({@code y >= center.y}, mirroring {@link ShieldGeometry#isInside}) before the
 * rescale. Points that are already contained are returned UNCHANGED (the same
 * {@link Vec3} instance), so legacy emissions that were always inside stay
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
	 * Shape-aware containment for emissions that must respect a DOME: the point is
	 * first clamped into the dome half-space ({@code y >= center.y}, the same rule
	 * {@link ShieldGeometry#isInside} applies), then rescaled onto
	 * {@code 0.98 * radius} if still outside the shell. For SPHERE this is exactly
	 * {@link #containPoint(Vec3, double, Vec3)}. Fully contained points are
	 * returned unchanged.
	 */
	public static Vec3 containPoint(ShieldShape shape, Vec3 center, double radius, Vec3 point) {
		if (shape == ShieldShape.DOME && point.y < center.y) {
			point = new Vec3(point.x, center.y, point.z);
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
}

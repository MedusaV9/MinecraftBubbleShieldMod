package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Straight end-rod rays beaming outward from just above the projector.
 * All rays point at or above the horizon so a dome never shows rays below
 * its base plane.
 *
 * <ul>
 * <li>v0: 4 horizontal rays, slowly rotating</li>
 * <li>v1: 6 rays angled 30 degrees upward</li>
 * <li>v2: 8 counter-rotating rays alternating between low and steep pitch</li>
 * </ul>
 */
public final class PrismaticRays implements InsideEffectBehavior {
	public static final String ID = "prismatic_rays";
	private static final int MAX_POINTS = 128;
	/** Rays end at this fraction of the radius so motes never poke through the shell. */
	private static final double RAY_LENGTH_FRAC = 0.9;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int rays = switch (variant) {
			case 1 -> 6;
			case 2 -> 8;
			default -> 4;
		};
		double spin = gameTime / 10.0 * (variant == 2 ? -0.25 : 0.12);
		int pointsPerRay = ctx.scaleCount(Mth.clamp((int) Math.round(radius * RAY_LENGTH_FRAC * def.behaviorStrength()), 4, MAX_POINTS / rays), MAX_POINTS / rays);
		double rayLength = radius * RAY_LENGTH_FRAC;
		for (int ray = 0; ray < rays; ray++) {
			double azimuth = spin + Math.PI * 2.0 * ray / rays;
			double elevation = switch (variant) {
				case 1 -> Math.toRadians(30.0);
				case 2 -> Math.toRadians(ray % 2 == 0 ? 15.0 : 55.0);
				default -> 0.0;
			};
			double cosElev = Math.cos(elevation);
			double dirX = Math.cos(azimuth) * cosElev;
			double dirY = Math.sin(elevation);
			double dirZ = Math.sin(azimuth) * cosElev;
			for (int i = 1; i <= pointsPerRay; i++) {
				double dist = rayLength * i / pointsPerRay;
				double dx = dirX * dist;
				double dy = 0.8 + dirY * dist;
				double dz = dirZ * dist;
				// The 0.8-block emitter offset can push steep ray tips past the shell at
				// small radii; rescale any point beyond 0.98r back inside.
				double offset = Math.sqrt(dx * dx + dy * dy + dz * dz);
				double maxDist = radius * 0.98;
				if (offset > maxDist) {
					double scale = maxDist / offset;
					dx *= scale;
					dy *= scale;
					dz *= scale;
				}

				level.sendParticles(ParticleTypes.END_ROD, true, false, center.x + dx, center.y + dy, center.z + dz, 1, 0.0, 0.0, 0.0, 0.0);
			}
		}
	}
}

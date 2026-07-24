package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
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
 * <li>v3: 5 rays drawn in palette-colored dust</li>
 * <li>v4: 6 rays bobbing up and down as they sweep</li>
 * <li>v5: 10 short fast-spinning search-light rays</li>
 * <li>v6: 3 double rays of firework sparks</li>
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
			case 3 -> 5;
			case 4 -> 6;
			case 5 -> 10;
			case 6 -> 3;
			default -> 4;
		};
		double spin = gameTime / 10.0 * switch (variant) {
			case 2 -> -0.25;
			case 5 -> 0.7;
			default -> 0.12;
		};
		DustParticleOptions dust = variant == 3
				? new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.1F)
				: null;
		// v6 draws each ray twice (a parallel pair), so halve its per-ray budget.
		int budgetPerRay = variant == 6 ? MAX_POINTS / (rays * 2) : MAX_POINTS / rays;
		double lengthFrac = variant == 5 ? 0.55 : RAY_LENGTH_FRAC;
		int pointsPerRay = ctx.scaleCount(Mth.clamp((int) Math.round(radius * lengthFrac * def.behaviorStrength()), 4, budgetPerRay), budgetPerRay);
		double rayLength = radius * lengthFrac;
		for (int ray = 0; ray < rays; ray++) {
			double azimuth = spin + Math.PI * 2.0 * ray / rays;
			double elevation = switch (variant) {
				case 1 -> Math.toRadians(30.0);
				case 2 -> Math.toRadians(ray % 2 == 0 ? 15.0 : 55.0);
				case 4 -> Math.toRadians(22.5 + 22.5 * Math.sin(gameTime / 10.0 * 0.6 + ray));
				case 5 -> Math.toRadians(20.0);
				case 6 -> Math.toRadians(25.0);
				default -> 0.0;
			};
			double cosElev = Math.cos(elevation);
			double dirX = Math.cos(azimuth) * cosElev;
			double dirY = Math.sin(elevation);
			double dirZ = Math.sin(azimuth) * cosElev;
			// The parallel partner ray of a v6 pair is offset sideways along the tangent.
			double tangentX = -Math.sin(azimuth) * 0.5;
			double tangentZ = Math.cos(azimuth) * 0.5;
			int lines = variant == 6 ? 2 : 1;
			for (int line = 0; line < lines; line++) {
				double sideX = variant == 6 ? (line == 0 ? tangentX : -tangentX) : 0.0;
				double sideZ = variant == 6 ? (line == 0 ? tangentZ : -tangentZ) : 0.0;
				for (int i = 1; i <= pointsPerRay; i++) {
					double dist = rayLength * i / pointsPerRay;
					double dx = dirX * dist + sideX;
					double dy = 0.8 + dirY * dist;
					double dz = dirZ * dist + sideZ;
					// The 0.8-block emitter offset can push steep ray tips past the shell at
					// small radii; contain any point beyond 0.98r back inside.
					ParticleOptions particle = variant == 3 ? dust : variant == 6 ? ParticleTypes.FIREWORK : ParticleTypes.END_ROD;
					BehaviorSupport.sendContained(level, particle, shape, center, radius,
							center.x + dx, center.y + dy, center.z + dz, 1, 0.0, 0.0, 0.0, 0.0);
				}
			}
		}
	}
}

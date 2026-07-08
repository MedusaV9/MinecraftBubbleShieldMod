package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Charged latitude bands crackling just inside the shield boundary: electric
 * sparks along each band with dust-plume wisps drifting off them. All bands sit
 * at or above the center plane, so a dome keeps its full cage.
 *
 * <ul>
 * <li>v0: one narrow band low above the equator</li>
 * <li>v1: two medium bands at mid latitudes</li>
 * <li>v2: three wide bands climbing to a polar crown</li>
 * </ul>
 */
public final class StormCage implements InsideEffectBehavior {
	public static final String ID = "storm_cage";
	/** Spark budget; every 4th spark adds a dust plume, so worst case is 96 + 24 = 120/pulse. */
	private static final int MAX_POINTS = 96;
	/** Bands sit at this fraction of the radius, just inside the surface. */
	private static final double SHELL_FRAC = 0.94;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		double[] latitudesDeg = switch (variant) {
			case 1 -> new double[] {10.0, 45.0};
			case 2 -> new double[] {20.0, 50.0, 75.0};
			default -> new double[] {15.0};
		};
		// Half-width of each band as jitter in latitude, in radians.
		double halfWidth = Math.toRadians(switch (variant) {
			case 1 -> 6.0;
			case 2 -> 9.0;
			default -> 3.0;
		});
		double spin = gameTime / 10.0 * 0.2;
		RandomSource random = level.getRandom();
		int budgetPerBand = MAX_POINTS / latitudesDeg.length;
		for (int band = 0; band < latitudesDeg.length; band++) {
			double latitude = Math.toRadians(latitudesDeg[band]);
			double bandRadius = radius * SHELL_FRAC * Math.cos(latitude);
			int points = ctx.scaleCount(Mth.clamp((int) Math.round(Math.PI * 2.0 * bandRadius / 2.5 * def.behaviorStrength()), 6, budgetPerBand), budgetPerBand);
			for (int i = 0; i < points; i++) {
				// Jitter each point's latitude within the band so it reads as a belt, not a line.
				double pointLatitude = latitude + (random.nextDouble() * 2.0 - 1.0) * halfWidth;
				double ringRadius = radius * SHELL_FRAC * Math.cos(pointLatitude);
				double angle = spin * (band % 2 == 0 ? 1.0 : -1.0) + Math.PI * 2.0 * i / points;
				double x = center.x + Math.cos(angle) * ringRadius;
				double y = center.y + radius * SHELL_FRAC * Math.sin(pointLatitude);
				double z = center.z + Math.sin(angle) * ringRadius;
				level.sendParticles(ParticleTypes.ELECTRIC_SPARK, true, false, x, y, z, 1, 0.05, 0.05, 0.05, 0.0);
				// Every 4th point sheds a dust-plume wisp so the bands look like storm clouds.
				if (i % 4 == 0) {
					level.sendParticles(ParticleTypes.DUST_PLUME, true, false, x, y, z, 1, 0.1, 0.1, 0.1, 0.0);
				}
			}
		}
	}
}

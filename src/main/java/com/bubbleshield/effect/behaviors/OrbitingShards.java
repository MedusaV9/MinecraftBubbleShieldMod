package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Tilted particle orbits circling the projector like electron shells.
 *
 * <ul>
 * <li>v0: one tilted end rod orbit ring</li>
 * <li>v1: two crossed orbits</li>
 * <li>v2: three orbits of electric sparks</li>
 * </ul>
 */
public final class OrbitingShards implements InsideEffectBehavior {
	public static final String ID = "orbiting_shards";
	private static final int MAX_POINTS = 128;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int orbits = def.behaviorVariant() + 1;
		SimpleParticleType particle = def.behaviorVariant() == 2 ? ParticleTypes.ELECTRIC_SPARK : ParticleTypes.END_ROD;
		double orbitRadius = radius * 0.7;
		int pointsPerOrbit = ctx.scaleCount(Mth.clamp((int) Math.round(orbitRadius * 2.0 * def.behaviorStrength()), 8, MAX_POINTS / orbits), MAX_POINTS / orbits);
		double phase = gameTime / 10.0 * 0.35;
		for (int orbit = 0; orbit < orbits; orbit++) {
			// Each orbit plane is tilted and rotated so multiple orbits visibly cross.
			double tilt = Math.toRadians(35.0) + orbit * (Math.PI / (orbits + 1));
			double cosTilt = Math.cos(tilt);
			double sinTilt = Math.sin(tilt);
			double azimuth = orbit * (Math.PI * 2.0 / 3.0);
			double cosAzimuth = Math.cos(azimuth);
			double sinAzimuth = Math.sin(azimuth);
			for (int i = 0; i < pointsPerOrbit; i++) {
				double angle = phase + Math.PI * 2.0 * i / pointsPerOrbit;
				// Circle in a plane tilted around the X axis, then rotated around Y by the azimuth.
				double px = Math.cos(angle) * orbitRadius;
				double py = Math.sin(angle) * orbitRadius * sinTilt;
				double pz = Math.sin(angle) * orbitRadius * cosTilt;
				double x = center.x + px * cosAzimuth - pz * sinAzimuth;
				double z = center.z + px * sinAzimuth + pz * cosAzimuth;
				// Anchor the orbits mid-bubble so the tilted rings stay inside the sphere.
				level.sendParticles(particle, true, false, x, center.y + radius * 0.5 + py, z, 1, 0.0, 0.0, 0.0, 0.0);
			}
		}
	}
}

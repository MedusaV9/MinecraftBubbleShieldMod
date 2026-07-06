package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

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
	/** Emitted points are rescaled to at most this fraction of the radius (stay inside the shell). */
	private static final double MAX_DIST_FRAC = 0.98;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
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
				// Offset from the shield center: orbits are anchored mid-bubble.
				double dx = px * cosAzimuth - pz * sinAzimuth;
				double dy = radius * 0.5 + py;
				double dz = px * sinAzimuth + pz * cosAzimuth;
				// The mid-bubble anchor plus the tilted orbit can reach ~1.2r; rescale any
				// point beyond 0.98r back inside so shards never render outside the shell.
				double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
				double maxDist = radius * MAX_DIST_FRAC;
				if (dist > maxDist) {
					double scale = maxDist / dist;
					dx *= scale;
					dy *= scale;
					dz *= scale;
				}

				level.sendParticles(particle, true, false, center.x + dx, center.y + dy, center.z + dz, 1, 0.0, 0.0, 0.0, 0.0);
			}
		}
	}
}

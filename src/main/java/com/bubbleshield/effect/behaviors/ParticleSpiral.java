package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * A double helix of dust particles climbing the upper hemisphere of the shield.
 */
public final class ParticleSpiral implements InsideEffectBehavior {
	public static final String ID = "particle_spiral";
	private static final int POINTS = 16;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime) {
		if (gameTime % 10L != 0L) {
			return;
		}

		DustParticleOptions primary = new DustParticleOptions(def.argbPrimary() & 0xFFFFFF, 1.0F);
		DustParticleOptions secondary = new DustParticleOptions(def.argbSecondary() & 0xFFFFFF, 1.0F);
		double phase = gameTime / 10.0 * 0.4;
		for (int i = 0; i < POINTS; i++) {
			double frac = i / (double) POINTS;
			double height = frac * radius;
			double ringRadius = Math.sqrt(Math.max(0.0, radius * radius - height * height));
			double angle = phase + frac * Math.PI * 4.0;
			double x = center.x + Math.cos(angle) * ringRadius;
			double z = center.z + Math.sin(angle) * ringRadius;
			level.sendParticles(primary, x, center.y + height, z, 1, 0.0, 0.0, 0.0, 0.0);
			level.sendParticles(secondary, center.x - (x - center.x), center.y + height, center.z - (z - center.z), 1, 0.0, 0.0, 0.0, 0.0);
		}
	}
}

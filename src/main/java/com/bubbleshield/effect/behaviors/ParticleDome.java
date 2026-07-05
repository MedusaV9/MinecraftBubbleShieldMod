package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * A slowly rotating ring of dust particles hugging the shield surface at chest height.
 */
public final class ParticleDome implements InsideEffectBehavior {
	public static final String ID = "particle_dome";
	private static final int POINTS = 24;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime) {
		if (gameTime % 10L != 0L) {
			return;
		}

		DustParticleOptions primary = new DustParticleOptions(def.argbPrimary() & 0xFFFFFF, 1.0F);
		DustParticleOptions secondary = new DustParticleOptions(def.argbSecondary() & 0xFFFFFF, 0.7F);
		double phase = gameTime / 10.0 * 0.3;
		for (int i = 0; i < POINTS; i++) {
			double angle = phase + Math.PI * 2.0 * i / POINTS;
			double x = center.x + Math.cos(angle) * radius;
			double z = center.z + Math.sin(angle) * radius;
			level.sendParticles(i % 2 == 0 ? primary : secondary, x, center.y + 1.0, z, 1, 0.05, 0.05, 0.05, 0.0);
		}
	}
}

package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A slowly rotating ring of dust particles hugging the shield surface at chest height.
 */
public final class ParticleDome implements InsideEffectBehavior {
	public static final String ID = "particle_dome";
	private static final int MIN_POINTS = 24;
	private static final int MAX_POINTS = 128;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime) {
		if (gameTime % 10L != 0L) {
			return;
		}

		DustParticleOptions primary = new DustParticleOptions(def.argbPrimary() & 0xFFFFFF, 1.0F);
		DustParticleOptions secondary = new DustParticleOptions(def.argbSecondary() & 0xFFFFFF, 0.7F);
		// Keep the point spacing roughly constant (one point per ~2 blocks of circumference)
		// so the ring does not look sparse at radius 100.
		int points = Mth.clamp((int) Math.round(Math.PI * 2.0 * radius / 2.0), MIN_POINTS, MAX_POINTS);
		double phase = gameTime / 10.0 * 0.3;
		for (int i = 0; i < points; i++) {
			double angle = phase + Math.PI * 2.0 * i / points;
			double x = center.x + Math.cos(angle) * radius;
			double z = center.z + Math.sin(angle) * radius;
			// overrideLimiter=true lifts the 32-block send limit so players anywhere inside
			// a large bubble still see the ring.
			level.sendParticles(i % 2 == 0 ? primary : secondary, true, false, x, center.y + 1.0, z, 1, 0.05, 0.05, 0.05, 0.0);
		}
	}
}

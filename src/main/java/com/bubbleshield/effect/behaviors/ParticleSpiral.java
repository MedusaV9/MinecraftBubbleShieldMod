package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A double helix of dust particles climbing the upper hemisphere of the shield.
 */
public final class ParticleSpiral implements InsideEffectBehavior {
	public static final String ID = "particle_spiral";
	private static final int MIN_POINTS = 16;
	private static final int MAX_POINTS = 64;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime) {
		if (gameTime % 10L != 0L) {
			return;
		}

		DustParticleOptions primary = new DustParticleOptions(def.argbPrimary() & 0xFFFFFF, 1.0F);
		DustParticleOptions secondary = new DustParticleOptions(def.argbSecondary() & 0xFFFFFF, 1.0F);
		// Scale helix density with the bubble size so the strands stay visible at radius 100.
		// Each point emits two particles (one per strand), so 64 points = 128 particles/pulse.
		int points = Mth.clamp((int) Math.round(radius * 1.5), MIN_POINTS, MAX_POINTS);
		double phase = gameTime / 10.0 * 0.4;
		for (int i = 0; i < points; i++) {
			double frac = i / (double) points;
			double height = frac * radius;
			double ringRadius = Math.sqrt(Math.max(0.0, radius * radius - height * height));
			double angle = phase + frac * Math.PI * 4.0;
			double x = center.x + Math.cos(angle) * ringRadius;
			double z = center.z + Math.sin(angle) * ringRadius;
			// overrideLimiter=true lifts the 32-block send limit for players inside large bubbles.
			level.sendParticles(primary, true, false, x, center.y + height, z, 1, 0.0, 0.0, 0.0, 0.0);
			level.sendParticles(secondary, true, false, center.x - (x - center.x), center.y + height, center.z - (z - center.z), 1, 0.0, 0.0, 0.0, 0.0);
		}
	}
}

package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Embers (flame particles) drifting down from the upper half of the bubble.
 */
public final class EmberRain implements InsideEffectBehavior {
	public static final String ID = "ember_rain";

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime) {
		if (gameTime % 10L != 0L) {
			return;
		}

		// Scale the ember count with the bubble size and override the 32-block send
		// limiter so players deep inside a large bubble (radius up to 100) still see them.
		int count = Mth.clamp((int) (radius * 2.5F), 20, 128);
		level.sendParticles(
				ParticleTypes.FLAME,
				true, false,
				center.x, center.y + radius * 0.6, center.z,
				count,
				radius * 0.5, radius * 0.25, radius * 0.5,
				0.02
		);
	}
}

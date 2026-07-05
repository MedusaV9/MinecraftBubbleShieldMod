package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Gentle snowflakes falling throughout the inside of the bubble.
 */
public final class Snowfall implements InsideEffectBehavior {
	public static final String ID = "snowfall";

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime) {
		if (gameTime % 10L != 0L) {
			return;
		}

		// Scale the flake count with the bubble size and override the 32-block send
		// limiter so players deep inside a large bubble (radius up to 100) still see them.
		int count = Mth.clamp((int) (radius * 3.0F), 24, 128);
		level.sendParticles(
				ParticleTypes.SNOWFLAKE,
				true, false,
				center.x, center.y + radius * 0.6, center.z,
				count,
				radius * 0.6, radius * 0.3, radius * 0.6,
				0.01
		);
	}
}

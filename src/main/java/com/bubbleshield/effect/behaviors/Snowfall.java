package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
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

		level.sendParticles(
				ParticleTypes.SNOWFLAKE,
				center.x, center.y + radius * 0.6, center.z,
				24,
				radius * 0.6, radius * 0.3, radius * 0.6,
				0.01
		);
	}
}

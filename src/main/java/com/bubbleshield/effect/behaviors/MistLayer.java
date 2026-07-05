package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * A low-hanging blanket of cloud particles rolling across the floor of the bubble.
 */
public final class MistLayer implements InsideEffectBehavior {
	public static final String ID = "mist_layer";

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime) {
		if (gameTime % 10L != 0L) {
			return;
		}

		level.sendParticles(
				ParticleTypes.CLOUD,
				center.x, center.y + 0.2, center.z,
				16,
				radius * 0.6, 0.15, radius * 0.6,
				0.005
		);
	}
}

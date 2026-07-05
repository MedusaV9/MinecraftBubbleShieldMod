package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A swarm of slow-drifting end rod motes floating around inside the bubble.
 */
public final class FireflySwarm implements InsideEffectBehavior {
	public static final String ID = "firefly_swarm";

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime) {
		if (gameTime % 10L != 0L) {
			return;
		}

		// Scale the mote count with the bubble size and override the 32-block send
		// limiter so players deep inside a large bubble (radius up to 100) still see them.
		int count = Mth.clamp((int) (radius * 1.5F), 12, 128);
		level.sendParticles(
				ParticleTypes.END_ROD,
				true, false,
				center.x, center.y + radius * 0.35, center.z,
				count,
				radius * 0.55, radius * 0.3, radius * 0.55,
				0.01
		);
	}
}

package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Embers drifting down from the upper half of the bubble.
 *
 * <ul>
 * <li>v0: flame particles</li>
 * <li>v1: falling lava droplets plus lava pops</li>
 * <li>v2: soul fire flames ("cold fire")</li>
 * </ul>
 */
public final class EmberRain implements InsideEffectBehavior {
	public static final String ID = "ember_rain";

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		if (variant == 0) {
			// v0 unchanged from the 10-behavior era: scale the ember count with the bubble
			// size and override the 32-block send limiter so players deep inside a large
			// bubble (radius up to 100) still see them.
			int count = ctx.scaleCount(Mth.clamp((int) (radius * 2.5F), 20, 128), 128);
			level.sendParticles(
					ParticleTypes.FLAME,
					true, false,
					center.x, center.y + radius * 0.6, center.z,
					count,
					radius * 0.5, radius * 0.25, radius * 0.5,
					0.02
			);
			return;
		}

		if (variant == 1) {
			int count = ctx.scaleCount(Mth.clamp((int) (radius * 2.5F * def.behaviorStrength()), 20, 112), 112);
			level.sendParticles(
					ParticleTypes.FALLING_LAVA,
					true, false,
					center.x, center.y + radius * 0.6, center.z,
					count,
					radius * 0.5, radius * 0.25, radius * 0.5,
					0.0
			);
			// A few lava pops near the floor sell the "raining embers" impact.
			level.sendParticles(ParticleTypes.LAVA, true, false, center.x, center.y + 0.4, center.z, Math.min(16, count / 4), radius * 0.4, 0.2, radius * 0.4, 0.0);
			return;
		}

		int count = ctx.scaleCount(Mth.clamp((int) (radius * 2.5F * def.behaviorStrength()), 20, 128), 128);
		level.sendParticles(
				ParticleTypes.SOUL_FIRE_FLAME,
				true, false,
				center.x, center.y + radius * 0.6, center.z,
				count,
				radius * 0.5, radius * 0.25, radius * 0.5,
				0.02
		);
	}
}

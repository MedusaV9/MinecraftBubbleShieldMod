package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A low-hanging blanket of particles rolling across the floor of the bubble.
 *
 * <ul>
 * <li>v0: cloud puffs</li>
 * <li>v1: a white ash / ash duotone</li>
 * <li>v2: a spore blossom haze</li>
 * </ul>
 */
public final class MistLayer implements InsideEffectBehavior {
	public static final String ID = "mist_layer";

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		if (variant == 0) {
			// v0 unchanged from the 10-behavior era: scale the cloud count with the bubble
			// size and override the 32-block send limiter so players deep inside a large
			// bubble (radius up to 100) still see them.
			int count = ctx.scaleCount(Mth.clamp((int) (radius * 2.0F), 16, 128), 128);
			level.sendParticles(
					ParticleTypes.CLOUD,
					true, false,
					center.x, center.y + 0.2, center.z,
					count,
					radius * 0.6, 0.15, radius * 0.6,
					0.005
			);
			return;
		}

		if (variant == 1) {
			int count = ctx.scaleCount(Mth.clamp((int) (radius * 2.0F * def.behaviorStrength()), 16, 84), 84);
			level.sendParticles(ParticleTypes.WHITE_ASH, true, false, center.x, center.y + 0.4, center.z, count, radius * 0.6, 0.3, radius * 0.6, 0.0);
			level.sendParticles(ParticleTypes.ASH, true, false, center.x, center.y + 0.6, center.z, Math.min(42, count / 2), radius * 0.6, 0.3, radius * 0.6, 0.0);
			return;
		}

		int count = ctx.scaleCount(Mth.clamp((int) (radius * 2.0F * def.behaviorStrength()), 16, 128), 128);
		level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, true, false, center.x, center.y + 0.8, center.z, count, radius * 0.6, 0.6, radius * 0.6, 0.0);
	}
}

package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Snow falling throughout the inside of the bubble.
 *
 * <ul>
 * <li>v0: gentle snowflakes</li>
 * <li>v1: a dense blizzard mixed with white ash</li>
 * <li>v2: sparse flakes with occasional snowball puffs</li>
 * </ul>
 */
public final class Snowfall implements InsideEffectBehavior {
	public static final String ID = "snowfall";

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		if (variant == 0) {
			// v0 unchanged from the 10-behavior era: scale the flake count with the bubble
			// size and override the 32-block send limiter so players deep inside a large
			// bubble (radius up to 100) still see them.
			int count = ctx.scaleCount(Mth.clamp((int) (radius * 3.0F), 24, 128), 128);
			level.sendParticles(
					ParticleTypes.SNOWFLAKE,
					true, false,
					center.x, center.y + radius * 0.6, center.z,
					count,
					radius * 0.6, radius * 0.3, radius * 0.6,
					0.01
			);
			return;
		}

		if (variant == 1) {
			// Blizzard: 96 flakes + 32 ash = 128 particles/pulse max.
			int flakes = ctx.scaleCount(Mth.clamp((int) (radius * 4.0F * def.behaviorStrength()), 32, 96), 96);
			level.sendParticles(ParticleTypes.SNOWFLAKE, true, false, center.x, center.y + radius * 0.6, center.z, flakes, radius * 0.6, radius * 0.3, radius * 0.6, 0.04);
			level.sendParticles(ParticleTypes.WHITE_ASH, true, false, center.x, center.y + radius * 0.4, center.z, Math.min(32, flakes / 3), radius * 0.6, radius * 0.3, radius * 0.6, 0.02);
			return;
		}

		int count = ctx.scaleCount(Mth.clamp((int) (radius * 1.5F * def.behaviorStrength()), 12, 96), 96);
		level.sendParticles(ParticleTypes.SNOWFLAKE, true, false, center.x, center.y + radius * 0.6, center.z, count, radius * 0.6, radius * 0.3, radius * 0.6, 0.005);
		// Occasional soft snowball puffs near the ground.
		level.sendParticles(ParticleTypes.ITEM_SNOWBALL, true, false, center.x, center.y + 0.8, center.z, 4, radius * 0.4, 0.3, radius * 0.4, 0.02);
	}
}

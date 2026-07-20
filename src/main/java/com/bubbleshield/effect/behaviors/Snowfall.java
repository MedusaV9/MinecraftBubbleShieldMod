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
 * <li>v3: sleet of snowflakes and dripstone water drips</li>
 * <li>v4: diamond dust: sparse flakes among end rod glints</li>
 * <li>v5: flakes swirling out of a rotating mid-height ring</li>
 * <li>v6: hail: heavy snowball pellets with floor poofs</li>
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

		if (variant == 2) {
			int count = ctx.scaleCount(Mth.clamp((int) (radius * 1.5F * def.behaviorStrength()), 12, 96), 96);
			level.sendParticles(ParticleTypes.SNOWFLAKE, true, false, center.x, center.y + radius * 0.6, center.z, count, radius * 0.6, radius * 0.3, radius * 0.6, 0.005);
			// Occasional soft snowball puffs near the ground.
			level.sendParticles(ParticleTypes.ITEM_SNOWBALL, true, false, center.x, center.y + 0.8, center.z, 4, radius * 0.4, 0.3, radius * 0.4, 0.02);
			return;
		}

		if (variant == 3) {
			// Sleet: 84 flakes + 28 dripstone drips = 112 particles/pulse max.
			int flakes = ctx.scaleCount(Mth.clamp((int) (radius * 2.5F * def.behaviorStrength()), 20, 84), 84);
			level.sendParticles(ParticleTypes.SNOWFLAKE, true, false, center.x, center.y + radius * 0.6, center.z, flakes, radius * 0.6, radius * 0.3, radius * 0.6, 0.01);
			level.sendParticles(ParticleTypes.FALLING_DRIPSTONE_WATER, true, false, center.x, center.y + radius * 0.55, center.z, Math.min(28, flakes / 3), radius * 0.55, radius * 0.25, radius * 0.55, 0.0);
			return;
		}

		if (variant == 4) {
			// Diamond dust: 84 flakes + 28 glints = 112 particles/pulse max.
			int flakes = ctx.scaleCount(Mth.clamp((int) (radius * 1.2F * def.behaviorStrength()), 10, 84), 84);
			level.sendParticles(ParticleTypes.SNOWFLAKE, true, false, center.x, center.y + radius * 0.5, center.z, flakes, radius * 0.6, radius * 0.35, radius * 0.6, 0.002);
			level.sendParticles(ParticleTypes.END_ROD, true, false, center.x, center.y + radius * 0.4, center.z, Math.min(28, flakes / 3), radius * 0.55, radius * 0.3, radius * 0.55, 0.002);
			return;
		}

		if (variant == 5) {
			// Swirl: flakes emitted along a rotating ring, falling from mid height.
			double ringRadius = radius * 0.6;
			int points = ctx.scaleCount(Mth.clamp((int) Math.round(Math.PI * 2.0 * ringRadius / 1.5 * def.behaviorStrength()), 12, 128), 128);
			double phase = gameTime / 10.0 * 0.5;
			for (int i = 0; i < points; i++) {
				double angle = phase + Math.PI * 2.0 * i / points;
				double x = center.x + Math.cos(angle) * ringRadius;
				double z = center.z + Math.sin(angle) * ringRadius;
				level.sendParticles(ParticleTypes.SNOWFLAKE, true, false, x, center.y + radius * 0.45, z, 1, 0.1, 0.2, 0.1, 0.01);
			}
			return;
		}

		// v6: hail; 96 pellets + 16 floor poofs = 112 particles/pulse max.
		int pellets = ctx.scaleCount(Mth.clamp((int) (radius * 3.0F * def.behaviorStrength()), 24, 96), 96);
		level.sendParticles(ParticleTypes.ITEM_SNOWBALL, true, false, center.x, center.y + radius * 0.6, center.z, pellets, radius * 0.55, radius * 0.3, radius * 0.55, 0.05);
		level.sendParticles(ParticleTypes.POOF, true, false, center.x, center.y + 0.3, center.z, Math.min(16, pellets / 6), radius * 0.4, 0.1, radius * 0.4, 0.0);
	}
}

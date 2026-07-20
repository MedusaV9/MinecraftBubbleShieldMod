package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.DustParticleOptions;
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
 * <li>v3: cosy campfire smoke rolling low</li>
 * <li>v4: a clean white smoke bank</li>
 * <li>v5: glowing fog: clouds threaded with glow motes</li>
 * <li>v6: a palette-colored dust fog hugging the floor</li>
 * </ul>
 */
public final class MistLayer implements InsideEffectBehavior {
	public static final String ID = "mist_layer";

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
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

		if (variant == 2) {
			int count = ctx.scaleCount(Mth.clamp((int) (radius * 2.0F * def.behaviorStrength()), 16, 128), 128);
			level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, true, false, center.x, center.y + 0.8, center.z, count, radius * 0.6, 0.6, radius * 0.6, 0.0);
			return;
		}

		if (variant == 3) {
			// Campfire smoke rises slowly, so keep the count low and the layer thin.
			int count = ctx.scaleCount(Mth.clamp((int) (radius * 1.0F * def.behaviorStrength()), 8, 48), 48);
			level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, false, center.x, center.y + 0.3, center.z, count, radius * 0.6, 0.1, radius * 0.6, 0.003);
			return;
		}

		if (variant == 4) {
			int count = ctx.scaleCount(Mth.clamp((int) (radius * 2.0F * def.behaviorStrength()), 16, 96), 96);
			level.sendParticles(ParticleTypes.WHITE_SMOKE, true, false, center.x, center.y + 0.3, center.z, count, radius * 0.6, 0.2, radius * 0.6, 0.002);
			return;
		}

		if (variant == 5) {
			// Glowing fog: 84 clouds + 28 glow motes = 112 particles/pulse max.
			int count = ctx.scaleCount(Mth.clamp((int) (radius * 2.0F * def.behaviorStrength()), 16, 84), 84);
			level.sendParticles(ParticleTypes.CLOUD, true, false, center.x, center.y + 0.2, center.z, count, radius * 0.6, 0.15, radius * 0.6, 0.005);
			level.sendParticles(ParticleTypes.GLOW, true, false, center.x, center.y + 0.6, center.z, Math.min(28, count / 3), radius * 0.55, 0.3, radius * 0.55, 0.0);
			return;
		}

		// v6: a knee-height fog rendered in the effect's palette dust.
		DustParticleOptions dust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.6F);
		int count = ctx.scaleCount(Mth.clamp((int) (radius * 2.0F * def.behaviorStrength()), 16, 128), 128);
		level.sendParticles(dust, true, false, center.x, center.y + 0.4, center.z, count, radius * 0.6, 0.2, radius * 0.6, 0.0);
	}
}

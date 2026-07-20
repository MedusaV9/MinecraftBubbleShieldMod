package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Leaves and petals drifting down through the bubble.
 *
 * <ul>
 * <li>v0: pale oak leaves</li>
 * <li>v1: cherry blossom petals</li>
 * <li>v2: falling spore blossoms</li>
 * <li>v3: leaves tinted in the effect's primary color</li>
 * <li>v4: a cherry and pale oak confetti mix</li>
 * <li>v5: a sweet drizzle of falling nectar</li>
 * <li>v6: secondary-tinted leaves with spore blossom sprinkles</li>
 * </ul>
 */
public final class FallingPetals implements InsideEffectBehavior {
	public static final String ID = "falling_petals";

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		if (variant == 4) {
			// Confetti: 64 cherry + 64 pale oak = 128 particles/pulse max.
			int count = ctx.scaleCount(Mth.clamp((int) (radius * 1.5F * def.behaviorStrength()), 8, 64), 64);
			level.sendParticles(ParticleTypes.CHERRY_LEAVES, true, false, center.x, center.y + radius * 0.55, center.z, count, radius * 0.6, radius * 0.25, radius * 0.6, 0.0);
			level.sendParticles(ParticleTypes.PALE_OAK_LEAVES, true, false, center.x, center.y + radius * 0.5, center.z, count, radius * 0.6, radius * 0.25, radius * 0.6, 0.0);
			return;
		}

		if (variant == 6) {
			// Tinted leaves in the secondary color with a spore sprinkle: 96 + 24 = 120 max.
			ColorParticleOption tinted = ColorParticleOption.create(ParticleTypes.TINTED_LEAVES, ctx.secondaryColor(def.argbSecondary()));
			int count = ctx.scaleCount(Mth.clamp((int) (radius * 2.0F * def.behaviorStrength()), 12, 96), 96);
			level.sendParticles(tinted, true, false, center.x, center.y + radius * 0.55, center.z, count, radius * 0.6, radius * 0.25, radius * 0.6, 0.0);
			level.sendParticles(ParticleTypes.FALLING_SPORE_BLOSSOM, true, false, center.x, center.y + radius * 0.5, center.z, Math.min(24, count / 4), radius * 0.55, radius * 0.2, radius * 0.55, 0.0);
			return;
		}

		ParticleOptions particle = switch (variant) {
			case 1 -> ParticleTypes.CHERRY_LEAVES;
			case 2 -> ParticleTypes.FALLING_SPORE_BLOSSOM;
			case 3 -> ColorParticleOption.create(ParticleTypes.TINTED_LEAVES, ctx.pickColor(def.argbPrimary(), def.argbSecondary()));
			case 5 -> ParticleTypes.FALLING_NECTAR;
			default -> ParticleTypes.PALE_OAK_LEAVES;
		};
		int count = ctx.scaleCount(Mth.clamp((int) (radius * 2.5F * def.behaviorStrength()), 16, 128), 128);
		level.sendParticles(
				particle,
				true, false,
				center.x, center.y + radius * 0.55, center.z,
				count,
				radius * 0.6, radius * 0.25, radius * 0.6,
				0.0
		);
	}
}

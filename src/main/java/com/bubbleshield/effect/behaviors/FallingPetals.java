package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
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
 * </ul>
 */
public final class FallingPetals implements InsideEffectBehavior {
	public static final String ID = "falling_petals";

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime) {
		if (gameTime % 10L != 0L) {
			return;
		}

		SimpleParticleType particle = switch (def.behaviorVariant()) {
			case 1 -> ParticleTypes.CHERRY_LEAVES;
			case 2 -> ParticleTypes.FALLING_SPORE_BLOSSOM;
			default -> ParticleTypes.PALE_OAK_LEAVES;
		};
		int count = Mth.clamp((int) (radius * 2.5F * def.behaviorStrength()), 16, 128);
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

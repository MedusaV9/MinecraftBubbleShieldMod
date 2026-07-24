package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Ambient spores drifting through the whole bubble interior.
 *
 * <ul>
 * <li>v0: mycelium motes</li>
 * <li>v1: warped (teal) spores</li>
 * <li>v2: crimson (red) spores</li>
 * <li>v3: a blossom flurry of falling spore petals</li>
 * <li>v4: grey ash and white ash drifting together</li>
 * <li>v5: a warped and crimson spore duotone</li>
 * <li>v6: green composter motes</li>
 * </ul>
 */
public final class SporeDrift implements InsideEffectBehavior {
	public static final String ID = "spore_drift";

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		if (variant == 4) {
			// Ash duotone: 64 + 64 = 128 particles/pulse max.
			int count = ctx.scaleCount(Mth.clamp((int) (radius * 1.0F * def.behaviorStrength()), 8, 64), 64);
			BehaviorSupport.sendContained(level, ParticleTypes.ASH, shape, center, radius, center.x, center.y + radius * 0.35, center.z, count, radius * 0.6, radius * 0.3, radius * 0.6, 0.0);
			BehaviorSupport.sendContained(level, ParticleTypes.WHITE_ASH, shape, center, radius, center.x, center.y + radius * 0.4, center.z, count, radius * 0.6, radius * 0.3, radius * 0.6, 0.0);
			return;
		}

		if (variant == 5) {
			// Both nether spores at once: 64 + 64 = 128 particles/pulse max.
			int count = ctx.scaleCount(Mth.clamp((int) (radius * 1.0F * def.behaviorStrength()), 8, 64), 64);
			BehaviorSupport.sendContained(level, ParticleTypes.WARPED_SPORE, shape, center, radius, center.x, center.y + radius * 0.4, center.z, count, radius * 0.6, radius * 0.3, radius * 0.6, 0.0);
			BehaviorSupport.sendContained(level, ParticleTypes.CRIMSON_SPORE, shape, center, radius, center.x, center.y + radius * 0.3, center.z, count, radius * 0.6, radius * 0.3, radius * 0.6, 0.0);
			return;
		}

		SimpleParticleType particle = switch (variant) {
			case 1 -> ParticleTypes.WARPED_SPORE;
			case 2 -> ParticleTypes.CRIMSON_SPORE;
			case 3 -> ParticleTypes.FALLING_SPORE_BLOSSOM;
			case 6 -> ParticleTypes.COMPOSTER;
			default -> ParticleTypes.MYCELIUM;
		};
		int count = ctx.scaleCount(Mth.clamp((int) (radius * 2.0F * def.behaviorStrength()), 16, 128), 128);
		BehaviorSupport.sendContained(level,
				particle,
				shape, center, radius,
				center.x, center.y + radius * 0.35, center.z,
				count,
				radius * 0.6, radius * 0.3, radius * 0.6,
				0.0
		);
	}
}

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
 * A swarm of slow-drifting motes floating around inside the bubble.
 *
 * <ul>
 * <li>v0: end rod motes</li>
 * <li>v1: the vanilla firefly particle</li>
 * <li>v2: glow motes with wax-on sparks</li>
 * </ul>
 */
public final class FireflySwarm implements InsideEffectBehavior {
	public static final String ID = "firefly_swarm";

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		if (variant == 0) {
			// v0 unchanged from the 10-behavior era: scale the mote count with the bubble
			// size and override the 32-block send limiter so players deep inside a large
			// bubble (radius up to 100) still see them.
			int count = ctx.scaleCount(Mth.clamp((int) (radius * 1.5F), 12, 128), 128);
			level.sendParticles(
					ParticleTypes.END_ROD,
					true, false,
					center.x, center.y + radius * 0.35, center.z,
					count,
					radius * 0.55, radius * 0.3, radius * 0.55,
					0.01
			);
			return;
		}

		if (variant == 1) {
			int count = ctx.scaleCount(Mth.clamp((int) (radius * 2.0F * def.behaviorStrength()), 12, 128), 128);
			level.sendParticles(ParticleTypes.FIREFLY, true, false, center.x, center.y + radius * 0.35, center.z, count, radius * 0.55, radius * 0.3, radius * 0.55, 0.01);
			return;
		}

		int count = ctx.scaleCount(Mth.clamp((int) (radius * 1.5F * def.behaviorStrength()), 12, 96), 96);
		level.sendParticles(ParticleTypes.GLOW, true, false, center.x, center.y + radius * 0.35, center.z, count, radius * 0.55, radius * 0.3, radius * 0.55, 0.01);
		level.sendParticles(ParticleTypes.WAX_ON, true, false, center.x, center.y + radius * 0.35, center.z, Math.min(32, count / 2), radius * 0.5, radius * 0.25, radius * 0.5, 0.0);
	}
}

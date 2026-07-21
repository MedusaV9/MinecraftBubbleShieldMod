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
 * <li>v3: fireflies mingling with glow motes</li>
 * <li>v4: a low carpet of end rod motes hugging the floor</li>
 * <li>v5: a firefly belt orbiting mid-bubble</li>
 * <li>v6: sparse fireflies with happy-villager glints</li>
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
			BehaviorSupport.sendContained(level,
					ParticleTypes.END_ROD,
					shape, center, radius,
				center.x, center.y + radius * 0.35, center.z,
					count,
					radius * 0.55, radius * 0.3, radius * 0.55,
					0.01
			);
			return;
		}

		if (variant == 1) {
			int count = ctx.scaleCount(Mth.clamp((int) (radius * 2.0F * def.behaviorStrength()), 12, 128), 128);
			BehaviorSupport.sendContained(level, ParticleTypes.FIREFLY, shape, center, radius, center.x, center.y + radius * 0.35, center.z, count, radius * 0.55, radius * 0.3, radius * 0.55, 0.01);
			return;
		}

		if (variant == 2) {
			int count = ctx.scaleCount(Mth.clamp((int) (radius * 1.5F * def.behaviorStrength()), 12, 96), 96);
			BehaviorSupport.sendContained(level, ParticleTypes.GLOW, shape, center, radius, center.x, center.y + radius * 0.35, center.z, count, radius * 0.55, radius * 0.3, radius * 0.55, 0.01);
			BehaviorSupport.sendContained(level, ParticleTypes.WAX_ON, shape, center, radius, center.x, center.y + radius * 0.35, center.z, Math.min(32, count / 2), radius * 0.5, radius * 0.25, radius * 0.5, 0.0);
			return;
		}

		if (variant == 3) {
			// Mixed swarm: 64 fireflies + 64 glow motes = 128 particles/pulse max.
			int count = ctx.scaleCount(Mth.clamp((int) (radius * 1.2F * def.behaviorStrength()), 8, 64), 64);
			BehaviorSupport.sendContained(level, ParticleTypes.FIREFLY, shape, center, radius, center.x, center.y + radius * 0.4, center.z, count, radius * 0.55, radius * 0.3, radius * 0.55, 0.01);
			BehaviorSupport.sendContained(level, ParticleTypes.GLOW, shape, center, radius, center.x, center.y + radius * 0.3, center.z, count, radius * 0.5, radius * 0.25, radius * 0.5, 0.005);
			return;
		}

		if (variant == 4) {
			int count = ctx.scaleCount(Mth.clamp((int) (radius * 2.5F * def.behaviorStrength()), 16, 128), 128);
			BehaviorSupport.sendContained(level, ParticleTypes.END_ROD, shape, center, radius, center.x, center.y + 0.6, center.z, count, radius * 0.6, 0.3, radius * 0.6, 0.003);
			return;
		}

		if (variant == 5) {
			// Belt: fireflies released along a slowly rotating ring at mid height.
			double ringRadius = radius * 0.55;
			int points = ctx.scaleCount(Mth.clamp((int) Math.round(Math.PI * 2.0 * ringRadius / 1.5 * def.behaviorStrength()), 10, 96), 96);
			double phase = gameTime / 10.0 * 0.25;
			for (int i = 0; i < points; i++) {
				double angle = phase + Math.PI * 2.0 * i / points;
				double x = center.x + Math.cos(angle) * ringRadius;
				double z = center.z + Math.sin(angle) * ringRadius;
				BehaviorSupport.sendContained(level, ParticleTypes.FIREFLY, shape, center, radius, x, center.y + radius * 0.4, z, 1, 0.15, 0.3, 0.15, 0.005);
			}
			return;
		}

		// v6: sparse fireflies with happy-villager glints; 48 + 16 = 64 particles/pulse max.
		int count = ctx.scaleCount(Mth.clamp((int) (radius * 1.0F * def.behaviorStrength()), 6, 48), 48);
		BehaviorSupport.sendContained(level, ParticleTypes.FIREFLY, shape, center, radius, center.x, center.y + radius * 0.35, center.z, count, radius * 0.55, radius * 0.3, radius * 0.55, 0.01);
		BehaviorSupport.sendContained(level, ParticleTypes.HAPPY_VILLAGER, shape, center, radius, center.x, center.y + radius * 0.35, center.z, Math.min(16, count / 3), radius * 0.5, radius * 0.25, radius * 0.5, 0.0);
	}
}

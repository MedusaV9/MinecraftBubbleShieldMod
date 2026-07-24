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
 * Embers drifting down from the upper half of the bubble.
 *
 * <ul>
 * <li>v0: flame particles</li>
 * <li>v1: falling lava droplets plus lava pops</li>
 * <li>v2: soul fire flames ("cold fire")</li>
 * <li>v3: green copper-fire flames</li>
 * <li>v4: mixed flames and falling lava with landing pops at the floor</li>
 * <li>v5: a gentle drizzle of small flames</li>
 * <li>v6: an ember storm of flames laced with cosy campfire smoke</li>
 * </ul>
 */
public final class EmberRain implements InsideEffectBehavior {
	public static final String ID = "ember_rain";

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		if (variant == 0) {
			// v0 unchanged from the 10-behavior era: scale the ember count with the bubble
			// size and override the 32-block send limiter so players deep inside a large
			// bubble (radius up to 100) still see them.
			int count = ctx.scaleCount(Mth.clamp((int) (radius * 2.5F), 20, 128), 128);
			BehaviorSupport.sendContained(level,
					ParticleTypes.FLAME,
					shape, center, radius,
				center.x, center.y + radius * 0.6, center.z,
					count,
					radius * 0.5, radius * 0.25, radius * 0.5,
					0.02
			);
			return;
		}

		if (variant == 1) {
			int count = ctx.scaleCount(Mth.clamp((int) (radius * 2.5F * def.behaviorStrength()), 20, 112), 112);
			BehaviorSupport.sendContained(level,
					ParticleTypes.FALLING_LAVA,
					shape, center, radius,
				center.x, center.y + radius * 0.6, center.z,
					count,
					radius * 0.5, radius * 0.25, radius * 0.5,
					0.0
			);
			// A few lava pops near the floor sell the "raining embers" impact.
			BehaviorSupport.sendContained(level, ParticleTypes.LAVA, shape, center, radius, center.x, center.y + 0.4, center.z, Math.min(16, count / 4), radius * 0.4, 0.2, radius * 0.4, 0.0);
			return;
		}

		if (variant == 2) {
			int count = ctx.scaleCount(Mth.clamp((int) (radius * 2.5F * def.behaviorStrength()), 20, 128), 128);
			BehaviorSupport.sendContained(level,
					ParticleTypes.SOUL_FIRE_FLAME,
					shape, center, radius,
				center.x, center.y + radius * 0.6, center.z,
					count,
					radius * 0.5, radius * 0.25, radius * 0.5,
					0.02
			);
			return;
		}

		if (variant == 3) {
			int count = ctx.scaleCount(Mth.clamp((int) (radius * 2.5F * def.behaviorStrength()), 20, 128), 128);
			BehaviorSupport.sendContained(level, ParticleTypes.COPPER_FIRE_FLAME, shape, center, radius, center.x, center.y + radius * 0.6, center.z, count, radius * 0.5, radius * 0.25, radius * 0.5, 0.02);
			return;
		}

		if (variant == 4) {
			// 56 flames + 56 lava droplets + 16 landing pops = 128 particles/pulse max.
			int count = ctx.scaleCount(Mth.clamp((int) (radius * 1.5F * def.behaviorStrength()), 12, 56), 56);
			BehaviorSupport.sendContained(level, ParticleTypes.FLAME, shape, center, radius, center.x, center.y + radius * 0.6, center.z, count, radius * 0.5, radius * 0.25, radius * 0.5, 0.02);
			BehaviorSupport.sendContained(level, ParticleTypes.FALLING_LAVA, shape, center, radius, center.x, center.y + radius * 0.5, center.z, count, radius * 0.5, radius * 0.2, radius * 0.5, 0.0);
			BehaviorSupport.sendContained(level, ParticleTypes.LANDING_LAVA, shape, center, radius, center.x, center.y + 0.2, center.z, Math.min(16, count / 3), radius * 0.4, 0.1, radius * 0.4, 0.0);
			return;
		}

		if (variant == 5) {
			int count = ctx.scaleCount(Mth.clamp((int) (radius * 3.0F * def.behaviorStrength()), 24, 128), 128);
			BehaviorSupport.sendContained(level, ParticleTypes.SMALL_FLAME, shape, center, radius, center.x, center.y + radius * 0.5, center.z, count, radius * 0.55, radius * 0.3, radius * 0.55, 0.005);
			return;
		}

		// v6: flame storm with cosy campfire smoke wisps; 96 + 24 = 120 particles/pulse max.
		int flames = ctx.scaleCount(Mth.clamp((int) (radius * 3.0F * def.behaviorStrength()), 24, 96), 96);
		BehaviorSupport.sendContained(level, ParticleTypes.FLAME, shape, center, radius, center.x, center.y + radius * 0.6, center.z, flames, radius * 0.5, radius * 0.25, radius * 0.5, 0.04);
		BehaviorSupport.sendContained(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, shape, center, radius, center.x, center.y + radius * 0.3, center.z, Math.min(24, flames / 4), radius * 0.45, radius * 0.2, radius * 0.45, 0.005);
	}
}

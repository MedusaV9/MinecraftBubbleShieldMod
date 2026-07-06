package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Freezes hostile mobs caught inside the shield (powder-snow style frost).
 *
 * <ul>
 * <li>v0: +60 frozen ticks with a snowflake burst</li>
 * <li>v1: +120 frozen ticks</li>
 * <li>v2: +160 frozen ticks plus Slowness I</li>
 * </ul>
 */
public final class FrostIntruders implements InsideEffectBehavior {
	public static final String ID = "frost_intruders";
	private static final int DURATION_TICKS = 60;
	/** Cap so repeated pulses cannot grow the freeze counter without bound. */
	private static final int MAX_FROZEN_TICKS = 400;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int freezeTicks = switch (variant) {
			case 1 -> 120;
			case 2 -> 160;
			default -> 60;
		};
		// Query Mob + Enemy instead of Monster so Enemy-only hostiles are covered too.
		AABB box = AABB.ofSize(center, radius * 2.0, radius * 2.0, radius * 2.0);
		for (Mob mob : level.getEntitiesOfClass(Mob.class, box, e -> e instanceof Enemy)) {
			if (mob.position().distanceTo(center) > radius) {
				continue;
			}

			mob.setTicksFrozen(Math.min(MAX_FROZEN_TICKS, mob.getTicksFrozen() + freezeTicks));
			level.sendParticles(ParticleTypes.SNOWFLAKE, true, false, mob.getX(), mob.getY() + mob.getBbHeight() * 0.5, mob.getZ(), ctx.scaleCount(8, 16), 0.3, 0.4, 0.3, 0.02);
			if (variant == 2) {
				mob.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, DURATION_TICKS, 0));
			}
		}
	}
}

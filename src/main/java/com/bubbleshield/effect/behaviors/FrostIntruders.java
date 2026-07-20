package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldGeometry;
import com.bubbleshield.shield.ShieldShape;

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
 * <li>v3: +100 frozen ticks plus a snowflake ring around each mob</li>
 * <li>v4: +140 frozen ticks plus Slowness II</li>
 * <li>v5: +80 frozen ticks plus Weakness I</li>
 * <li>v6: +200 frozen ticks plus a snowball pelt burst</li>
 * </ul>
 */
public final class FrostIntruders implements InsideEffectBehavior {
	public static final String ID = "frost_intruders";
	private static final int DURATION_TICKS = 60;
	/** Cap so repeated pulses cannot grow the freeze counter without bound. */
	private static final int MAX_FROZEN_TICKS = 400;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int freezeTicks = switch (variant) {
			case 1 -> 120;
			case 2 -> 160;
			case 3 -> 100;
			case 4 -> 140;
			case 5 -> 80;
			case 6 -> 200;
			default -> 60;
		};
		// Query Mob + Enemy instead of Monster so Enemy-only hostiles are covered too.
		AABB box = AABB.ofSize(center, radius * 2.0, radius * 2.0, radius * 2.0);
		for (Mob mob : level.getEntitiesOfClass(Mob.class, box, e -> e instanceof Enemy)) {
			if (!ShieldGeometry.isInside(shape, center, radius, mob.position())) {
				continue;
			}

			mob.setTicksFrozen(Math.min(MAX_FROZEN_TICKS, mob.getTicksFrozen() + freezeTicks));
			level.sendParticles(ParticleTypes.SNOWFLAKE, true, false, mob.getX(), mob.getY() + mob.getBbHeight() * 0.5, mob.getZ(), ctx.scaleCount(8, 16), 0.3, 0.4, 0.3, 0.02);
			if (variant == 2) {
				mob.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, DURATION_TICKS, 0));
			} else if (variant == 3) {
				double spin = gameTime / 10.0 * 0.5;
				for (int i = 0; i < 6; i++) {
					double angle = spin + Math.PI * 2.0 * i / 6;
					level.sendParticles(ParticleTypes.SNOWFLAKE, true, false,
							mob.getX() + Math.cos(angle) * 0.9, mob.getY() + mob.getBbHeight() * 0.5, mob.getZ() + Math.sin(angle) * 0.9, 1, 0.02, 0.1, 0.02, 0.0);
				}
			} else if (variant == 4) {
				mob.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, DURATION_TICKS, 1));
			} else if (variant == 5) {
				mob.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, DURATION_TICKS, 0));
			} else if (variant == 6) {
				level.sendParticles(ParticleTypes.ITEM_SNOWBALL, true, false, mob.getX(), mob.getY() + mob.getBbHeight() * 0.7, mob.getZ(), ctx.scaleCount(6, 12), 0.3, 0.3, 0.3, 0.05);
			}
		}
	}
}

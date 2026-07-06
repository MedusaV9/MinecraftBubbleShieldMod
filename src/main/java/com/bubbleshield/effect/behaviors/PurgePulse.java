package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Periodically shoves hostile mobs away from the projector.
 *
 * <ul>
 * <li>v0: a soft knockback every 40 ticks</li>
 * <li>v1: a stronger shove with gust particles</li>
 * <li>v2: the strongest shove plus 1.0 magic damage</li>
 * </ul>
 */
public final class PurgePulse implements InsideEffectBehavior {
	public static final String ID = "purge_pulse";
	/** Cap on the horizontal speed a pulse can push a mob to. */
	private static final double MAX_HORIZONTAL_SPEED = 1.2;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime, ContextState ctx) {
		// Every 4th %10 window, so the purge reads as a distinct pulse.
		if (gameTime % ctx.effectiveThrottle(40L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		double push = switch (variant) {
			case 1 -> 0.8;
			case 2 -> 1.1;
			default -> 0.5;
		} * Mth.clamp(def.behaviorStrength(), 0.8F, 1.5F);

		// Query Mob + Enemy instead of Monster so Enemy-only hostiles are covered too.
		AABB box = AABB.ofSize(center, radius * 2.0, radius * 2.0, radius * 2.0);
		for (Mob mob : level.getEntitiesOfClass(Mob.class, box, e -> e instanceof Enemy)) {
			if (mob.position().distanceTo(center) > radius) {
				continue;
			}

			Vec3 away = mob.position().subtract(center);
			Vec3 horizontal = new Vec3(away.x, 0.0, away.z);
			horizontal = horizontal.lengthSqr() < 1.0E-6 ? new Vec3(1.0, 0.0, 0.0) : horizontal.normalize();

			Vec3 delta = mob.getDeltaMovement().add(horizontal.scale(push)).add(0.0, 0.15, 0.0);
			double horizontalSpeed = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
			if (horizontalSpeed > MAX_HORIZONTAL_SPEED) {
				double scale = MAX_HORIZONTAL_SPEED / horizontalSpeed;
				delta = new Vec3(delta.x * scale, delta.y, delta.z * scale);
			}

			mob.setDeltaMovement(delta);
			mob.hurtMarked = true;
			if (variant >= 1) {
				level.sendParticles(ParticleTypes.GUST, true, false, mob.getX(), mob.getY() + 0.5, mob.getZ(), 1, 0.2, 0.2, 0.2, 0.0);
			}

			if (variant == 2) {
				mob.hurtServer(level, level.damageSources().magic(), 1.0F);
			}
		}
	}
}

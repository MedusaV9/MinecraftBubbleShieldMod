package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldGeometry;
import com.bubbleshield.shield.ShieldShape;

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
 * <li>v3: quicker medium shoves (every 30 ticks) with small gusts</li>
 * <li>v4: an uppercut pulse that pops hostiles upward with cloud puffs</li>
 * <li>v5: a soft shove announced by a gust ring around the projector</li>
 * <li>v6: a slow heavy slam (every 60 ticks) dealing 2.0 magic damage</li>
 * </ul>
 */
public final class PurgePulse implements InsideEffectBehavior {
	public static final String ID = "purge_pulse";
	/** Cap on the horizontal speed a pulse can push a mob to. */
	private static final double MAX_HORIZONTAL_SPEED = 1.2;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		int variant = def.behaviorVariant();
		// Every 4th %10 window (v3: every 3rd, v6: every 6th), so the purge reads as a
		// distinct pulse instead of a constant force.
		long cadence = switch (variant) {
			case 3 -> 30L;
			case 6 -> 60L;
			default -> 40L;
		};
		if (gameTime % ctx.effectiveThrottle(cadence) != 0L) {
			return;
		}

		double push = switch (variant) {
			case 1 -> 0.8;
			case 2 -> 1.1;
			case 3 -> 0.65;
			case 4 -> 0.6;
			case 6 -> 1.2;
			default -> 0.5;
		} * Mth.clamp(def.behaviorStrength(), 0.8F, 1.5F);

		if (variant == 5) {
			// Announce the wave: a gust ring flares around the projector before the shove.
			int points = ctx.scaleCount(8, 16);
			double ringRadius = radius * 0.5;
			for (int i = 0; i < points; i++) {
				double angle = Math.PI * 2.0 * i / points;
				level.sendParticles(ParticleTypes.GUST, true, false,
						center.x + Math.cos(angle) * ringRadius, center.y + 0.8, center.z + Math.sin(angle) * ringRadius, 1, 0.1, 0.1, 0.1, 0.0);
			}
		}

		// Query Mob + Enemy instead of Monster so Enemy-only hostiles are covered too.
		AABB box = AABB.ofSize(center, radius * 2.0, radius * 2.0, radius * 2.0);
		for (Mob mob : level.getEntitiesOfClass(Mob.class, box, e -> e instanceof Enemy)) {
			if (!ShieldGeometry.isInside(shape, center, radius, mob.position())) {
				continue;
			}

			Vec3 away = mob.position().subtract(center);
			Vec3 horizontal = new Vec3(away.x, 0.0, away.z);
			horizontal = horizontal.lengthSqr() < 1.0E-6 ? new Vec3(1.0, 0.0, 0.0) : horizontal.normalize();

			Vec3 delta = mob.getDeltaMovement().add(horizontal.scale(push)).add(0.0, variant == 4 ? 0.45 : 0.15, 0.0);
			double horizontalSpeed = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
			if (horizontalSpeed > MAX_HORIZONTAL_SPEED) {
				double scale = MAX_HORIZONTAL_SPEED / horizontalSpeed;
				delta = new Vec3(delta.x * scale, delta.y, delta.z * scale);
			}

			mob.setDeltaMovement(delta);
			mob.hurtMarked = true;
			if (variant == 1 || variant == 2) {
				level.sendParticles(ParticleTypes.GUST, true, false, mob.getX(), mob.getY() + 0.5, mob.getZ(), 1, 0.2, 0.2, 0.2, 0.0);
			} else if (variant == 3) {
				level.sendParticles(ParticleTypes.SMALL_GUST, true, false, mob.getX(), mob.getY() + 0.5, mob.getZ(), 2, 0.2, 0.2, 0.2, 0.0);
			} else if (variant == 4) {
				level.sendParticles(ParticleTypes.CLOUD, true, false, mob.getX(), mob.getY() + 0.2, mob.getZ(), 4, 0.2, 0.1, 0.2, 0.02);
			} else if (variant == 6) {
				level.sendParticles(ParticleTypes.GUST, true, false, mob.getX(), mob.getY() + 0.5, mob.getZ(), 2, 0.3, 0.3, 0.3, 0.0);
			}

			if (variant == 2) {
				mob.hurtServer(level, level.damageSources().magic(), 1.0F);
			} else if (variant == 6) {
				mob.hurtServer(level, level.damageSources().magic(), 2.0F);
			}
		}
	}
}

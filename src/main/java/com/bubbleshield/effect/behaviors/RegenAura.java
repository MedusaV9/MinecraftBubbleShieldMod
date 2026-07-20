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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Grants brief Regeneration to every player standing inside the shield.
 *
 * <ul>
 * <li>v0: Regeneration I</li>
 * <li>v1: Regeneration I plus heart particles above each player</li>
 * <li>v2: Regeneration I plus Absorption I</li>
 * <li>v3: Regeneration I plus happy-villager sparkles</li>
 * <li>v4: a shorter but stronger Regeneration II</li>
 * <li>v5: Regeneration I plus a small heart ring circling each player</li>
 * <li>v6: Regeneration I plus Health Boost I</li>
 * </ul>
 */
public final class RegenAura implements InsideEffectBehavior {
	public static final String ID = "regen_aura";
	private static final int DURATION_TICKS = 60;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		AABB box = AABB.ofSize(center, radius * 2.0, radius * 2.0, radius * 2.0);
		for (Player player : level.getEntitiesOfClass(Player.class, box)) {
			if (!ShieldGeometry.isInside(shape, center, radius, player.position())) {
				continue;
			}

			if (variant == 4) {
				player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 40, 1));
			} else {
				player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, DURATION_TICKS, 0));
			}

			if (variant == 1) {
				level.sendParticles(ParticleTypes.HEART, true, false, player.getX(), player.getY() + 1.5, player.getZ(), ctx.scaleCount(2, 8), 0.3, 0.3, 0.3, 0.0);
			} else if (variant == 2) {
				player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, DURATION_TICKS, 0));
			} else if (variant == 3) {
				level.sendParticles(ParticleTypes.HAPPY_VILLAGER, true, false, player.getX(), player.getY() + 1.2, player.getZ(), ctx.scaleCount(3, 8), 0.4, 0.5, 0.4, 0.0);
			} else if (variant == 5) {
				double spin = gameTime / 10.0 * 0.6;
				for (int i = 0; i < 4; i++) {
					double angle = spin + Math.PI * 2.0 * i / 4;
					level.sendParticles(ParticleTypes.HEART, true, false,
							player.getX() + Math.cos(angle) * 0.9, player.getY() + 1.0, player.getZ() + Math.sin(angle) * 0.9, 1, 0.02, 0.02, 0.02, 0.0);
				}
			} else if (variant == 6) {
				player.addEffect(new MobEffectInstance(MobEffects.HEALTH_BOOST, DURATION_TICKS, 0));
			}
		}
	}
}

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
 * Grants aquatic comfort buffs to every player standing inside the shield.
 *
 * <ul>
 * <li>v0: Water Breathing</li>
 * <li>v1: Water Breathing plus Dolphin's Grace with dolphin trail particles</li>
 * <li>v2: Water Breathing plus Conduit Power</li>
 * <li>v3: Water Breathing plus a splash ring circling each player</li>
 * <li>v4: Water Breathing plus Dolphin's Grace and Speed I</li>
 * <li>v5: Water Breathing plus nautilus glints swirling to each player</li>
 * <li>v6: Water Breathing plus Regeneration while actually in water</li>
 * </ul>
 */
public final class TideAura implements InsideEffectBehavior {
	public static final String ID = "tide_aura";
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

			player.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, DURATION_TICKS, 0));
			if (variant == 1) {
				player.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, DURATION_TICKS, 0));
				BehaviorSupport.sendContained(level, ParticleTypes.DOLPHIN, shape, center, radius, player.getX(), player.getY() + 0.7, player.getZ(), ctx.scaleCount(4, 12), 0.4, 0.4, 0.4, 0.0);
			} else if (variant == 2) {
				player.addEffect(new MobEffectInstance(MobEffects.CONDUIT_POWER, DURATION_TICKS, 0));
			} else if (variant == 3) {
				double spin = gameTime / 10.0 * 0.5;
				for (int i = 0; i < 5; i++) {
					double angle = spin + Math.PI * 2.0 * i / 5;
					BehaviorSupport.sendContained(level, ParticleTypes.SPLASH, shape, center, radius,
							player.getX() + Math.cos(angle) * 1.0, player.getY() + 0.6, player.getZ() + Math.sin(angle) * 1.0, 1, 0.05, 0.15, 0.05, 0.02);
				}
			} else if (variant == 4) {
				player.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, DURATION_TICKS, 0));
				player.addEffect(new MobEffectInstance(MobEffects.SPEED, DURATION_TICKS, 0));
			} else if (variant == 5) {
				// Nautilus glints fly towards the player (count=0 fly-towards packet form:
				// the glint SPAWNS at target + offset). Both the target and the spawn ring
				// are contained -- a player hugging the wall would otherwise put the spawn
				// ~1.7 blocks outside it.
				double spawnAngle = gameTime / 10.0 * 0.7;
				Vec3 target = BehaviorSupport.containPoint(shape, center, radius,
						new Vec3(player.getX(), player.getY() + 1.0, player.getZ()));
				Vec3 spawn = BehaviorSupport.containPoint(shape, center, radius, new Vec3(
						target.x + Math.cos(spawnAngle) * 1.6, target.y + 0.5, target.z + Math.sin(spawnAngle) * 1.6));
				BehaviorSupport.sendContained(level, ParticleTypes.NAUTILUS, shape, center, radius, target.x, target.y, target.z, 0,
						spawn.x - target.x, spawn.y - target.y, spawn.z - target.z, 1.0);
			} else if (variant == 6 && player.isInWater()) {
				player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, DURATION_TICKS, 0));
			}
		}
	}
}

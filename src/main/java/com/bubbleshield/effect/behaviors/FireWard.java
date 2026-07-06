package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Snuffs out burning players inside the shield.
 *
 * <ul>
 * <li>v0: clears fire with a smoke puff</li>
 * <li>v1: also grants Fire Resistance I</li>
 * <li>v2: also plays an extinguish hiss and lava-pop deny FX when snuffing</li>
 * </ul>
 */
public final class FireWard implements InsideEffectBehavior {
	public static final String ID = "fire_ward";
	private static final int DURATION_TICKS = 60;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime) {
		if (gameTime % 10L != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		AABB box = AABB.ofSize(center, radius * 2.0, radius * 2.0, radius * 2.0);
		for (Player player : level.getEntitiesOfClass(Player.class, box)) {
			if (player.position().distanceTo(center) > radius) {
				continue;
			}

			if (variant >= 1) {
				player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, DURATION_TICKS, 0));
			}

			if (!player.isOnFire()) {
				continue;
			}

			player.clearFire();
			level.sendParticles(ParticleTypes.SMOKE, true, false, player.getX(), player.getY() + 1.0, player.getZ(), 12, 0.3, 0.5, 0.3, 0.02);
			if (variant == 2) {
				level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.FIRE_EXTINGUISH, SoundSource.AMBIENT, 0.8F, 1.0F);
				level.sendParticles(ParticleTypes.LAVA, true, false, player.getX(), player.getY() + 0.5, player.getZ(), 6, 0.3, 0.3, 0.3, 0.0);
			}
		}
	}
}

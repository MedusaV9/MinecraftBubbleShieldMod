package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldGeometry;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
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
 * <li>v3: quenches with a splash-droplet ring instead of smoke</li>
 * <li>v4: Fire Resistance with white steam puffs when snuffing</li>
 * <li>v5: Fire Resistance plus Resistance I</li>
 * <li>v6: snuffs every burning living entity inside, mobs included</li>
 * </ul>
 */
public final class FireWard implements InsideEffectBehavior {
	public static final String ID = "fire_ward";
	private static final int DURATION_TICKS = 60;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		AABB box = AABB.ofSize(center, radius * 2.0, radius * 2.0, radius * 2.0);
		if (variant == 6) {
			// Ward the whole menagerie: every burning living entity inside is snuffed.
			for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box)) {
				if (!ShieldGeometry.isInside(shape, center, radius, entity.position()) || !entity.isOnFire()) {
					continue;
				}

				entity.clearFire();
				level.sendParticles(ParticleTypes.SMOKE, true, false, entity.getX(), entity.getY() + entity.getBbHeight() * 0.6, entity.getZ(), ctx.scaleCount(10, 20), 0.3, 0.4, 0.3, 0.02);
			}
			return;
		}

		for (Player player : level.getEntitiesOfClass(Player.class, box)) {
			if (!ShieldGeometry.isInside(shape, center, radius, player.position())) {
				continue;
			}

			if (variant == 1 || variant == 2 || variant == 4 || variant == 5) {
				player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, DURATION_TICKS, 0));
			}

			if (variant == 5) {
				player.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, DURATION_TICKS, 0));
			}

			if (!player.isOnFire()) {
				continue;
			}

			player.clearFire();
			if (variant == 3) {
				// A quenching splash ring instead of the smoke puff.
				for (int i = 0; i < 8; i++) {
					double angle = Math.PI * 2.0 * i / 8;
					level.sendParticles(ParticleTypes.SPLASH, true, false,
							player.getX() + Math.cos(angle) * 0.8, player.getY() + 0.8, player.getZ() + Math.sin(angle) * 0.8, 1, 0.05, 0.2, 0.05, 0.05);
				}
				level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.FIRE_EXTINGUISH, SoundSource.AMBIENT, 0.7F, 1.3F);
				continue;
			}

			if (variant == 4) {
				level.sendParticles(ParticleTypes.WHITE_SMOKE, true, false, player.getX(), player.getY() + 1.0, player.getZ(), ctx.scaleCount(12, 24), 0.3, 0.5, 0.3, 0.02);
				continue;
			}

			level.sendParticles(ParticleTypes.SMOKE, true, false, player.getX(), player.getY() + 1.0, player.getZ(), ctx.scaleCount(12, 24), 0.3, 0.5, 0.3, 0.02);
			if (variant == 2) {
				level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.FIRE_EXTINGUISH, SoundSource.AMBIENT, 0.8F, 1.0F);
				level.sendParticles(ParticleTypes.LAVA, true, false, player.getX(), player.getY() + 0.5, player.getZ(), 6, 0.3, 0.3, 0.3, 0.0);
			}
		}
	}
}

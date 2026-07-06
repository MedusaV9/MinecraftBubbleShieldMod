package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Electric sparks crawling just under the shield surface.
 *
 * <ul>
 * <li>v0: sparse sparks</li>
 * <li>v1: denser sparks with occasional gusts</li>
 * <li>v2: dense sparks with crackle bursts synced to a clicking sound</li>
 * </ul>
 */
public final class StaticField implements InsideEffectBehavior {
	public static final String ID = "static_field";

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime) {
		if (gameTime % 10L != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		RandomSource random = level.getRandom();
		int sparks = Mth.clamp((int) (radius * (variant == 0 ? 0.8F : 1.6F) * def.behaviorStrength()), 6, 96);
		for (int i = 0; i < sparks; i++) {
			// Random points on the upper hemisphere shell, just inside the surface.
			double theta = random.nextDouble() * Math.PI * 2.0;
			double up = random.nextDouble();
			double horizontal = Math.sqrt(Math.max(0.0, 1.0 - up * up));
			double shell = radius * (0.88 + random.nextDouble() * 0.08);
			double x = center.x + horizontal * Math.cos(theta) * shell;
			double y = center.y + up * shell;
			double z = center.z + horizontal * Math.sin(theta) * shell;
			level.sendParticles(ParticleTypes.ELECTRIC_SPARK, true, false, x, y, z, 1, 0.05, 0.05, 0.05, 0.0);
		}

		if (variant >= 1 && gameTime % 40L == 0L) {
			level.sendParticles(ParticleTypes.GUST, true, false, center.x, center.y + radius * 0.4, center.z, 2, radius * 0.3, radius * 0.2, radius * 0.3, 0.0);
		}

		if (variant == 2 && gameTime % 40L == 0L) {
			// Crackle burst at one random surface point, synced to a sharp click.
			double angle = random.nextDouble() * Math.PI * 2.0;
			double x = center.x + Math.cos(angle) * radius * 0.9;
			double z = center.z + Math.sin(angle) * radius * 0.9;
			double y = center.y + 1.0 + random.nextDouble() * radius * 0.4;
			level.sendParticles(ParticleTypes.ELECTRIC_SPARK, true, false, x, y, z, 24, 0.4, 0.4, 0.4, 0.1);
			level.playSound(null, x, y, z, SoundEvents.SCULK_CLICKING, SoundSource.AMBIENT, Mth.clamp(radius / 12.0F, 0.6F, 4.0F), 1.8F);
		}
	}
}

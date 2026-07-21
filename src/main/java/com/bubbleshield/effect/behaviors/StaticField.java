package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

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
 * <li>v3: sparks flecked with crit stars</li>
 * <li>v4: a rotating meridian arc of concentrated sparks</li>
 * <li>v5: sparks with wax-off flashes and frequent gusts</li>
 * <li>v6: scattered mini crackle pockets across the shell</li>
 * </ul>
 */
public final class StaticField implements InsideEffectBehavior {
	public static final String ID = "static_field";

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		RandomSource random = level.getRandom();
		if (variant == 4) {
			// One concentrated arc from the equator over the pole, rotating steadily.
			double azimuth = gameTime / 10.0 * 0.5;
			double shell = radius * 0.92;
			int arcPoints = ctx.scaleCount(Mth.clamp((int) Math.round(Math.PI * shell * def.behaviorStrength()), 12, 96), 96);
			for (int i = 0; i <= arcPoints; i++) {
				double latitude = Math.PI * i / arcPoints;
				double y = center.y + Math.sin(latitude) * shell;
				double horizontal = Math.cos(latitude) * shell;
				BehaviorSupport.sendContained(level, ParticleTypes.ELECTRIC_SPARK, shape, center, radius, center.x + Math.cos(azimuth) * horizontal, y, center.z + Math.sin(azimuth) * horizontal, 1, 0.05, 0.05, 0.05, 0.0);
			}
			return;
		}

		if (variant == 6) {
			// Four random spark pockets instead of an even shell dusting.
			int pockets = ctx.scaleCount(4, 8);
			for (int p = 0; p < pockets; p++) {
				double theta = random.nextDouble() * Math.PI * 2.0;
				double up = random.nextDouble();
				double horizontal = Math.sqrt(Math.max(0.0, 1.0 - up * up));
				double shell = radius * 0.9;
				BehaviorSupport.sendContained(level, ParticleTypes.ELECTRIC_SPARK, shape, center, radius, center.x + horizontal * Math.cos(theta) * shell, center.y + up * shell, center.z + horizontal * Math.sin(theta) * shell, 12, 0.3, 0.3, 0.3, 0.08);
			}

			if (gameTime % 40L == 0L) {
				level.playSound(null, center.x, center.y + radius * 0.5, center.z, SoundEvents.SCULK_CLICKING, SoundSource.AMBIENT, Mth.clamp(radius / 12.0F, 0.6F, 4.0F), 1.4F);
			}
			return;
		}

		float density = switch (variant) {
			case 0 -> 0.8F;
			case 3 -> 1.2F;
			case 5 -> 1.4F;
			default -> 1.6F;
		};
		int sparks = ctx.scaleCount(Mth.clamp((int) (radius * density * def.behaviorStrength()), 6, 96), 96);
		for (int i = 0; i < sparks; i++) {
			// Random points on the upper hemisphere shell, just inside the surface.
			double theta = random.nextDouble() * Math.PI * 2.0;
			double up = random.nextDouble();
			double horizontal = Math.sqrt(Math.max(0.0, 1.0 - up * up));
			double shell = radius * (0.88 + random.nextDouble() * 0.08);
			double x = center.x + horizontal * Math.cos(theta) * shell;
			double y = center.y + up * shell;
			double z = center.z + horizontal * Math.sin(theta) * shell;
			BehaviorSupport.sendContained(level, ParticleTypes.ELECTRIC_SPARK, shape, center, radius, x, y, z, 1, 0.05, 0.05, 0.05, 0.0);
			if (variant == 3 && i % 3 == 0) {
				// The fleck hangs 0.3 below its spark: near the equator that dips under a
				// dome's base plane, so contain it shape-aware.
				BehaviorSupport.sendContained(level, ParticleTypes.CRIT, shape, center, radius, x, y - 0.3, z, 1, 0.1, 0.1, 0.1, 0.02);
			} else if (variant == 5 && i % 4 == 0) {
				BehaviorSupport.sendContained(level, ParticleTypes.WAX_OFF, shape, center, radius, x, y - 0.2, z, 1, 0.1, 0.1, 0.1, 0.0);
			}
		}

		if ((variant == 1 || variant == 2) && gameTime % 40L == 0L) {
			BehaviorSupport.sendContained(level, ParticleTypes.GUST, shape, center, radius, center.x, center.y + radius * 0.4, center.z, 2, radius * 0.3, radius * 0.2, radius * 0.3, 0.0);
		}

		if (variant == 5 && gameTime % 20L == 0L) {
			BehaviorSupport.sendContained(level, ParticleTypes.GUST, shape, center, radius, center.x, center.y + radius * 0.4, center.z, 1, radius * 0.3, radius * 0.2, radius * 0.3, 0.0);
		}

		if (variant == 2 && gameTime % 40L == 0L) {
			// Crackle burst at one random surface point, synced to a sharp click. The
			// raw 0.9r ring point plus the random 1.0..(1.0 + 0.4r) height can land past
			// the shell (~1.06r worst case), so contain the burst anchor onto 0.98r.
			double angle = random.nextDouble() * Math.PI * 2.0;
			Vec3 burst = BehaviorSupport.containPoint(shape, center, radius, new Vec3(
					center.x + Math.cos(angle) * radius * 0.9,
					center.y + 1.0 + random.nextDouble() * radius * 0.4,
					center.z + Math.sin(angle) * radius * 0.9));
			BehaviorSupport.sendContained(level, ParticleTypes.ELECTRIC_SPARK, shape, center, radius, burst.x, burst.y, burst.z, 24, 0.4, 0.4, 0.4, 0.1);
			level.playSound(null, burst.x, burst.y, burst.z, SoundEvents.SCULK_CLICKING, SoundSource.AMBIENT, Mth.clamp(radius / 12.0F, 0.6F, 4.0F), 1.8F);
		}
	}
}

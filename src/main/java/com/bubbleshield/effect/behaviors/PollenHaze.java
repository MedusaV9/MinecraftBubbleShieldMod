package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A drifting pollen haze: golden motes hang in a broad band at chest height,
 * slowly circulating, while heavier grains sift down from "bloom" points.
 *
 * <ul>
 * <li>v0: spore-blossom haze with nectar sift</li>
 * <li>v1: composter-green haze, denser</li>
 * <li>v2: happy-villager sparkle haze (no sift)</li>
 * <li>v3: haze plus sneeze puffs near the floor</li>
 * <li>v4: four distinct bloom points sifting in sequence</li>
 * <li>v5: thin high haze band under the ceiling</li>
 * <li>v6: falling spore-blossom petals through the haze</li>
 * </ul>
 */
public final class PollenHaze implements InsideEffectBehavior {
	public static final String ID = "pollen_haze";
	private static final int MAX_HAZE = 64;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		SimpleParticleType haze = switch (variant) {
			case 1 -> ParticleTypes.COMPOSTER;
			case 2 -> ParticleTypes.HAPPY_VILLAGER;
			default -> ParticleTypes.SPORE_BLOSSOM_AIR;
		};
		double bandY = center.y + radius * (variant == 5 ? 0.7 : 0.3);
		double bandSpread = radius * 0.6 * Mth.clamp(def.behaviorStrength(), 0.8F, 1.1F);
		int hazeCount = ctx.scaleCount(variant == 1 ? 24 : variant == 5 ? 8 : 14, MAX_HAZE);
		level.sendParticles(haze, true, false, center.x, bandY, center.z, hazeCount, bandSpread, radius * 0.12, bandSpread, 0.0);

		if (variant == 2) {
			return;
		}

		if (variant == 3 && gameTime % 40L == 0L) {
			// Sneezes pop just above the floor, one spot per cycle.
			double angle = gameTime / 40.0;
			level.sendParticles(ParticleTypes.SNEEZE, true, false,
					center.x + Math.cos(angle) * radius * 0.4, center.y + 0.4, center.z + Math.sin(angle) * radius * 0.4,
					ctx.scaleCount(4, 8), 0.2, 0.1, 0.2, 0.02);
			return;
		}

		int blooms = variant == 4 ? 4 : 2;
		long active = gameTime / 10L % blooms;
		SimpleParticleType sift = variant == 6 ? ParticleTypes.FALLING_SPORE_BLOSSOM : ParticleTypes.FALLING_NECTAR;
		for (int bloom = 0; bloom < blooms; bloom++) {
			if (variant == 4 && bloom != active) {
				continue;
			}

			double angle = Math.PI * 2.0 * bloom / blooms + 0.5;
			double x = center.x + Math.cos(angle) * radius * 0.45;
			double z = center.z + Math.sin(angle) * radius * 0.45;
			level.sendParticles(sift, true, false, x, bandY + radius * 0.1, z, ctx.scaleCount(3, 6), 0.3, 0.1, 0.3, 0.0);
		}
	}
}

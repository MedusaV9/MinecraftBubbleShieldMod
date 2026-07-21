package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Periodic fiery bursts erupting near the top of the bubble.
 *
 * <ul>
 * <li>v0: one flame/smoke burst every 40 ticks</li>
 * <li>v1: faster cadence (every 20 ticks) and larger bursts</li>
 * <li>v2: twin opposing bursts with a poof finale</li>
 * <li>v3: one massive slow burst with an explosion flash</li>
 * <li>v4: three small bursts spread around the dome</li>
 * <li>v5: crackling firework-spark bursts</li>
 * <li>v6: a gust shockwave burst of end rod motes</li>
 * </ul>
 */
public final class MeteorBurst implements InsideEffectBehavior {
	public static final String ID = "meteor_burst";

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		int variant = def.behaviorVariant();
		// Coarser multiples of the standard %10 throttle: bursts feel like events, not rain.
		long cadence = switch (variant) {
			case 0 -> 40L;
			case 3 -> 60L;
			case 5 -> 40L;
			case 6 -> 30L;
			default -> 20L;
		};
		if (gameTime % ctx.effectiveThrottle(cadence) != 0L) {
			return;
		}

		RandomSource random = level.getRandom();
		if (variant == 3) {
			// One heavy strike: an explosion flash, a flame shower and lingering smoke.
			double azimuth = random.nextDouble() * Math.PI * 2.0;
			double x = center.x + Math.cos(azimuth) * radius * 0.3;
			double y = center.y + radius * 0.7;
			double z = center.z + Math.sin(azimuth) * radius * 0.3;
			int flames = ctx.scaleCount(Mth.clamp((int) (radius * 6.0F * def.behaviorStrength()), 24, 96), 96);
			BehaviorSupport.sendContained(level, ParticleTypes.EXPLOSION, shape, center, radius, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
			BehaviorSupport.sendContained(level, ParticleTypes.FLAME, shape, center, radius, x, y, z, flames, 0.7, 0.7, 0.7, 0.2);
			BehaviorSupport.sendContained(level, ParticleTypes.LARGE_SMOKE, shape, center, radius, x, y - 0.5, z, Math.min(24, flames / 4), 0.8, 0.8, 0.8, 0.03);
			return;
		}

		if (variant == 4) {
			// Three small bursts evenly spread around the upper bowl.
			double baseAzimuth = random.nextDouble() * Math.PI * 2.0;
			int flames = ctx.scaleCount(Mth.clamp((int) (radius * 2.0F * def.behaviorStrength()), 8, 32), 32);
			for (int b = 0; b < 3; b++) {
				double azimuth = baseAzimuth + b * (Math.PI * 2.0 / 3.0);
				double x = center.x + Math.cos(azimuth) * radius * 0.45;
				double y = center.y + radius * (0.55 + 0.15 * b / 2.0);
				double z = center.z + Math.sin(azimuth) * radius * 0.45;
				BehaviorSupport.sendContained(level, ParticleTypes.FLAME, shape, center, radius, x, y, z, flames, 0.3, 0.3, 0.3, 0.1);
				BehaviorSupport.sendContained(level, ParticleTypes.SMOKE, shape, center, radius, x, y, z, flames / 4, 0.4, 0.4, 0.4, 0.03);
			}
			return;
		}

		if (variant == 5) {
			double azimuth = random.nextDouble() * Math.PI * 2.0;
			double x = center.x + Math.cos(azimuth) * radius * 0.35;
			double y = center.y + radius * 0.7;
			double z = center.z + Math.sin(azimuth) * radius * 0.35;
			int sparks = ctx.scaleCount(Mth.clamp((int) (radius * 5.0F * def.behaviorStrength()), 20, 112), 112);
			BehaviorSupport.sendContained(level, ParticleTypes.FIREWORK, shape, center, radius, x, y, z, sparks, 0.3, 0.3, 0.3, 0.18);
			return;
		}

		if (variant == 6) {
			// A shockwave: gusts at the impact point and a mote spray pushed outward.
			double azimuth = random.nextDouble() * Math.PI * 2.0;
			double x = center.x + Math.cos(azimuth) * radius * 0.35;
			double y = center.y + radius * 0.6;
			double z = center.z + Math.sin(azimuth) * radius * 0.35;
			int motes = ctx.scaleCount(Mth.clamp((int) (radius * 4.0F * def.behaviorStrength()), 16, 96), 96);
			BehaviorSupport.sendContained(level, ParticleTypes.GUST, shape, center, radius, x, y, z, 2, 0.2, 0.2, 0.2, 0.0);
			BehaviorSupport.sendContained(level, ParticleTypes.END_ROD, shape, center, radius, x, y, z, motes, 0.2, 0.2, 0.2, 0.25);
			return;
		}

		int bursts = variant == 2 ? 2 : 1;
		// Worst case (v2): 2 * (32 + 16) + 12 = 108 particles/pulse.
		int flames = ctx.scaleCount(
				Mth.clamp((int) (radius * (variant == 0 ? 3.0F : 4.5F) * def.behaviorStrength() / bursts), 12, variant == 2 ? 32 : 64),
				variant == 2 ? 32 : 64);
		double baseAzimuth = random.nextDouble() * Math.PI * 2.0;
		for (int b = 0; b < bursts; b++) {
			double azimuth = baseAzimuth + b * Math.PI;
			double x = center.x + Math.cos(azimuth) * radius * 0.35;
			double y = center.y + radius * 0.75;
			double z = center.z + Math.sin(azimuth) * radius * 0.35;
			BehaviorSupport.sendContained(level, ParticleTypes.FLAME, shape, center, radius, x, y, z, flames, 0.4, 0.4, 0.4, 0.15);
			BehaviorSupport.sendContained(level, ParticleTypes.SMOKE, shape, center, radius, x, y, z, flames / 2, 0.6, 0.6, 0.6, 0.05);
			if (variant == 2 && b == 1) {
				BehaviorSupport.sendContained(level, ParticleTypes.POOF, shape, center, radius, x, y, z, 12, 0.3, 0.3, 0.3, 0.1);
			}
		}
	}
}

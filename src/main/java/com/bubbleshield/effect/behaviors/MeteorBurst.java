package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;

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
 * </ul>
 */
public final class MeteorBurst implements InsideEffectBehavior {
	public static final String ID = "meteor_burst";

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime, ContextState ctx) {
		int variant = def.behaviorVariant();
		// Coarser multiples of the standard %10 throttle: bursts feel like events, not rain.
		long cadence = variant == 0 ? 40L : 20L;
		if (gameTime % ctx.effectiveThrottle(cadence) != 0L) {
			return;
		}

		RandomSource random = level.getRandom();
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
			level.sendParticles(ParticleTypes.FLAME, true, false, x, y, z, flames, 0.4, 0.4, 0.4, 0.15);
			level.sendParticles(ParticleTypes.SMOKE, true, false, x, y, z, flames / 2, 0.6, 0.6, 0.6, 0.05);
			if (variant == 2 && b == 1) {
				level.sendParticles(ParticleTypes.POOF, true, false, x, y, z, 12, 0.3, 0.3, 0.3, 0.1);
			}
		}
	}
}

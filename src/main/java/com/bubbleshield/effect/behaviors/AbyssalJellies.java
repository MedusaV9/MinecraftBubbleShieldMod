package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Giant abyssal jellyfish pulsing slowly up through the bubble: each jelly is
 * a breathing bell (a glow rim ring over a squid-ink shadow) that bobs upward
 * on every contraction, trailing palette-dust tentacles (primary and secondary
 * strands alternate). Purely hash-seeded particles -- no entities, no state,
 * no cleanup.
 *
 * <ul>
 * <li>v0: three placid moon jellies</li>
 * <li>v1: a bloom of five small jellies</li>
 * <li>v2: one colossal deep jelly with eight long tentacles</li>
 * <li>v3: ink-dark jellies (squid-ink bells, glow only at every third rim point)</li>
 * <li>v4: bioluminescent pulse (a glow-ink flash on each bell contraction)</li>
 * <li>v5: counter-drifting layers (odd jellies sink while even jellies rise)</li>
 * <li>v6: long-liners (six tentacles of four beads each)</li>
 * </ul>
 */
public final class AbyssalJellies implements InsideEffectBehavior {
	public static final String ID = "abyssal_jellies";
	/** Worst case v3: 5 jellies x (rim 10 + crown 1 + ink 2 + tentacles 4x3) = 125 particles/pulse. */
	private static final int MAX_JELLIES = 5;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int jellies = ctx.scaleCount(switch (variant) {
			case 1 -> 5;
			case 2 -> 1;
			case 5 -> 4;
			default -> 3;
		}, switch (variant) {
			case 2 -> 1;
			case 4 -> 4;
			case 6 -> 3;
			default -> MAX_JELLIES;
		});
		long pulseIndex = gameTime / 10L;
		ParticleOptions tentaclePrimary = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 0.9F);
		ParticleOptions tentacleSecondary = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.9F);
		for (int j = 0; j < jellies; j++) {
			long seed = BehaviorSupport.mix(j * 131L + 17L);
			// Each jelly ascends over a 640-tick cycle, phase-shifted by its hash;
			// at the ceiling it dissolves upward and re-forms near the floor.
			double rise = ((pulseIndex + (long) (BehaviorSupport.hash01(seed) * 64.0)) % 64L) / 64.0;
			if (variant == 5 && j % 2 == 1) {
				rise = 1.0 - rise;
			}

			double drift = pulseIndex * 0.015 * (variant == 5 && j % 2 == 1 ? -1.0 : 1.0);
			double angle = BehaviorSupport.hash01(seed + 1L) * Math.PI * 2.0 + drift;
			double dist = Math.sqrt(BehaviorSupport.hash01(seed + 2L)) * radius * (variant == 2 ? 0.25 : 0.45);
			// The bell breathes over 8 pulses and bobs upward on each contraction.
			double pulse = ((pulseIndex + j * 2L) % 8L) / 8.0;
			double breathe = 0.7 + 0.3 * Math.cos(pulse * Math.PI * 2.0);
			double x = center.x + Math.cos(angle) * dist;
			double bellY = center.y + radius * (0.15 + 0.5 * rise) + radius * 0.06 * Math.sin(pulse * Math.PI * 2.0);
			double z = center.z + Math.sin(angle) * dist;
			double bell = radius * (variant == 2 ? 0.24 : 0.12) * breathe * Mth.clamp(def.behaviorStrength(), 0.8F, 1.4F) + 0.3;
			// The bell rim ring; near the ceiling the containment sweep gently
			// squashes the widest bells back inside the shell.
			int rim = ctx.scaleCount(variant == 2 ? 14 : 8, variant == 2 ? 18 : 10);
			for (int i = 0; i < rim; i++) {
				double a = Math.PI * 2.0 * i / rim + pulseIndex * 0.05;
				ParticleOptions rimMote = variant == 3 && i % 3 != 0 ? ParticleTypes.SQUID_INK : ParticleTypes.GLOW;
				BehaviorSupport.sendContained(level, rimMote, shape, center, radius,
						x + Math.cos(a) * bell, bellY, z + Math.sin(a) * bell, 1, 0.03, 0.03, 0.03, 0.0);
			}

			// The crown and the ink shadow hanging under the bell.
			BehaviorSupport.sendContained(level, variant == 3 ? ParticleTypes.SQUID_INK : ParticleTypes.GLOW, shape, center, radius,
					x, bellY + bell * 0.5, z, 1, 0.05, 0.05, 0.05, 0.0);
			BehaviorSupport.sendContained(level, ParticleTypes.SQUID_INK, shape, center, radius,
					x, bellY - bell * 0.3, z, variant == 2 || variant == 3 ? 2 : 1, bell * 0.25, 0.1, bell * 0.25, 0.0);
			if (variant == 4 && pulse == 0.0) {
				// The contraction flash (pulse steps in eighths, so 0.0 is hit exactly).
				BehaviorSupport.sendContained(level, ParticleTypes.GLOW_SQUID_INK, shape, center, radius,
						x, bellY, z, 2, bell * 0.3, 0.1, bell * 0.3, 0.01);
			}

			int tentacles = variant == 2 ? 8 : variant == 6 ? 6 : 4;
			int beads = variant == 2 || variant == 6 ? 4 : 3;
			for (int t = 0; t < tentacles; t++) {
				double ta = Math.PI * 2.0 * t / tentacles + BehaviorSupport.hash01(seed + 3L) * Math.PI;
				double sway = Math.sin(pulseIndex * 0.35 + t * 1.3 + j) * 0.15;
				for (int b = 1; b <= beads; b++) {
					// Tentacles reach for the center plane but never cross it.
					double drop = Math.min(b * bell * 0.5, (bellY - center.y) * 0.85 * b / beads);
					BehaviorSupport.sendContained(level, t % 2 == 0 ? tentaclePrimary : tentacleSecondary, shape, center, radius,
							x + Math.cos(ta) * (bell * 0.7 + sway * b * 0.3),
							bellY - drop,
							z + Math.sin(ta) * (bell * 0.7 + sway * b * 0.3), 1, 0.02, 0.02, 0.02, 0.0);
				}
			}
		}
	}
}

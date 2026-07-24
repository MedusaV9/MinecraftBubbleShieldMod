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
 * A procession of ghosts circling the bubble perimeter near the floor: each
 * mourner is a sculk-soul column wrapped in a palette dust shroud, walking a
 * 0.75r circle. Deterministic angles (no state) keep the procession in step
 * across pulses.
 *
 * <ul>
 * <li>v0: single file, clockwise</li>
 * <li>v1: a counter-rotating double ring (outer 0.75r, inner 0.55r)</li>
 * <li>v2: mourners that halt at stations and flare a soul burst</li>
 * <li>v3: lantern-bearers (a held soul-fire flame above each mourner)</li>
 * <li>v4: a sculk pop as each mourner crosses a quarter-lap line</li>
 * <li>v5: a height-wave procession (sinusoidal bobbing)</li>
 * <li>v6: a dense, slow funeral wave (1.5x mourners)</li>
 * </ul>
 */
public final class SoulProcession implements InsideEffectBehavior {
	public static final String ID = "soul_procession";
	/** Worst case v6: 15 mourners x (souls 2 + shroud 2 + extras 1) = 75 particles/pulse. */
	private static final int MAX_MOURNERS = 15;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int base = Mth.clamp((int) (radius * 0.9F * def.behaviorStrength()), 4, 10);
		int mourners = ctx.scaleCount(variant == 6 ? base * 3 / 2 : base, variant == 6 ? MAX_MOURNERS : 10);
		double omega = variant == 6 ? 0.02 : 0.05; // radians per tick around the ring
		ParticleOptions shroud = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.0F);
		for (int m = 0; m < mourners; m++) {
			double slot = Math.PI * 2.0 * m / mourners;
			// v1 splits the procession across two counter-rotating rings.
			boolean innerRing = variant == 1 && m % 2 == 1;
			double dir = innerRing ? -1.0 : 1.0;
			double ringRadius = radius * (innerRing ? 0.55 : 0.75);
			double angle;
			if (variant == 2) {
				// March 40 ticks, halt 40 ticks: angle advances only while marching.
				long cycle = gameTime / 80L;
				boolean marching = gameTime % 80L < 40L;
				double progress = cycle + (marching ? (gameTime % 80L) / 40.0 : 1.0);
				angle = slot + progress * 0.5;
				if (!marching) {
					// The halted flare at each station.
					BehaviorSupport.sendContained(level, ParticleTypes.SOUL, shape, center, radius,
							center.x + Math.cos(angle) * ringRadius, center.y + 1.2, center.z + Math.sin(angle) * ringRadius,
							2, 0.15, 0.3, 0.15, 0.02);
				}
			} else {
				angle = slot + gameTime * omega * dir;
			}

			double x = center.x + Math.cos(angle) * ringRadius;
			double z = center.z + Math.sin(angle) * ringRadius;
			double y = center.y + 0.3;
			if (variant == 5) {
				y += radius * 0.25 * (0.5 + 0.5 * Math.sin(angle * 3.0));
			}

			BehaviorSupport.sendContained(level, ParticleTypes.SCULK_SOUL, shape, center, radius,
					x, y, z, 2, 0.1, 0.4, 0.1, 0.02);
			BehaviorSupport.sendContained(level, shroud, shape, center, radius,
					x, y + 0.4, z, 2, 0.2, 0.35, 0.2, 0.0);
			if (variant == 3) {
				BehaviorSupport.sendContained(level, ParticleTypes.SOUL_FIRE_FLAME, shape, center, radius,
						x, y + 1.1, z, 1, 0.03, 0.03, 0.03, 0.0);
			} else if (variant == 4 && crossedQuarter(angle, omega * 10.0)) {
				BehaviorSupport.sendContained(level, ParticleTypes.SCULK_CHARGE_POP, shape, center, radius,
						x, y + 0.6, z, 1, 0.1, 0.1, 0.1, 0.0);
			}
		}
	}

	/** Whether the mourner crossed one of the four quarter-lap lines since the previous pulse. */
	private static boolean crossedQuarter(double angle, double pulseStep) {
		double quarter = Math.PI / 2.0;
		return Math.floor(angle / quarter) != Math.floor((angle - pulseStep) / quarter);
	}
}

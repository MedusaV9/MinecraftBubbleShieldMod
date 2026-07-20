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
 * Phantom silhouettes sweeping under the bubble ceiling: each phantom is a
 * V of dust "wings" swept back from a body mote, circling on an ellipse with
 * periodic dives toward the floor and an ash wake behind it. Pure particles --
 * the silhouette is redrawn every pulse from deterministic math.
 *
 * <ul>
 * <li>v0: a lone phantom circling high</li>
 * <li>v1: a trio at staggered phases</li>
 * <li>v2: frequent divers (short dive cycle)</li>
 * <li>v3: spectral white phantoms (white-ash wake and wings)</li>
 * <li>v4: wide-wing gliders (bigger V, no dives)</li>
 * <li>v5: a storm flock of five, fast</li>
 * <li>v6: midnight veil (squid-ink puffs at each dive's lowest point)</li>
 * </ul>
 */
public final class PhantomFlock implements InsideEffectBehavior {
	public static final String ID = "phantom_flock";
	/** Worst case v5: 5 phantoms x (body 1 + wings 6 + wake 2) = 45 particles/pulse (x128 cap never reached). */
	private static final int MAX_PHANTOMS = 5;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int flock = switch (variant) {
			case 0 -> 1;
			case 1 -> 3;
			case 5 -> 5;
			default -> 2;
		};
		flock = ctx.scaleCount(flock, MAX_PHANTOMS);
		double omega = variant == 5 ? 0.05 : 0.02;
		long diveCycle = variant == 2 ? 8L : 12L;
		double wingSpan = Math.min(variant == 4 ? 1.6 : 1.0, radius * 0.22) * Mth.clamp(def.behaviorStrength(), 0.8F, 1.3F);
		ParticleOptions wingDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 1.3F);
		ParticleOptions bodyDust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.1F);
		ParticleOptions wake = variant == 3 ? ParticleTypes.WHITE_ASH : ParticleTypes.ASH;
		for (int p = 0; p < flock; p++) {
			double angle = gameTime * omega + Math.PI * 2.0 * p / flock;
			double heightFrac = 0.55;
			long cycle = (gameTime / 10L + p * 3L) % diveCycle;
			boolean divingNow = variant != 4 && cycle < 3L;
			if (divingNow) {
				// A dive dips from the ceiling toward the floor and back over 3 pulses.
				double dive = Math.sin(Math.PI * (cycle + (gameTime % 10L) / 10.0) / 3.0);
				heightFrac = 0.55 - 0.45 * dive;
				if (variant == 6 && cycle == 1L) {
					BehaviorSupport.sendContained(level, ParticleTypes.SQUID_INK, shape, center, radius,
							center.x + Math.cos(angle) * radius * 0.55, center.y + radius * 0.12, center.z + Math.sin(angle) * radius * 0.55,
							2, 0.2, 0.1, 0.2, 0.01);
				}
			}

			double x = center.x + Math.cos(angle) * radius * 0.55;
			double y = center.y + radius * heightFrac;
			double z = center.z + Math.sin(angle) * radius * 0.55;
			BehaviorSupport.sendContained(level, bodyDust, shape, center, radius, x, y, z, 1, 0.05, 0.05, 0.05, 0.0);

			// Wings: two swept-back arms of a V, perpendicular-ish to the flight heading.
			double heading = angle + Math.PI / 2.0; // tangent of the circle
			for (int side = -1; side <= 1; side += 2) {
				double armAngle = heading + side * 2.5;
				for (int s = 1; s <= 3; s++) {
					double reach = wingSpan * s / 3.0;
					BehaviorSupport.sendContained(level, variant == 3 ? wake : wingDust, shape, center, radius,
							x + Math.cos(armAngle) * reach, y + 0.08 * s, z + Math.sin(armAngle) * reach,
							1, 0.03, 0.03, 0.03, 0.0);
				}
			}

			// The wake trails behind the flight direction.
			BehaviorSupport.sendContained(level, wake, shape, center, radius,
					x - Math.cos(heading) * 0.6, y + 0.1, z - Math.sin(heading) * 0.6, 2, 0.15, 0.1, 0.15, 0.0);
		}
	}
}

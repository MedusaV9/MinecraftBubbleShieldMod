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
 * A floor ring of short spectral hands clawing out of the ground: each hand
 * is a stubby palette dust column topped with secondary-dust fingertips and
 * wrapped in grave ash, rising about 0.6 blocks and sinking back on a shared
 * cycle, with a sculk pop at full extension. Rise phases are hash-seeded per
 * slot, so the ring writhes without any per-shield state.
 *
 * <p>Worst-case budget (v6 at full extension, countMult 3): 14 hands x
 * (palm dust 2 + fingertip dust 1 + ash 2 + pop 1) = 84 particles/pulse
 * (&lt;= 128).
 *
 * <ul>
 * <li>v0: the classic ring (staggered phases, one ring at 0.6r)</li>
 * <li>v1: a synchronized grasp (every hand rises and pops together)</li>
 * <li>v2: the double burial ring (inner 0.4r and outer 0.7r, out of step)</li>
 * <li>v3: grasping fingers (taller reach, soul wisp escaping each pop)</li>
 * <li>v4: the cold barrow (white-ash hands, no pop, a smoke sigh instead)</li>
 * <li>v5: a wandering grave (the ring slowly rotates while clawing)</li>
 * <li>v6: mass unearthing (1.5x hands, denser ash)</li>
 * </ul>
 */
public final class GraveHands implements InsideEffectBehavior {
	public static final String ID = "grave_hands";
	/** Worst case v6 at full extension: 14 hands x (palm 2 + fingertip 1 + ash 2 + pop 1) = 84 particles/pulse. */
	private static final int MAX_HANDS = 14;
	/** One claw cycle: rise to ~0.6 blocks and sink back over this many ticks. */
	private static final long CYCLE_TICKS = 60L;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int base = Mth.clamp((int) (radius * 1.0F * def.behaviorStrength()), 5, 9);
		int hands = ctx.scaleCount(variant == 6 ? base * 3 / 2 : base, variant == 6 ? MAX_HANDS : 9);
		double reach = variant == 3 ? 0.9 : 0.6;
		double spin = variant == 5 ? gameTime * 0.004 : 0.0;
		double y0 = center.y + 0.05;
		ParticleOptions palm = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 0.8F);
		ParticleOptions fingertip = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.5F);
		for (int h = 0; h < hands; h++) {
			// v2 alternates slots between the inner and outer burial rings.
			boolean inner = variant == 2 && h % 2 == 1;
			double ringRadius = radius * (variant == 2 ? (inner ? 0.4 : 0.7) : 0.6);
			double angle = spin + Math.PI * 2.0 * h / hands;
			double x = center.x + Math.cos(angle) * ringRadius;
			double z = center.z + Math.sin(angle) * ringRadius;
			// The per-slot phase offset: v1 grasps in unison, the rest stagger.
			double phaseOffset = variant == 1 ? 0.0 : BehaviorSupport.hash01(BehaviorSupport.mix(h * 131L + (inner ? 7L : 0L)));
			double phase = (gameTime / (double) CYCLE_TICKS + phaseOffset) % 1.0;
			double extension = 0.5 - 0.5 * Math.cos(Math.PI * 2.0 * phase);
			double handTop = y0 + reach * extension;
			BehaviorSupport.sendContained(level, palm, shape, center, radius,
					x, (y0 + handTop) * 0.5, z, 2, 0.05, reach * extension * 0.25 + 0.02, 0.05, 0.0);
			BehaviorSupport.sendContained(level, fingertip, shape, center, radius,
					x, handTop, z, 1, 0.08, 0.03, 0.08, 0.0);
			ParticleOptions soil = variant == 4 ? ParticleTypes.WHITE_ASH : ParticleTypes.ASH;
			BehaviorSupport.sendContained(level, soil, shape, center, radius,
					x, y0 + 0.1, z, variant == 6 ? 2 : 1, 0.12, 0.06, 0.12, 0.01);
			// Full extension: the pulse grid steps phase in sixths of the cycle
			// (10 of 60 ticks), so the half-pulse window around the crest is hit
			// exactly once per cycle per hand.
			boolean crest = Math.abs(phase - 0.5) < 10.0 / CYCLE_TICKS * 0.5;
			if (crest) {
				if (variant == 4) {
					BehaviorSupport.sendContained(level, ParticleTypes.SMOKE, shape, center, radius,
							x, handTop + 0.15, z, 1, 0.04, 0.08, 0.04, 0.01);
				} else {
					BehaviorSupport.sendContained(level, ParticleTypes.SCULK_CHARGE_POP, shape, center, radius,
							x, handTop + 0.1, z, 1, 0.06, 0.04, 0.06, 0.0);
					if (variant == 3) {
						BehaviorSupport.sendContained(level, ParticleTypes.SOUL, shape, center, radius,
								x, handTop + 0.3, z, 1, 0.05, 0.15, 0.05, 0.02);
					}
				}
			}
		}
	}
}

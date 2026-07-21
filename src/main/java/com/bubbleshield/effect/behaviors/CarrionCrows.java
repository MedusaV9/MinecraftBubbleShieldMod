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
 * A slow murder of ghost crows circling under the bubble apex: each crow is a
 * squid-ink fleck bobbing on its own wing-beat, shedding ash "feathers" that
 * drift down beneath the gyre, with a palette dust eye glint riding every
 * bird. The circle breathes (radius swells and shrinks over a long period),
 * so the murder never looks pinned. Pure particles, no entities, no state.
 *
 * <ul>
 * <li>v0: a slow murder of five</li>
 * <li>v1: a big murder of eight on a tight circle</li>
 * <li>v2: a raven pair (two large birds, doubled bodies)</li>
 * <li>v3: a molting murder (feathers every pulse, from every bird)</li>
 * <li>v4: a spooked murder (the circle periodically scatters wide, with poofs)</li>
 * <li>v5: a descending gyre (the murder spirals from apex to mid height and resets)</li>
 * <li>v6: a white-winged omen (white-ash feathers, brighter glints)</li>
 * </ul>
 */
public final class CarrionCrows implements InsideEffectBehavior {
	public static final String ID = "carrion_crows";
	/** Worst case v3 at ctx-max murder: 8 crows x (body 1 + feathers 2 + eye dust 1) = 32, + roost dust 1 = 33 particles/pulse. */
	private static final int MAX_CROWS = 8;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int murder = ctx.scaleCount(switch (variant) {
			case 1 -> 8;
			case 2 -> 2;
			default -> 5;
		}, MAX_CROWS);
		double omega = 0.015 * Mth.clamp(def.behaviorStrength(), 0.7F, 1.5F);
		// The gyre breathes over ~40s; v4's spook spikes it wide every ~16s.
		double breathe = 0.06 * Math.sin(gameTime * 0.008);
		boolean spooked = variant == 4 && gameTime % 320L < 40L;
		double ringFrac = (variant == 1 ? 0.25 : 0.35) + breathe + (spooked ? 0.15 : 0.0);
		double heightFrac = switch (variant) {
			// The descending gyre: apex down to mid height, then reset.
			case 5 -> 0.72 - 0.4 * ((gameTime % 600L) / 600.0);
			// Slightly lower apex so the spook scatter stays within ~0.85r.
			case 4 -> 0.62;
			default -> 0.68;
		};
		ParticleOptions feather = variant == 6 ? ParticleTypes.WHITE_ASH : ParticleTypes.ASH;
		ParticleOptions eyeDust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, variant == 6 ? 0.8F : 0.5F);
		for (int c = 0; c < murder; c++) {
			double angle = gameTime * omega + Math.PI * 2.0 * c / Math.max(1, murder);
			// Each crow bobs on its own hash-offset wing-beat.
			double beat = Math.sin(gameTime * 0.25 + BehaviorSupport.hash01(BehaviorSupport.mix(c * 911L)) * Math.PI * 2.0);
			double x = center.x + Math.cos(angle) * radius * ringFrac;
			double y = center.y + radius * heightFrac + beat * 0.3;
			double z = center.z + Math.sin(angle) * radius * ringFrac;
			BehaviorSupport.sendContained(level, ParticleTypes.SQUID_INK, shape, center, radius,
					x, y, z, variant == 2 ? 2 : 1, 0.08, 0.05, 0.08, 0.0);
			// The recolor accent: a palette dust eye glint on every bird, every variant.
			BehaviorSupport.sendContained(level, eyeDust, shape, center, radius,
					x, y + 0.15, z, 1, 0.02, 0.02, 0.02, 0.0);
			// Molting: feathers flutter down beneath the gyre. Every crow sheds on
			// v3; otherwise one hash-picked crow sheds per pulse.
			if (variant == 3 || (int) (BehaviorSupport.hash01(BehaviorSupport.mix(gameTime / 10L)) * murder) == c) {
				BehaviorSupport.sendContained(level, feather, shape, center, radius,
						x, Math.max(center.y + 0.2, y - 0.8), z, variant == 3 ? 2 : 1, 0.1, 0.3, 0.1, 0.0);
			}

			if (spooked) {
				BehaviorSupport.sendContained(level, ParticleTypes.POOF, shape, center, radius,
						x, y, z, 1, 0.1, 0.1, 0.1, 0.02);
			}
		}

		// The carrion roost: a secondary-dust smudge on the floor beneath the gyre
		// (second palette strand, present in every variant).
		BehaviorSupport.sendContained(level,
				new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 1.0F),
				shape, center, radius, center.x, center.y + 0.15, center.z, 1, radius * 0.1, 0.02, radius * 0.1, 0.0);
	}
}

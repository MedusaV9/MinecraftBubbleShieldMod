package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A ghost-reef shoal swimming through the air: schooling nautilus motes ride
 * the count=0 fly-towards packet form along undulating arcs at mid-height
 * (each mote spawns slightly behind its target on the path, so the school
 * visibly swims), with glow-ink puffs and a palette dust mote at the shoal's
 * centroid. NAUTILUS and DOLPHIN are air-safe in 26.2 (conduits and dolphins
 * emit them above water; no water-conditioned removal in their client
 * classes).
 *
 * <ul>
 * <li>v0: a small shoal</li>
 * <li>v1: dolphin arcs (dolphin motes on jump parabolas)</li>
 * <li>v2: ink shadows trailing the shoal</li>
 * <li>v3: a spiral shoal (helix around the axis)</li>
 * <li>v4: a predator pass that scatters the shoal</li>
 * <li>v5: an abyssal school (dark dust bodies, sparse glow)</li>
 * <li>v6: tide chorus (conduit pings under the shoal)</li>
 * </ul>
 */
public final class SpectralShoal implements InsideEffectBehavior {
	public static final String ID = "spectral_shoal";
	/** Worst case: 24 fish + puffs 6 + centroid 1 + predator 3 = 34 particles/pulse. */
	private static final int MAX_FISH = 24;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int fish = ctx.scaleCount(Mth.clamp((int) (radius * 2.0F * def.behaviorStrength()), 8, MAX_FISH), MAX_FISH);
		double phase = gameTime / 10.0 * 0.3;
		double predAngle = phase * 2.5;
		ParticleOptions abyssalBody = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.9F);
		for (int i = 0; i < fish; i++) {
			double along = phase + Math.PI * 2.0 * i / fish;
			double spread = variant == 4 && angleGap(along, predAngle) < 0.6 ? 0.35 : 0.0;
			Vec3 target = arcPoint(center, radius, along, i, variant, spread);
			if (variant == 1) {
				// Dolphin motes ride jump parabolas instead of the swim stream.
				double tj = ((gameTime + i * 7L) % 40L) / 40.0;
				double jump = radius * 0.3 * 4.0 * tj * (1.0 - tj);
				BehaviorSupport.sendContained(level, ParticleTypes.DOLPHIN, shape, center, radius,
						target.x, center.y + radius * 0.15 + jump, target.z, 1, 0.1, 0.1, 0.1, 0.01);
			} else if (variant == 5) {
				BehaviorSupport.sendContained(level, abyssalBody, shape, center, radius,
						target.x, target.y, target.z, 1, 0.08, 0.08, 0.08, 0.0);
				if (i % 3 == 0) {
					BehaviorSupport.sendContained(level, ParticleTypes.GLOW, shape, center, radius,
							target.x, target.y + 0.1, target.z, 1, 0.05, 0.05, 0.05, 0.0);
				}
			} else {
				// The swim stream: spawn behind on the arc, fly to the target (count=0).
				Vec3 tgt = BehaviorSupport.containPoint(shape, center, radius, target);
				Vec3 spawn = BehaviorSupport.containPoint(shape, center, radius,
						arcPoint(center, radius, along - 0.35, i, variant, spread));
				level.sendParticles(ParticleTypes.NAUTILUS, true, false, tgt.x, tgt.y, tgt.z, 0,
						spawn.x - tgt.x, spawn.y - tgt.y, spawn.z - tgt.z, 1.0);
			}

			if (variant == 2 && i % 4 == 0) {
				BehaviorSupport.sendContained(level, ParticleTypes.SQUID_INK, shape, center, radius,
						target.x, target.y - 0.2, target.z, 1, 0.1, 0.1, 0.1, 0.005);
			} else if (i % 5 == 0 && variant != 5) {
				BehaviorSupport.sendContained(level, ParticleTypes.GLOW_SQUID_INK, shape, center, radius,
						target.x, target.y + 0.15, target.z, 1, 0.1, 0.1, 0.1, 0.0);
			}
		}

		// The recolor accent: one palette dust mote at the shoal's centroid height.
		BehaviorSupport.sendContained(level, new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 0.8F),
				shape, center, radius, center.x, center.y + radius * 0.4, center.z, 1, 0.3, 0.2, 0.3, 0.0);
		if (variant == 4) {
			// The predator: one fast, large arc cutting through the school.
			Vec3 pred = arcPoint(center, radius, predAngle, 97, variant, 0.0);
			BehaviorSupport.sendContained(level, ParticleTypes.GLOW_SQUID_INK, shape, center, radius,
					pred.x, pred.y, pred.z, 3, 0.2, 0.15, 0.2, 0.02);
		} else if (variant == 6 && gameTime % 40L == 0L) {
			level.playSound(null, center.x, center.y + radius * 0.3, center.z,
					SoundEvents.CONDUIT_AMBIENT, SoundSource.AMBIENT, 0.3F, 1.4F);
		}
	}

	/** A point on the shoal's undulating swim arc (v3 climbs a helix instead). */
	private static Vec3 arcPoint(Vec3 center, float radius, double along, int fish, int variant, double scatter) {
		double dist = radius * (variant == 3 ? 0.5 : 0.6) + scatter * radius;
		double y;
		if (variant == 3) {
			// The helix: height tracks the angle, wrapping every full turn.
			double frac = (along / (Math.PI * 2.0) % 1.0 + 1.0) % 1.0;
			y = center.y + radius * (0.15 + 0.4 * frac);
		} else {
			y = center.y + radius * (0.35 + 0.2 * Math.sin(along * 2.0 + fish * 0.7));
		}

		return new Vec3(center.x + Math.cos(along) * dist, y, center.z + Math.sin(along) * dist);
	}

	/** The smallest absolute angular distance between two ring angles. */
	private static double angleGap(double a, double b) {
		double gap = Math.abs((a - b) % (Math.PI * 2.0));
		return Math.min(gap, Math.PI * 2.0 - gap);
	}
}

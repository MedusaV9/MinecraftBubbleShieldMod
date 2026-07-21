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
 * Dust creeper silhouettes fusing and popping harmlessly: each effigy stands
 * on a hash-cycled floor anchor, swells over its fuse (a secondary-strand
 * arming flash flickering across the body late in the countdown), then
 * "detonates" into a poof burst ringed by gust puffs -- pure particles, no
 * blast, no entities. The body is palette dust, so the owner recolor retints
 * the whole minefield.
 *
 * <ul>
 * <li>v0: two patient effigies on a long fuse</li>
 * <li>v1: a minefield of four short-fuse effigies</li>
 * <li>v2: charged effigies (an electric-spark sheath during the fuse)</li>
 * <li>v3: one giant effigy (explosion flash instead of the gust ring)</li>
 * <li>v4: a chain reaction (staggered fuses ripple the detonations)</li>
 * <li>v5: duds that fizzle into smoke instead of popping</li>
 * <li>v6: festive effigies (firework crackle in the pop)</li>
 * </ul>
 */
public final class CreeperEffigies implements InsideEffectBehavior {
	public static final String ID = "creeper_effigies";
	/** Worst case v6 detonation pulse: 6 effigies x (poof 4 + gust ring 4 + firework 6 + ember dust 1) = 90 particles/pulse. */
	private static final int MAX_EFFIGIES = 6;
	/** Pulses per fuse-and-pop cycle (the last pulse is the detonation). */
	private static final long CYCLE_PULSES = 8L;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int effigies = ctx.scaleCount(switch (variant) {
			case 1, 4 -> 4;
			case 3 -> 1;
			default -> 2;
		}, variant == 3 ? 2 : MAX_EFFIGIES);
		long cyclePulses = variant == 1 ? 6L : CYCLE_PULSES;
		long pulse = gameTime / 10L;
		double scale = Math.min(radius * 0.24, (variant == 3 ? 1.7 : 1.0) * Mth.clamp(def.behaviorStrength(), 0.8F, 1.3F));
		ParticleOptions bodyDust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.1F);
		ParticleOptions headDust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.4F);
		ParticleOptions faceDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.6F);
		ParticleOptions flashDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 1.8F);
		for (int e = 0; e < effigies; e++) {
			// v4 staggers the fuses two pulses apart, so pops ripple down the chain.
			long stagger = variant == 4 ? e * 2L : 0L;
			long phase = (pulse + stagger) % cyclePulses;
			long cycle = (pulse + stagger) / cyclePulses;
			Vec3 anchor = anchorPoint(center, radius, e, cycle);
			if (phase == cyclePulses - 1L) {
				detonate(level, shape, center, radius, variant, anchor, scale, bodyDust);
				continue;
			}

			// The fuse: the whole silhouette swells toward the pop.
			double swell = 1.0 + 0.3 * phase / (cyclePulses - 1.0);
			long seed = BehaviorSupport.mix(cycle * 977L + e * 31L);
			double facing = BehaviorSupport.hash01(seed + 3L) * Math.PI * 2.0;
			double fx = Math.cos(facing);
			double fz = Math.sin(facing);
			for (int side = -1; side <= 1; side += 2) {
				// Stubby feet astride the facing axis.
				BehaviorSupport.sendContained(level, bodyDust, shape, center, radius,
						anchor.x - fz * side * 0.22 * scale, anchor.y + 0.15 * scale, anchor.z + fx * side * 0.22 * scale,
						1, 0.02, 0.02, 0.02, 0.0);
			}

			for (int k = 0; k < 3; k++) {
				BehaviorSupport.sendContained(level, bodyDust, shape, center, radius,
						anchor.x, anchor.y + (0.5 + 0.35 * k) * scale * swell, anchor.z,
						1, 0.04 * swell, 0.02, 0.04 * swell, 0.0);
			}

			double headY = anchor.y + 1.55 * scale * swell;
			BehaviorSupport.sendContained(level, headDust, shape, center, radius,
					anchor.x, headY, anchor.z, 1, 0.03, 0.03, 0.03, 0.0);
			// The sad face: a secondary-strand mote on the head's facing side.
			BehaviorSupport.sendContained(level, faceDust, shape, center, radius,
					anchor.x + fx * 0.14 * scale, headY - 0.05 * scale, anchor.z + fz * 0.14 * scale,
					1, 0.03, 0.03, 0.03, 0.0);
			if (phase >= cyclePulses - 3L && (pulse + e) % 2L == 0L) {
				// The late-fuse arming flash flickers over the torso.
				BehaviorSupport.sendContained(level, flashDust, shape, center, radius,
						anchor.x, anchor.y + 0.85 * scale * swell, anchor.z, 1, 0.06, 0.15, 0.06, 0.0);
			}

			if (variant == 2) {
				BehaviorSupport.sendContained(level, ParticleTypes.ELECTRIC_SPARK, shape, center, radius,
						anchor.x, anchor.y + 0.85 * scale * swell, anchor.z, 2, 0.2 * scale, 0.4 * scale, 0.2 * scale, 0.02);
			}

			if (phase == cyclePulses - 2L && e == 0) {
				level.playSound(null, anchor.x, anchor.y + scale, anchor.z,
						SoundEvents.CREEPER_PRIMED, SoundSource.AMBIENT, 0.35F, 1.0F);
			}
		}
	}

	/** The harmless pop: a poof heart, a gust ring (v3 explosion, v5 fizzle, v6 crackle) and a palette dust ember. */
	private static void detonate(ServerLevel level, ShieldShape shape, Vec3 center, float radius,
			int variant, Vec3 anchor, double scale, ParticleOptions bodyDust) {
		double heartY = anchor.y + 0.8 * scale;
		if (variant == 5) {
			// The dud: a smoky fizzle where the pop should have been.
			BehaviorSupport.sendContained(level, ParticleTypes.SMOKE, shape, center, radius,
					anchor.x, heartY, anchor.z, 3, 0.2 * scale, 0.35 * scale, 0.2 * scale, 0.01);
			BehaviorSupport.sendContained(level, bodyDust, shape, center, radius,
					anchor.x, heartY, anchor.z, 1, 0.1, 0.1, 0.1, 0.0);
			return;
		}

		BehaviorSupport.sendContained(level, ParticleTypes.POOF, shape, center, radius,
				anchor.x, heartY, anchor.z, 4, 0.3 * scale, 0.3 * scale, 0.3 * scale, 0.02);
		if (variant == 3) {
			BehaviorSupport.sendContained(level, ParticleTypes.EXPLOSION, shape, center, radius,
					anchor.x, heartY, anchor.z, 1, 0.0, 0.0, 0.0, 0.0);
			BehaviorSupport.sendContained(level, ParticleTypes.GUST, shape, center, radius,
					anchor.x, heartY, anchor.z, 2, 0.5 * scale, 0.2 * scale, 0.5 * scale, 0.0);
		} else {
			for (int g = 0; g < 4; g++) {
				// The shockwave ring: four gust puffs around the blast heart.
				double angle = Math.PI * 0.5 * g;
				BehaviorSupport.sendContained(level, ParticleTypes.GUST, shape, center, radius,
						anchor.x + Math.cos(angle) * 0.9 * scale, heartY, anchor.z + Math.sin(angle) * 0.9 * scale,
						1, 0.05, 0.05, 0.05, 0.0);
			}
		}

		if (variant == 6) {
			BehaviorSupport.sendContained(level, ParticleTypes.FIREWORK, shape, center, radius,
					anchor.x, heartY, anchor.z, 6, 0.25 * scale, 0.25 * scale, 0.25 * scale, 0.08);
		}

		// The ember: one palette dust mote at the blast heart, every variant.
		BehaviorSupport.sendContained(level, bodyDust, shape, center, radius,
				anchor.x, heartY, anchor.z, 1, 0.15, 0.15, 0.15, 0.0);
	}

	/** The hash-cycled effigy anchor: within 0.6r horizontally, on the floor plane. */
	private static Vec3 anchorPoint(Vec3 center, float radius, int effigy, long cycle) {
		long seed = BehaviorSupport.mix(cycle * 977L + effigy * 31L);
		double angle = BehaviorSupport.hash01(seed) * Math.PI * 2.0;
		double dist = Math.sqrt(BehaviorSupport.hash01(seed + 1L)) * radius * 0.6;
		return new Vec3(center.x + Math.cos(angle) * dist, center.y + 0.1, center.z + Math.sin(angle) * dist);
	}
}

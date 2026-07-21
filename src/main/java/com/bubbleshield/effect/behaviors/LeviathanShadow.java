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
 * A huge unseen leviathan sliding low through the bubble like something under
 * ice: a long undulating spine of large-smoke vertebrae over a squid-ink
 * shadow, gliding just above the center plane (dome-safe) on a slow circular
 * course with hash-wandered laps. Palette dust dorsal fins ride every third
 * vertebra (primary strand) and the head carries a secondary-strand marker,
 * so the owner recolor tints the beast. Purely particles -- no entities, no
 * state, no cleanup.
 *
 * <ul>
 * <li>v0: one long patient pass</li>
 * <li>v1: a breaching bow (the midsection arcs up with glow-ink shimmer)</li>
 * <li>v2: twin serpents circling in opposition</li>
 * <li>v3: the colossus (doubled smoke bulk, a cloud blowhole spout at the head)</li>
 * <li>v4: fin ridge (a raised dust fin spine on every vertebra)</li>
 * <li>v5: hunting spiral (the course breathes between 0.35r and 0.65r)</li>
 * <li>v6: abyssal lateral line (glow-ink dots down every other vertebra)</li>
 * </ul>
 */
public final class LeviathanShadow implements InsideEffectBehavior {
	public static final String ID = "leviathan_shadow";
	/** Worst case v3: 16 vertebrae x (smoke 2 + ink 1) + fins 6 + head 1 + spout 3 = 58 particles/pulse (v6: 16x2 + glow 8 + fins 6 + head 1 = 47). */
	private static final int MAX_SEGMENTS = 16;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int serpents = variant == 2 ? 2 : 1;
		int segments = ctx.scaleCount(
				Mth.clamp((int) (radius * 0.5F * def.behaviorStrength()), 8, MAX_SEGMENTS / serpents),
				MAX_SEGMENTS / serpents);
		long pulseIndex = gameTime / 10L;
		double speed = variant == 3 ? 0.05 : 0.09;
		ParticleOptions finDust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.1F);
		ParticleOptions headDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 1.4F);
		for (int s = 0; s < serpents; s++) {
			// Each lap wanders to a hash-picked course radius and depth.
			long lap = (long) (pulseIndex * speed / (Math.PI * 2.0)) + s * 977L;
			long seed = BehaviorSupport.mix(lap * 419L + s);
			double course = radius * (variant == 5
					? 0.5 + 0.15 * Math.sin(pulseIndex * 0.05)
					: 0.4 + 0.25 * BehaviorSupport.hash01(seed));
			double depth = radius * (0.08 + 0.1 * BehaviorSupport.hash01(seed + 1L));
			double headAngle = pulseIndex * speed * (s == 0 ? 1.0 : -1.0) + s * Math.PI;
			// Vertebra spacing shrinks on big shields so the beast keeps its length.
			double spacing = Math.min(0.35, 2.2 / course);
			for (int i = 0; i < segments; i++) {
				double angle = headAngle - i * spacing * (s == 0 ? 1.0 : -1.0);
				double x = center.x + Math.cos(angle) * course;
				double z = center.z + Math.sin(angle) * course;
				// The swim undulation, plus v1's periodic breach bow at the midsection.
				double y = center.y + depth + radius * 0.03 * Math.sin(pulseIndex * 0.6 + i * 0.8);
				if (variant == 1 && pulseIndex % 16L < 4L) {
					y += radius * 0.22 * Math.sin(Math.PI * i / (segments - 1.0));
				}

				BehaviorSupport.sendContained(level, ParticleTypes.LARGE_SMOKE, shape, center, radius,
						x, y, z, variant == 3 ? 2 : 1, variant == 3 ? 0.35 : 0.2, 0.1, variant == 3 ? 0.35 : 0.2, 0.0);
				// The ink shadow hugging the plane beneath the smoke bulk.
				BehaviorSupport.sendContained(level, ParticleTypes.SQUID_INK, shape, center, radius,
						x, Math.max(center.y + 0.1, y - depth * 0.6), z, 1, 0.25, 0.05, 0.25, 0.0);
				if (variant == 4 || i % 3 == 0) {
					// The recolor accent: a dust dorsal fin, every variant.
					BehaviorSupport.sendContained(level, finDust, shape, center, radius,
							x, y + (variant == 4 ? 0.6 : 0.35), z, 1, 0.04, 0.08, 0.04, 0.0);
				}

				if (variant == 6 && i % 2 == 0) {
					BehaviorSupport.sendContained(level, ParticleTypes.GLOW_SQUID_INK, shape, center, radius,
							x, y + 0.15, z, 1, 0.05, 0.05, 0.05, 0.0);
				} else if (variant == 1 && pulseIndex % 16L < 4L && i == segments / 2) {
					BehaviorSupport.sendContained(level, ParticleTypes.GLOW_SQUID_INK, shape, center, radius,
							x, y + 0.3, z, 2, 0.2, 0.1, 0.2, 0.01);
				}
			}

			// The head marker (secondary strand) leading the spine.
			double hx = center.x + Math.cos(headAngle + spacing * (s == 0 ? 1.0 : -1.0)) * course;
			double hz = center.z + Math.sin(headAngle + spacing * (s == 0 ? 1.0 : -1.0)) * course;
			BehaviorSupport.sendContained(level, headDust, shape, center, radius,
					hx, center.y + depth + 0.2, hz, 1, 0.05, 0.05, 0.05, 0.0);
			if (variant == 3 && pulseIndex % 8L == 0L) {
				// The blowhole spout when the colossus surfaces for a beat.
				BehaviorSupport.sendContained(level, ParticleTypes.CLOUD, shape, center, radius,
						hx, center.y + depth + 0.8, hz, 3, 0.15, 0.3, 0.15, 0.02);
			}
		}
	}
}

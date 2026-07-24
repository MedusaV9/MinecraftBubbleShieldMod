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
 * Ectoplasmic fog fronts: long cloud banks sweeping chords across the bubble
 * interior like weather fronts, with white smoke curling off each bank's
 * leading edge. Bank positions are pure functions of gameTime (hash-seeded
 * headings, looping sweep phases), so the behavior is stateless and one shared
 * instance serves every shield. Palette dust motes ride every bank so the
 * owner color override tints the fog.
 *
 * <ul>
 * <li>v0: two slow low fog banks</li>
 * <li>v1: three stacked banks at staggered heights</li>
 * <li>v2: one massive wall-front spanning the whole interior</li>
 * <li>v3: fast squall line with a dense smoke leading edge</li>
 * <li>v4: crossing banks (opposed headings meeting mid-bubble)</li>
 * <li>v5: luminous fog (glow motes suspended in each bank)</li>
 * <li>v6: creeping ground fog hugging the floor with curling wisps</li>
 * </ul>
 */
public final class EctoFogBanks implements InsideEffectBehavior {
	public static final String ID = "ecto_fog_banks";
	/**
	 * Per-pulse budget; worst case v3 (capped at 40 points): 40 bank points +
	 * 40 edge smoke + 20 dust + 14 squall smoke = 114/pulse (v2: 48 cloud +
	 * 24 smoke + 24 dust + 16 glow crest = 112).
	 */
	private static final int MAX_POINTS = 48;
	/** v3 emits edge smoke on EVERY point, so its point cap is lower. */
	private static final int MAX_SQUALL_POINTS = 40;
	/** One full chord sweep takes this many ticks (v3 squalls halve it). */
	private static final long SWEEP_TICKS = 400L;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int banks = switch (variant) {
			case 1 -> 3;
			case 2, 3 -> 1;
			default -> 2;
		};
		long sweepTicks = variant == 3 ? SWEEP_TICKS / 2L : SWEEP_TICKS;
		int budget = (variant == 3 ? MAX_SQUALL_POINTS : MAX_POINTS) / banks;
		int points = ctx.scaleCount(Mth.clamp((int) (radius * 0.6F * def.behaviorStrength()), 5, budget), budget);
		double halfSpan = radius * (variant == 2 ? 0.65 : 0.55);
		ParticleOptions dust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.0F);
		for (int bank = 0; bank < banks; bank++) {
			long sweep = gameTime / sweepTicks + bank * 37L;
			double phase = (gameTime % sweepTicks) / (double) sweepTicks;
			long seed = BehaviorSupport.mix(sweep * 131L + bank);
			// The front's heading; v4's second bank sweeps head-on into the first.
			double heading = BehaviorSupport.hash01(seed) * Math.PI * 2.0
					+ (variant == 4 && bank == 1 ? Math.PI : 0.0);
			double dirX = Math.cos(heading);
			double dirZ = Math.sin(heading);
			// The bank line advances across the interior: -0.6r to +0.6r along the
			// heading. A v2 wall corner can still co-peak past the shell (~1.0r with
			// the top bank height); the containment sweep pulls such points back in.
			double advance = (-0.6 + 1.2 * phase) * radius;
			double bankY = center.y + radius * switch (variant) {
				case 1 -> 0.15 + 0.2 * bank;
				case 6 -> 0.06;
				default -> 0.18;
			};
			for (int i = 0; i < points; i++) {
				// Spread the bank's body along the chord perpendicular to the heading,
				// with a hash ripple so the front reads ragged, not ruler-straight.
				double t = -1.0 + 2.0 * i / Math.max(1, points - 1);
				double ripple = (BehaviorSupport.hash01(seed + i * 7L) - 0.5) * radius * 0.12;
				double x = center.x + dirX * (advance + ripple) - dirZ * halfSpan * t;
				double z = center.z + dirZ * (advance + ripple) + dirX * halfSpan * t;
				BehaviorSupport.sendContained(level, ParticleTypes.CLOUD, shape, center, radius,
						x, bankY, z, 1, 0.15, 0.06, 0.15, 0.0);
				// White smoke curling at the leading edge, slightly ahead and above.
				if (i % (variant == 3 ? 1 : 2) == 0) {
					BehaviorSupport.sendContained(level, ParticleTypes.WHITE_SMOKE, shape, center, radius,
							x + dirX * 0.5, bankY + (variant == 6 ? 0.15 : 0.4), z + dirZ * 0.5, 1, 0.1, 0.12, 0.1, 0.005);
				}

				// The recolor accent: palette dust motes suspended in every bank.
				if (i % 2 == 0) {
					BehaviorSupport.sendContained(level, dust, shape, center, radius,
							x, bankY + 0.2, z, 1, 0.12, 0.08, 0.12, 0.0);
				}

				if (variant == 5 && i % 3 == 0) {
					BehaviorSupport.sendContained(level, ParticleTypes.GLOW, shape, center, radius,
							x, bankY + 0.3, z, 1, 0.1, 0.1, 0.1, 0.0);
				} else if (variant == 6 && i % 3 == 0) {
					// Ground-fog curls on the darker second strand.
					BehaviorSupport.sendContained(level,
							new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.7F),
							shape, center, radius, x, bankY + 0.1, z, 1, 0.1, 0.05, 0.1, 0.0);
				}
			}

			if (variant == 3 && points >= 3) {
				// The squall's dense forward smoke wall, one point every third slot.
				for (int i = 0; i < points; i += 3) {
					double t = -1.0 + 2.0 * i / Math.max(1, points - 1);
					BehaviorSupport.sendContained(level, ParticleTypes.WHITE_SMOKE, shape, center, radius,
							center.x + dirX * (advance + 1.0) - dirZ * halfSpan * t,
							bankY + 0.6,
							center.z + dirZ * (advance + 1.0) + dirX * halfSpan * t,
							1, 0.12, 0.15, 0.12, 0.01);
				}
			} else if (variant == 2 && points >= 3) {
				// The wall-front's glow crest running along its top.
				for (int i = 0; i < points; i += 3) {
					double t = -1.0 + 2.0 * i / Math.max(1, points - 1);
					BehaviorSupport.sendContained(level, ParticleTypes.GLOW, shape, center, radius,
							center.x + dirX * advance - dirZ * halfSpan * t,
							bankY + radius * 0.15,
							center.z + dirZ * advance + dirX * halfSpan * t,
							1, 0.1, 0.1, 0.1, 0.0);
				}
			}
		}
	}
}

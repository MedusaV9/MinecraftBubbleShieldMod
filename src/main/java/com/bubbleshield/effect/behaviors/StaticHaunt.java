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
 * Haunted static: glitch pockets that pop into existence at hash-seeded
 * anchors as brief dense rectangles of dust-plume noise laced with electric
 * sparks, hold for a moment, then cut out completely -- dead air until the
 * next window opens somewhere else. Pocket anchors, sizes and tilts derive
 * entirely from the window index (no fields), and each pocket outlines its
 * rectangle with palette dust so the owner color override tints the glitches.
 *
 * <ul>
 * <li>v0: two mid-size pockets on a steady blink cadence</li>
 * <li>v1: one big broadcast panel of coarse static</li>
 * <li>v2: four small fast flickers with barely-on windows</li>
 * <li>v3: scanline pocket (the rectangle fills line by line downward)</li>
 * <li>v4: crackle pocket (spark-heavy mix and a dying-out afterglow)</li>
 * <li>v5: dim ghost signal that only reaches half brightness</li>
 * <li>v6: twin mirrored pockets popping on alternating half-cycles</li>
 * </ul>
 */
public final class StaticHaunt implements InsideEffectBehavior {
	public static final String ID = "static_haunt";
	/**
	 * Noise budget per pulse across all live pockets; worst case v4: 88 noise
	 * (44 plume + 44 spark) + 22 dust frame + 12 afterglow = 122/pulse
	 * (v2 with all four flickers on: 4 x (22 + 6) = 112; v1: 88 + 22 = 110).
	 */
	private static final int MAX_NOISE = 88;
	/** Every 4th noise cell doubles as a palette dust frame pixel. */
	private static final int FRAME_INTERVAL = 4;
	/** One blink window: pocket on for the first ON_FRAC, dead air after. */
	private static final long WINDOW_TICKS = 60L;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int pockets = switch (variant) {
			case 1, 3, 4, 5 -> 1;
			case 2 -> 4;
			default -> 2;
		};
		long windowTicks = variant == 2 ? WINDOW_TICKS / 2L : WINDOW_TICKS;
		// The on-fraction of each window; v2 flickers barely register.
		double onFrac = switch (variant) {
			case 2 -> 0.25;
			case 4 -> 0.65;
			default -> 0.5;
		};
		int budget = MAX_NOISE / pockets;
		ParticleOptions frameDust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 0.9F);
		ParticleOptions ghostDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.7F);
		boolean anyLive = false;
		for (int p = 0; p < pockets; p++) {
			// v6's twins alternate: the second pocket's timeline is shifted half a
			// window, so exactly one of the pair is live at any moment. v2 staggers
			// its four flickers by a quarter window each so they pop out of sync.
			long pocketTime = gameTime + (variant == 6 && p == 1 ? windowTicks / 2L
					: variant == 2 ? p * (windowTicks / 4L) : 0L);
			long window = pocketTime / windowTicks + (variant == 6 ? 0L : p * 53L);
			double phase = (pocketTime % windowTicks) / (double) windowTicks;
			if (phase >= onFrac) {
				continue;
			}

			anyLive = true;
			long seed = BehaviorSupport.mix(window * 613L + (variant == 6 ? 0L : p));
			// The pocket anchor: within 0.6r horizontally (0.4r for v1's big panel,
			// whose rectangle reaches farther), 0.1r..0.4r above center. v6's second
			// twin mirrors the first through the vertical axis. A rectangle corner
			// can still peak near ~0.9r; the containment sweep pulls it back in.
			double angle = BehaviorSupport.hash01(seed) * Math.PI * 2.0 + (variant == 6 && p == 1 ? Math.PI : 0.0);
			double dist = Math.sqrt(BehaviorSupport.hash01(seed + 1L)) * radius * (variant == 1 ? 0.4 : 0.6);
			double anchorY = center.y + radius * (0.1 + 0.3 * BehaviorSupport.hash01(seed + 2L));
			double anchorX = center.x + Math.cos(angle) * dist;
			double anchorZ = center.z + Math.sin(angle) * dist;
			// The rectangle: a tilted panel, hash-sized per window.
			double halfW = radius * (variant == 1 ? 0.3 : 0.12) * (0.7 + 0.6 * BehaviorSupport.hash01(seed + 3L));
			double halfH = radius * (variant == 1 ? 0.18 : 0.08) * (0.7 + 0.6 * BehaviorSupport.hash01(seed + 4L));
			double tilt = BehaviorSupport.hash01(seed + 5L) * Math.PI;
			double uX = Math.cos(tilt);
			double uZ = Math.sin(tilt);
			int cells = ctx.scaleCount(Mth.clamp((int) (radius * 0.8F * def.behaviorStrength()), 8, budget), budget);
			for (int i = 0; i < cells; i++) {
				long cellSeed = seed + 100L + i * 11L + (gameTime / 10L) * 977L;
				double u = BehaviorSupport.hash01(cellSeed) * 2.0 - 1.0;
				double v = BehaviorSupport.hash01(cellSeed + 1L) * 2.0 - 1.0;
				if (variant == 3) {
					// The scanline fill: cells only exist above the sweeping line.
					v = 1.0 - 2.0 * phase / onFrac * BehaviorSupport.hash01(cellSeed + 2L);
				}

				double x = anchorX + uX * halfW * u;
				double y = anchorY + halfH * v;
				double z = anchorZ + uZ * halfW * u;
				// The static mix: plume grain with sparks laced through it.
				boolean spark = variant == 4 ? i % 2 == 0 : i % 3 == 0;
				BehaviorSupport.sendContained(level, spark ? ParticleTypes.ELECTRIC_SPARK : ParticleTypes.DUST_PLUME,
						shape, center, radius, x, y, z, 1, 0.03, 0.03, 0.03, variant == 5 ? 0.0 : 0.01);
				if (i % FRAME_INTERVAL == 0) {
					// The frame pixel: palette dust snapped to the rectangle's edge.
					double edgeU = u >= 0.0 ? 1.0 : -1.0;
					boolean vertical = BehaviorSupport.hash01(cellSeed + 3L) < 0.5;
					double fx = anchorX + uX * halfW * (vertical ? u : edgeU);
					double fy = anchorY + halfH * (vertical ? (v >= 0.0 ? 1.0 : -1.0) : v);
					double fz = anchorZ + uZ * halfW * (vertical ? u : edgeU);
					BehaviorSupport.sendContained(level, variant == 5 ? ghostDust : frameDust,
							shape, center, radius, fx, fy, fz, 1, 0.02, 0.02, 0.02, 0.0);
				}
			}

			if (variant == 4 && phase > onFrac * 0.7) {
				// The dying-out afterglow smear as the crackle pocket cuts out.
				BehaviorSupport.sendContained(level, ghostDust, shape, center, radius,
						anchorX, anchorY, anchorZ, ctx.scaleCount(6, 12), halfW * 0.5, halfH * 0.5, halfW * 0.5, 0.0);
			}
		}

		// Dead-air keepalive: when every pocket is between windows, a single dim
		// palette mote drifts at the center so each %10 pulse still emits.
		if (!anyLive) {
			BehaviorSupport.sendContained(level, ghostDust, shape, center, radius,
					center.x, center.y + radius * 0.3, center.z, 1, 0.1, 0.1, 0.1, 0.0);
		}
	}
}

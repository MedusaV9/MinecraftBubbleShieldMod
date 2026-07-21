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
 * A slow eclipse playing out on the inner sky: a glow-and-palette-dust corona
 * ring hangs at a drifting azimuth while a dark squid-ink/ash moon disc
 * transits across it; each cycle culminates in totality with a firework flash,
 * a darker second-strand "diamond ring" glint and a single bell resonance.
 * Stateless: the transit phase and drift derive purely from gameTime.
 *
 * <ul>
 * <li>v0: the classic eclipse, one transit every 200 ticks</li>
 * <li>v1: a fast transit every 100 ticks</li>
 * <li>v2: a double corona with an outer second-strand dust ring</li>
 * <li>v3: a low horizon eclipse with a heavier moon disc</li>
 * <li>v4: an annular eclipse -- a bright palette annulus survives totality</li>
 * <li>v5: a shadow eclipse with a second-strand penumbra glow around the
 * moon</li>
 * <li>v6: twin eclipses on opposite azimuths, half-weight each</li>
 * </ul>
 */
public final class EclipseDisc implements InsideEffectBehavior {
	public static final String ID = "eclipse_disc";
	/**
	 * Per-pulse budget, worst case v6 at full context scaling: 2 anchors x
	 * (10 glow + 10 corona dust + 10 ink + 10 ash) = 80, plus totality pulses
	 * 2 x (12 firework + 4 diamond-ring dust) = 32, total 112 particles (v2
	 * tops at 10 + 10 + 10 + 10 + 10 + 12 + 4 = 66); always &lt;= 128.
	 */
	private static final int MAX_RING_POINTS = 10;
	private static final int MAX_DISC = 10;
	private static final int MAX_FLASH = 12;
	private static final int MAX_DIAMOND = 4;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		long period = variant == 1 ? 100L : 200L;
		double phase = (gameTime % period) / (double) period;
		// Signed transit offset in [-1, 1]: the moon crosses the corona center.
		double off = (phase - 0.5) * 2.0;
		boolean totality = Math.abs(off) < 0.1;
		int anchors = variant == 6 ? 2 : 1;
		int ringPoints = ctx.scaleCount(variant == 6 ? 6 : 8, MAX_RING_POINTS);
		int discPoints = ctx.scaleCount(variant == 6 ? 5 : variant == 3 ? 8 : 6, MAX_DISC);
		double heightFrac = variant == 3 ? 0.3 : 0.5;
		double ringR = radius * 0.14 * Mth.clamp(def.behaviorStrength(), 0.8F, 1.2F);
		double discR = radius * (variant == 3 ? 0.09 : variant == 4 ? 0.05 : 0.07);
		ParticleOptions coronaDust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.1F);
		ParticleOptions strandDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.9F);
		for (int n = 0; n < anchors; n++) {
			// The sun anchor drifts slowly around the sky: 0.5r out, 0.3-0.5r up,
			// so even the corona rim stays within the ~0.85r anchor envelope.
			double azimuth = gameTime / 10.0 * 0.02 + n * Math.PI;
			Vec3 anchor = new Vec3(
					center.x + Math.cos(azimuth) * radius * 0.5,
					center.y + radius * heightFrac,
					center.z + Math.sin(azimuth) * radius * 0.5);
			// The transit runs along the horizontal tangent of the anchor azimuth.
			Vec3 tangent = new Vec3(-Math.sin(azimuth), 0.0, Math.cos(azimuth));
			for (int i = 0; i < ringPoints; i++) {
				// The corona: glow points and a palette dust strand interleaved on
				// the ring (the ring plane spans the tangent and vertical axes).
				double a = Math.PI * 2.0 * i / ringPoints + gameTime / 10.0 * 0.06;
				double half = Math.PI / ringPoints;
				Vec3 glowP = anchor.add(tangent.scale(Math.cos(a) * ringR)).add(0.0, Math.sin(a) * ringR, 0.0);
				Vec3 dustP = anchor.add(tangent.scale(Math.cos(a + half) * ringR)).add(0.0, Math.sin(a + half) * ringR, 0.0);
				BehaviorSupport.sendContained(level, ParticleTypes.GLOW, shape, center, radius,
						glowP.x, glowP.y, glowP.z, 1, 0.03, 0.03, 0.03, 0.0);
				BehaviorSupport.sendContained(level, coronaDust, shape, center, radius,
						dustP.x, dustP.y, dustP.z, 1, 0.03, 0.03, 0.03, 0.0);
				if (variant == 2) {
					Vec3 outer = anchor.add(tangent.scale(Math.cos(a) * ringR * 1.5)).add(0.0, Math.sin(a) * ringR * 1.5, 0.0);
					BehaviorSupport.sendContained(level, strandDust, shape, center, radius,
							outer.x, outer.y, outer.z, 1, 0.03, 0.03, 0.03, 0.0);
				}
			}

			// The moon: a dark ink-and-ash disc sliding across the corona.
			Vec3 moon = anchor.add(tangent.scale(off * radius * 0.3));
			BehaviorSupport.sendContained(level, ParticleTypes.SQUID_INK, shape, center, radius,
					moon.x, moon.y, moon.z, discPoints, discR, discR, discR, 0.0);
			BehaviorSupport.sendContained(level, ParticleTypes.ASH, shape, center, radius,
					moon.x, moon.y, moon.z, discPoints, discR * 1.3, discR * 1.3, discR * 1.3, 0.0);
			if (variant == 5) {
				BehaviorSupport.sendContained(level, strandDust, shape, center, radius,
						moon.x, moon.y, moon.z, 4, discR * 1.8, discR * 1.8, discR * 1.8, 0.0);
			}

			if (totality) {
				// Totality: the flash, and the darker diamond-ring glint at the
				// trailing corona limb.
				BehaviorSupport.sendContained(level, ParticleTypes.FIREWORK, shape, center, radius,
						anchor.x, anchor.y, anchor.z, ctx.scaleCount(variant == 6 ? 6 : 8, MAX_FLASH), 0.1, 0.1, 0.1, 0.08);
				Vec3 limb = anchor.add(tangent.scale(-ringR)).add(0.0, ringR * 0.3, 0.0);
				BehaviorSupport.sendContained(level, strandDust, shape, center, radius,
						limb.x, limb.y, limb.z, ctx.scaleCount(2, MAX_DIAMOND), 0.05, 0.05, 0.05, 0.0);
				if (variant == 4) {
					// The annulus: a tight bright palette ring surviving around the
					// small moon.
					BehaviorSupport.sendContained(level, coronaDust, shape, center, radius,
							moon.x, moon.y, moon.z, 4, discR * 1.6, discR * 1.6, discR * 1.6, 0.0);
				}
			}
		}

		// One resonance per cycle, on the exact mid-transit tick (period and
		// period/2 are multiples of every effective throttle that divides 10).
		if (gameTime % period == period / 2L) {
			level.playSound(null, center.x, center.y + radius * heightFrac, center.z,
					SoundEvents.BELL_RESONATE, SoundSource.AMBIENT, 0.3F, 0.7F);
		}
	}
}

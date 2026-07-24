package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A zodiac clock: twelve thin dust pillars standing on a wide ring near the
 * wall, the quiet signs drawn in the darker second strand while the active
 * sign blazes in the palette color and the "hand" steps from sign to sign
 * every 20 ticks (a soft chime marks each step). Stateless: the active index
 * is pure gameTime arithmetic.
 *
 * <ul>
 * <li>v0: the classic clock, one blazing sign with a glow cap</li>
 * <li>v1: a racing hand advancing every pulse, no chime</li>
 * <li>v2: gradient pillars (primary-to-secondary transition dust)</li>
 * <li>v3: twin hands -- the opposite sign lights up in the second strand</li>
 * <li>v4: a dust spoke sweeping from the projector to the active sign</li>
 * <li>v5: an afterglow -- the three trailing signs fade out behind the
 * hand</li>
 * <li>v6: starry caps -- end-rod pillar tips and a firework on the active
 * sign</li>
 * </ul>
 */
public final class ZodiacBeams implements InsideEffectBehavior {
	public static final String ID = "zodiac_beams";
	/**
	 * Per-pulse budget, worst case v6: 11 quiet signs x 3 dust + 12 end-rod
	 * caps + active (8 dust + 3 firework) = 56 particles (v4: 33 + 8 + 1 glow
	 * + 8 spoke = 50, v5: 24 quiet + afterglow 4 + 2 + 1 + active 8 + 1 glow
	 * = 40); always &lt;= 128.
	 */
	private static final int MAX_QUIET_SAMPLES = 3;
	private static final int MAX_ACTIVE_SAMPLES = 8;
	private static final int MAX_SPOKE = 8;
	private static final int SIGNS = 12;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		long cadence = variant == 1 ? 10L : 20L;
		int active = (int) ((gameTime / cadence) % SIGNS);
		int quietSamples = ctx.scaleCount(2, MAX_QUIET_SAMPLES);
		int activeSamples = ctx.scaleCount(5, MAX_ACTIVE_SAMPLES);
		// The sign ring: bases at 0.78r out, just above the center plane, tips
		// up to ~0.33r higher -- even at the strength clamp the tips stay
		// within ~0.85r of the center (and dome-safe, never below center.y).
		double ringDist = radius * 0.78;
		double baseY = center.y + radius * 0.04;
		double pillarHeight = radius * 0.24 * Mth.clamp(def.behaviorStrength(), 0.8F, 1.2F);
		ParticleOptions quietDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.7F);
		ParticleOptions activeDust = variant == 2
				? new DustColorTransitionOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF,
						ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 1.3F)
				: new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.3F);
		for (int sign = 0; sign < SIGNS; sign++) {
			double angle = Math.PI * 2.0 * sign / SIGNS;
			double x = center.x + Math.cos(angle) * ringDist;
			double z = center.z + Math.sin(angle) * ringDist;
			boolean lit = sign == active || (variant == 3 && sign == (active + SIGNS / 2) % SIGNS);
			// v5 afterglow: how many steps behind the hand this sign trails (1-3).
			int fade = variant == 5 ? (active - sign + SIGNS) % SIGNS : 0;
			int samples = lit ? activeSamples : quietSamples;
			ParticleOptions dust = lit ? activeDust : quietDust;
			if (variant == 5 && !lit && fade >= 1 && fade <= 3) {
				samples = Math.max(1, activeSamples * (4 - fade) / 6);
				dust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF,
						1.1F - 0.25F * fade);
			}

			for (int i = 0; i < samples; i++) {
				// The pillar: evenly stacked dust with a slow upward shimmer.
				double t = (i + (gameTime % 20L) / 20.0) / samples;
				BehaviorSupport.sendContained(level, dust, shape, center, radius,
						x, baseY + pillarHeight * t, z, 1, 0.02, 0.05, 0.02, 0.0);
			}

			if (variant == 6) {
				BehaviorSupport.sendContained(level, ParticleTypes.END_ROD, shape, center, radius,
						x, baseY + pillarHeight, z, 1, 0.03, 0.03, 0.03, 0.0);
			}

			if (lit) {
				ParticleOptions cap = variant == 6 ? ParticleTypes.FIREWORK : ParticleTypes.GLOW;
				BehaviorSupport.sendContained(level, cap, shape, center, radius,
						x, baseY + pillarHeight + radius * 0.02, z,
						variant == 6 ? 3 : 1, 0.05, 0.05, 0.05, variant == 6 ? 0.04 : 0.0);
			}

			if (variant == 4 && sign == active) {
				// The hand: a dust spoke from the projector to the blazing sign.
				int spoke = ctx.scaleCount(5, MAX_SPOKE);
				for (int i = 1; i <= spoke; i++) {
					double t = (double) i / (spoke + 1);
					BehaviorSupport.sendContained(level, activeDust, shape, center, radius,
							Mth.lerp(t, center.x, x), baseY + radius * 0.02, Mth.lerp(t, center.z, z),
							1, 0.02, 0.02, 0.02, 0.0);
				}
			}
		}

		// The step chime, once per hand advance (cadence 20 aligns with every
		// effective throttle that divides 10); the racing v1 stays silent.
		if (variant != 1 && gameTime % cadence == 0L) {
			double a = Math.PI * 2.0 * active / SIGNS;
			level.playSound(null, center.x + Math.cos(a) * ringDist, baseY + pillarHeight, center.z + Math.sin(a) * ringDist,
					SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.AMBIENT, 0.35F, 1.3F);
		}
	}
}

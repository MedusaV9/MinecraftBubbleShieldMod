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
 * A lantern festival: paper lanterns (a palette-dust shell around a small flame
 * with wax-on glints) rising in staggered flights from hash-seeded launch sites,
 * then winking out in a puff of white smoke near the canopy. Purely particles --
 * no entities, no state, no cleanup; launch sites re-roll every flight.
 *
 * <ul>
 * <li>v0: classic warm festival (four-mote shells, wax glints every other pulse)</li>
 * <li>v1: twin procession -- two spiral lines launching from opposite banks, the
 * second line in the darker palette tone</li>
 * <li>v2: giant lanterns -- fewer aloft, six-mote shells, larger dust</li>
 * <li>v3: quick release -- half-period flights with a thicker wink-out puff</li>
 * <li>v4: floating ceiling -- lanterns rise then hover in a drifting canopy
 * (no wink-out, occasional smoke wisps)</li>
 * <li>v5: two-tone alternation -- every other lantern glows in the secondary tone</li>
 * <li>v6: wisp lanterns -- small motes, constant wax shimmer, a smoke thread
 * trailing below each lantern</li>
 * </ul>
 */
public final class LanternFestival implements InsideEffectBehavior {
	public static final String ID = "lantern_festival";
	/** Worst case v4/v6: 14 lanterns x (4 shell dust + 1 flame + 1 wax + 1 smoke) = 98 particles/pulse. */
	private static final int MAX_LANTERNS = 14;
	/** v2's giant lanterns carry six-mote shells, so fewer fly at once (10 x 8 = 80/pulse). */
	private static final int MAX_GIANT_LANTERNS = 10;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		long flightTicks = variant == 3 ? 100L : 200L;
		int base = Mth.clamp((int) (radius * 0.3F * def.behaviorStrength()), 4, variant == 2 ? 8 : 12);
		int lanterns = ctx.scaleCount(base, variant == 2 ? MAX_GIANT_LANTERNS : MAX_LANTERNS);
		int shellPoints = variant == 2 ? 6 : 4;
		float dustSize = variant == 2 ? 1.3F : variant == 6 ? 0.6F : 0.9F;
		ParticleOptions primaryShell = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, dustSize);
		ParticleOptions secondaryShell = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, dustSize);
		for (int l = 0; l < lanterns; l++) {
			// Staggered flights: every lantern is offset a fraction of the period.
			long shifted = gameTime + (long) l * flightTicks / Math.max(1, lanterns);
			long flight = shifted / flightTicks;
			double t = (shifted % flightTicks) / (double) flightTicks;
			long seed = BehaviorSupport.mix(flight * 131L + l);
			double angle;
			double dist;
			if (variant == 1) {
				// Twin procession: two spiral lines released from opposite banks.
				angle = (l % 2 == 0 ? 0.0 : Math.PI) + flight * 0.7 + t * 0.5;
				dist = radius * (0.25 + 0.25 * BehaviorSupport.hash01(seed + 1L));
			} else {
				angle = BehaviorSupport.hash01(seed) * Math.PI * 2.0;
				dist = Math.sqrt(BehaviorSupport.hash01(seed + 1L)) * radius * 0.5;
			}

			if (variant == 4) {
				// The canopy drifts in a slow circle once a lantern reaches it.
				angle += Math.max(0.0, t - 0.5) * 1.5;
			}

			// Rise from 0.08r to a ~0.66r apex (v4 parks at the apex for the
			// second half of its period instead of winking out).
			double rise = variant == 4 ? Math.min(t, 0.5) * 2.0 : t;
			double x = center.x + Math.cos(angle) * dist;
			double z = center.z + Math.sin(angle) * dist;
			double y = center.y + radius * (0.08 + 0.58 * rise);
			if (variant != 4 && t > 0.88) {
				// The candle dies: white smoke and one darker cooling mote.
				BehaviorSupport.sendContained(level, ParticleTypes.WHITE_SMOKE, shape, center, radius,
						x, y, z, variant == 3 ? 3 : 2, 0.12, 0.1, 0.12, 0.01);
				BehaviorSupport.sendContained(level, secondaryShell, shape, center, radius,
						x, y, z, 1, 0.05, 0.05, 0.05, 0.0);
				continue;
			}

			double body = Math.min(radius * 0.05, 0.45) * (variant == 2 ? 1.8 : 1.0);
			ParticleOptions shell = (variant == 1 || variant == 5) && l % 2 != 0 ? secondaryShell : primaryShell;
			for (int k = 0; k < shellPoints; k++) {
				double sa = Math.PI * 2.0 * k / shellPoints + t * 4.0;
				BehaviorSupport.sendContained(level, shell, shape, center, radius,
						x + Math.cos(sa) * body, y + 0.1, z + Math.sin(sa) * body, 1, 0.02, 0.03, 0.02, 0.0);
			}

			// The candle inside the paper shell.
			BehaviorSupport.sendContained(level, ParticleTypes.SMALL_FLAME, shape, center, radius,
					x, y, z, 1, 0.02, 0.04, 0.02, 0.0);
			if (variant == 6 || (gameTime / 10L + l) % 2L == 0L) {
				BehaviorSupport.sendContained(level, ParticleTypes.WAX_ON, shape, center, radius,
						x, y + 0.25, z, 1, 0.08, 0.06, 0.08, 0.0);
			}

			if (variant == 6) {
				BehaviorSupport.sendContained(level, ParticleTypes.WHITE_SMOKE, shape, center, radius,
						x, y - 0.3, z, 1, 0.03, 0.08, 0.03, 0.0);
			} else if (variant == 4 && t >= 0.5 && (gameTime / 10L + l) % 4L == 0L) {
				BehaviorSupport.sendContained(level, ParticleTypes.WHITE_SMOKE, shape, center, radius,
						x, y + 0.2, z, 1, 0.05, 0.05, 0.05, 0.0);
			}
		}
	}
}

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
 * Ghostly aurora curtains: vertical sheets of glow and palette dust hanging
 * just under the ceiling, folding along their length in slow sine waves like
 * polar light seen from below. Each sheet is a chord whose fold phase, drift
 * and drape depth are pure functions of gameTime -- stateless, no fields.
 * Every variant hangs palette dust in the drape so the owner color override
 * recolors the lights.
 *
 * <ul>
 * <li>v0: two soft counter-phased curtains</li>
 * <li>v1: one deep-draping curtain with long vertical falls</li>
 * <li>v2: three narrow ripple sheets folding quickly</li>
 * <li>v3: gradient curtains (primary-to-secondary dust strands)</li>
 * <li>v4: a corona ring-curtain circling the pole instead of a chord</li>
 * <li>v5: dim flickering curtains that surge on a slow cycle</li>
 * <li>v6: twin crossing curtains with end-rod crest sparks</li>
 * </ul>
 */
public final class AuroraGhosts implements InsideEffectBehavior {
	public static final String ID = "aurora_ghosts";
	/**
	 * Column budget; worst case v6: 2 sheets x 20 columns x (glow + dust +
	 * fall dust) + 2 x 3 crest sparks = 126/pulse (v1: 40 x 3 = 120).
	 */
	private static final int MAX_COLUMNS = 40;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int sheets = switch (variant) {
			case 1, 4 -> 1;
			case 2 -> 3;
			default -> 2;
		};
		// v5 surges: the curtain dims to a third of its columns off-peak.
		double surge = variant == 5 ? 0.35 + 0.65 * 0.5 * (1.0 + Math.sin(gameTime * 0.02)) : 1.0;
		int budget = MAX_COLUMNS / sheets;
		int columns = ctx.scaleCount(Mth.clamp((int) (radius * 0.7F * def.behaviorStrength() * surge), 4, budget), budget);
		double ceilingY = center.y + radius * 0.62;
		double drapeDepth = radius * (variant == 1 ? 0.3 : 0.16);
		double halfSpan = radius * 0.55;
		double drift = gameTime * 0.0015;
		double foldRate = variant == 2 ? 0.09 : 0.035;
		ParticleOptions dust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.1F);
		ParticleOptions lowerDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.9F);
		for (int sheet = 0; sheet < sheets; sheet++) {
			double yaw = drift + (variant == 6 ? Math.PI / 2.0 * sheet : Math.PI * sheet / sheets);
			double dirX = Math.cos(yaw);
			double dirZ = Math.sin(yaw);
			double phase = sheet * Math.PI + gameTime * foldRate;
			for (int i = 0; i < columns; i++) {
				double t = -1.0 + 2.0 * i / Math.max(1, columns - 1);
				// The sine fold: the sheet ripples sideways along its chord and the
				// drape bottom breathes up and down with a second harmonic.
				double fold = Math.sin(t * 4.0 + phase);
				double drape = drapeDepth * (0.55 + 0.45 * Math.sin(t * 2.0 + phase * 0.7));
				double x;
				double z;
				if (variant == 4) {
					// The corona: the curtain closes into a ring around the pole.
					double angle = Math.PI * 2.0 * i / columns + drift * 4.0;
					double ringDist = radius * (0.4 + 0.05 * fold);
					x = center.x + Math.cos(angle) * ringDist;
					z = center.z + Math.sin(angle) * ringDist;
				} else {
					x = center.x + dirX * halfSpan * t - dirZ * radius * 0.08 * fold;
					z = center.z + dirZ * halfSpan * t + dirX * radius * 0.08 * fold;
				}

				// Crest glow at the ceiling line, palette dust in the drape, the darker
				// strand at the fall's bottom hem.
				BehaviorSupport.sendContained(level, ParticleTypes.GLOW, shape, center, radius,
						x, ceilingY, z, 1, 0.05, 0.1, 0.05, 0.0);
				BehaviorSupport.sendContained(level, variant == 3 ? lowerDust : dust, shape, center, radius,
						x, ceilingY - drape * 0.5, z, 1, 0.05, 0.15, 0.05, 0.0);
				if (variant == 3) {
					// The gradient pair: primary above, darker secondary already at mid-fall.
					BehaviorSupport.sendContained(level, dust, shape, center, radius,
							x, ceilingY - drape * 0.2, z, 1, 0.05, 0.1, 0.05, 0.0);
				} else {
					BehaviorSupport.sendContained(level, lowerDust, shape, center, radius,
							x, ceilingY - drape, z, 1, 0.05, 0.1, 0.05, 0.0);
				}

				if (variant == 6 && i % 8 == 0) {
					BehaviorSupport.sendContained(level, ParticleTypes.END_ROD, shape, center, radius,
							x, ceilingY + 0.2, z, 1, 0.04, 0.06, 0.04, 0.005);
				}
			}
		}
	}
}

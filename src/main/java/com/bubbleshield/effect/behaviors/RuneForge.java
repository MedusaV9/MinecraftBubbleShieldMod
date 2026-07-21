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
 * A floor-level rune forge: anvil stations near the center plane hold a
 * glowing dust billet, ENCHANTED_HIT hammer sparks land on the strike beat,
 * and every few beats the finished rune is released as ENCHANT glyphs drifting
 * up off the billet (routed through {@link BehaviorSupport#sendContained},
 * which hands the fly-toward form to the dip-safe path). Stateless -- the
 * forging rhythm derives entirely from gameTime.
 *
 * <ul>
 * <li>v0: one anvil at the center, steady four-beat forging</li>
 * <li>v1: twin anvils across the center, strikes alternating</li>
 * <li>v2: the master forge -- a heavier billet, bigger spark bursts, slow six-beat swing</li>
 * <li>v3: rapid tempo -- a strike every pulse, small sparks</li>
 * <li>v4: quenching station -- each released rune is followed by a splash hiss and white steam</li>
 * <li>v5: rune scriptorium -- glyphs release every fourth beat as a rising stacked spiral</li>
 * <li>v6: four anvils on a ring, the hammer marching around them</li>
 * </ul>
 */
public final class RuneForge implements InsideEffectBehavior {
	public static final String ID = "rune_forge";
	/**
	 * Worst case v6 (release pulse, strikes never coincide): 4 anvils x (billet 7
	 * + body 2) = 36 dust + 4 x 6 glyphs = 60 particles/pulse; strike pulses peak
	 * at 36 + 12 sparks = 48.
	 */
	private static final int MAX_SPARKS = 12;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int anvils = switch (variant) {
			case 1 -> 2;
			case 6 -> 4;
			default -> 1;
		};
		long beat = switch (variant) {
			case 2 -> 6L;
			case 3 -> 1L;
			default -> 4L;
		};
		long pulse = gameTime / 10L;
		double strength = Mth.clamp(def.behaviorStrength(), 0.7F, 1.3F);
		// The station sits just above the center plane (the dome floor), so the
		// forge reads as floor-mounted in every shape.
		double floorY = center.y + radius * 0.1;
		double billetHalf = radius * (variant == 2 ? 0.16 : 0.11) * strength;
		ParticleOptions billetDust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF,
				variant == 2 ? 1.3F : 1.0F);
		ParticleOptions bodyDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 1.2F);
		for (int a = 0; a < anvils; a++) {
			double stationAngle = Math.PI * 2.0 * a / anvils;
			double stationDist = anvils == 1 ? 0.0 : radius * 0.35;
			double ax = center.x + Math.cos(stationAngle) * stationDist;
			double az = center.z + Math.sin(stationAngle) * stationDist;
			// The billet: a glowing bar laid across the anvil face, always drawn.
			int barPoints = ctx.scaleCount(variant == 2 ? 9 : 7, 9);
			for (int k = 0; k < barPoints; k++) {
				double along = (k / (double) Math.max(1, barPoints - 1) - 0.5) * 2.0 * billetHalf;
				BehaviorSupport.sendContained(level, billetDust, shape, center, radius,
						ax + Math.cos(stationAngle + Math.PI / 2.0) * along, floorY + 0.2,
						az + Math.sin(stationAngle + Math.PI / 2.0) * along, 1, 0.02, 0.02, 0.02, 0.0);
			}

			// The anvil body: two darker palette points under the billet.
			BehaviorSupport.sendContained(level, bodyDust, shape, center, radius, ax, floorY, az, 1, 0.06, 0.04, 0.06, 0.0);
			BehaviorSupport.sendContained(level, bodyDust, shape, center, radius, ax, floorY + 0.1, az, 1, 0.04, 0.03, 0.04, 0.0);
			// The hammer strike: v1 alternates anvils, v6 marches around the ring.
			boolean struck = switch (variant) {
				case 1, 6 -> pulse % beat == 0L && pulse / beat % anvils == a;
				default -> pulse % beat == 0L;
			};
			if (struck) {
				int sparks = ctx.scaleCount(variant == 2 ? 8 : variant == 3 ? 3 : 6, MAX_SPARKS);
				BehaviorSupport.sendContained(level, ParticleTypes.ENCHANTED_HIT, shape, center, radius,
						ax, floorY + 0.35, az, sparks, 0.12, 0.08, 0.12, 0.05);
			}

			// The finished rune lifts off as ENCHANT glyphs converging above the billet.
			long releaseBeat = beat * (variant == 5 ? 1L : 2L);
			if (pulse % releaseBeat == releaseBeat - 1L) {
				int glyphs = ctx.scaleCount(variant == 2 ? 8 : 5, variant == 2 ? 10 : 6);
				if (variant == 5) {
					// The scriptorium stacks its release as a short rising spiral.
					for (int s = 0; s < glyphs; s++) {
						double spiralAngle = pulse * 0.7 + s * 1.1;
						BehaviorSupport.sendContained(level, ParticleTypes.ENCHANT, shape, center, radius,
								ax + Math.cos(spiralAngle) * 0.3, floorY + 0.5 + radius * 0.06 * s, az + Math.sin(spiralAngle) * 0.3,
								0, 0.4, -0.3, 0.4, 1.0);
					}
				} else {
					BehaviorSupport.sendContained(level, ParticleTypes.ENCHANT, shape, center, radius,
							ax, floorY + radius * 0.25, az, glyphs, 0.15, 0.1, 0.15, 0.4);
				}

				if (variant == 4) {
					// The quench barrel hisses beside the anvil right after release.
					double bx = ax + Math.cos(stationAngle + Math.PI) * radius * 0.12;
					double bz = az + Math.sin(stationAngle + Math.PI) * radius * 0.12;
					BehaviorSupport.sendContained(level, ParticleTypes.SPLASH, shape, center, radius,
							bx, floorY + 0.25, bz, ctx.scaleCount(4, 8), 0.1, 0.05, 0.1, 0.0);
					BehaviorSupport.sendContained(level, ParticleTypes.WHITE_SMOKE, shape, center, radius,
							bx, floorY + 0.5, bz, ctx.scaleCount(3, 6), 0.08, 0.15, 0.08, 0.01);
				}
			}
		}
	}
}

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
 * Three invisible belfries hanging in the upper bubble: each bell is a
 * swinging palette dust outline (a rim ring, a waist pair and an overshooting
 * secondary-dust clapper) pivoting below its ring-slotted hanger, pealing a
 * note-and-wax-off glint whenever the swing reaches its extreme. Swing phases
 * derive from gameTime (v5's change-ringing periods from
 * {@link BehaviorSupport#hash01}), so one shared instance rings every shield
 * with no fields and no cleanup.
 *
 * <p>Worst-case budget (v6 pealing, countMult 3): 5 bells x (rim 8 + waist 2 +
 * clapper 1 + note 2 + wax-off 2) = 75 particles/pulse (&lt;= 128).
 *
 * <ul>
 * <li>v0: three bells swinging in step</li>
 * <li>v1: a rippling round (staggered phases, peals chase around the ring)</li>
 * <li>v2: the great bell (one huge central bell, slow deep peals)</li>
 * <li>v3: ghost ringers (a soul wisp tugging a dust rope under each bell)</li>
 * <li>v4: the muffled toll (smoke instead of glints, low rare peals)</li>
 * <li>v5: change-ringing (hash-seeded swing periods, peals interleave)</li>
 * <li>v6: the full carillon (five bells, doubled peal glints)</li>
 * </ul>
 */
public final class PhantomBells implements InsideEffectBehavior {
	public static final String ID = "phantom_bells";
	/** Worst case v6 pealing: 5 bells x (rim 8 + waist 2 + clapper 1 + note 2 + wax-off 2) = 75 particles/pulse. */
	private static final int MAX_RIM_POINTS = 8;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int bells = switch (variant) {
			case 2 -> 1;
			case 6 -> 5;
			default -> 3;
		};
		int rimPoints = ctx.scaleCount(6, MAX_RIM_POINTS);
		double bellLength = Math.max(1.2, radius * (variant == 2 ? 0.2 : 0.12)) * Mth.clamp(def.behaviorStrength(), 0.7F, 1.3F);
		double rimRadius = bellLength * 0.45;
		double hangerY = center.y + radius * 0.55;
		ParticleOptions bronze = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.0F);
		ParticleOptions clapperDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.7F);
		for (int b = 0; b < bells; b++) {
			double slot = Math.PI * 2.0 * b / bells;
			Vec3 pivot = variant == 2
					? new Vec3(center.x, hangerY, center.z)
					: new Vec3(center.x + Math.cos(slot) * radius * 0.55, hangerY, center.z + Math.sin(slot) * radius * 0.55);
			// The swing period: v2 tolls slowly, v5 rings changes on hash-seeded
			// periods, the rest share one period with per-variant phase offsets.
			double period = switch (variant) {
				case 2 -> 120.0;
				case 5 -> 60.0 + 40.0 * BehaviorSupport.hash01(BehaviorSupport.mix(b * 419L));
				default -> 80.0;
			};
			double phase = Math.PI * 2.0 * (gameTime / period) + (variant == 1 ? Math.PI * 2.0 * b / bells : 0.0);
			double swing = Math.sin(phase) * (variant == 4 ? 0.35 : 0.7);
			// The swing plane points at the bubble center (unit +x for the great bell).
			double dirX = variant == 2 ? 1.0 : -Math.cos(slot);
			double dirZ = variant == 2 ? 0.0 : -Math.sin(slot);
			// Bell axis and its in-plane normal, both unit by construction.
			double axX = Math.sin(swing) * dirX;
			double axY = -Math.cos(swing);
			double axZ = Math.sin(swing) * dirZ;
			double upX = Math.cos(swing) * dirX;
			double upY = Math.sin(swing);
			double upZ = Math.cos(swing) * dirZ;
			// The horizontal tangent completes the rim frame.
			double tanX = -dirZ;
			double tanZ = dirX;
			for (int k = 0; k < Math.max(1, rimPoints); k++) {
				double a = Math.PI * 2.0 * k / Math.max(1, rimPoints);
				double ox = Math.cos(a) * upX + Math.sin(a) * tanX;
				double oy = Math.cos(a) * upY;
				double oz = Math.cos(a) * upZ + Math.sin(a) * tanZ;
				BehaviorSupport.sendContained(level, bronze, shape, center, radius,
						pivot.x + axX * bellLength + ox * rimRadius,
						pivot.y + axY * bellLength + oy * rimRadius,
						pivot.z + axZ * bellLength + oz * rimRadius, 1, 0.03, 0.03, 0.03, 0.0);
			}

			// The waist pair halfway up the flank.
			for (int w = -1; w <= 1; w += 2) {
				BehaviorSupport.sendContained(level, bronze, shape, center, radius,
						pivot.x + axX * bellLength * 0.55 + w * tanX * rimRadius * 0.6,
						pivot.y + axY * bellLength * 0.55,
						pivot.z + axZ * bellLength * 0.55 + w * tanZ * rimRadius * 0.6, 1, 0.03, 0.03, 0.03, 0.0);
			}

			// The clapper overshoots the swing by 30%.
			double clapperSwing = swing * 1.3;
			BehaviorSupport.sendContained(level, clapperDust, shape, center, radius,
					pivot.x + Math.sin(clapperSwing) * dirX * bellLength * 0.95,
					pivot.y - Math.cos(clapperSwing) * bellLength * 0.95,
					pivot.z + Math.sin(clapperSwing) * dirZ * bellLength * 0.95, 1, 0.02, 0.02, 0.02, 0.0);
			if (variant == 3) {
				// The ghost ringer hauls a secondary-dust rope under the bell
				// (clamped above the center plane so it stays dome-natural).
				double tug = 0.5 + 0.5 * Math.sin(phase + Math.PI);
				double ropeY = Math.max(center.y + 1.2, pivot.y - bellLength - 0.6 - tug * 0.5);
				BehaviorSupport.sendContained(level, clapperDust, shape, center, radius,
						pivot.x, ropeY, pivot.z, 2, 0.04, 0.3, 0.04, 0.0);
				BehaviorSupport.sendContained(level, ParticleTypes.SOUL, shape, center, radius,
						pivot.x, Math.max(center.y + 0.4, ropeY - 0.8), pivot.z, 1, 0.06, 0.1, 0.06, 0.01);
			}

			// The peal fires at the swing extremes (|sin| near 1).
			if (Math.abs(Math.sin(phase)) > 0.9) {
				double mouthX = pivot.x + axX * bellLength;
				double mouthY = pivot.y + axY * bellLength;
				double mouthZ = pivot.z + axZ * bellLength;
				BehaviorSupport.sendContained(level, ParticleTypes.NOTE, shape, center, radius,
						mouthX, mouthY + 0.3, mouthZ, variant == 6 ? 2 : 1, 0.15, 0.15, 0.15, 0.0);
				if (variant == 4) {
					BehaviorSupport.sendContained(level, ParticleTypes.SMOKE, shape, center, radius,
							mouthX, mouthY, mouthZ, 1, 0.1, 0.1, 0.1, 0.01);
				} else {
					BehaviorSupport.sendContained(level, ParticleTypes.WAX_OFF, shape, center, radius,
							mouthX, mouthY, mouthZ, variant == 6 ? 2 : 1, 0.12, 0.12, 0.12, 0.0);
				}
			}
		}

		// The audible resonance rings on its own cadence (the swing-extreme
		// pulses need not align with a fixed tick grid).
		if (gameTime % (variant == 4 ? 160L : 80L) == 0L) {
			level.playSound(null, center.x, hangerY, center.z, SoundEvents.BELL_RESONATE, SoundSource.AMBIENT,
					variant == 4 ? 0.2F : 0.3F, variant == 2 || variant == 4 ? 0.7F : 1.4F);
		}
	}
}

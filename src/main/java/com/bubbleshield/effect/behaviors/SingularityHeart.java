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
 * A singularity heart accreting at the bubble's core: palette-dust streams
 * (arms alternate the primary and secondary strands) spiral inward onto a
 * squid-ink event horizon hovering above the projector, while electric-spark
 * jets fire from both poles (the down jet clipped above the center plane, so
 * the whole accretor is dome-safe). Purely deterministic particles -- no
 * entities, no state, no cleanup.
 *
 * <ul>
 * <li>v0: a four-arm accretor</li>
 * <li>v1: a six-arm fast spinner</li>
 * <li>v2: the pulsar (jets only on the beat, but doubled in length)</li>
 * <li>v3: counter-rotating twin disks stacked around the horizon</li>
 * <li>v4: the heavy core (a fatter horizon feeding on reverse-portal infall)</li>
 * <li>v5: a tilted accretor (the disk precesses, beads riding its wobble)</li>
 * <li>v6: the unstable heart (an enchanted-hit shudder ring every eighth pulse)</li>
 * </ul>
 */
public final class SingularityHeart implements InsideEffectBehavior {
	public static final String ID = "singularity_heart";
	/** Worst case v1/v3 (countMult 3): 72 arm dust + core 4 + jets 2x6 + v4 infall 6 or v6 ring 8 = <=96 particles/pulse. */
	private static final int MAX_ARM_BEADS = 72;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int arms = variant == 1 || variant == 3 ? 6 : 4;
		int beadBudget = MAX_ARM_BEADS / arms;
		int beads = ctx.scaleCount(Mth.clamp((int) (radius * 0.4F * def.behaviorStrength()), 6, beadBudget), beadBudget);
		long pulseIndex = gameTime / 10L;
		double spin = pulseIndex * (variant == 1 ? 0.35 : 0.15);
		double coreY = center.y + radius * 0.35;
		double reach = radius * 0.6;
		double coreR = radius * (variant == 4 ? 0.08 : 0.05) + 0.3;
		ParticleOptions armPrimary = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 0.8F);
		ParticleOptions armSecondary = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.8F);
		// Beads slide one bead-slot inward across every 4-pulse cycle, so the
		// streams visibly fall onto the horizon instead of standing still.
		double slide = (pulseIndex % 4L) / 4.0;
		for (int a = 0; a < arms; a++) {
			double armAngle = Math.PI * 2.0 * a / arms + spin * (variant == 3 && a % 2 == 1 ? -1.0 : 1.0);
			double plane = variant == 3 ? (a % 2 == 0 ? radius * 0.08 : -radius * 0.08) : 0.0;
			for (int b = 0; b < beads; b++) {
				double t = (b + slide) / beads;
				double dist = coreR + (reach - coreR) * t;
				// The inward winding: closer beads have swung further around.
				double angle = armAngle + (1.0 - t) * 2.4 * (variant == 3 && a % 2 == 1 ? -1.0 : 1.0);
				double y = coreY + plane;
				if (variant == 5) {
					// The precessing disk: a tilt plane slowly wobbling around the core.
					y += Math.cos(angle + pulseIndex * 0.07) * dist * 0.35;
				}

				BehaviorSupport.sendContained(level, a % 2 == 0 ? armPrimary : armSecondary, shape, center, radius,
						center.x + Math.cos(angle) * dist, Math.max(center.y + 0.1, y), center.z + Math.sin(angle) * dist,
						1, 0.02, 0.02, 0.02, 0.0);
			}
		}

		// The event horizon: a tight ink knot that never lets light settle.
		BehaviorSupport.sendContained(level, ParticleTypes.SQUID_INK, shape, center, radius,
				center.x, coreY, center.z, ctx.scaleCount(3, 4), coreR * 0.4, coreR * 0.4, coreR * 0.4, 0.01);
		if (variant == 4) {
			// Infall flecks sucked in between the arms.
			int infall = ctx.scaleCount(4, 6);
			for (int i = 0; i < infall; i++) {
				double angle = spin * 1.7 + Math.PI * 2.0 * i / infall + Math.PI / arms;
				double dist = coreR + (reach - coreR) * (0.3 + 0.5 * BehaviorSupport.hash01(BehaviorSupport.mix(pulseIndex * 13L + i)));
				BehaviorSupport.sendContained(level, ParticleTypes.REVERSE_PORTAL, shape, center, radius,
						center.x + Math.cos(angle) * dist, coreY - 0.2, center.z + Math.sin(angle) * dist, 1, 0.05, 0.05, 0.05, 0.01);
			}
		} else if (variant == 6 && pulseIndex % 8L == 0L) {
			// The shudder: a flat sparkle ring snapping out around the horizon.
			int ring = ctx.scaleCount(6, 8);
			for (int i = 0; i < ring; i++) {
				double angle = Math.PI * 2.0 * i / ring;
				BehaviorSupport.sendContained(level, ParticleTypes.ENCHANTED_HIT, shape, center, radius,
						center.x + Math.cos(angle) * coreR * 2.5, coreY, center.z + Math.sin(angle) * coreR * 2.5, 1, 0.05, 0.05, 0.05, 0.02);
			}
		}

		// The polar jets: sparks marching out along the spin axis; the down jet
		// clips above the center plane so a dome never truncates it mid-flight,
		// and the up jet's tip (~0.9r at small radii) rides the containment sweep.
		boolean jetsFiring = variant != 2 || pulseIndex % 4L == 0L;
		if (jetsFiring) {
			int jetLen = ctx.scaleCount(variant == 2 ? 6 : 4, 6);
			double step = radius * 0.08;
			for (int j = 1; j <= jetLen; j++) {
				BehaviorSupport.sendContained(level, ParticleTypes.ELECTRIC_SPARK, shape, center, radius,
						center.x, coreY + coreR + j * step, center.z, 1, 0.04, 0.08, 0.04, 0.01);
				BehaviorSupport.sendContained(level, ParticleTypes.ELECTRIC_SPARK, shape, center, radius,
						center.x, Math.max(center.y + 0.1, coreY - coreR - j * step), center.z, 1, 0.04, 0.08, 0.04, 0.01);
			}
		}
	}
}

package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Anglerfish lures hanging from the bubble ceiling: glow bulbs swaying on
 * palette-dust stalks (secondary-strand beads under a primary attachment
 * mote), and on the beat an unseen squid-ink maw snaps shut around one lure.
 * Purely hash-slotted particles -- no entities, no state, no cleanup.
 *
 * <ul>
 * <li>v0: three patient lures</li>
 * <li>v1: five twitchy lures (double sway, half-length re-slot cycle)</li>
 * <li>v2: one deep matriarch (a long six-bead stalk, a triple-glow bulb, a twelve-point maw)</li>
 * <li>v3: false stars (bulbs flicker off on odd pulses; the stalks persist)</li>
 * <li>v4: chain lures (glow-ink links alternating with the dust beads)</li>
 * <li>v5: a hunting pair (two maws snap on the same beat)</li>
 * <li>v6: the abyss chandelier (lures parked on a slowly turning ceiling ring)</li>
 * </ul>
 */
public final class AnglerfishLures implements InsideEffectBehavior {
	public static final String ID = "anglerfish_lures";
	/** Worst case v5 (countMult 3, snap beat): 2 snapped x (rig 7 + maw 10) + 3 idle x (rig 7 + twinkle 1) = 58 particles/pulse. */
	private static final int MAX_LURES = 5;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int lures = ctx.scaleCount(switch (variant) {
			case 1 -> 5;
			case 2 -> 1;
			case 5, 6 -> 4;
			default -> 3;
		}, variant == 2 ? 1 : MAX_LURES);
		long pulseIndex = gameTime / 10L;
		long slotPulses = variant == 1 ? 8L : 16L;
		long slotCycle = pulseIndex / slotPulses;
		long beatPhase = pulseIndex % 4L;
		ParticleOptions attachDust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 0.9F);
		ParticleOptions beadDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.7F);
		// The maw victim this beat: hash-picked so snaps wander between lures.
		int victim = (int) (BehaviorSupport.hash01(BehaviorSupport.mix(pulseIndex / 4L * 47L)) * lures);
		for (int l = 0; l < lures; l++) {
			Vec3 attach = attachPoint(center, radius, l, slotCycle, variant, pulseIndex);
			double sway = Math.sin(pulseIndex * 0.5 + l * 1.7) * (variant == 1 ? 0.5 : 0.25);
			int beads = variant == 2 ? 6 : 4;
			double stalkLen = radius * (variant == 2 ? 0.35 : 0.2);
			// The ceiling attachment mote (primary strand), then the stalk beads
			// swaying more the farther they hang from the attachment.
			BehaviorSupport.sendContained(level, attachDust, shape, center, radius,
					attach.x, attach.y, attach.z, 1, 0.04, 0.04, 0.04, 0.0);
			for (int b = 1; b <= beads; b++) {
				double frac = b / (double) beads;
				ParticleOptions bead = variant == 4 && b % 2 == 0 ? ParticleTypes.GLOW_SQUID_INK : beadDust;
				BehaviorSupport.sendContained(level, bead, shape, center, radius,
						attach.x + sway * frac * frac, attach.y - stalkLen * frac, attach.z + sway * 0.6 * frac * frac,
						1, 0.02, 0.03, 0.02, 0.0);
			}

			double lx = attach.x + sway;
			double ly = attach.y - stalkLen;
			double lz = attach.z + sway * 0.6;
			boolean bulbLit = variant != 3 || pulseIndex % 2L == 0L;
			if (bulbLit) {
				BehaviorSupport.sendContained(level, ParticleTypes.GLOW, shape, center, radius,
						lx, ly, lz, variant == 2 ? 3 : 2, 0.06, 0.06, 0.06, 0.005);
			}

			boolean snapped = beatPhase == 0L && (l == victim || (variant == 5 && l == (victim + lures / 2) % lures));
			if (snapped) {
				// The maw: two ink jaw arcs closing around the bulb, and a dark puff.
				int jawPoints = variant == 2 ? 12 : 8;
				double gape = variant == 2 ? 1.4 : 0.8;
				for (int i = 0; i < jawPoints; i++) {
					double a = Math.PI * i / (jawPoints - 1.0);
					double jx = Math.cos(a) * gape;
					double jy = Math.sin(a) * gape * 0.6;
					BehaviorSupport.sendContained(level, ParticleTypes.SQUID_INK, shape, center, radius,
							lx + jx, ly + (i % 2 == 0 ? jy : -jy), lz, 1, 0.04, 0.04, 0.04, 0.01);
				}

				BehaviorSupport.sendContained(level, ParticleTypes.SQUID_INK, shape, center, radius,
						lx, ly, lz, 2, gape * 0.3, gape * 0.2, gape * 0.3, 0.02);
			} else if (bulbLit && (pulseIndex + l) % 3L == 0L) {
				// An idle twinkle so the lure reads as bait, not a lamp.
				BehaviorSupport.sendContained(level, ParticleTypes.GLOW_SQUID_INK, shape, center, radius,
						lx, ly - 0.15, lz, 1, 0.05, 0.05, 0.05, 0.0);
			}
		}
	}

	/**
	 * The hash-slotted ceiling attachment: within 0.35r horizontally at
	 * 0.6r..0.75r up, so attachment distance stays under ~0.83r and the hanging
	 * stalk keeps the whole rig inside the shell. v6 parks the attachments on a
	 * slowly turning ceiling ring instead.
	 */
	private static Vec3 attachPoint(Vec3 center, float radius, int lure, long slotCycle, int variant, long pulseIndex) {
		if (variant == 6) {
			double angle = pulseIndex * 0.03 + Math.PI * 2.0 * lure / 4.0;
			return new Vec3(center.x + Math.cos(angle) * radius * 0.35, center.y + radius * 0.7,
					center.z + Math.sin(angle) * radius * 0.35);
		}

		long seed = BehaviorSupport.mix(slotCycle * 769L + lure * 11L);
		double angle = BehaviorSupport.hash01(seed) * Math.PI * 2.0;
		double dist = Math.sqrt(BehaviorSupport.hash01(seed + 1L)) * radius * 0.35;
		double y = radius * (0.6 + 0.15 * BehaviorSupport.hash01(seed + 2L));
		return new Vec3(center.x + Math.cos(angle) * dist, center.y + y, center.z + Math.sin(angle) * dist);
	}
}

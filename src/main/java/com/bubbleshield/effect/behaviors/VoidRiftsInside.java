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
 * Vertical void rifts tearing open inside the bubble: each hash-slotted slit
 * is a portal-mote seam edged in squid ink that widens over its cycle, leaks
 * reverse-portal flecks while agape, then zips shut in a run of enchanted-hit
 * sparks. A palette dust seam mote (primary) and cap mote (secondary strand)
 * ride every rift, so the owner recolor tints the tear. Purely hash-seeded
 * particles -- no entities, no state, no cleanup.
 *
 * <ul>
 * <li>v0: three slow rifts</li>
 * <li>v1: five brief slits on a half-length cycle</li>
 * <li>v2: one grand rift, taller and wider with a doubled leak</li>
 * <li>v3: paired rifts (each slit has a twin mirrored through the center axis)</li>
 * <li>v4: crackling rifts (enchanted-hit flecks along the edges every pulse)</li>
 * <li>v5: inverted rifts (ink seam, portal fringe, leaks sinking instead of rising)</li>
 * <li>v6: a rift ring (slits parked on a slowly turning mid-height ring)</li>
 * </ul>
 */
public final class VoidRiftsInside implements InsideEffectBehavior {
	public static final String ID = "void_rifts_inside";
	/** Worst case v0/v5/v6 (countMult 3, zip pulse): 5 rifts x (seam 6 + zip 6 + fringe 6 + dust 2) = 100 particles/pulse. */
	private static final int MAX_RIFTS = 5;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int rifts = ctx.scaleCount(switch (variant) {
			case 1 -> 5;
			case 2 -> 1;
			case 3 -> 2;
			case 6 -> 4;
			default -> 3;
		}, switch (variant) {
			case 2 -> 1;
			case 3 -> 2;
			case 4 -> 4;
			default -> MAX_RIFTS;
		});
		long pulseIndex = gameTime / 10L;
		// v1 slits live half as long: widen for 3 pulses, zip on the 4th.
		long cycle = variant == 1 ? 4L : 8L;
		ParticleOptions seamDust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.0F);
		ParticleOptions capDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.7F);
		for (int r = 0; r < rifts; r++) {
			long phase = (pulseIndex + r * 3L) % cycle;
			boolean zipping = phase == cycle - 1L;
			double open = zipping ? 0.15 : phase / (double) (cycle - 2L);
			Vec3 pos = riftAnchor(center, radius, r, (pulseIndex + r * 3L) / cycle, variant, pulseIndex);
			emitRift(level, shape, center, radius, ctx, variant, pos, open, zipping, seamDust, capDust);
			if (variant == 3) {
				// The twin tears open mirrored through the center axis.
				Vec3 mirrored = new Vec3(2.0 * center.x - pos.x, pos.y, 2.0 * center.z - pos.z);
				emitRift(level, shape, center, radius, ctx, variant, mirrored, open, zipping, seamDust, capDust);
			}
		}
	}

	private static void emitRift(ServerLevel level, ShieldShape shape, Vec3 center, float radius,
			ContextState ctx, int variant, Vec3 pos, double open, boolean zipping, ParticleOptions seamDust, ParticleOptions capDust) {
		int seamPoints = variant == 2 ? 10 : variant == 1 ? 4 : 6;
		double height = radius * (variant == 2 ? 0.6 : variant == 1 ? 0.3 : 0.45);
		double width = (0.15 + open * (variant == 2 ? 1.2 : 0.7)) * Math.min(radius * 0.1, 1.0);
		// The slit faces the projector so it reads as a flat tear, not a column.
		double facing = Math.atan2(center.z - pos.z, center.x - pos.x) + Math.PI / 2.0;
		double wx = Math.cos(facing) * width;
		double wz = Math.sin(facing) * width;
		ParticleOptions seam = variant == 5 ? ParticleTypes.SQUID_INK : ParticleTypes.PORTAL;
		ParticleOptions fringe = variant == 5 ? ParticleTypes.PORTAL : ParticleTypes.SQUID_INK;
		for (int i = 0; i < seamPoints; i++) {
			double frac = (i + 0.5) / seamPoints;
			double y = pos.y + height * frac;
			// The seam bulges widest at mid-height, tapering to the tips.
			double bulge = Math.sin(frac * Math.PI);
			BehaviorSupport.sendContained(level, seam, shape, center, radius,
					pos.x, y, pos.z, 1, width * bulge * 0.3, 0.04, width * bulge * 0.3, 0.0);
			if (zipping) {
				BehaviorSupport.sendContained(level, ParticleTypes.ENCHANTED_HIT, shape, center, radius,
						pos.x, y, pos.z, 1, 0.06, 0.06, 0.06, 0.02);
			}

			if (i % 2 == 0 || variant == 2) {
				// The ink fringe hugs both lips of the slit.
				BehaviorSupport.sendContained(level, fringe, shape, center, radius,
						pos.x + wx * bulge, y, pos.z + wz * bulge, 1, 0.03, 0.05, 0.03, 0.0);
				BehaviorSupport.sendContained(level, fringe, shape, center, radius,
						pos.x - wx * bulge, y, pos.z - wz * bulge, 1, 0.03, 0.05, 0.03, 0.0);
			}
		}

		if (!zipping && open > 0.3) {
			// The agape rift leaks reverse-portal flecks out of the seam.
			int leak = ctx.scaleCount(variant == 2 ? 6 : 3, variant == 2 ? 6 : 3);
			double leakLift = variant == 5 ? -height * 0.2 : height * 0.2;
			for (int i = 0; i < leak; i++) {
				double frac = BehaviorSupport.hash01(BehaviorSupport.mix((long) (pos.x * 31.0) + i * 7L));
				// v5's sinking leaks stay above the slit base (itself above the plane).
				BehaviorSupport.sendContained(level, ParticleTypes.REVERSE_PORTAL, shape, center, radius,
						pos.x + wx * 1.5 * (i % 2 == 0 ? 1.0 : -1.0),
						Math.max(pos.y + 0.1, pos.y + height * frac + leakLift),
						pos.z + wz * 1.5 * (i % 2 == 0 ? 1.0 : -1.0), 1, 0.08, 0.1, 0.08, 0.01);
			}
		}

		if (variant == 4) {
			// Crackle: edge flecks every pulse, not just at the zip.
			BehaviorSupport.sendContained(level, ParticleTypes.ENCHANTED_HIT, shape, center, radius,
					pos.x, pos.y + height * 0.5, pos.z, 2, width * 1.2, height * 0.3, width * 1.2, 0.02);
		}

		// The recolor accents: a primary seam mote and a secondary cap mote.
		BehaviorSupport.sendContained(level, seamDust, shape, center, radius,
				pos.x, pos.y + height * 0.5, pos.z, 1, 0.05, 0.1, 0.05, 0.0);
		BehaviorSupport.sendContained(level, capDust, shape, center, radius,
				pos.x, pos.y + height, pos.z, 1, 0.04, 0.04, 0.04, 0.0);
	}

	/**
	 * The hash-slotted rift anchor (slit base): within 0.6r horizontally with the
	 * base at 0.1r..0.25r above the plane, so even the grand rift's 0.6r-tall tip
	 * reaches only ~0.9r before the containment sweep. v6 parks the slits on a
	 * slowly turning mid-height ring instead.
	 */
	private static Vec3 riftAnchor(Vec3 center, float radius, int rift, long slotCycle, int variant, long pulseIndex) {
		if (variant == 6) {
			double angle = pulseIndex * 0.04 + Math.PI * 2.0 * rift / 4.0;
			return new Vec3(center.x + Math.cos(angle) * radius * 0.55, center.y + radius * 0.2,
					center.z + Math.sin(angle) * radius * 0.55);
		}

		long seed = BehaviorSupport.mix(slotCycle * 641L + rift * 13L);
		double angle = BehaviorSupport.hash01(seed) * Math.PI * 2.0;
		double dist = Math.sqrt(BehaviorSupport.hash01(seed + 1L)) * radius * (variant == 2 ? 0.3 : 0.6);
		double y = radius * (0.1 + 0.15 * BehaviorSupport.hash01(seed + 2L));
		return new Vec3(center.x + Math.cos(angle) * dist, center.y + y, center.z + Math.sin(angle) * dist);
	}
}

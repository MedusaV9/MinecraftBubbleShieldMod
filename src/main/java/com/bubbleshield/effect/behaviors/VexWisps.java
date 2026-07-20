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
 * Vex/allay-like wisps darting through the bubble: each wisp flies straight
 * between hash-seeded waypoints (re-aimed every dart segment), trailing a short
 * afterglow, all purely particles -- no entities, no state, no cleanup. A
 * palette dust mote rides every wisp so the owner color override recolors the
 * swarm.
 *
 * <ul>
 * <li>v0: pale vex darts (end-rod heads, glow trails)</li>
 * <li>v1: allay shimmer (glow heads, a note sparkle at each dart's apex)</li>
 * <li>v2: mirrored twin dancers (every wisp has a twin reflected through the axis)</li>
 * <li>v3: aggressive zigzag (short darts, enchanted-hit flecks on re-aim)</li>
 * <li>v4: a slow halo ring of wisps orbiting at mid-height</li>
 * <li>v5: soul-tinged wisps with soul-fire trails</li>
 * <li>v6: a mote swarm (twice the wisps, small dust heads, wax-off glints)</li>
 * </ul>
 */
public final class VexWisps implements InsideEffectBehavior {
	public static final String ID = "vex_wisps";
	/** Worst case v2: 8 wisps x 2 mirrored positions x (head 2 + trail 3 + dust 1) = 96 particles/pulse. */
	private static final int MAX_WISPS = 24;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int base = Mth.clamp((int) (radius * 1.2F * def.behaviorStrength()), 3, variant == 2 ? 8 : 12);
		int wisps = ctx.scaleCount(variant == 6 ? base * 2 : base, variant == 6 ? MAX_WISPS : variant == 2 ? 8 : 12);
		// v3 re-aims twice as often, so the zigzag reads as agitated.
		long segTicks = variant == 3 ? 20L : 40L;
		long segment = gameTime / segTicks;
		double t = (gameTime % segTicks) / (double) segTicks;
		ParticleOptions dustMote = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, variant == 6 ? 0.5F : 0.7F);
		for (int w = 0; w < wisps; w++) {
			Vec3 pos;
			if (variant == 4) {
				// The halo: wisps parked on a slowly turning mid-height ring.
				double angle = gameTime / 10.0 * 0.06 + Math.PI * 2.0 * w / wisps;
				pos = new Vec3(
						center.x + Math.cos(angle) * radius * 0.6,
						center.y + radius * 0.45,
						center.z + Math.sin(angle) * radius * 0.6);
			} else {
				Vec3 from = dartPoint(center, radius, w, segment);
				Vec3 to = dartPoint(center, radius, w, segment + 1L);
				pos = from.lerp(to, t);
			}

			emitWisp(level, shape, center, radius, def, ctx, variant, pos, t, dustMote);
			if (variant == 2) {
				// The mirror twin dances the same dart reflected through the center axis.
				Vec3 mirrored = new Vec3(2.0 * center.x - pos.x, pos.y, 2.0 * center.z - pos.z);
				emitWisp(level, shape, center, radius, def, ctx, variant, mirrored, t, dustMote);
			}
		}
	}

	private static void emitWisp(ServerLevel level, ShieldShape shape, Vec3 center, float radius,
			EffectDefinition def, ContextState ctx, int variant, Vec3 pos, double t, ParticleOptions dustMote) {
		ParticleOptions head = switch (variant) {
			case 1 -> ParticleTypes.GLOW;
			case 6 -> dustMote;
			default -> ParticleTypes.END_ROD;
		};
		BehaviorSupport.sendContained(level, head, shape, center, radius,
				pos.x, pos.y, pos.z, variant == 6 ? 1 : 2, 0.05, 0.05, 0.05, 0.01);
		// The recolor accent: one palette dust mote on every wisp, every variant.
		BehaviorSupport.sendContained(level, dustMote, shape, center, radius,
				pos.x, pos.y - 0.15, pos.z, 1, 0.06, 0.06, 0.06, 0.0);
		if (variant != 6) {
			ParticleOptions trail = variant == 5 ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.GLOW;
			for (int k = 1; k <= 3; k++) {
				// A short afterglow hanging slightly behind and below the head.
				BehaviorSupport.sendContained(level, trail, shape, center, radius,
						pos.x, pos.y - 0.12 * k, pos.z, 1, 0.08, 0.04, 0.08, 0.0);
			}
		} else if ((long) (t * 4.0) % 2L == 0L) {
			BehaviorSupport.sendContained(level, ParticleTypes.WAX_OFF, shape, center, radius,
					pos.x, pos.y + 0.2, pos.z, 1, 0.1, 0.1, 0.1, 0.0);
		}

		if (variant == 1 && t == 0.5) {
			// The dart apex sparkle (t steps in quarters, so 0.5 is hit exactly).
			BehaviorSupport.sendContained(level, ParticleTypes.NOTE, shape, center, radius,
					pos.x, pos.y + 0.3, pos.z, 1, 0.05, 0.05, 0.05, 0.0);
		} else if (variant == 3 && t == 0.0) {
			// Direction-change fleck at every re-aim.
			BehaviorSupport.sendContained(level, ParticleTypes.ENCHANTED_HIT, shape, center, radius,
					pos.x, pos.y, pos.z, 2, 0.1, 0.1, 0.1, 0.02);
		}
	}

	/**
	 * The hash-seeded dart waypoint for one wisp and segment: inside 0.75r
	 * horizontally and 0.1r..0.6r above the center plane (dome-safe by
	 * construction, max reach ~0.96r before the containment sweep).
	 */
	private static Vec3 dartPoint(Vec3 center, float radius, int wisp, long segment) {
		long seed = BehaviorSupport.mix(segment * 197L + wisp);
		double angle = BehaviorSupport.hash01(seed) * Math.PI * 2.0;
		double dist = Math.sqrt(BehaviorSupport.hash01(seed + 1L)) * radius * 0.75;
		double y = radius * (0.1 + 0.5 * BehaviorSupport.hash01(seed + 2L));
		return new Vec3(center.x + Math.cos(angle) * dist, center.y + y, center.z + Math.sin(angle) * dist);
	}
}

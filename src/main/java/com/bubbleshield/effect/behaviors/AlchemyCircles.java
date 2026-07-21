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
 * Nested transmutation circles hovering low over the projector: concentric
 * dust rings counter-rotate (marked glyph nodes make the spin readable), WITCH
 * and GLOW flecks drift off the inscription, and the working flashes at the
 * center whenever the ring glyphs align. Stateless -- ring phases and fleck
 * placement derive from gameTime and {@link BehaviorSupport#hash01}.
 *
 * <ul>
 * <li>v0: two counter-rotating rings, four glyph nodes each</li>
 * <li>v1: three nested rings, alternating spin</li>
 * <li>v2: the grand array -- wider rings joined by four dust spokes</li>
 * <li>v3: unstable working -- rings wobble vertically, flash twice as often</li>
 * <li>v4: a tilted double circle, halves offset in height</li>
 * <li>v5: the slow seal -- heavy dust, sparse glyphs, rare but bigger flash</li>
 * <li>v6: a glyph fountain -- flecks release upward from the inner ring each alignment</li>
 * </ul>
 */
public final class AlchemyCircles implements InsideEffectBehavior {
	public static final String ID = "alchemy_circles";
	/** Worst case v2: 80 ring dust + 2 x (4 witch + 4 glow) flecks + 12 spoke dust + flash (12 + 6) = 126 particles/pulse. */
	private static final int MAX_RING_POINTS = 80;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int rings = variant == 1 ? 3 : 2;
		double strength = Mth.clamp(def.behaviorStrength(), 0.7F, 1.3F);
		double outerR = radius * (variant == 2 ? 0.6 : 0.45) * strength;
		double planeY = center.y + radius * 0.12;
		double spin = gameTime / 10.0 * (variant == 5 ? 0.04 : 0.12);
		long pulse = gameTime / 10L;
		long flashPeriod = switch (variant) {
			case 3 -> 4L;
			case 5 -> 16L;
			default -> 8L;
		};
		ParticleOptions inkDust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF,
				variant == 5 ? 1.4F : 1.0F);
		ParticleOptions sealDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 1.2F);
		int budget = MAX_RING_POINTS / rings;
		for (int ring = 0; ring < rings; ring++) {
			double rr = outerR * (1.0 - 0.35 * ring);
			double dir = ring % 2 == 0 ? 1.0 : -1.0;
			double phase = spin * dir;
			double ringY = planeY;
			if (variant == 3) {
				// The unstable working: each ring wobbles on its own beat.
				ringY += Math.sin(gameTime / 10.0 * 0.9 + ring * 2.1) * radius * 0.05;
			}

			int points = ctx.scaleCount(Mth.clamp((int) (Math.PI * 2.0 * rr / 0.5), 10, variant == 5 ? budget / 2 : budget), budget);
			int nodes = variant == 5 ? 3 : 4;
			for (int k = 0; k < points; k++) {
				double a = phase + Math.PI * 2.0 * k / points;
				double y = ringY;
				if (variant == 4) {
					// The tilted circle: opposite halves ride high and low.
					y += Math.sin(a) * radius * 0.08 + (ring == 1 ? radius * 0.06 : 0.0);
				}

				// Glyph nodes (every points/nodes-th point) use the darker seal strand.
				boolean node = k % Math.max(1, points / nodes) == 0;
				BehaviorSupport.sendContained(level, node ? sealDust : inkDust, shape, center, radius,
						center.x + Math.cos(a) * rr, y, center.z + Math.sin(a) * rr, 1, 0.02, 0.02, 0.02, 0.0);
			}

			// Inscription flecks: WITCH ink motes and a GLOW at a wandering glyph.
			double fleckA = phase + Math.PI * 2.0 * BehaviorSupport.hash01(pulse * 53L + ring);
			BehaviorSupport.sendContained(level, ParticleTypes.WITCH, shape, center, radius,
					center.x + Math.cos(fleckA) * rr, ringY + 0.15, center.z + Math.sin(fleckA) * rr,
					ctx.scaleCount(2, 4), 0.08, 0.05, 0.08, 0.0);
			BehaviorSupport.sendContained(level, ParticleTypes.GLOW, shape, center, radius,
					center.x + Math.cos(fleckA + Math.PI) * rr, ringY + 0.1, center.z + Math.sin(fleckA + Math.PI) * rr,
					ctx.scaleCount(2, 4), 0.06, 0.04, 0.06, 0.0);
		}

		if (variant == 2) {
			// The grand array's four spokes join the outer ring to the center.
			for (int s = 0; s < 4; s++) {
				double a = spin + Math.PI / 2.0 * s + Math.PI / 4.0;
				for (int k = 1; k <= 3; k++) {
					double d = outerR * k / 4.0;
					BehaviorSupport.sendContained(level, inkDust, shape, center, radius,
							center.x + Math.cos(a) * d, planeY, center.z + Math.sin(a) * d, 1, 0.02, 0.02, 0.02, 0.0);
				}
			}
		}

		if (pulse % flashPeriod == 0L) {
			// The transmutation flash when the glyphs align.
			int flash = ctx.scaleCount(variant == 5 ? 10 : 6, 12);
			BehaviorSupport.sendContained(level, ParticleTypes.GLOW, shape, center, radius,
					center.x, planeY + radius * 0.08, center.z, flash, 0.2, 0.15, 0.2, 0.05);
			BehaviorSupport.sendContained(level, sealDust, shape, center, radius,
					center.x, planeY + radius * 0.05, center.z, ctx.scaleCount(3, 6), 0.15, 0.1, 0.15, 0.0);
			if (variant == 6) {
				// The fountain: WITCH flecks jet upward off the inner ring.
				double innerR = outerR * 0.65;
				for (int j = 0; j < 4; j++) {
					double a = spin + Math.PI / 2.0 * j;
					BehaviorSupport.sendContained(level, ParticleTypes.WITCH, shape, center, radius,
							center.x + Math.cos(a) * innerR, planeY + 0.2, center.z + Math.sin(a) * innerR,
							0, 0.0, radius * 0.3, 0.0, 1.0);
				}
			}
		}
	}
}

package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Waist-high pollen elementals ambling between hash-seeded floor anchors: each
 * one is a little whirl of COMPOSTER green flecks around a FALLING_NECTAR
 * dribble, crowned by a palette dust mote, and every few strides one of them
 * lets out a SNEEZE puff -- purely particles, no entities, no state, no
 * cleanup.
 *
 * <ul>
 * <li>v0: meadow amblers (a small troupe drifting anchor to anchor)</li>
 * <li>v1: courting pairs (elementals walk in twos, whirls intertwined)</li>
 * <li>v2: sneezy season (every elemental sneezes on the beat, doubled puffs)</li>
 * <li>v3: heavy bloom (denser nectar dribbles, spore-blossom motes in the whirl)</li>
 * <li>v4: whirlwind waltz (faster spin, a small gust kick on each sneeze beat)</li>
 * <li>v5: honey-dusted (dripping-honey blobs replace half the nectar)</li>
 * <li>v6: pollen giants (fewer, taller elementals with double-deck whirls)</li>
 * </ul>
 */
public final class PollenElementals implements InsideEffectBehavior {
	public static final String ID = "pollen_elementals";
	/** One anchor-to-anchor amble takes this long; the sneeze beat fires once per stride. */
	private static final long STRIDE_TICKS = 80L;
	private static final int MAX_ELEMENTALS = 8;
	/**
	 * Worst case v2 (countMult maxed): 8 elementals x (whirl 4 + nectar 2 + crown
	 * 1 + base dust 1 + sneeze 4) = 96 particles/pulse; v6 peaks at 5 x (whirl 8 +
	 * nectar 2 + crown 1 + base 1) + sneeze 3 = 63, every other variant lower --
	 * all well under 128.
	 */
	private static final int MAX_WHIRL = 8;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int base = Mth.clamp((int) (radius * 0.6F * def.behaviorStrength()), 2, variant == 6 ? 4 : 6);
		int elementals = ctx.scaleCount(base, variant == 6 ? 5 : MAX_ELEMENTALS);
		DustParticleOptions crown = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 0.8F);
		DustParticleOptions baseDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.6F);
		long stride = gameTime / STRIDE_TICKS;
		double t = (gameTime % STRIDE_TICKS) / (double) STRIDE_TICKS;
		// The sneeze beat: the first pulse of every stride.
		boolean sneezeBeat = gameTime % STRIDE_TICKS < 10L;
		double waist = 0.9 + (variant == 6 ? 0.9 : 0.0);
		for (int e = 0; e < elementals; e++) {
			Vec3 from = anchor(center, radius, e, stride);
			Vec3 to = anchor(center, radius, e, stride + 1L);
			Vec3 pos = from.lerp(to, t);
			if (variant == 1 && e % 2 == 1) {
				// Courting pairs: odd elementals shadow their partner's path, offset a step.
				Vec3 lead = anchor(center, radius, e - 1, stride).lerp(anchor(center, radius, e - 1, stride + 1L), t);
				pos = new Vec3(lead.x + 0.6, lead.y, lead.z + 0.6);
			}

			emitElemental(level, shape, center, radius, ctx, variant, pos, waist, gameTime, e, crown, baseDust);
			if (sneezeBeat && (variant == 2 || e == (int) (stride % Math.max(1, elementals)))) {
				int puffs = ctx.scaleCount(2, variant == 2 ? 4 : 3);
				BehaviorSupport.sendContained(level, ParticleTypes.SNEEZE, shape, center, radius,
						pos.x, pos.y + waist * 0.7, pos.z, puffs, 0.15, 0.1, 0.15, 0.02);
				if (variant == 4) {
					BehaviorSupport.sendContained(level, ParticleTypes.SMALL_GUST, shape, center, radius,
							pos.x, pos.y + waist * 0.4, pos.z, 1, 0.1, 0.05, 0.1, 0.0);
				}
			}
		}
	}

	/** One elemental's body: the composter whirl, the nectar dribble, the crown and base dust. */
	private static void emitElemental(ServerLevel level, ShieldShape shape, Vec3 center, float radius, ContextState ctx,
			int variant, Vec3 pos, double waist, long gameTime, int index, DustParticleOptions crown, DustParticleOptions baseDust) {
		double spin = gameTime * (variant == 4 ? 0.5 : 0.25) + index * 2.1;
		int whirl = ctx.scaleCount(variant == 6 ? 6 : 3, variant == 6 ? MAX_WHIRL : 4);
		for (int k = 0; k < whirl; k++) {
			double a = spin + Math.PI * 2.0 * k / Math.max(1, whirl);
			// v6 stacks a second whirl deck at chest height.
			double deckY = variant == 6 && k % 2 == 1 ? waist * 0.75 : waist * 0.4;
			BehaviorSupport.sendContained(level, variant == 3 && k == 0 ? ParticleTypes.SPORE_BLOSSOM_AIR : ParticleTypes.COMPOSTER,
					shape, center, radius,
					pos.x + Math.cos(a) * 0.45, pos.y + deckY, pos.z + Math.sin(a) * 0.45,
					1, 0.04, 0.04, 0.04, 0.0);
		}

		int nectar = ctx.scaleCount(variant == 3 ? 2 : 1, 2);
		BehaviorSupport.sendContained(level, variant == 5 && index % 2 == 0 ? ParticleTypes.DRIPPING_HONEY : ParticleTypes.FALLING_NECTAR,
				shape, center, radius,
				pos.x, pos.y + waist * 0.6, pos.z, nectar, 0.15, 0.2, 0.15, 0.0);
		// The recolor accents: a crown mote and a base mote on every elemental, every variant.
		BehaviorSupport.sendContained(level, crown, shape, center, radius,
				pos.x, pos.y + waist, pos.z, 1, 0.05, 0.03, 0.05, 0.0);
		BehaviorSupport.sendContained(level, baseDust, shape, center, radius,
				pos.x, pos.y + 0.1, pos.z, 1, 0.1, 0.02, 0.1, 0.0);
	}

	/**
	 * A hash-seeded amble anchor: inside 0.65r horizontally and 0.05r..0.12r above
	 * the center plane (dome-safe by construction; the tallest v6 crown tops out
	 * around 0.12r + 1.8 blocks, still inside 0.85r at the minimum radius 4).
	 */
	private static Vec3 anchor(Vec3 center, float radius, int elemental, long stride) {
		long seed = BehaviorSupport.mix(stride * 389L + elemental * 6151L);
		double angle = BehaviorSupport.hash01(seed) * Math.PI * 2.0;
		double dist = Math.sqrt(BehaviorSupport.hash01(seed + 1L)) * radius * 0.65;
		double y = radius * (0.05 + 0.07 * BehaviorSupport.hash01(seed + 2L));
		return new Vec3(center.x + Math.cos(angle) * dist, center.y + y, center.z + Math.sin(angle) * dist);
	}
}

package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A dryad spirit wandering the bubble floor on a hash-seeded path: each pulse
 * her newest footprint blossoms (HAPPY_VILLAGER twinkle + petals + a palette
 * dust accent), the two previous footprints fade to single petals, and the
 * three oldest crumble into MYCELIUM specks -- purely particles, no entities,
 * no state, no cleanup.
 *
 * <ul>
 * <li>v0: cherry dryad (cherry-petal blossoms on every footprint)</li>
 * <li>v1: pale grove spirit (pale oak petals)</li>
 * <li>v2: twin dryads wandering two independent paths</li>
 * <li>v3: palette dryad (footprints bloom in palette-tinted leaves)</li>
 * <li>v4: elder dryad (spore blossom haze over the fresh print, richer mycelium decay)</li>
 * <li>v5: firefly-attended dryad (fireflies hover over the fresh blossom)</li>
 * <li>v6: burst-step dryad (each fresh footprint erupts as a twinkle ring)</li>
 * </ul>
 */
public final class DryadBloom implements InsideEffectBehavior {
	public static final String ID = "dryad_bloom";
	/** One footprint per pulse cadence: trail slot j is the path position j * STEP_TICKS ago. */
	private static final long STEP_TICKS = 10L;
	/** Wander segment length: one waypoint-to-waypoint stride. */
	private static final long SEG_TICKS = 60L;
	/**
	 * Worst case v2 (twin dryads, countMult maxed): 2 dryads x (bloom 8 + petals 6
	 * + dust 2 + fading 2x(petal 1 + twinkle 1) + specks 3x4) = 64 particles/pulse;
	 * v4 peaks at 44, every other variant below that -- all well under 128.
	 */
	private static final int MAX_BLOOM = 8;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int dryads = variant == 2 ? 2 : 1;
		ParticleOptions accent = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 0.9F);
		ParticleOptions accentDim = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.7F);
		ParticleOptions petal = switch (variant) {
			case 1 -> ParticleTypes.PALE_OAK_LEAVES;
			case 3 -> ColorParticleOption.create(ParticleTypes.TINTED_LEAVES, ctx.pickColor(def.argbPrimary(), def.argbSecondary()));
			default -> ParticleTypes.CHERRY_LEAVES;
		};
		for (int d = 0; d < dryads; d++) {
			// Footprint ages: j=0 fresh bloom, j=1..2 fading petals, j=3..5 mycelium decay.
			for (int j = 0; j <= 5; j++) {
				Vec3 pos = pathPoint(center, radius, d, gameTime - j * STEP_TICKS);
				if (j == 0) {
					emitFreshBloom(level, shape, center, radius, def, ctx, variant, pos, petal, accent);
				} else if (j <= 2) {
					BehaviorSupport.sendContained(level, petal, shape, center, radius,
							pos.x, pos.y + 0.25, pos.z, 1, 0.15, 0.1, 0.15, 0.0);
					BehaviorSupport.sendContained(level, ParticleTypes.HAPPY_VILLAGER, shape, center, radius,
							pos.x, pos.y + 0.15, pos.z, 1, 0.12, 0.08, 0.12, 0.0);
					if (j == 1) {
						// The second palette strand rides the newest fading print.
						BehaviorSupport.sendContained(level, accentDim, shape, center, radius,
								pos.x, pos.y + 0.1, pos.z, 1, 0.1, 0.05, 0.1, 0.0);
					}
				} else {
					int specks = ctx.scaleCount(2, variant == 4 ? 6 : 4);
					BehaviorSupport.sendContained(level, ParticleTypes.MYCELIUM, shape, center, radius,
							pos.x, pos.y + 0.05, pos.z, specks, 0.2, 0.05, 0.2, 0.0);
				}
			}
		}
	}

	private static void emitFreshBloom(ServerLevel level, ShieldShape shape, Vec3 center, float radius,
			EffectDefinition def, ContextState ctx, int variant, Vec3 pos, ParticleOptions petal, ParticleOptions accent) {
		if (variant == 6) {
			// The burst-step: the twinkles land as a ring around the footprint.
			int ringPoints = ctx.scaleCount(6, MAX_BLOOM);
			for (int i = 0; i < ringPoints; i++) {
				double a = Math.PI * 2.0 * i / Math.max(1, ringPoints) + ringPhase(pos);
				BehaviorSupport.sendContained(level, ParticleTypes.HAPPY_VILLAGER, shape, center, radius,
						pos.x + Math.cos(a) * 0.6, pos.y + 0.2, pos.z + Math.sin(a) * 0.6, 1, 0.05, 0.05, 0.05, 0.0);
			}
		} else {
			int bloom = ctx.scaleCount(Mth.clamp((int) (radius * 0.3F * def.behaviorStrength()), 2, 6), MAX_BLOOM);
			BehaviorSupport.sendContained(level, ParticleTypes.HAPPY_VILLAGER, shape, center, radius,
					pos.x, pos.y + 0.2, pos.z, bloom, 0.25, 0.15, 0.25, 0.0);
		}

		BehaviorSupport.sendContained(level, petal, shape, center, radius,
				pos.x, pos.y + 0.35, pos.z, ctx.scaleCount(3, 6), 0.2, 0.15, 0.2, 0.0);
		// The recolor accent: one palette dust mote on every fresh print, every variant.
		BehaviorSupport.sendContained(level, accent, shape, center, radius,
				pos.x, pos.y + 0.25, pos.z, 1, 0.08, 0.05, 0.08, 0.0);
		if (variant == 4) {
			BehaviorSupport.sendContained(level, ParticleTypes.SPORE_BLOSSOM_AIR, shape, center, radius,
					pos.x, pos.y + 0.8, pos.z, ctx.scaleCount(3, 6), 0.3, 0.2, 0.3, 0.0);
		} else if (variant == 5) {
			BehaviorSupport.sendContained(level, ParticleTypes.FIREFLY, shape, center, radius,
					pos.x, pos.y + 0.9, pos.z, ctx.scaleCount(2, 4), 0.25, 0.2, 0.25, 0.0);
		}
	}

	/** A tiny position-derived ring rotation so successive burst rings do not stack pixel-perfectly. */
	private static double ringPhase(Vec3 pos) {
		return BehaviorSupport.hash01((long) (pos.x * 31.0) ^ (long) (pos.z * 17.0)) * Math.PI;
	}

	/** The dryad's smooth wander: a lerp between consecutive hash-seeded floor waypoints. */
	private static Vec3 pathPoint(Vec3 center, float radius, int dryad, long time) {
		long segment = Math.floorDiv(time, SEG_TICKS);
		double t = Math.floorMod(time, SEG_TICKS) / (double) SEG_TICKS;
		return waypoint(center, radius, dryad, segment).lerp(waypoint(center, radius, dryad, segment + 1L), t);
	}

	/**
	 * A hash-seeded footpath waypoint: inside 0.7r horizontally and 0.06r..0.14r
	 * above the center plane (dome-safe by construction, max reach ~0.72r).
	 */
	private static Vec3 waypoint(Vec3 center, float radius, int dryad, long segment) {
		long seed = BehaviorSupport.mix(segment * 131L + dryad * 7919L);
		double angle = BehaviorSupport.hash01(seed) * Math.PI * 2.0;
		double dist = Math.sqrt(BehaviorSupport.hash01(seed + 1L)) * radius * 0.7;
		double y = radius * (0.06 + 0.08 * BehaviorSupport.hash01(seed + 2L));
		return new Vec3(center.x + Math.cos(angle) * dist, center.y + y, center.z + Math.sin(angle) * dist);
	}
}

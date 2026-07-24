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
 * A poltergeist hurling unseen objects: each object cluster (crit sparks
 * around a palette dust fleck) is yanked along a jerky hash-seeded polyline
 * whose waypoints alternate between floor level and the ceiling. After four
 * segments the journey ends in an enchanted-hit scatter and the object
 * re-rolls a fresh polyline. Object phases are staggered so scatters never
 * all land on the same pulse.
 *
 * <ul>
 * <li>v0: a handful of objects on 20-tick yanks</li>
 * <li>v1: a frenzy (10-tick segments, twice as jerky)</li>
 * <li>v2: mirrored mischief (every object has an axis-mirrored twin)</li>
 * <li>v3: heavy furniture (slow 30-tick drags below 0.45r, an extra crit)</li>
 * <li>v4: a flurry of small flecks (more objects, dust-led clusters)</li>
 * <li>v5: high hurls (waypoints up to 0.75r, a bigger scatter)</li>
 * <li>v6: chained tosses (two dust echoes dragged behind each object)</li>
 * </ul>
 */
public final class PoltergeistToss implements InsideEffectBehavior {
	public static final String ID = "poltergeist_toss";
	/** Worst case v6: 8 objects x (cluster crit 2 + dust 2 + echoes 2 x (crit 1 + dust 1)) = 64, plus 2 staggered scatters x (enchanted-hit 6 + dust 2) = 16 -> 80 particles/pulse (v2: 5 x 2 x 4 + 2 x 2 x 8 = 72). */
	private static final int MAX_OBJECTS = 12;
	/** Segments per journey; the scatter fires on the last pulse of the fourth. */
	private static final int JOURNEY_SEGMENTS = 4;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int base = variant == 4 ? Mth.clamp((int) (5.0F * def.behaviorStrength()), 5, 8)
				: variant == 2 ? 4
				: Mth.clamp((int) (4.0F * def.behaviorStrength()), 3, 5);
		int objects = ctx.scaleCount(base, variant == 4 ? MAX_OBJECTS : variant == 2 ? 5 : 8);
		long segTicks = variant == 1 ? 10L : variant == 3 ? 30L : 20L;
		double t = (gameTime % segTicks) / (double) segTicks;
		ParticleOptions fleck = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, variant == 3 ? 1.1F : 0.7F);
		ParticleOptions echoDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.6F);
		for (int o = 0; o < objects; o++) {
			// The +o offset staggers journeys so scatters spread across pulses.
			long segment = gameTime / segTicks + o;
			Vec3 from = waypoint(center, radius, o, segment, variant);
			Vec3 to = waypoint(center, radius, o, segment + 1L, variant);
			// The jerk: a per-pulse hash stumble off the straight yank line.
			long jerkSeed = BehaviorSupport.mix(gameTime / 10L * 337L + o * 41L);
			Vec3 pos = from.lerp(to, t).add(
					(BehaviorSupport.hash01(jerkSeed) - 0.5) * 0.3,
					(BehaviorSupport.hash01(jerkSeed + 1L) - 0.5) * 0.3,
					(BehaviorSupport.hash01(jerkSeed + 2L) - 0.5) * 0.3);
			emitObject(level, shape, center, radius, variant, pos, fleck);
			if (variant == 2) {
				// The mirrored twin is yanked through the opposite half.
				Vec3 mirrored = new Vec3(2.0 * center.x - pos.x, pos.y, 2.0 * center.z - pos.z);
				emitObject(level, shape, center, radius, variant, mirrored, fleck);
			} else if (variant == 6) {
				for (int k = 1; k <= 2; k++) {
					// The chained echoes drag behind on the same polyline.
					Vec3 echo = from.lerp(to, Math.max(0.0, t - 0.22 * k));
					BehaviorSupport.sendContained(level, ParticleTypes.CRIT, shape, center, radius,
							echo.x, echo.y, echo.z, 1, 0.05, 0.05, 0.05, 0.02);
					BehaviorSupport.sendContained(level, echoDust, shape, center, radius,
							echo.x, echo.y, echo.z, 1, 0.05, 0.05, 0.05, 0.0);
				}
			}

			// The journey's final pulse: the object smashes in a scatter.
			if (segment % JOURNEY_SEGMENTS == JOURNEY_SEGMENTS - 1 && gameTime % segTicks == segTicks - 10L) {
				BehaviorSupport.sendContained(level, ParticleTypes.ENCHANTED_HIT, shape, center, radius,
						to.x, to.y, to.z, variant == 5 ? 10 : 6, 0.3, 0.3, 0.3, 0.1);
				BehaviorSupport.sendContained(level, fleck, shape, center, radius,
						to.x, to.y, to.z, 2, 0.25, 0.25, 0.25, 0.0);
				if (variant == 2) {
					BehaviorSupport.sendContained(level, ParticleTypes.ENCHANTED_HIT, shape, center, radius,
							2.0 * center.x - to.x, to.y, 2.0 * center.z - to.z, 6, 0.3, 0.3, 0.3, 0.1);
					BehaviorSupport.sendContained(level, fleck, shape, center, radius,
							2.0 * center.x - to.x, to.y, 2.0 * center.z - to.z, 2, 0.25, 0.25, 0.25, 0.0);
				}
			}
		}
	}

	private static void emitObject(ServerLevel level, ShieldShape shape, Vec3 center, float radius,
			int variant, Vec3 pos, ParticleOptions fleck) {
		BehaviorSupport.sendContained(level, ParticleTypes.CRIT, shape, center, radius,
				pos.x, pos.y, pos.z, variant == 3 ? 3 : variant == 4 ? 1 : 2, 0.1, 0.1, 0.1, 0.05);
		// The recolor accent: palette dust flecks on every cluster, every variant.
		BehaviorSupport.sendContained(level, fleck, shape, center, radius,
				pos.x, pos.y, pos.z, 2, 0.08, 0.08, 0.08, 0.0);
	}

	/**
	 * The hash-seeded polyline waypoint: even segments sit near the floor
	 * (0.08r..0.18r up, within 0.65r horizontally), odd segments near the
	 * ceiling (0.45r..0.75r by variant, within 0.4r horizontally, max reach
	 * ~0.85r), so every yank is a floor-to-ceiling snap. Dome-safe: always at
	 * or above the center plane.
	 */
	private static Vec3 waypoint(Vec3 center, float radius, int object, long segment, int variant) {
		long seed = BehaviorSupport.mix(segment * 613L + object * 7L + variant);
		boolean high = segment % 2L != 0L;
		double angle = BehaviorSupport.hash01(seed) * Math.PI * 2.0;
		double dist = Math.sqrt(BehaviorSupport.hash01(seed + 1L)) * radius * (high ? 0.4 : 0.65);
		double ceiling = variant == 3 ? 0.45 : variant == 5 ? 0.75 : 0.6;
		double y = high
				? radius * (ceiling - 0.1 * BehaviorSupport.hash01(seed + 2L))
				: radius * (0.08 + 0.1 * BehaviorSupport.hash01(seed + 2L));
		return new Vec3(center.x + Math.cos(angle) * dist, center.y + y, center.z + Math.sin(angle) * dist);
	}
}

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
 * The turning wheel of the year: the bubble floor is quartered into four
 * season quadrants -- spring CHERRY_LEAVES, summer FIREFLY, autumn
 * PALE_OAK_LEAVES, winter SNOWFLAKE -- and a palette dust sweep-spoke rotates
 * quadrant by quadrant, waking each season's mood as it passes while the
 * previous quadrant fades out -- purely particles, no entities, no state, no
 * cleanup.
 *
 * <ul>
 * <li>v0: the turning year (sunwise sweep, one season in bloom at a time)</li>
 * <li>v1: widdershins year (the wheel turns backward, seasons in reverse)</li>
 * <li>v2: split year (opposite quadrants bloom together: spring with autumn, summer with winter)</li>
 * <li>v3: palette solstice (the active quadrant blooms in palette dust with season flecks)</li>
 * <li>v4: storm of seasons (double wheel speed, all four quadrants murmur at once)</li>
 * <li>v5: winter's grip (snowflakes bleed into the quadrants neighbouring winter)</li>
 * <li>v6: midsummer night (fireflies drift over the whole wheel all year round)</li>
 * </ul>
 */
public final class SeasonsWheel implements InsideEffectBehavior {
	public static final String ID = "seasons_wheel";
	/** One season per quadrant lasts this long; a full year is four seasons. v4 halves it. */
	private static final long SEASON_TICKS = 200L;
	private static final int MAX_BURST = 24;
	private static final int MAX_FADE = 12;
	/**
	 * Worst case v4 (countMult maxed): active burst 24 + 3 murmuring quadrants x
	 * 12 + spoke 8 + trailing strand 6 = 74 particles/pulse; v5 peaks at 24 + 12
	 * + 2x6 snow bleed + 14 spoke = 62 and v2 at 24 + 2x12 + 14 = 62, every other
	 * variant at most 58 -- all well under 128.
	 */
	private static final int MAX_SPOKE = 8;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		long seasonTicks = variant == 4 ? SEASON_TICKS / 2L : SEASON_TICKS;
		int season = (int) ((gameTime / seasonTicks) % 4L);
		double t = (gameTime % seasonTicks) / (double) seasonTicks;
		if (variant == 1) {
			// Widdershins: the wheel turns backward through the year (the reversed
			// season index and phase also make the sweep angle run backward).
			season = 3 - season;
			t = 1.0 - t;
		}

		int burstBase = Mth.clamp((int) (radius * 0.8F * def.behaviorStrength()), 6, 18);
		double moodY = center.y + radius * 0.3;
		// The active quadrant in bloom, and the previous one fading out.
		emitQuadrant(level, shape, center, radius, def, ctx, variant, season, ctx.scaleCount(burstBase, MAX_BURST), moodY);
		emitQuadrant(level, shape, center, radius, def, ctx, variant, (season + 3) % 4, ctx.scaleCount(burstBase / 2, MAX_FADE), moodY);
		if (variant == 2) {
			// The split year: the opposite quadrant blooms in step with the active one.
			emitQuadrant(level, shape, center, radius, def, ctx, variant, (season + 2) % 4, ctx.scaleCount(burstBase / 2, MAX_FADE), moodY);
		} else if (variant == 4) {
			// The storm: the two remaining quadrants murmur along too.
			emitQuadrant(level, shape, center, radius, def, ctx, variant, (season + 1) % 4, ctx.scaleCount(burstBase / 2, MAX_FADE), moodY);
			emitQuadrant(level, shape, center, radius, def, ctx, variant, (season + 2) % 4, ctx.scaleCount(burstBase / 2, MAX_FADE), moodY);
		} else if (variant == 5) {
			// Winter's grip: snow sprinkles bleed into winter's two neighbours (autumn and spring).
			for (int neighbour : new int[] {2, 0}) {
				Vec3 c = quadrantCentroid(center, radius, neighbour);
				BehaviorSupport.sendContained(level, ParticleTypes.SNOWFLAKE, shape, center, radius,
						c.x, moodY, c.z, ctx.scaleCount(3, 6), radius * 0.15, radius * 0.1, radius * 0.15, 0.0);
			}
		} else if (variant == 6) {
			BehaviorSupport.sendContained(level, ParticleTypes.FIREFLY, shape, center, radius,
					center.x, moodY + radius * 0.15, center.z, ctx.scaleCount(4, 8), radius * 0.3, radius * 0.12, radius * 0.3, 0.0);
		}

		emitSpoke(level, shape, center, radius, def, ctx, season, t);
	}

	/** One quadrant's seasonal mood: a spread burst at the quadrant centroid. */
	private static void emitQuadrant(ServerLevel level, ShieldShape shape, Vec3 center, float radius,
			EffectDefinition def, ContextState ctx, int variant, int quadrant, int count, double moodY) {
		if (count <= 0) {
			return;
		}

		Vec3 c = quadrantCentroid(center, radius, quadrant);
		double spread = radius * 0.18;
		if (variant == 3) {
			// The palette solstice: the bloom itself is palette dust, season-flecked.
			ParticleOptions dust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.0F);
			BehaviorSupport.sendContained(level, dust, shape, center, radius,
					c.x, moodY, c.z, count, spread, radius * 0.12, spread, 0.0);
			BehaviorSupport.sendContained(level, seasonParticle(quadrant), shape, center, radius,
					c.x, moodY + 0.3, c.z, Math.min(4, count), spread * 0.7, radius * 0.08, spread * 0.7, 0.0);
			return;
		}

		BehaviorSupport.sendContained(level, seasonParticle(quadrant), shape, center, radius,
				c.x, moodY, c.z, count, spread, radius * 0.12, spread, 0.0);
	}

	/**
	 * The rotating sweep-spoke: primary dust motes along the current sweep line
	 * plus a shorter trailing strand in the secondary color -- the wheel's
	 * palette accent, drawn every pulse for every variant.
	 */
	private static void emitSpoke(ServerLevel level, ShieldShape shape, Vec3 center, float radius,
			EffectDefinition def, ContextState ctx, int season, double t) {
		double sweep = (season + t) * Math.PI * 0.5;
		double trailing = sweep - Math.PI * 0.08;
		ParticleOptions spokeDust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 0.9F);
		ParticleOptions trailDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.6F);
		double spokeY = center.y + radius * 0.12;
		int motes = ctx.scaleCount(6, MAX_SPOKE);
		for (int i = 0; i < motes; i++) {
			double d = radius * (0.15 + 0.5 * (i + 0.5) / Math.max(1, motes));
			BehaviorSupport.sendContained(level, spokeDust, shape, center, radius,
					center.x + Math.cos(sweep) * d, spokeY, center.z + Math.sin(sweep) * d, 1, 0.04, 0.04, 0.04, 0.0);
		}

		int trailMotes = ctx.scaleCount(4, 6);
		for (int i = 0; i < trailMotes; i++) {
			double d = radius * (0.2 + 0.4 * (i + 0.5) / Math.max(1, trailMotes));
			BehaviorSupport.sendContained(level, trailDust, shape, center, radius,
					center.x + Math.cos(trailing) * d, spokeY, center.z + Math.sin(trailing) * d, 1, 0.04, 0.04, 0.04, 0.0);
		}
	}

	/** Quadrant q's home season particle: 0 spring, 1 summer, 2 autumn, 3 winter. */
	private static ParticleOptions seasonParticle(int quadrant) {
		return switch (quadrant) {
			case 1 -> ParticleTypes.FIREFLY;
			case 2 -> ParticleTypes.PALE_OAK_LEAVES;
			case 3 -> ParticleTypes.SNOWFLAKE;
			default -> ParticleTypes.CHERRY_LEAVES;
		};
	}

	/**
	 * Quadrant q's centroid: 0.5r out along the quadrant's bisector, 0.1r above
	 * the center plane (the mood bursts use their own 0.3r height; dome-safe, and
	 * with the 0.18r burst spread the reach stays ~0.7r).
	 */
	private static Vec3 quadrantCentroid(Vec3 center, float radius, int quadrant) {
		double a = quadrant * Math.PI * 0.5 + Math.PI * 0.25;
		return new Vec3(center.x + Math.cos(a) * radius * 0.5, center.y + radius * 0.1, center.z + Math.sin(a) * radius * 0.5);
	}
}

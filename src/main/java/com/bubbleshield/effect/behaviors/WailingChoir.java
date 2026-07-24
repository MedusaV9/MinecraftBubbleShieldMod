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
 * A semicircle of robed choristers facing the middle of the bubble: each
 * singer is a soul column under a palette dust hood (dust hem at the robe
 * foot), swelling in height with the bar and releasing a note burst on the
 * beat. Angles and swell phases derive from gameTime, and per-singer statures
 * from {@link BehaviorSupport#hash01}, so one shared instance serves every
 * shield with no fields and no cleanup.
 *
 * <p>Worst-case budget (v6 on the beat, countMult 3): 12 singers x (soul 2 +
 * hood dust 1 + hem dust 1 + note 2) = 72 particles/pulse (&lt;= 128).
 *
 * <ul>
 * <li>v0: the classic semicircle (a shared slow swell)</li>
 * <li>v1: antiphonal arcs (two facing semicircles trading the swell)</li>
 * <li>v2: cantor and choir (a tall central soloist leads the beat)</li>
 * <li>v3: a rolling crescendo (the swell travels along the arc)</li>
 * <li>v4: the dirge (squat singers, ash breath, a beat every other bar)</li>
 * <li>v5: candlelit vigil (a hymnal flame held before each singer)</li>
 * <li>v6: the grand choir (1.5x singers, doubled note bursts)</li>
 * </ul>
 */
public final class WailingChoir implements InsideEffectBehavior {
	public static final String ID = "wailing_choir";
	/** Worst case v6 on the beat: 12 singers x (soul 2 + hood dust 1 + hem dust 1 + note 2) = 72 particles/pulse. */
	private static final int MAX_SINGERS = 12;
	/** One bar of the hymn: the swell rises and falls over this many ticks. */
	private static final long BAR_TICKS = 80L;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int base = Mth.clamp((int) (radius * 0.9F * def.behaviorStrength()), 5, 8);
		int singers = ctx.scaleCount(variant == 6 ? base * 3 / 2 : base, variant == 6 ? MAX_SINGERS : 8);
		// v4 tolls only every other bar; every other variant beats once per bar.
		long beatPeriod = variant == 4 ? BAR_TICKS * 2L : BAR_TICKS;
		boolean beat = gameTime % beatPeriod < 10L;
		double bar = (gameTime % BAR_TICKS) / (double) BAR_TICKS;
		double sharedSwell = 0.5 - 0.5 * Math.cos(Math.PI * 2.0 * bar);
		double facing = gameTime * 0.002;
		double y0 = center.y + 0.1;
		ParticleOptions hood = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 0.9F);
		ParticleOptions hem = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.6F);
		for (int s = 0; s < singers; s++) {
			// v1 sends every odd singer to the facing arc, half a bar out of step.
			boolean farArc = variant == 1 && s % 2 == 1;
			double angle = facing - Math.PI / 2.0 + Math.PI * s / Math.max(1, singers - 1) + (farArc ? Math.PI : 0.0);
			double swellPhase = bar;
			if (variant == 3) {
				// The crescendo rolls along the arc, one singer after the next.
				swellPhase -= (double) s / Math.max(1, singers);
			} else if (farArc) {
				swellPhase += 0.5;
			}

			double swell = variant == 1 || variant == 3 ? 0.5 - 0.5 * Math.cos(Math.PI * 2.0 * swellPhase) : sharedSwell;
			double ringRadius = radius * (variant == 4 ? 0.5 : 0.6);
			double x = center.x + Math.cos(angle) * ringRadius;
			double z = center.z + Math.sin(angle) * ringRadius;
			// Hash-seeded stature so the arc silhouette is uneven but stable.
			double stature = 0.85 + 0.3 * BehaviorSupport.hash01(BehaviorSupport.mix(s * 173L));
			double bodyHeight = (0.8 + radius * (variant == 4 ? 0.04 : 0.08) * (0.4 + swell)) * stature;
			BehaviorSupport.sendContained(level, ParticleTypes.SOUL, shape, center, radius,
					x, y0 + bodyHeight * 0.45, z, 2, 0.08, bodyHeight * 0.3, 0.08, 0.02);
			BehaviorSupport.sendContained(level, hood, shape, center, radius,
					x, y0 + bodyHeight, z, 1, 0.06, 0.06, 0.06, 0.0);
			BehaviorSupport.sendContained(level, hem, shape, center, radius,
					x, y0 + 0.15, z, 1, 0.12, 0.05, 0.12, 0.0);
			if (variant == 4) {
				// The dirge breath drifts inward from under the hood.
				BehaviorSupport.sendContained(level, ParticleTypes.ASH, shape, center, radius,
						x - Math.cos(angle) * 0.4, y0 + bodyHeight * 0.85, z - Math.sin(angle) * 0.4, 1, 0.06, 0.06, 0.06, 0.01);
			} else if (variant == 5) {
				// The hymnal candle held in front of the singer.
				BehaviorSupport.sendContained(level, ParticleTypes.SMALL_FLAME, shape, center, radius,
						x - Math.cos(angle) * 0.35, y0 + bodyHeight * 0.6, z - Math.sin(angle) * 0.35, 1, 0.02, 0.04, 0.02, 0.0);
			}

			if (beat) {
				BehaviorSupport.sendContained(level, ParticleTypes.NOTE, shape, center, radius,
						x, y0 + bodyHeight + 0.4, z, variant == 6 ? 2 : 1, 0.1, 0.15, 0.1, 0.0);
			}
		}

		if (variant == 2) {
			// The cantor: a taller soloist column in the middle of the arc.
			double soloHeight = 0.8 + radius * 0.12 * (0.4 + sharedSwell);
			BehaviorSupport.sendContained(level, ParticleTypes.SOUL, shape, center, radius,
					center.x, y0 + soloHeight * 0.5, center.z, 2, 0.1, soloHeight * 0.35, 0.1, 0.02);
			BehaviorSupport.sendContained(level, hood, shape, center, radius,
					center.x, y0 + soloHeight, center.z, 1, 0.06, 0.06, 0.06, 0.0);
			if (beat) {
				BehaviorSupport.sendContained(level, ParticleTypes.NOTE, shape, center, radius,
						center.x, y0 + soloHeight + 0.5, center.z, 2, 0.15, 0.2, 0.15, 0.0);
			}
		}

		if (gameTime % beatPeriod == 0L) {
			level.playSound(null, center.x, y0 + 1.5, center.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.AMBIENT,
					0.35F, variant == 4 ? 0.6F : 1.1F);
		}
	}
}

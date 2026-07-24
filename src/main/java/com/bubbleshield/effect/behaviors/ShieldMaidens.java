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
 * An outward-facing ring of round dust shield discs held near the wall
 * (~0.85r): each disc is a rim of palette dust around a secondary-dust boss,
 * standing vertically in the tangent plane so it faces away from the center.
 * On the marching beat the rank braces -- a crit flare off the boss and a gust
 * ring rolling over the disc. Pure phase-derived particles: no fields, no
 * entities, no cleanup; the owner recolor repaints rims and bosses alike.
 *
 * <ul>
 * <li>v0: eight maidens on a steady clockwise march</li>
 * <li>v1: a tight phalanx of twelve</li>
 * <li>v2: staggered ranks (alternating discs raised half a step)</li>
 * <li>v3: counter-march (widdershins, bosses in the secondary color)</li>
 * <li>v4: tower shields (tall oval discs with a doubled rim)</li>
 * <li>v5: rolling brace (the brace travels around the ring one maiden per beat)</li>
 * <li>v6: parade salute (firework sparks crown each brace)</li>
 * </ul>
 */
public final class ShieldMaidens implements InsideEffectBehavior {
	public static final String ID = "shield_maidens";
	/**
	 * Worst case v4 all bracing: 8 discs x (12 doubled-rim + 1 boss + 2 crit + 1 gust)
	 * = 128 particles/pulse; v1 and v6 hit 120 (v4 caps at 8 discs, v6 at 10).
	 */
	private static final int MAX_MAIDENS = 12;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int base = Mth.clamp((int) (6.0F + radius * 0.06F * def.behaviorStrength()), 6, variant == 1 ? 12 : 8);
		// The heavier discs cap lower so the per-pulse budget holds (see MAX_MAIDENS).
		int maidens = ctx.scaleCount(base, variant == 4 ? 8 : variant == 6 ? 10 : MAX_MAIDENS);
		double ringDist = radius * 0.85;
		double height = radius * 0.25;
		double discR = Mth.clamp(radius * 0.08F, 0.4F, 0.9F);
		// The march: the whole rank steps around the ring (v3 goes widdershins).
		double march = gameTime / 10.0 * 0.04 * (variant == 3 ? -1.0 : 1.0);
		long beat = gameTime / 10L;
		boolean rankBraces = beat % 4L == 0L;
		ParticleOptions rimDust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.0F);
		ParticleOptions bossDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 1.3F);
		for (int m = 0; m < maidens; m++) {
			double angle = march + Math.PI * 2.0 * m / maidens;
			double y = height + (variant == 2 && (m & 1) == 0 ? radius * 0.12 : 0.0);
			Vec3 disc = new Vec3(center.x + Math.cos(angle) * ringDist, center.y + y, center.z + Math.sin(angle) * ringDist);
			// The disc stands vertically in the tangent plane, facing outward.
			double tangent = angle + Math.PI / 2.0;
			double tx = Math.cos(tangent);
			double tz = Math.sin(tangent);
			int rim = 6;
			for (int i = 0; i < rim; i++) {
				double a = Math.PI * 2.0 * i / rim;
				double stretch = variant == 4 ? 1.5 : 1.0;
				BehaviorSupport.sendContained(level, rimDust, shape, center, radius,
						disc.x + tx * Math.cos(a) * discR,
						disc.y + Math.sin(a) * discR * stretch,
						disc.z + tz * Math.cos(a) * discR, 1, 0.0, 0.0, 0.0, 0.0);
				if (variant == 4) {
					// The doubled rim: a second ring of dots half a phase off.
					double b = a + Math.PI / rim;
					BehaviorSupport.sendContained(level, rimDust, shape, center, radius,
							disc.x + tx * Math.cos(b) * discR,
							disc.y + Math.sin(b) * discR * stretch,
							disc.z + tz * Math.cos(b) * discR, 1, 0.0, 0.0, 0.0, 0.0);
				}
			}

			BehaviorSupport.sendContained(level, bossDust, shape, center, radius,
					disc.x, disc.y, disc.z, 1, 0.03, 0.03, 0.03, 0.0);
			// The brace: v5 rolls it around the ring one maiden per beat, the
			// other variants brace the whole rank on the marching beat.
			boolean braces = variant == 5 ? beat % maidens == m : rankBraces;
			if (braces) {
				BehaviorSupport.sendContained(level, ParticleTypes.CRIT, shape, center, radius,
						disc.x, disc.y, disc.z, ctx.scaleCount(2, 2), 0.15, 0.15, 0.15, 0.05);
				BehaviorSupport.sendContained(level, ParticleTypes.GUST, shape, center, radius,
						disc.x, disc.y, disc.z, 1, 0.1, 0.1, 0.1, 0.0);
				if (variant == 6) {
					BehaviorSupport.sendContained(level, ParticleTypes.FIREWORK, shape, center, radius,
							disc.x, disc.y + discR + 0.2, disc.z, 2, 0.1, 0.1, 0.1, 0.04);
				}
			}
		}
	}
}

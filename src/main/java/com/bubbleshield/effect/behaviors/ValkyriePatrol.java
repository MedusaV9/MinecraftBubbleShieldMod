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
 * Winged silhouettes gliding figure-eights at mid height: each valkyrie is a
 * palette dust body with two swept end-rod wing arcs angled to its heading,
 * shedding wax-off feather glints on the wingbeat. Pure particles driven by a
 * lemniscate phase -- no entities, no fields, no cleanup; the owner recolor
 * repaints every body and banner.
 *
 * <ul>
 * <li>v0: two valkyries on a wide, unhurried figure-eight</li>
 * <li>v1: a swift squadron of three (double glide speed)</li>
 * <li>v2: mirrored wings (each valkyrie has a twin on the crossed eight)</li>
 * <li>v3: banner bearers (a secondary-dust ribbon trails each flight)</li>
 * <li>v4: high sentries (higher altitude, longer wing sweep)</li>
 * <li>v5: dive patrol (the eight bobs steeply; a cloud rush at the low point)</li>
 * <li>v6: molting wings (feather glints shed every pulse, drifting below)</li>
 * </ul>
 */
public final class ValkyriePatrol implements InsideEffectBehavior {
	public static final String ID = "valkyrie_patrol";
	/** Worst case v5: 6 flights x (6 wing dots + 1 body dust + 1 glint + 4 cloud) = 72 particles/pulse (v3 hits 66). */
	private static final int MAX_FLIGHTS = 6;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int base = Mth.clamp((int) (2.0F + radius * 0.03F * def.behaviorStrength()), 2, variant == 1 ? 3 : variant == 2 ? 3 : 4);
		int flights = ctx.scaleCount(base, variant == 2 ? 3 : MAX_FLIGHTS);
		double speed = (variant == 1 ? 0.06 : 0.03) * Math.PI;
		double span = radius * (variant == 4 ? 0.65 : 0.55);
		double altitude = radius * (variant == 4 ? 0.6 : 0.45);
		double wingSpan = variant == 4 ? 1.4 : 1.1;
		long beat = gameTime / 10L;
		ParticleOptions bodyDust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.1F);
		ParticleOptions bannerDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.9F);
		for (int f = 0; f < flights; f++) {
			double phase = gameTime / 10.0 * speed + Math.PI * 2.0 * f / Math.max(1, flights)
					+ BehaviorSupport.hash01(BehaviorSupport.mix(f * 131L)) * 0.7;
			emitValkyrie(level, shape, center, radius, ctx, variant, beat + f,
					eightPoint(center, span, altitude, radius, variant, phase, 0.0), phase, wingSpan, bodyDust, bannerDust,
					span, altitude);
			if (variant == 2) {
				// The mirror twin glides the same eight rotated a quarter turn.
				emitValkyrie(level, shape, center, radius, ctx, variant, beat + f + 1L,
						eightPoint(center, span, altitude, radius, variant, phase, Math.PI / 2.0), phase, wingSpan, bodyDust, bannerDust,
						span, altitude);
			}
		}
	}

	private static void emitValkyrie(ServerLevel level, ShieldShape shape, Vec3 center, float radius, ContextState ctx,
			int variant, long beat, Vec3 pos, double phase, double wingSpan, ParticleOptions bodyDust, ParticleOptions bannerDust,
			double span, double altitude) {
		// The heading is the lemniscate tangent; wings sweep perpendicular to it.
		double hx = Math.cos(phase);
		double hz = Math.cos(2.0 * phase);
		double heading = Math.atan2(hz, hx);
		double wing = heading + Math.PI / 2.0;
		double wx = Math.cos(wing);
		double wz = Math.sin(wing);
		for (int side = -1; side <= 1; side += 2) {
			for (int k = 1; k <= 3; k++) {
				// The arc: each outer dot sits farther out, higher, and swept back.
				double out = wingSpan * k / 3.0;
				BehaviorSupport.sendContained(level, ParticleTypes.END_ROD, shape, center, radius,
						pos.x + side * wx * out - hx * 0.12 * k,
						pos.y + 0.05 + 0.12 * k,
						pos.z + side * wz * out - hz * 0.12 * k, 1, 0.02, 0.02, 0.02, 0.0);
			}
		}

		// The recolor accent: the palette dust body, every flight, every pulse.
		BehaviorSupport.sendContained(level, bodyDust, shape, center, radius,
				pos.x, pos.y, pos.z, 1, 0.05, 0.05, 0.05, 0.0);
		if (variant == 6 || beat % 2L == 0L) {
			// The wingbeat feather glint, drifting just below the wing root.
			BehaviorSupport.sendContained(level, ParticleTypes.WAX_OFF, shape, center, radius,
					pos.x + wx * 0.4, pos.y - 0.2, pos.z + wz * 0.4, 1, 0.15, 0.1, 0.15, 0.0);
		}

		if (variant == 3) {
			for (int k = 1; k <= 3; k++) {
				// The banner: a ribbon hanging back along the flown path.
				Vec3 tail = eightPoint(center, span, altitude, radius, variant, phase - 0.12 * k, 0.0);
				BehaviorSupport.sendContained(level, bannerDust, shape, center, radius,
						tail.x, tail.y - 0.1 * k, tail.z, 1, 0.03, 0.03, 0.03, 0.0);
			}
		} else if (variant == 5 && Math.sin(2.0 * phase) < -0.85) {
			BehaviorSupport.sendContained(level, ParticleTypes.CLOUD, shape, center, radius,
					pos.x, pos.y - 0.3, pos.z, ctx.scaleCount(2, 4), 0.25, 0.1, 0.25, 0.01);
		}
	}

	/**
	 * The mid-height figure-eight (Gerono lemniscate, optionally rotated): within
	 * {@code span <= 0.65r} horizontally at {@code 0.45r}/{@code 0.6r} altitude,
	 * with a gentle bob (steep on v5) that never dips below {@code 0.25r} --
	 * dome-safe and inside ~0.85r before the containment sweep.
	 */
	private static Vec3 eightPoint(Vec3 center, double span, double altitude, float radius, int variant, double phase, double rot) {
		double ex = Math.sin(phase) * span;
		double ez = Math.sin(phase) * Math.cos(phase) * span;
		double bob = Math.sin(2.0 * phase) * radius * (variant == 5 ? 0.18 : 0.06);
		double cr = Math.cos(rot);
		double sr = Math.sin(rot);
		return new Vec3(center.x + ex * cr - ez * sr, center.y + altitude + bob, center.z + ex * sr + ez * cr);
	}
}

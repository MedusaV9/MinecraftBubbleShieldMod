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
 * One large, slow stag apparition pacing a wide floor arc: the body is a tall
 * cloud column under an end-rod spine mote, the antlers are two swept-back
 * branching arms of palette dust points redrawn every pulse, and glow
 * hoof-falls land on quantized arc steps behind it -- so the prints appear to
 * linger where the stag has walked. Pure particles, no entities, no state.
 *
 * <ul>
 * <li>v0: the pale stag</li>
 * <li>v1: a great elk (longer antler arms)</li>
 * <li>v2: twin stags pacing opposite ends of the arc</li>
 * <li>v3: a frost stag (snowflake breath and a snow-dusted back)</li>
 * <li>v4: an ember stag (small-flame hoof-falls)</li>
 * <li>v5: a shade stag (smoke body, ash veil)</li>
 * <li>v6: a crowned stag (wax-on glints on the antler tips)</li>
 * </ul>
 */
public final class SpectralStag implements InsideEffectBehavior {
	public static final String ID = "spectral_stag";
	/** Worst case v2 at ctx-max antlers: 2 stags x (body 3 + spine 1 + chest dust 1 + antlers 2x6 + hooves 2) = 38 particles/pulse. */
	private static final int MAX_ANTLER_POINTS = 6;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int antlerPoints = ctx.scaleCount(variant == 1 ? 5 : 3, MAX_ANTLER_POINTS);
		double scale = Math.min(1.0, radius * 0.14) * Mth.clamp(def.behaviorStrength(), 0.8F, 1.3F);
		ParticleOptions antlerDust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 0.8F);
		ParticleOptions chestDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 1.2F);
		int stags = variant == 2 ? 2 : 1;
		for (int s = 0; s < stags; s++) {
			// The wide slow arc: one lap takes ~2600 ticks; twins pace half a lap apart.
			double angle = gameTime * 0.0024 + Math.PI * s;
			double arcDist = radius * 0.6;
			double x = center.x + Math.cos(angle) * arcDist;
			double z = center.z + Math.sin(angle) * arcDist;
			double bodyY = center.y + 0.9 * scale;
			double heading = angle + Math.PI / 2.0;
			double hx = Math.cos(heading);
			double hz = Math.sin(heading);
			ParticleOptions body = variant == 5 ? ParticleTypes.SMOKE : ParticleTypes.CLOUD;
			BehaviorSupport.sendContained(level, body, shape, center, radius,
					x, bodyY, z, 3, 0.35 * scale, 0.3 * scale, 0.35 * scale, 0.005);
			BehaviorSupport.sendContained(level, ParticleTypes.END_ROD, shape, center, radius,
					x, bodyY + 0.5 * scale, z, 1, 0.03, 0.03, 0.03, 0.0);
			// The recolor accent: a secondary-dust chest blaze, every variant.
			BehaviorSupport.sendContained(level, chestDust, shape, center, radius,
					x + hx * 0.4 * scale, bodyY, z + hz * 0.4 * scale, 1, 0.05, 0.08, 0.05, 0.0);

			// Antlers: two dust-point arms swept up and back off the head.
			double headX = x + hx * 0.7 * scale;
			double headY = bodyY + 0.8 * scale;
			double headZ = z + hz * 0.7 * scale;
			double armLen = (variant == 1 ? 1.4 : 1.0) * scale;
			for (int side = -1; side <= 1; side += 2) {
				double sideAngle = heading + side * 2.2;
				double sx = Math.cos(sideAngle);
				double sz = Math.sin(sideAngle);
				for (int k = 1; k <= antlerPoints; k++) {
					double reach = armLen * k / antlerPoints;
					// The fork: mid-arm points branch slightly outward.
					double flare = k > antlerPoints / 2 ? 0.12 * scale * side : 0.0;
					BehaviorSupport.sendContained(level, antlerDust, shape, center, radius,
							headX + sx * reach - hz * flare, headY + 0.55 * reach, headZ + sz * reach + hx * flare,
							1, 0.0, 0.0, 0.0, 0.0);
				}

				if (variant == 6) {
					// The crown: a glint on each antler tip.
					BehaviorSupport.sendContained(level, ParticleTypes.WAX_ON, shape, center, radius,
							headX + sx * armLen, headY + 0.55 * armLen + 0.1, headZ + sz * armLen, 1, 0.05, 0.05, 0.05, 0.0);
				}
			}

			// Hoof-falls on quantized arc steps just behind the stag, alternating gait.
			ParticleOptions hoof = variant == 4 ? ParticleTypes.SMALL_FLAME : ParticleTypes.GLOW;
			double step = 0.7 * scale / Math.max(1.0, arcDist);
			for (int h = 1; h <= 2; h++) {
				double printAngle = Math.floor((angle - h * step) / step) * step;
				double lateral = (Math.floorMod((long) Math.floor(printAngle / step), 2L) == 0L ? 1 : -1) * 0.25 * scale;
				BehaviorSupport.sendContained(level, hoof, shape, center, radius,
						center.x + Math.cos(printAngle) * arcDist - hz * lateral,
						center.y + 0.1,
						center.z + Math.sin(printAngle) * arcDist + hx * lateral,
						1, 0.02, 0.02, 0.02, 0.0);
			}

			if (variant == 3) {
				// Frost breath ahead of the muzzle, snow motes settling on the back.
				BehaviorSupport.sendContained(level, ParticleTypes.SNOWFLAKE, shape, center, radius,
						headX + hx * 0.3 * scale, headY - 0.15, headZ + hz * 0.3 * scale, 1, 0.05, 0.05, 0.05, 0.02);
				BehaviorSupport.sendContained(level, ParticleTypes.SNOWFLAKE, shape, center, radius,
						x - hx * 0.3 * scale, bodyY + 0.4 * scale, z - hz * 0.3 * scale, 1, 0.1, 0.05, 0.1, 0.0);
			} else if (variant == 5) {
				// The ash veil drifting off the shade's flanks.
				BehaviorSupport.sendContained(level, ParticleTypes.ASH, shape, center, radius,
						x - hx * 0.4 * scale, bodyY + 0.2 * scale, z - hz * 0.4 * scale, 2, 0.2, 0.15, 0.2, 0.0);
			}
		}
	}
}

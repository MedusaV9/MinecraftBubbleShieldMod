package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Aurora curtains: sinusoidally waving ribbons of light hanging high inside the
 * bubble, slowly drifting around the vertical axis. Ribbons sit in the upper
 * half so they read in both sphere and dome shapes.
 *
 * <ul>
 * <li>v0: one palette-dust ribbon</li>
 * <li>v1: two counter-phased ribbons, primary and secondary strands</li>
 * <li>v2: one gradient ribbon (primary-to-secondary transition dust)</li>
 * <li>v3: three thin fast ribbons</li>
 * <li>v4: one end-rod ribbon with strong vertical waving</li>
 * <li>v5: one slow glow ribbon low over the floor</li>
 * <li>v6: two gradient ribbons crossing at right angles</li>
 * </ul>
 */
public final class AuroraRibbons implements InsideEffectBehavior {
	public static final String ID = "aurora_ribbons";
	private static final int MAX_POINTS = 128;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int ribbons = switch (variant) {
			case 1, 6 -> 2;
			case 3 -> 3;
			default -> 1;
		};
		double drift = gameTime / 10.0 * (variant == 3 ? 0.22 : variant == 5 ? 0.04 : 0.09);
		double waveAmp = radius * (variant == 4 ? 0.22 : 0.1);
		double height = radius * (variant == 5 ? 0.15 : 0.55);
		double span = radius * 0.8 * Mth.clamp(def.behaviorStrength(), 0.8F, 1.2F);
		int budget = MAX_POINTS / ribbons;
		int points = ctx.scaleCount(Mth.clamp((int) Math.round(span / 0.6), 8, budget), budget);
		for (int ribbon = 0; ribbon < ribbons; ribbon++) {
			ParticleOptions particle = ribbonParticle(variant, ribbon, def, ctx);
			double yaw = drift + (variant == 6 ? Math.PI / 2.0 * ribbon : Math.PI * ribbon);
			double dirX = Math.cos(yaw);
			double dirZ = Math.sin(yaw);
			double phaseOffset = ribbon * Math.PI;
			for (int i = 0; i < points; i++) {
				double t = -1.0 + 2.0 * i / (points - 1);
				double wave = Math.sin(t * 3.0 + gameTime / 10.0 * 0.6 + phaseOffset);
				double x = center.x + dirX * span * t - dirZ * waveAmp * 0.3 * wave;
				double y = center.y + height + waveAmp * wave;
				double z = center.z + dirZ * span * t + dirX * waveAmp * 0.3 * wave;
				level.sendParticles(particle, true, false, x, y, z, 1, 0.05, 0.2, 0.05, 0.0);
			}
		}
	}

	private static ParticleOptions ribbonParticle(int variant, int ribbon, EffectDefinition def, ContextState ctx) {
		return switch (variant) {
			case 1 -> new DustParticleOptions((ribbon == 0
					? ctx.pickColor(def.argbPrimary(), def.argbSecondary())
					: ctx.secondaryColor(def.argbSecondary())) & 0xFFFFFF, 1.1F);
			case 2, 6 -> new DustColorTransitionOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF,
					ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 1.2F);
			case 3 -> new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 0.8F);
			case 4 -> ParticleTypes.END_ROD;
			case 5 -> ParticleTypes.GLOW;
			default -> new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.3F);
		};
	}
}

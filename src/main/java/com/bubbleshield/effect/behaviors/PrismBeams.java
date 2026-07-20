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
 * Rotating light beams: vertical dust columns standing on a ring around the
 * projector, sweeping slowly like lighthouse prisms, each capped with a glint.
 *
 * <ul>
 * <li>v0: three palette-dust beams</li>
 * <li>v1: four thinner beams, faster sweep</li>
 * <li>v2: three gradient beams (primary-to-secondary transition)</li>
 * <li>v3: two thick counter-rotating beams</li>
 * <li>v4: three beams that tilt outward like searchlights</li>
 * <li>v5: five short beams hugging the floor</li>
 * <li>v6: three end-rod beams with firework caps</li>
 * </ul>
 */
public final class PrismBeams implements InsideEffectBehavior {
	public static final String ID = "prism_beams";
	private static final int MAX_POINTS = 120;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int beams = switch (variant) {
			case 1 -> 4;
			case 3 -> 2;
			case 5 -> 5;
			default -> 3;
		};
		double sweep = gameTime / 10.0 * switch (variant) {
			case 1 -> 0.25;
			case 3 -> -0.1;
			default -> 0.1;
		};
		double ringDist = radius * 0.5 * Mth.clamp(def.behaviorStrength(), 0.8F, 1.2F);
		double beamHeight = radius * (variant == 5 ? 0.25 : 0.75);
		ParticleOptions dust = switch (variant) {
			case 2 -> new DustColorTransitionOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF,
					ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 1.2F);
			case 6 -> ParticleTypes.END_ROD;
			default -> new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF,
					variant == 3 ? 1.6F : variant == 1 ? 0.9F : 1.2F);
		};
		int budget = MAX_POINTS / beams;
		int steps = ctx.scaleCount(Mth.clamp((int) (beamHeight / 0.4), 5, budget), budget);
		for (int beam = 0; beam < beams; beam++) {
			double angle = sweep + Math.PI * 2.0 * beam / beams;
			double baseX = center.x + Math.cos(angle) * ringDist;
			double baseZ = center.z + Math.sin(angle) * ringDist;
			// Searchlights lean outward, staying inside the wall at full height.
			double tilt = variant == 4 ? 0.35 : 0.0;
			double topX = baseX + Math.cos(angle) * beamHeight * tilt;
			double topZ = baseZ + Math.sin(angle) * beamHeight * tilt;
			for (int i = 0; i <= steps; i++) {
				double t = (double) i / steps;
				level.sendParticles(dust, true, false,
						Mth.lerp(t, baseX, topX), center.y + 0.2 + beamHeight * t, Mth.lerp(t, baseZ, topZ),
						1, 0.04, 0.1, 0.04, 0.0);
			}

			ParticleOptions cap = variant == 6 ? ParticleTypes.FIREWORK : ParticleTypes.GLOW;
			level.sendParticles(cap, true, false, topX, center.y + 0.2 + beamHeight, topZ, 1, 0.05, 0.05, 0.05, variant == 6 ? 0.05 : 0.0);
		}
	}
}

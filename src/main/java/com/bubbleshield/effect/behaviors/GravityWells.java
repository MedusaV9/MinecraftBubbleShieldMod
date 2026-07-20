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
 * Orbiting "gravity wells": points circling the projector, each surrounded by
 * concentric rings that contract into the well over a four-pulse cycle, reading
 * as suction. All well anchors stay above the center plane (dome-safe).
 *
 * <ul>
 * <li>v0: two wells of portal motes</li>
 * <li>v1: three wells of reverse-portal motes</li>
 * <li>v2: one heavy well of palette dust</li>
 * <li>v3: two fast wells of squid ink</li>
 * <li>v4: three end rod wells stacked at rising heights</li>
 * <li>v5: two slow witch-magic wells</li>
 * <li>v6: four small sculk-soul wells</li>
 * </ul>
 */
public final class GravityWells implements InsideEffectBehavior {
	public static final String ID = "gravity_wells";
	private static final int MAX_POINTS = 128;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int wells = switch (variant) {
			case 1, 4 -> 3;
			case 2 -> 1;
			case 6 -> 4;
			default -> 2;
		};
		ParticleOptions particle = switch (variant) {
			case 1 -> ParticleTypes.REVERSE_PORTAL;
			case 2 -> new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.3F);
			case 3 -> ParticleTypes.SQUID_INK;
			case 4 -> ParticleTypes.END_ROD;
			case 5 -> ParticleTypes.WITCH;
			case 6 -> ParticleTypes.SCULK_SOUL;
			default -> ParticleTypes.PORTAL;
		};
		double orbitSpeed = switch (variant) {
			case 3 -> 0.5;
			case 5 -> 0.08;
			default -> 0.2;
		};
		// The suction cycle: rings contract from the outer rim onto the well anchor.
		long phase = gameTime / 10L % 4L;
		double wellReach = Math.min(radius * 0.28, 3.0) * Mth.clamp(def.behaviorStrength(), 0.8F, 1.5F);
		double orbit = gameTime / 10.0 * orbitSpeed;
		int budgetPerWell = MAX_POINTS / (wells * 2);
		for (int well = 0; well < wells; well++) {
			double wellAngle = orbit + Math.PI * 2.0 * well / wells;
			double anchorDist = radius * (variant == 2 ? 0.0 : 0.5);
			double wx = center.x + Math.cos(wellAngle) * anchorDist;
			double wy = center.y + radius * (variant == 4 ? 0.25 + 0.2 * well : 0.4);
			double wz = center.z + Math.sin(wellAngle) * anchorDist;
			// Two rings per well, one pulse cycle apart, both falling inward.
			for (int ring = 0; ring < 2; ring++) {
				double shrink = ((phase + ring * 2L) % 4L) / 3.0;
				double ringRadius = wellReach * (1.0 - 0.8 * shrink) + 0.2;
				int points = ctx.scaleCount(Mth.clamp((int) Math.round(Math.PI * 2.0 * ringRadius / 0.7), 4, budgetPerWell), budgetPerWell);
				for (int i = 0; i < points; i++) {
					double angle = Math.PI * 2.0 * i / points;
					level.sendParticles(particle, true, false,
							wx + Math.cos(angle) * ringRadius, wy + (ring == 0 ? 0.0 : 0.4), wz + Math.sin(angle) * ringRadius, 1, 0.02, 0.02, 0.02, 0.0);
				}
			}
		}
	}
}

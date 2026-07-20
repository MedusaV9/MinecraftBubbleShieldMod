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
 * Wandering dust devils: small twisters that patrol the shield floor, each a
 * short helix of particles tapering upward. Twister anchors circle the
 * projector on staggered orbits so they never leave the wall.
 *
 * <ul>
 * <li>v0: two dust-plume twisters</li>
 * <li>v1: three smaller poof twisters</li>
 * <li>v2: two palette-dust twisters</li>
 * <li>v3: one tall cloud twister</li>
 * <li>v4: three fast white-smoke twisters</li>
 * <li>v5: two slow mycelium twisters hugging the ground</li>
 * <li>v6: two crimson-spore twisters with an ash haze between them</li>
 * </ul>
 */
public final class SandDevils implements InsideEffectBehavior {
	public static final String ID = "sand_devils";
	private static final int MAX_POINTS = 128;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int devils = switch (variant) {
			case 1, 4 -> 3;
			case 3 -> 1;
			default -> 2;
		};
		ParticleOptions particle = switch (variant) {
			case 1 -> ParticleTypes.POOF;
			case 2 -> new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.2F);
			case 3 -> ParticleTypes.CLOUD;
			case 4 -> ParticleTypes.WHITE_SMOKE;
			case 5 -> ParticleTypes.MYCELIUM;
			case 6 -> ParticleTypes.CRIMSON_SPORE;
			default -> ParticleTypes.DUST_PLUME;
		};
		double wander = gameTime / 10.0 * (variant == 4 ? 0.3 : variant == 5 ? 0.05 : 0.12);
		double spin = gameTime / 10.0 * 1.1;
		double coreHeight = radius * (variant == 3 ? 0.7 : variant == 5 ? 0.2 : 0.4) * Mth.clamp(def.behaviorStrength(), 0.8F, 1.3F);
		int turns = variant == 5 ? 4 : 8;
		int budget = MAX_POINTS / devils;
		int points = ctx.scaleCount(Mth.clamp(turns * 3, 9, budget), budget);
		for (int devil = 0; devil < devils; devil++) {
			double orbitAngle = wander + Math.PI * 2.0 * devil / devils;
			double orbitDist = radius * 0.55;
			double baseX = center.x + Math.cos(orbitAngle) * orbitDist;
			double baseZ = center.z + Math.sin(orbitAngle) * orbitDist;
			for (int i = 0; i < points; i++) {
				double t = (double) i / points;
				double swirl = spin + t * Math.PI * 2.0 * turns / 3.0 + devil;
				// The funnel tapers: wide skirt at the floor, tight tip on top.
				double funnel = (0.9 - 0.7 * t) * Math.min(radius * 0.18, 1.4);
				level.sendParticles(particle, true, false,
						baseX + Math.cos(swirl) * funnel, center.y + 0.1 + coreHeight * t, baseZ + Math.sin(swirl) * funnel,
						1, 0.03, 0.05, 0.03, 0.0);
			}
		}

		if (variant == 6) {
			// A thin ash haze drifting between the twisters.
			int haze = ctx.scaleCount(8, 16);
			for (int i = 0; i < haze; i++) {
				double angle = wander * 0.7 + Math.PI * 2.0 * i / haze;
				level.sendParticles(ParticleTypes.ASH, true, false,
						center.x + Math.cos(angle) * radius * 0.3, center.y + 0.6, center.z + Math.sin(angle) * radius * 0.3,
						1, 0.3, 0.2, 0.3, 0.0);
			}
		}
	}
}

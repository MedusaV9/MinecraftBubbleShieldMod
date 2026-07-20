package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Shimmering tide pools on the shield floor: fixed pool spots ringed with
 * ripples that expand outward in turn, as if raindrops kept landing in them.
 *
 * <ul>
 * <li>v0: three splash pools</li>
 * <li>v1: four smaller bubble pools rippling faster</li>
 * <li>v2: three glinting pools with a nautilus swirl above the active one</li>
 * <li>v3: two large pools with dolphin arcs leaping between them</li>
 * <li>v4: three underwater-mote pools (submerged look)</li>
 * <li>v5: five micro-pools twinkling in sequence</li>
 * <li>v6: three glow pools shimmering teal at night-depth</li>
 * </ul>
 */
public final class TidePools implements InsideEffectBehavior {
	public static final String ID = "tide_pools";
	private static final int MAX_RING = 32;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int pools = switch (variant) {
			case 1 -> 4;
			case 3 -> 2;
			case 5 -> 5;
			default -> 3;
		};
		SimpleParticleType ripple = switch (variant) {
			case 1 -> ParticleTypes.BUBBLE_POP;
			case 4 -> ParticleTypes.UNDERWATER;
			case 6 -> ParticleTypes.GLOW;
			default -> ParticleTypes.SPLASH;
		};
		long pulse = gameTime / 10L;
		double ripplePhase = pulse % 3L / 2.0;
		double poolDist = radius * 0.5 * Mth.clamp(def.behaviorStrength(), 0.8F, 1.2F);
		double poolSize = Math.min(radius * 0.2, variant == 3 ? 2.2 : 1.2);
		int budget = MAX_RING;
		for (int pool = 0; pool < pools; pool++) {
			double angle = Math.PI * 2.0 * pool / pools + 1.1;
			double x = center.x + Math.cos(angle) * poolDist;
			double z = center.z + Math.sin(angle) * poolDist;
			// One raindrop ring per pool, expanding over three pulses; pools take turns.
			boolean active = pulse % pools == pool;
			double ringRadius = poolSize * (0.3 + 0.7 * ripplePhase);
			int points = ctx.scaleCount(Mth.clamp((int) (ringRadius * 8.0), 5, budget / pools), budget / pools);
			for (int i = 0; i < points; i++) {
				double a = Math.PI * 2.0 * i / points;
				level.sendParticles(ripple, true, false,
						x + Math.cos(a) * ringRadius, center.y + 0.12, z + Math.sin(a) * ringRadius, 1, 0.03, 0.02, 0.03, 0.0);
			}

			if (!active) {
				continue;
			}

			if (variant == 2) {
				// Nautilus glints spiral in over the active pool (count=0 fly-towards form).
				level.sendParticles(ParticleTypes.NAUTILUS, true, false, x, center.y + 0.6, z, 0,
						Math.cos(pulse) * 0.8, 0.4, Math.sin(pulse) * 0.8, 1.0);
			} else if (variant == 5) {
				level.sendParticles(ParticleTypes.END_ROD, true, false, x, center.y + 0.3, z, 1, 0.05, 0.05, 0.05, 0.01);
			} else if (variant == 6) {
				level.sendParticles(ParticleTypes.GLOW_SQUID_INK, true, false, x, center.y + 0.2, z, ctx.scaleCount(3, 6), 0.2, 0.05, 0.2, 0.0);
			}
		}

		if (variant == 3 && pulse % 4L == 0L) {
			// A dolphin arc hops from pool to pool.
			double from = 1.1;
			double to = 1.1 + Math.PI;
			int steps = ctx.scaleCount(10, 16);
			for (int i = 0; i <= steps; i++) {
				double t = (double) i / steps;
				double angle = Mth.lerp(t, from, to);
				double arcY = center.y + 0.3 + Math.sin(t * Math.PI) * 1.5;
				level.sendParticles(ParticleTypes.DOLPHIN, true, false,
						center.x + Math.cos(angle) * poolDist, arcY, center.z + Math.sin(angle) * poolDist, 1, 0.05, 0.05, 0.05, 0.0);
			}
		}
	}
}

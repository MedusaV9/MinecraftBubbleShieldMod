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
 * Spectral riders galloping laps around a 0.7r track near the floor: each
 * rider is an end-rod head over cloud hoof-dust, bobbing with the gallop and
 * bursting a POOF when it completes a lap. A palette dust saddle rides every
 * ghost so the owner color override recolors the hunt.
 *
 * <ul>
 * <li>v0: two chasers in single file</li>
 * <li>v1: a counter-race (the two riders run opposite ways)</li>
 * <li>v2: a relay quartet (poof handoffs at the quarter-lap lines)</li>
 * <li>v3: a sprint (double gallop speed)</li>
 * <li>v4: hurdlers (hash-timed jump arcs over unseen fences)</li>
 * <li>v5: phantom trails (three dust after-images behind each rider)</li>
 * <li>v6: a stampede (more riders, heavier hoof-dust)</li>
 * </ul>
 */
public final class GhostRiders implements InsideEffectBehavior {
	public static final String ID = "ghost_riders";
	/** Worst case v6: 8 riders x (head 2 + saddle dust 1 + hoofs 3) = 48, plus the shared-clock lap pulse 8 x (poof 6 + dust 2) = 64 -> 112 particles/pulse (v5: 96, v2: 78). */
	private static final int MAX_RIDERS = 8;
	/** Particles in one lap-completion POOF burst. */
	private static final int POOF_BURST = 6;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int base = variant == 2 ? 4 : variant == 6 ? Mth.clamp((int) (4.0F * def.behaviorStrength()), 4, 6) : 2;
		int riders = ctx.scaleCount(base, variant == 6 ? MAX_RIDERS : 6);
		double omega = (variant == 3 ? 0.09 : 0.045) * def.behaviorStrength();
		double trackRadius = radius * 0.7;
		ParticleOptions saddle = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 0.8F);
		ParticleOptions trail = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.7F);
		for (int r = 0; r < riders; r++) {
			// v1 sends odd riders the opposite way around the track; v0 bunches
			// the field into a single chasing file instead of even spacing.
			double dir = variant == 1 && r % 2 == 1 ? -1.0 : 1.0;
			double slot = variant == 0 ? r * 0.5 : Math.PI * 2.0 * r / riders;
			double angle = slot + gameTime * omega * dir;
			double x = center.x + Math.cos(angle) * trackRadius;
			double z = center.z + Math.sin(angle) * trackRadius;
			double bob = Math.abs(Math.sin(gameTime * 0.25 + r * 1.3)) * 0.35;
			double headY = center.y + 0.9 + bob;
			if (variant == 4) {
				// The hurdle arc: a hash-timed leap once per ~4-second window.
				long window = (gameTime + r * 23L) / 80L;
				double jumpPhase = ((gameTime + r * 23L) % 80L) / 80.0;
				if (BehaviorSupport.hash01(BehaviorSupport.mix(window * 61L + r)) < 0.6) {
					headY += radius * 0.15 * Math.sin(jumpPhase * Math.PI);
				}
			}

			BehaviorSupport.sendContained(level, ParticleTypes.END_ROD, shape, center, radius,
					x, headY, z, 2, 0.08, 0.12, 0.08, 0.01);
			// The recolor accent: one palette dust saddle on every rider, every variant.
			BehaviorSupport.sendContained(level, saddle, shape, center, radius,
					x, headY - 0.3, z, 1, 0.06, 0.06, 0.06, 0.0);
			BehaviorSupport.sendContained(level, ParticleTypes.CLOUD, shape, center, radius,
					x, center.y + 0.12, z, variant == 6 ? 3 : 2, 0.15, 0.03, 0.15, 0.01);
			if (variant == 5) {
				for (int k = 1; k <= 3; k++) {
					// After-images strung out behind the rider along the track.
					double back = angle - dir * 0.12 * k;
					BehaviorSupport.sendContained(level, trail, shape, center, radius,
							center.x + Math.cos(back) * trackRadius, headY - 0.05 * k, center.z + Math.sin(back) * trackRadius,
							1, 0.05, 0.05, 0.05, 0.0);
				}
			}

			// The burst: lap completion, or v2's quarter-lap relay handoffs.
			double lapLine = variant == 2 ? Math.PI / 2.0 : Math.PI * 2.0;
			if (crossedLine(angle - slot, dir * omega * 10.0, lapLine)) {
				BehaviorSupport.sendContained(level, ParticleTypes.POOF, shape, center, radius,
						x, center.y + 0.6, z, POOF_BURST, 0.25, 0.25, 0.25, 0.05);
				BehaviorSupport.sendContained(level, saddle, shape, center, radius,
						x, center.y + 0.9, z, 2, 0.2, 0.2, 0.2, 0.0);
			}
		}
	}

	/** Whether the rider's own lap progress crossed a multiple of {@code line} since the previous pulse. */
	private static boolean crossedLine(double progress, double pulseStep, double line) {
		return Math.floor(progress / line) != Math.floor((progress - pulseStep) / line);
	}
}

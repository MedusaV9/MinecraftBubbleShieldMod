package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Specters in irons hauling their chains across the bubble floor: each spirit
 * is a soul column trudging between hash-seeded waypoints, dragging a
 * segmented chain of palette dust links that sags behind it, with scrape
 * sparks flaring on the drag beats. Waypoints derive from the shield center
 * (no state), and the links use both palette strands, so the owner color
 * override reforges every chain.
 *
 * <ul>
 * <li>v0: two burdened specters on long hauls</li>
 * <li>v1: a coffle (three specters chained single file to one leader)</li>
 * <li>v2: heavy irons (longer chains, slower trudge, sparks every beat)</li>
 * <li>v3: restless rattlers (short quick hauls, crit flecks mid-drag)</li>
 * <li>v4: sculk-bound spirits (sculk-soul bodies, darker links)</li>
 * <li>v5: anchor-draggers (a smoke plume where the chain end gouges the floor)</li>
 * <li>v6: a chain-gang ring (specters spaced on one circle, chains linking on)</li>
 * </ul>
 */
public final class ChainedSpecters implements InsideEffectBehavior {
	public static final String ID = "chained_specters";
	/** Worst case v2 at full context scale: 4 specters x (body 3 + head 1 + links 7 + sparks 2) = 52 particles/pulse. */
	private static final int MAX_SPECTERS = 4;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int base = switch (variant) {
			case 0 -> 2;
			case 1, 6 -> 3;
			default -> 2;
		};
		int specters = ctx.scaleCount(Math.max(2, Math.round(base * def.behaviorStrength())), MAX_SPECTERS);
		long haulTicks = switch (variant) {
			case 2 -> 120L;
			case 3 -> 40L;
			default -> 80L;
		};
		int links = variant == 2 ? 7 : 5;
		double chainLen = Math.clamp(radius * (variant == 2 ? 0.3 : 0.22), 1.0, variant == 2 ? 4.5 : 3.0);
		// Per-shield identity: waypoints are seeded from the projector position.
		long shieldSeed = (long) Math.floor(center.x) * 341873128712L + (long) Math.floor(center.z) * 132897987541L;
		ParticleOptions body = variant == 4 ? ParticleTypes.SCULK_SOUL : ParticleTypes.SOUL;
		ParticleOptions linkA = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, variant == 2 ? 1.1F : 0.8F);
		ParticleOptions linkB = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, variant == 2 ? 0.9F : 0.6F);
		long pulse = gameTime / 10L;
		for (int s = 0; s < specters; s++) {
			Vec3 pos;
			Vec3 back;
			if (variant == 6) {
				// The chain-gang ring: specters share one circle, chains trailing
				// along the arc to the specter behind.
				double angle = gameTime * 0.02 + Math.PI * 2.0 * s / specters;
				double ringRadius = radius * 0.6;
				pos = new Vec3(center.x + Math.cos(angle) * ringRadius, center.y + 0.25, center.z + Math.sin(angle) * ringRadius);
				double trail = angle - chainLen / ringRadius;
				back = new Vec3(center.x + Math.cos(trail) * ringRadius, center.y + 0.25, center.z + Math.sin(trail) * ringRadius);
			} else {
				long haul = gameTime / haulTicks;
				double t = (gameTime % haulTicks) / (double) haulTicks;
				// v1 chains the coffle to the leader's waypoints, offset a haul each.
				int track = variant == 1 ? 0 : s;
				long lag = variant == 1 ? s : 0L;
				Vec3 from = waypoint(center, radius, shieldSeed, track, haul - lag);
				Vec3 to = waypoint(center, radius, shieldSeed, track, haul - lag + 1L);
				pos = from.lerp(to, t);
				back = pos.lerp(from, Math.min(1.0, chainLen / Math.max(1.0e-4, pos.distanceTo(from))));
			}

			// The specter: a soul column with a glow head.
			BehaviorSupport.sendContained(level, body, shape, center, radius,
					pos.x, pos.y + 0.7, pos.z, 3, 0.1, 0.35, 0.1, 0.02);
			BehaviorSupport.sendContained(level, ParticleTypes.GLOW, shape, center, radius,
					pos.x, pos.y + 1.3, pos.z, 1, 0.04, 0.04, 0.04, 0.0);
			for (int k = 1; k <= links; k++) {
				// The chain: alternating palette links sagging toward the drag end.
				double f = k / (double) (links + 1);
				double sag = 0.35 * Math.sin(Math.PI * f);
				Vec3 link = pos.lerp(back, f);
				BehaviorSupport.sendContained(level, k % 2 == 0 ? linkB : linkA, shape, center, radius,
						link.x, link.y + 0.25 - sag * 0.2, link.z, 1, 0.02, 0.02, 0.02, 0.0);
			}

			// The drag beat: sparks where the chain end bites the floor.
			boolean beat = variant == 2 || (pulse + s) % 2L == 0L;
			if (beat) {
				BehaviorSupport.sendContained(level, ParticleTypes.SCRAPE, shape, center, radius,
						back.x, back.y + 0.1, back.z, 2, 0.12, 0.05, 0.12, 0.02);
			}

			if (variant == 3 && !beat) {
				// The rattle: crit flecks jumping off the mid-chain between beats.
				Vec3 mid = pos.lerp(back, 0.5);
				BehaviorSupport.sendContained(level, ParticleTypes.CRIT, shape, center, radius,
						mid.x, mid.y + 0.3, mid.z, 2, 0.15, 0.1, 0.15, 0.05);
			} else if (variant == 5) {
				BehaviorSupport.sendContained(level, ParticleTypes.SMOKE, shape, center, radius,
						back.x, back.y + 0.15, back.z, 2, 0.1, 0.08, 0.1, 0.01);
			}
		}
	}

	/**
	 * The hash-seeded floor waypoint for one specter and haul: within 0.7r
	 * horizontally, slightly above the center plane (dome-safe by construction).
	 */
	private static Vec3 waypoint(Vec3 center, float radius, long shieldSeed, int specter, long haul) {
		long seed = BehaviorSupport.mix(shieldSeed + haul * 613L + specter * 41L);
		double angle = BehaviorSupport.hash01(seed) * Math.PI * 2.0;
		double dist = Math.sqrt(BehaviorSupport.hash01(seed + 1L)) * radius * 0.7;
		return new Vec3(center.x + Math.cos(angle) * dist, center.y + 0.25, center.z + Math.sin(angle) * dist);
	}
}

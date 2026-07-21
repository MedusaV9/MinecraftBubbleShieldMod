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
 * A loping spectral wolf pack bounding between hash-seeded floor waypoints:
 * each wolf is a cloud-puff body under a pair of end-rod eyes, hopping through
 * three bounds per leg, huffing a snowflake breath ahead of the muzzle and
 * pressing a palette dust paw print into the floor every pulse. Waypoints are
 * seeded from the projector position, so every shield hosts its own pack --
 * pure particles, no entities, no state, no cleanup.
 *
 * <ul>
 * <li>v0: a pack of four loping wolves</li>
 * <li>v1: a restless pack of six (short quick legs)</li>
 * <li>v2: a lone alpha (larger frame, a secondary-dust mane)</li>
 * <li>v3: frost wolves (double breath, a snowflake blizzard wake)</li>
 * <li>v4: a shadow pack (smoke bodies)</li>
 * <li>v5: five pups (half height, quick legs)</li>
 * <li>v6: a single-file patrol circling the projector</li>
 * </ul>
 */
public final class GhostWolves implements InsideEffectBehavior {
	public static final String ID = "ghost_wolves";
	/** Worst case v3 at ctx-max pack: 6 wolves x (body 2 + eyes 2 + breath 2 + paw dust 1 + wake 2) = 54 particles/pulse. */
	private static final int MAX_WOLVES = 6;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int pack = ctx.scaleCount(switch (variant) {
			case 1 -> 6;
			case 2 -> 1;
			case 5 -> 5;
			default -> 4;
		}, MAX_WOLVES);
		long legTicks = variant == 1 || variant == 5 ? 30L : 60L;
		long leg = gameTime / legTicks;
		double t = (gameTime % legTicks) / (double) legTicks;
		// Per-shield identity: waypoints are seeded from the projector position.
		long shieldSeed = (long) Math.floor(center.x) * 341873128712L + (long) Math.floor(center.z) * 132897987541L;
		double scale = (variant == 2 ? 1.3 : variant == 5 ? 0.55 : 1.0)
				* Math.min(1.0, radius * 0.2) * Mth.clamp(def.behaviorStrength(), 0.8F, 1.3F);
		ParticleOptions body = variant == 4 ? ParticleTypes.SMOKE : ParticleTypes.CLOUD;
		ParticleOptions pawDust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, variant == 2 ? 0.9F : 0.6F);
		for (int w = 0; w < pack; w++) {
			Vec3 from = waypoint(center, radius, shieldSeed, w, pack, leg, variant);
			Vec3 to = waypoint(center, radius, shieldSeed, w, pack, leg + 1L, variant);
			double dx = to.x - from.x;
			double dz = to.z - from.z;
			double heading = Math.abs(dx) + Math.abs(dz) < 1.0e-4 ? 0.0 : Math.atan2(dz, dx);
			double hx = Math.cos(heading);
			double hz = Math.sin(heading);
			// Three bounds per leg: the hop arc lifts the whole silhouette.
			double hop = 0.5 * scale * Math.abs(Math.sin(Math.PI * 3.0 * t));
			double x = from.x + dx * t;
			double y = from.y + hop;
			double z = from.z + dz * t;
			BehaviorSupport.sendContained(level, body, shape, center, radius,
					x, y + 0.35 * scale, z, 2, 0.18 * scale, 0.12 * scale, 0.18 * scale, 0.01);
			// The eye pair sits across the muzzle, perpendicular to the heading.
			double headX = x + hx * 0.45 * scale;
			double headY = y + 0.55 * scale;
			double headZ = z + hz * 0.45 * scale;
			double ex = Math.cos(heading + Math.PI / 2.0) * 0.12 * scale;
			double ez = Math.sin(heading + Math.PI / 2.0) * 0.12 * scale;
			BehaviorSupport.sendContained(level, ParticleTypes.END_ROD, shape, center, radius,
					headX + ex, headY, headZ + ez, 1, 0.0, 0.0, 0.0, 0.0);
			BehaviorSupport.sendContained(level, ParticleTypes.END_ROD, shape, center, radius,
					headX - ex, headY, headZ - ez, 1, 0.0, 0.0, 0.0, 0.0);
			BehaviorSupport.sendContained(level, ParticleTypes.SNOWFLAKE, shape, center, radius,
					headX + hx * 0.25 * scale, headY - 0.1, headZ + hz * 0.25 * scale,
					variant == 3 ? 2 : 1, 0.05, 0.05, 0.05, 0.02);
			// The recolor accent: a palette dust paw print under every wolf, every variant.
			BehaviorSupport.sendContained(level, pawDust, shape, center, radius,
					x, center.y + 0.1, z, 1, 0.08, 0.02, 0.08, 0.0);
			if (variant == 2) {
				// The alpha's mane: a secondary-dust strand across the shoulders.
				BehaviorSupport.sendContained(level,
						new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.9F),
						shape, center, radius,
						x - hx * 0.15 * scale, y + 0.75 * scale, z - hz * 0.15 * scale, 2, 0.12, 0.08, 0.12, 0.0);
			} else if (variant == 3) {
				// The blizzard wake swirling behind the bound.
				BehaviorSupport.sendContained(level, ParticleTypes.SNOWFLAKE, shape, center, radius,
						x - hx * 0.5 * scale, y + 0.3 * scale, z - hz * 0.5 * scale, 2, 0.15, 0.15, 0.15, 0.01);
			}
		}
	}

	/**
	 * The hash-seeded floor waypoint for one wolf and leg (within 0.65r
	 * horizontally, just above the center plane, dome-safe by construction).
	 * v6 walks a single-file ring instead, so the patrol circles the projector.
	 */
	private static Vec3 waypoint(Vec3 center, float radius, long shieldSeed, int wolf, int pack, long leg, int variant) {
		if (variant == 6) {
			double angle = leg * 0.55 + Math.PI * 2.0 * wolf / Math.max(1, pack);
			return new Vec3(center.x + Math.cos(angle) * radius * 0.55, center.y + 0.25, center.z + Math.sin(angle) * radius * 0.55);
		}

		long seed = BehaviorSupport.mix(shieldSeed + leg * 577L + wolf * 31L);
		double angle = BehaviorSupport.hash01(seed) * Math.PI * 2.0;
		double dist = Math.sqrt(BehaviorSupport.hash01(seed + 1L)) * radius * 0.65;
		return new Vec3(center.x + Math.cos(angle) * dist, center.y + 0.25, center.z + Math.sin(angle) * dist);
	}
}

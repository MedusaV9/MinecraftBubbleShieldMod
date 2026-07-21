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
 * A knee-height ecto-mist hedge maze drawn in cloud and white smoke: radial
 * spoke walls plus concentric arc walls form a slowly rotating spoke-and-arc
 * labyrinth, each wall pierced by hash-seeded gaps that re-roll every minute
 * (1200 ticks), so the way through keeps changing. A palette dust wisp crowns
 * every spoke so the owner color override recolors the maze.
 *
 * <p>Worst-case budget (v6, countMult 3): spokes 8 x (segments 6 + crown 1) =
 * 56, arcs 2 x 24 segments = 48; total 104 particles/pulse (&lt;= 128).
 * Non-v6 worst case (v3): 6 x (7 + crown 1 + snowflake 1) + 2 x 20 = 94.
 *
 * <ul>
 * <li>v0: the classic maze (five spokes, two arcs, slow clockwise turn)</li>
 * <li>v1: the counter maze (arcs and spokes rotate opposite ways)</li>
 * <li>v2: a will-o'-wisp warden (a glow drifter patrols the outer arc)</li>
 * <li>v3: the cold hedge (white-smoke walls, snowflake dusting)</li>
 * <li>v4: breathing walls (hedge height swells and sinks with a slow sigh)</li>
 * <li>v5: the broken maze (double the gaps, mist sighing out of each gap)</li>
 * <li>v6: the grand labyrinth (more spokes, denser arc segments)</li>
 * </ul>
 */
public final class EctoMistMaze implements InsideEffectBehavior {
	public static final String ID = "ecto_mist_maze";
	/** Worst case v6: 8 spokes x (6 segments + 1 crown) + 2 arcs x 24 segments = 104 particles/pulse. */
	private static final int MAX_SPOKES = 8;
	private static final int MAX_ARC_SEGMENTS = 24;
	/** Gap layout lifetime: gaps re-hash once this many ticks pass (one minute). */
	private static final long GAP_EPOCH_TICKS = 1200L;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		long epoch = gameTime / GAP_EPOCH_TICKS;
		double turn = gameTime * 0.0035 * Mth.clamp(def.behaviorStrength(), 0.5F, 1.5F);
		// Knee height, breathing in v4.
		double hedgeY = center.y + 0.5 + (variant == 4 ? 0.25 * Math.sin(gameTime * 0.02) : 0.0);
		double gapChance = variant == 5 ? 0.5 : 0.25;
		ParticleOptions mist = variant == 3 ? ParticleTypes.WHITE_SMOKE : ParticleTypes.CLOUD;
		ParticleOptions crown = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 0.8F);
		ParticleOptions arcDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.6F);

		int spokes = ctx.scaleCount(variant == 6 ? 7 : 5, variant == 6 ? MAX_SPOKES : 6);
		int spokeSegments = variant == 6 ? 6 : 7;
		for (int s = 0; s < Math.max(1, spokes); s++) {
			double angle = turn + Math.PI * 2.0 * s / Math.max(1, spokes);
			for (int seg = 0; seg < spokeSegments; seg++) {
				double frac = 0.2 + 0.55 * (seg + 0.5) / spokeSegments;
				if (isGap(epoch, 11L + s * 31L + seg, gapChance)) {
					if (variant == 5) {
						// The broken hedge sighs mist out of every missing segment.
						BehaviorSupport.sendContained(level, ParticleTypes.SMOKE, shape, center, radius,
								center.x + Math.cos(angle) * radius * frac, hedgeY + 0.3, center.z + Math.sin(angle) * radius * frac,
								1, 0.05, 0.1, 0.05, 0.01);
					}

					continue;
				}

				BehaviorSupport.sendContained(level, mist, shape, center, radius,
						center.x + Math.cos(angle) * radius * frac, hedgeY, center.z + Math.sin(angle) * radius * frac,
						1, radius * 0.02, 0.15, radius * 0.02, 0.0);
			}

			// The palette crown on the spoke's outer end (the recolor accent).
			BehaviorSupport.sendContained(level, crown, shape, center, radius,
					center.x + Math.cos(angle) * radius * 0.75, hedgeY + 0.25, center.z + Math.sin(angle) * radius * 0.75,
					1, 0.06, 0.06, 0.06, 0.0);
			if (variant == 3) {
				BehaviorSupport.sendContained(level, ParticleTypes.SNOWFLAKE, shape, center, radius,
						center.x + Math.cos(angle) * radius * 0.5, hedgeY + 0.35, center.z + Math.sin(angle) * radius * 0.5,
						1, 0.1, 0.08, 0.1, 0.01);
			}
		}

		double arcTurn = variant == 1 ? -turn : turn * 0.6;
		int arcSegments = ctx.scaleCount(variant == 6 ? 16 : 12, variant == 6 ? MAX_ARC_SEGMENTS : 20);
		for (int ring = 0; ring < 2; ring++) {
			double ringFrac = ring == 0 ? 0.35 : 0.65;
			for (int seg = 0; seg < Math.max(1, arcSegments); seg++) {
				// Arc slots start at 1001 so they never collide with spoke slots.
				if (isGap(epoch, 1001L + ring * 53L + seg, gapChance)) {
					continue;
				}

				double angle = arcTurn + Math.PI * 2.0 * seg / Math.max(1, arcSegments);
				// Alternate mist and secondary dust so the arcs read as hedges too.
				ParticleOptions wall = seg % 3 == 0 ? arcDust : mist;
				BehaviorSupport.sendContained(level, wall, shape, center, radius,
						center.x + Math.cos(angle) * radius * ringFrac, hedgeY, center.z + Math.sin(angle) * radius * ringFrac,
						1, 0.08, 0.15, 0.08, 0.0);
			}
		}

		if (variant == 2) {
			// The warden wisp walks the outer arc hunting for the current gap.
			double angle = gameTime * 0.03;
			BehaviorSupport.sendContained(level, ParticleTypes.GLOW, shape, center, radius,
					center.x + Math.cos(angle) * radius * 0.65, hedgeY + 0.4, center.z + Math.sin(angle) * radius * 0.65,
					2, 0.05, 0.05, 0.05, 0.01);
		}
	}

	/** Whether this wall segment is one of the epoch's hash-rolled gaps. */
	private static boolean isGap(long epoch, long slot, double gapChance) {
		return BehaviorSupport.hash01(BehaviorSupport.mix(epoch * 977L + slot)) < gapChance;
	}
}

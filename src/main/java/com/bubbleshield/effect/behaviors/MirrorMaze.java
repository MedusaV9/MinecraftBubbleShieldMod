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
 * A maze of thin vertical mirror panes arranged as spokes around the center: a
 * wandering END_ROD mote glides between hash-seeded waypoints, and whenever its
 * path stands past a pane's spoke angle this pulse, the pane "catches" it -- a
 * mirrored twin flashes on the reflected side with a CRIT glint at the pane.
 * Stateless: pane layout, waypoints and crossings all derive from gameTime and
 * {@link BehaviorSupport#hash01}.
 *
 * <ul>
 * <li>v0: four panes, one wandering mote</li>
 * <li>v1: six panes on a slowly turning carousel</li>
 * <li>v2: two motes wandering the maze together</li>
 * <li>v3: tall panes -- twice the pane height, glints doubled</li>
 * <li>v4: the shard gallery -- panes drawn in the darker secondary strand</li>
 * <li>v5: a glow mote (GLOW head) leaving a short trail</li>
 * <li>v6: eight narrow panes, dense hall-of-mirrors reflections</li>
 * </ul>
 */
public final class MirrorMaze implements InsideEffectBehavior {
	public static final String ID = "mirror_maze";
	/** Worst case v2: 96 pane dust + 2 motes x (head 1 + rider 1 + twin 2 + crit 4) = 112 particles/pulse. */
	private static final int MAX_PANE_POINTS = 96;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int panes = switch (variant) {
			case 1 -> 6;
			case 6 -> 8;
			default -> 4;
		};
		double strength = Mth.clamp(def.behaviorStrength(), 0.7F, 1.3F);
		// v3's tall panes trade reach for height so the pane top corner stays
		// within ~0.83r even at max strength (0.585r out, 0.58r up).
		double paneInner = radius * 0.2;
		double paneOuter = radius * (variant == 6 || variant == 3 ? 0.45 : 0.55) * strength;
		double paneHalfH = radius * (variant == 3 ? 0.28 : 0.15);
		double paneMidY = center.y + radius * (variant == 3 ? 0.3 : 0.25);
		// v1's carousel slowly turns the whole pane array.
		double arrayPhase = variant == 1 ? gameTime / 10.0 * 0.03 : 0.0;
		ParticleOptions paneDust = new DustParticleOptions((variant == 4
				? ctx.secondaryColor(def.argbSecondary())
				: ctx.pickColor(def.argbPrimary(), def.argbSecondary())) & 0xFFFFFF, 0.8F);
		ParticleOptions glintDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 1.1F);
		int budget = MAX_PANE_POINTS / panes;
		int perPane = ctx.scaleCount(Mth.clamp((int) (paneHalfH * 12.0), 6, budget), budget);
		for (int p = 0; p < panes; p++) {
			double a = arrayPhase + Math.PI * 2.0 * p / panes;
			// The pane: a thin vertical sheet of dust along its spoke.
			for (int k = 0; k < perPane; k++) {
				long seed = BehaviorSupport.mix(gameTime / 10L * 131L + p * 17L + k);
				double d = paneInner + (paneOuter - paneInner) * BehaviorSupport.hash01(seed);
				double y = paneMidY + (BehaviorSupport.hash01(seed + 1L) - 0.5) * 2.0 * paneHalfH;
				BehaviorSupport.sendContained(level, paneDust, shape, center, radius,
						center.x + Math.cos(a) * d, y, center.z + Math.sin(a) * d, 1, 0.01, 0.03, 0.01, 0.0);
			}
		}

		int motes = variant == 2 ? 2 : 1;
		long segTicks = 40L;
		long segment = gameTime / segTicks;
		double t = (gameTime % segTicks) / (double) segTicks;
		for (int m = 0; m < motes; m++) {
			Vec3 from = wanderPoint(center, radius, paneMidY - center.y, m, segment);
			Vec3 to = wanderPoint(center, radius, paneMidY - center.y, m, segment + 1L);
			Vec3 pos = from.lerp(to, t);
			ParticleOptions head = variant == 5 ? ParticleTypes.GLOW : ParticleTypes.END_ROD;
			BehaviorSupport.sendContained(level, head, shape, center, radius,
					pos.x, pos.y, pos.z, 1, 0.03, 0.03, 0.03, 0.0);
			// The palette rider keeps the mote recolorable in every variant.
			BehaviorSupport.sendContained(level, glintDust, shape, center, radius,
					pos.x, pos.y - 0.12, pos.z, 1, 0.04, 0.04, 0.04, 0.0);
			if (variant == 5) {
				BehaviorSupport.sendContained(level, ParticleTypes.GLOW, shape, center, radius,
						pos.x, pos.y - 0.25, pos.z, 1, 0.05, 0.05, 0.05, 0.0);
			}

			// Pane crossing: the pane whose spoke sector the mote stands in this
			// pulse "catches" it once per sector entry (quantized mote azimuth).
			int sector = sectorOf(center, pos, arrayPhase, panes);
			int prevSector = sectorOf(center, from, arrayPhase, panes);
			if (sector != prevSector) {
				double paneA = arrayPhase + Math.PI * 2.0 * sector / panes;
				// Reflect the mote across the pane's vertical plane through the center.
				Vec3 rel = new Vec3(pos.x - center.x, 0.0, pos.z - center.z);
				Vec3 n = new Vec3(-Math.sin(paneA), 0.0, Math.cos(paneA));
				double dot = rel.x * n.x + rel.z * n.z;
				Vec3 twin = new Vec3(pos.x - 2.0 * dot * n.x, pos.y, pos.z - 2.0 * dot * n.z);
				BehaviorSupport.sendContained(level, head, shape, center, radius,
						twin.x, twin.y, twin.z, 1, 0.03, 0.03, 0.03, 0.0);
				BehaviorSupport.sendContained(level, glintDust, shape, center, radius,
						twin.x, twin.y - 0.12, twin.z, 1, 0.04, 0.04, 0.04, 0.0);
				// The CRIT glint sparks at the pane face between mote and twin.
				Vec3 gate = pos.lerp(twin, 0.5);
				BehaviorSupport.sendContained(level, ParticleTypes.CRIT, shape, center, radius,
						gate.x, gate.y, gate.z, ctx.scaleCount(variant == 3 ? 4 : 2, variant == 3 ? 8 : 4), 0.06, 0.1, 0.06, 0.05);
			}
		}
	}

	/** The spoke sector (0..panes-1) a position's azimuth falls in, in the carousel frame. */
	private static int sectorOf(Vec3 center, Vec3 pos, double arrayPhase, int panes) {
		double a = Math.atan2(pos.z - center.z, pos.x - center.x) - arrayPhase;
		return (int) Math.floor(a / (Math.PI * 2.0) * panes + 2.0 * panes) % panes;
	}

	/**
	 * The mote's hash-seeded waypoint: inside 0.7r horizontally, hovering in the
	 * pane band (0.1r above the center plane up to the band mid, dome-safe).
	 */
	private static Vec3 wanderPoint(Vec3 center, float radius, double bandY, int mote, long segment) {
		long seed = BehaviorSupport.mix(segment * 269L + mote * 7L);
		double angle = BehaviorSupport.hash01(seed) * Math.PI * 2.0;
		double dist = (0.15 + 0.55 * BehaviorSupport.hash01(seed + 1L)) * radius;
		double y = center.y + radius * 0.1 + Math.max(0.0, bandY - radius * 0.1) * BehaviorSupport.hash01(seed + 2L);
		return new Vec3(center.x + Math.cos(angle) * dist, y, center.z + Math.sin(angle) * dist);
	}
}

package com.bubbleshield.effect.behaviors;

import java.util.ArrayList;
import java.util.List;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldGeometry;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Totem pillars slowly rotating around the projector, glow eyes at the crown,
 * flaring an end-rod gaze tracer toward the nearest player standing inside the
 * bubble. The tracer is purely cosmetic -- the sentinels never damage or affect
 * the player. Pillars are stacked palette dust (the owner recolor repaints the
 * watchposts) and everything is phase/hash-derived: no fields, no cleanup.
 *
 * <ul>
 * <li>v0: three watching totems, gaze flares on alternating beats</li>
 * <li>v1: four swift wardens on a faster ring</li>
 * <li>v2: tall totems with twin stacked eye pairs</li>
 * <li>v3: vigilant -- the tracer fires every pulse and ends in an enchanted-hit fleck</li>
 * <li>v4: watchfire crests (a small flame dances atop every pillar)</li>
 * <li>v5: counter-rotating patrol with wax-off eye glints</li>
 * <li>v6: one central monolith with four eyes wrapped in a dual dust spiral</li>
 * </ul>
 */
public final class SentinelTotems implements InsideEffectBehavior {
	public static final String ID = "sentinel_totems";
	/** Conservative worst case v1 (every totem tracing): 4 totems x (6 pillar dust + 1 crown + 2 eyes + 5 tracer dots) = 56 particles/pulse. */
	private static final int MAX_SEGMENTS = 6;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int totems = variant == 6 ? 1 : variant == 1 ? 4 : 3;
		int segments = ctx.scaleCount(Mth.clamp((int) (2.0F + radius * 0.04F * def.behaviorStrength()), 2, 4), MAX_SEGMENTS);
		double ringDist = variant == 6 ? 0.0 : radius * 0.55;
		double height = radius * (variant == 2 || variant == 6 ? 0.5 : 0.35);
		// v1 patrols faster; v5 walks the ring widdershins.
		double spin = gameTime / 10.0 * (variant == 1 ? 0.1 : 0.05) * (variant == 5 ? -1.0 : 1.0);
		long beat = gameTime / 10L;
		ParticleOptions pillarDust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.1F);
		ParticleOptions crownDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 1.3F);
		List<Player> inside = new ArrayList<>();
		for (Player player : level.getEntitiesOfClass(Player.class, AABB.ofSize(center, 2.0 * radius, 2.0 * radius, 2.0 * radius))) {
			if (ShieldGeometry.isInside(shape, center, radius, player.position())) {
				inside.add(player);
			}
		}

		for (int t = 0; t < totems; t++) {
			double angle = spin + Math.PI * 2.0 * t / totems;
			double bx = center.x + Math.cos(angle) * ringDist;
			double bz = center.z + Math.sin(angle) * ringDist;
			Vec3 head = new Vec3(bx, center.y + height, bz);
			for (int s = 0; s < segments; s++) {
				BehaviorSupport.sendContained(level, pillarDust, shape, center, radius,
						bx, center.y + height * (s + 0.5) / segments, bz, 1, 0.06, 0.06, 0.06, 0.0);
			}

			BehaviorSupport.sendContained(level, crownDust, shape, center, radius,
					head.x, head.y + 0.25, head.z, 1, 0.05, 0.05, 0.05, 0.0);
			emitEyes(level, shape, center, radius, variant, head, angle);
			if (variant == 4) {
				BehaviorSupport.sendContained(level, ParticleTypes.FLAME, shape, center, radius,
						head.x, head.y + 0.4, head.z, 1, 0.05, 0.08, 0.05, 0.01);
			} else if (variant == 5 && (beat + t) % 4L == 0L) {
				BehaviorSupport.sendContained(level, ParticleTypes.WAX_OFF, shape, center, radius,
						head.x, head.y + 0.1, head.z, 1, 0.1, 0.1, 0.1, 0.0);
			}

			// The gaze: a dotted end-rod tracer toward the nearest player inside
			// (particle-only; the target is never damaged or affected).
			Player target = nearest(inside, head);
			if (target != null && (variant == 3 || (beat + t) % 2L == 0L)) {
				Vec3 gaze = new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.8, target.getZ());
				for (int d = 1; d <= 5; d++) {
					Vec3 dot = head.lerp(gaze, d / 6.0);
					BehaviorSupport.sendContained(level, ParticleTypes.END_ROD, shape, center, radius,
							dot.x, dot.y, dot.z, 1, 0.02, 0.02, 0.02, 0.0);
				}

				if (variant == 3) {
					BehaviorSupport.sendContained(level, ParticleTypes.ENCHANTED_HIT, shape, center, radius,
							gaze.x, gaze.y, gaze.z, 1, 0.15, 0.15, 0.15, 0.02);
				}
			}
		}

		if (variant == 6) {
			// The monolith's dual spiral: alternating strands winding up the pillar.
			double wr = Mth.clamp(radius * 0.06F, 0.35F, 1.2F);
			for (int k = 0; k < 8; k++) {
				double wind = gameTime / 10.0 * 0.25 + k * 0.8;
				BehaviorSupport.sendContained(level, (k & 1) == 0 ? pillarDust : crownDust, shape, center, radius,
						center.x + Math.cos(wind) * wr, center.y + height * (k + 0.5) / 8.0, center.z + Math.sin(wind) * wr,
						1, 0.0, 0.0, 0.0, 0.0);
			}
		}
	}

	/** The crown eyes: a glow pair perpendicular to the radial line; v2/v6 stack a second pair. */
	private static void emitEyes(ServerLevel level, ShieldShape shape, Vec3 center, float radius, int variant, Vec3 head, double angle) {
		double face = angle + Math.PI / 2.0;
		double ex = Math.cos(face) * 0.18;
		double ez = Math.sin(face) * 0.18;
		int rows = variant == 2 || variant == 6 ? 2 : 1;
		for (int row = 0; row < rows; row++) {
			double y = head.y - 0.3 * row;
			BehaviorSupport.sendContained(level, ParticleTypes.GLOW, shape, center, radius,
					head.x + ex, y, head.z + ez, 1, 0.0, 0.0, 0.0, 0.0);
			BehaviorSupport.sendContained(level, ParticleTypes.GLOW, shape, center, radius,
					head.x - ex, y, head.z - ez, 1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	private static Player nearest(List<Player> players, Vec3 pos) {
		Player nearest = null;
		double best = Double.MAX_VALUE;
		for (Player player : players) {
			double dist = player.position().distanceToSqr(pos);
			if (dist < best) {
				best = dist;
				nearest = player;
			}
		}

		return nearest;
	}
}

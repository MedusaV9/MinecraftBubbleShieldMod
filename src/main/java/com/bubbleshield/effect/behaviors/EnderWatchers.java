package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.TrailParticleOption;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Floating pairs of enderman-like eyes watching from hash-cycled slots: each
 * pair materializes with a portal shimmer, holds its slot for a while, blinks
 * out with a poof and reappears elsewhere. The eyes are palette dust motes, so
 * the owner recolor changes the watchers' iris color.
 *
 * <ul>
 * <li>v0: three watching pairs</li>
 * <li>v1: eight brief pairs (half the hold time)</li>
 * <li>v2: ender streaks between slots (a TRAIL particle chases the new slot)</li>
 * <li>v3: reverse-portal fountains under each pair</li>
 * <li>v4: one giant eye (dust iris ring, bright pupil, enchant lashes)</li>
 * <li>v5: shy eyes that blink out while a player is within 4 blocks</li>
 * <li>v6: a constellation (pairs joined by dotted end-rod lines)</li>
 * </ul>
 */
public final class EnderWatchers implements InsideEffectBehavior {
	public static final String ID = "ender_watchers";
	/** Worst case v1: 8 pairs x (eyes 2 + shimmer 2) = 32 particles/pulse. */
	private static final int MAX_PAIRS = 8;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int dustRgb = ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF;
		ParticleOptions eyeDust = new DustParticleOptions(dustRgb, 0.8F);
		if (variant == 4) {
			emitGiantEye(level, shape, center, radius, def, ctx, gameTime, dustRgb);
			return;
		}

		int pairs = ctx.scaleCount(switch (variant) {
			case 0 -> 3;
			case 1 -> 8;
			case 6 -> 3;
			default -> 4;
		}, MAX_PAIRS);
		long hold = variant == 1 ? 20L : 40L;
		long slotCycle = gameTime / hold;
		double blinkFrac = (gameTime % hold) / (double) hold;
		for (int i = 0; i < pairs; i++) {
			Vec3 pos = slotPoint(center, radius, i, slotCycle);
			if (blinkFrac >= 0.75) {
				// Blinking out: a poof where the eyes were, no eyes this pulse.
				BehaviorSupport.sendContained(level, ParticleTypes.POOF, shape, center, radius,
						pos.x, pos.y, pos.z, 2, 0.15, 0.15, 0.15, 0.01);
				continue;
			}

			if (variant == 5 && playerNear(level, pos, 4.0)) {
				// Shy eyes vanish instantly while watched up close.
				BehaviorSupport.sendContained(level, ParticleTypes.POOF, shape, center, radius,
						pos.x, pos.y, pos.z, 2, 0.15, 0.15, 0.15, 0.01);
				continue;
			}

			// The eye pair sits perpendicular to the line of sight toward the center.
			double gaze = Math.atan2(center.z - pos.z, center.x - pos.x) + Math.PI / 2.0;
			double ex = Math.cos(gaze) * 0.18;
			double ez = Math.sin(gaze) * 0.18;
			BehaviorSupport.sendContained(level, eyeDust, shape, center, radius, pos.x + ex, pos.y, pos.z + ez, 1, 0.0, 0.0, 0.0, 0.0);
			BehaviorSupport.sendContained(level, eyeDust, shape, center, radius, pos.x - ex, pos.y, pos.z - ez, 1, 0.0, 0.0, 0.0, 0.0);
			BehaviorSupport.sendContained(level, ParticleTypes.PORTAL, shape, center, radius,
					pos.x, pos.y - 0.2, pos.z, 2, 0.25, 0.25, 0.25, 0.02);
			if (variant == 2 && blinkFrac == 0.0) {
				// The re-materialize streak: a trail flies from the OLD slot to the new one.
				Vec3 from = BehaviorSupport.containPoint(shape, center, radius, slotPoint(center, radius, i, slotCycle - 1L));
				Vec3 target = BehaviorSupport.containPoint(shape, center, radius, pos);
				level.sendParticles(new TrailParticleOption(target, dustRgb, 8), true, false,
						from.x, from.y, from.z, 2, 0.05, 0.05, 0.05, 0.0);
			} else if (variant == 3) {
				BehaviorSupport.sendContained(level, ParticleTypes.REVERSE_PORTAL, shape, center, radius,
						pos.x, pos.y - 0.5, pos.z, 2, 0.1, 0.2, 0.1, 0.02);
			} else if (variant == 6) {
				// Dotted end-rod line to the next watcher in the constellation.
				Vec3 next = slotPoint(center, radius, (i + 1) % pairs, slotCycle);
				for (int d = 1; d <= 4; d++) {
					Vec3 dot = pos.lerp(next, d / 5.0);
					BehaviorSupport.sendContained(level, ParticleTypes.END_ROD, shape, center, radius,
							dot.x, dot.y, dot.z, 1, 0.0, 0.0, 0.0, 0.0);
				}
			}
		}
	}

	/** v4: one giant eye -- a dust iris ring, a bright pupil and enchant "lashes" streaming in. */
	private static void emitGiantEye(ServerLevel level, ShieldShape shape, Vec3 center, float radius,
			EffectDefinition def, ContextState ctx, long gameTime, int dustRgb) {
		Vec3 pos = slotPoint(center, radius, 0, gameTime / 80L);
		ParticleOptions iris = new DustParticleOptions(dustRgb, 1.0F);
		ParticleOptions pupil = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 1.5F);
		int ring = ctx.scaleCount(10, 14);
		for (int i = 0; i < ring; i++) {
			double angle = Math.PI * 2.0 * i / ring;
			// The iris ring stands vertically, facing the projector.
			double gaze = Math.atan2(center.z - pos.z, center.x - pos.x) + Math.PI / 2.0;
			BehaviorSupport.sendContained(level, iris, shape, center, radius,
					pos.x + Math.cos(gaze) * Math.cos(angle) * 0.8,
					pos.y + Math.sin(angle) * 0.8,
					pos.z + Math.sin(gaze) * Math.cos(angle) * 0.8, 1, 0.0, 0.0, 0.0, 0.0);
		}

		BehaviorSupport.sendContained(level, pupil, shape, center, radius, pos.x, pos.y, pos.z, 1, 0.05, 0.05, 0.05, 0.0);
		// Lashes: enchant glyphs fly into the eye (count=0 form; spawn ring contained).
		for (int i = 0; i < 6; i++) {
			double angle = gameTime / 10.0 * 0.3 + Math.PI * 2.0 * i / 6.0;
			Vec3 spawn = BehaviorSupport.containPoint(shape, center, radius, new Vec3(
					pos.x + Math.cos(angle) * 1.4, pos.y + 0.5 * Math.sin(angle * 2.0), pos.z + Math.sin(angle) * 1.4));
			Vec3 target = BehaviorSupport.containPoint(shape, center, radius, pos);
			level.sendParticles(ParticleTypes.ENCHANT, true, false, target.x, target.y, target.z, 0,
					spawn.x - target.x, spawn.y - target.y, spawn.z - target.z, 1.0);
		}
	}

	private static boolean playerNear(ServerLevel level, Vec3 pos, double range) {
		for (Player player : level.getEntitiesOfClass(Player.class, AABB.ofSize(pos, range * 2.0, range * 2.0, range * 2.0))) {
			if (player.position().distanceToSqr(pos) <= range * range) {
				return true;
			}
		}

		return false;
	}

	/** The hash-cycled watcher slot: within 0.7r horizontally, 0.15r..0.55r above the plane. */
	private static Vec3 slotPoint(Vec3 center, float radius, int pair, long slotCycle) {
		long seed = BehaviorSupport.mix(slotCycle * 883L + pair * 7L);
		double angle = BehaviorSupport.hash01(seed) * Math.PI * 2.0;
		double dist = Math.sqrt(BehaviorSupport.hash01(seed + 1L)) * radius * 0.7;
		double y = radius * (0.15 + 0.4 * BehaviorSupport.hash01(seed + 2L));
		return new Vec3(center.x + Math.cos(angle) * dist, center.y + y, center.z + Math.sin(angle) * dist);
	}
}

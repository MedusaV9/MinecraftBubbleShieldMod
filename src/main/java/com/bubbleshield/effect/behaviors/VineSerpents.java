package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Vine serpents slithering up hash-seeded helical stalks: each serpent is a
 * chain of green dust segments winding from the floor toward the canopy, its
 * head marked by a palette dust mote, and when a climb completes the serpent
 * dissolves at the top in a leaf-fleck puff before re-seeding a new stalk --
 * purely particles, no entities, no state, no cleanup.
 *
 * <ul>
 * <li>v0: garden serpents (leisurely helices, a cherry-leaf dissolve)</li>
 * <li>v1: pale serpents (pale-oak dissolve, slimmer twin-strand bodies)</li>
 * <li>v2: braided pair (two serpents share each stalk half a turn apart)</li>
 * <li>v3: palette adders (segments tinted via TINTED_LEAVES in the palette color)</li>
 * <li>v4: quick vipers (twice the climb rate, sneeze-spore flecks at the dissolve)</li>
 * <li>v5: glowworm vines (a happy-villager glint rides every third segment)</li>
 * <li>v6: great wyrm (one thick serpent, doubled segments, mycelium dissolve)</li>
 * </ul>
 */
public final class VineSerpents implements InsideEffectBehavior {
	public static final String ID = "vine_serpents";
	/** One full floor-to-canopy climb per serpent; v4 halves this. */
	private static final long CLIMB_TICKS = 160L;
	private static final int MAX_SERPENTS = 6;
	/**
	 * Worst case v2 (countMult maxed, every staggered stalk dissolving on the same
	 * pulse): 6 stalks x 2 braided serpents x 8 segments + 6 dissolves x 4 flecks
	 * = 120 particles/pulse; v5 peaks at 6 x (8 segments + 2 glints) + 24 = 84 and
	 * v6 at 16 + 4 = 20 -- all under 128.
	 */
	private static final int MAX_SEGMENTS = 16;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		long climbTicks = variant == 4 ? CLIMB_TICKS / 2L : CLIMB_TICKS;
		int stalks = variant == 6 ? 1 : ctx.scaleCount(Mth.clamp((int) (radius * 0.4F * def.behaviorStrength()), 2, 4), MAX_SERPENTS);
		int segments = ctx.scaleCount(variant == 6 ? 12 : 6, variant == 6 ? MAX_SEGMENTS : 8);
		// Green body dust; v3 swaps the body for palette-tinted leaves instead.
		ParticleOptions body = variant == 3
				? ColorParticleOption.create(ParticleTypes.TINTED_LEAVES, ctx.pickColor(def.argbPrimary(), def.argbSecondary()))
				: new DustParticleOptions(0x3D8A2E, variant == 6 ? 1.4F : variant == 1 ? 0.6F : 0.9F);
		DustParticleOptions head = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, variant == 6 ? 1.3F : 1.0F);
		DustParticleOptions tailTip = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.7F);
		for (int s = 0; s < Math.max(1, stalks); s++) {
			long climb = gameTime / climbTicks;
			double t = (gameTime % climbTicks) / (double) climbTicks;
			emitSerpent(level, shape, center, radius, variant, s, climb, t, 0.0, segments, body, head, tailTip);
			if (variant == 2) {
				// The braid partner: same stalk, half a turn behind.
				emitSerpent(level, shape, center, radius, variant, s, climb, t, Math.PI, segments, body, head, tailTip);
			}

			// 0.85, not 0.9: the v4 quick-viper pulse grid (80-tick climbs sampled
			// every 10 ticks) tops out at t = 0.875 and must still dissolve.
			if (t >= 0.85) {
				emitDissolve(level, shape, center, radius, ctx, variant, stalkTop(center, radius, s, climb));
			}
		}
	}

	/** One serpent: dust segments trailing down the helix behind the climbing head. */
	private static void emitSerpent(ServerLevel level, ShieldShape shape, Vec3 center, float radius, int variant,
			int stalk, long climb, double t, double braidPhase, int segments, ParticleOptions body, ParticleOptions head, ParticleOptions tailTip) {
		for (int k = 0; k < segments; k++) {
			// Segment k hangs a fixed climb-fraction behind the head.
			double segT = t - k * 0.035;
			if (segT < 0.0) {
				break;
			}

			Vec3 pos = helixPoint(center, radius, stalk, climb, segT, braidPhase);
			boolean isHead = k == 0;
			BehaviorSupport.sendContained(level, isHead ? head : k == segments - 1 ? tailTip : body,
					shape, center, radius, pos.x, pos.y, pos.z, 1, 0.04, 0.04, 0.04, 0.0);
			if (variant == 5 && k % 3 == 2) {
				BehaviorSupport.sendContained(level, ParticleTypes.HAPPY_VILLAGER, shape, center, radius,
						pos.x, pos.y + 0.15, pos.z, 1, 0.05, 0.05, 0.05, 0.0);
			}
		}
	}

	/** The top-out dissolve: the serpent unravels into leaf flecks at the canopy. */
	private static void emitDissolve(ServerLevel level, ShieldShape shape, Vec3 center, float radius, ContextState ctx,
			int variant, Vec3 top) {
		ParticleOptions fleck = switch (variant) {
			case 1 -> ParticleTypes.PALE_OAK_LEAVES;
			case 4 -> ParticleTypes.SNEEZE;
			case 6 -> ParticleTypes.MYCELIUM;
			default -> ParticleTypes.CHERRY_LEAVES;
		};
		BehaviorSupport.sendContained(level, fleck, shape, center, radius,
				top.x, top.y + 0.2, top.z, ctx.scaleCount(3, 4), 0.3, 0.2, 0.3, 0.01);
	}

	/**
	 * A point on a stalk's helix at climb-fraction {@code segT}: the axis foot is
	 * hash-seeded inside 0.5r horizontally, the coil radius is ~0.16r, and the
	 * climb spans 0.05r..0.6r above the center plane (dome-safe by construction;
	 * the farthest helix-top corner is ~sqrt(0.66^2 + 0.6^2) = 0.89r, inside the
	 * 0.98r shell without ever needing the containment sweep).
	 */
	private static Vec3 helixPoint(Vec3 center, float radius, int stalk, long climb, double segT, double braidPhase) {
		long seed = BehaviorSupport.mix(climb * 271L + stalk * 4099L);
		double footAngle = BehaviorSupport.hash01(seed) * Math.PI * 2.0;
		double footDist = Math.sqrt(BehaviorSupport.hash01(seed + 1L)) * radius * 0.5;
		double footX = center.x + Math.cos(footAngle) * footDist;
		double footZ = center.z + Math.sin(footAngle) * footDist;
		double turns = 2.5 + 1.5 * BehaviorSupport.hash01(seed + 2L);
		double coil = footAngle + braidPhase + segT * turns * Math.PI * 2.0;
		double coilR = radius * 0.16;
		double y = center.y + radius * (0.05 + 0.55 * segT);
		return new Vec3(footX + Math.cos(coil) * coilR, y, footZ + Math.sin(coil) * coilR);
	}

	/** Where a stalk's climb tops out (segT = 1): the dissolve anchor. */
	private static Vec3 stalkTop(Vec3 center, float radius, int stalk, long climb) {
		return helixPoint(center, radius, stalk, climb, 1.0, 0.0);
	}
}

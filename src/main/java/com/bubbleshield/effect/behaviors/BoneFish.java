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
 * Skeletal ghost fish swimming the air at mid height: each fish is a palette
 * dust skull towing a string of white-ash vertebrae, and the school shuttles
 * along a slowly turning diameter with sine undulation -- so every fish
 * periodically crosses the central axis, where it bursts through in a poof of
 * bone dust. Pure particles, no entities, no state, no cleanup.
 *
 * <ul>
 * <li>v0: a small school of four</li>
 * <li>v1: a big school of seven</li>
 * <li>v2: one giant bone pike (a long eight-vertebra spine)</li>
 * <li>v3: a charnel school (ash and white-ash vertebrae alternate)</li>
 * <li>v4: a leaping school (high jump arcs at mid-crossing)</li>
 * <li>v5: split shoals (odd fish shuttle on the crossed diameter)</li>
 * <li>v6: an ossuary drift (slow, glow marrow motes between vertebrae)</li>
 * </ul>
 */
public final class BoneFish implements InsideEffectBehavior {
	public static final String ID = "bone_fish";
	/** Worst case v6 at ctx-max school: 8 fish x (skull 1 + vertebrae 4 + marrow 2) = 56, + axis poofs ~4 + heart mote 1 = 61 particles/pulse. */
	private static final int MAX_FISH = 8;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int fish = ctx.scaleCount(switch (variant) {
			case 1 -> 7;
			case 2 -> 1;
			default -> 4;
		}, MAX_FISH);
		int vertebrae = variant == 2 ? 8 : 4;
		double speed = variant == 6 ? 0.010 : 0.022;
		// The whole school's shuttle diameter slowly rotates around the axis.
		double diameterAngle = gameTime * 0.003;
		ParticleOptions skull = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, variant == 2 ? 1.3F : 0.8F);
		ParticleOptions marrow = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.6F);
		for (int i = 0; i < fish; i++) {
			double dirAngle = diameterAngle + (variant == 5 && i % 2 == 1 ? Math.PI / 2.0 : 0.0);
			double dx = Math.cos(dirAngle);
			double dz = Math.sin(dirAngle);
			// The shuttle: along swings across the diameter, so the axis is crossed
			// twice per period; each fish rides its own phase offset.
			double swim = gameTime * speed + Math.PI * 2.0 * i / Math.max(1, fish);
			double along = Math.sin(swim) * radius * 0.58;
			double headTilt = Math.cos(swim); // >0 heading outward, <0 heading back
			double spacing = 0.35 * Math.min(1.0, radius * 0.08);
			for (int k = 0; k <= vertebrae; k++) {
				// k=0 is the skull; the spine trails behind the swim direction.
				double back = k * spacing * (headTilt >= 0.0 ? -1.0 : 1.0);
				double at = along + back;
				double wave = Math.sin(swim * 3.0 - k * 0.9) * (0.25 + radius * 0.01);
				double y = center.y + radius * 0.45 + wave;
				if (variant == 4) {
					// The leap: an arc peaking as the fish crosses the axis.
					y += radius * 0.15 * Math.max(0.0, 1.0 - Math.abs(along) / (radius * 0.3));
				}

				double x = center.x + dx * at - dz * wave * 0.3;
				double z = center.z + dz * at + dx * wave * 0.3;
				if (k == 0) {
					BehaviorSupport.sendContained(level, skull, shape, center, radius, x, y, z, 1, 0.02, 0.02, 0.02, 0.0);
					continue;
				}

				ParticleOptions bone = variant == 3 && k % 2 == 0 ? ParticleTypes.ASH : ParticleTypes.WHITE_ASH;
				BehaviorSupport.sendContained(level, bone, shape, center, radius, x, y, z, 1, 0.02, 0.02, 0.02, 0.0);
				if (variant == 6 && k % 2 == 1) {
					BehaviorSupport.sendContained(level, ParticleTypes.GLOW, shape, center, radius,
							x, y + 0.12, z, 1, 0.02, 0.02, 0.02, 0.0);
				}
			}

			// The axis crossing: a bone-dust poof as the skull passes the center line.
			if (Math.abs(along) < Math.max(0.5, radius * 0.06)) {
				BehaviorSupport.sendContained(level, ParticleTypes.POOF, shape, center, radius,
						center.x + dx * along, center.y + radius * 0.45, center.z + dz * along,
						2, 0.2, 0.15, 0.2, 0.01);
			}
		}

		// The marrow shimmer: a secondary-dust mote at the school's shuttle heart,
		// every pulse, every variant (second palette strand).
		BehaviorSupport.sendContained(level, marrow, shape, center, radius,
				center.x, center.y + radius * 0.45, center.z, 1, 0.25, 0.15, 0.25, 0.0);
	}
}

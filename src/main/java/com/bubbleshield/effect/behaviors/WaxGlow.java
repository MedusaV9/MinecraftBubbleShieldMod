package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldGeometry;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Glow motes plus a waxy sparkle ring orbiting every player inside the shield.
 *
 * <ul>
 * <li>v0: one wax-on ring circling at chest height</li>
 * <li>v1: wax-on and wax-off rings counter-rotating on a wider orbit</li>
 * <li>v2: a fast wax-on helix bobbing between feet and head</li>
 * </ul>
 */
public final class WaxGlow implements InsideEffectBehavior {
	public static final String ID = "wax_glow";
	/** Ring points stay within this fraction of the radius (inside the shell). */
	private static final double MAX_DIST_FRAC = 0.98;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		double orbitRadius = variant == 1 ? 1.3 : 0.8;
		double spin = gameTime / 10.0 * (variant == 2 ? 1.1 : 0.45);
		int points = ctx.scaleCount(variant == 0 ? 5 : 4, 8);
		AABB box = AABB.ofSize(center, radius * 2.0, radius * 2.0, radius * 2.0);
		for (Player player : level.getEntitiesOfClass(Player.class, box)) {
			if (!ShieldGeometry.isInside(shape, center, radius, player.position())) {
				continue;
			}

			level.sendParticles(ParticleTypes.GLOW, true, false, player.getX(), player.getY() + 1.0, player.getZ(), ctx.scaleCount(2, 6), 0.3, 0.5, 0.3, 0.0);
			for (int i = 0; i < points; i++) {
				double angle = spin + Math.PI * 2.0 * i / points;
				double x = player.getX() + Math.cos(angle) * orbitRadius;
				double z = player.getZ() + Math.sin(angle) * orbitRadius;
				// v2 bobs the whole ring up and down the player's body like a helix scan.
				double y = player.getY() + (variant == 2 ? 0.2 + 1.4 * (0.5 + 0.5 * Math.sin(spin + i)) : 1.1);
				// A player hugging the wall puts parts of their orbit ring outside the
				// shell; pull any such point back to 0.98r like the other behaviors.
				sendContained(level, ParticleTypes.WAX_ON, center, radius, x, y, z);
				if (variant == 1) {
					// The counter-rotating partner ring uses wax-off sparks.
					double counterAngle = -angle + Math.PI / points;
					sendContained(
							level, ParticleTypes.WAX_OFF, center, radius,
							player.getX() + Math.cos(counterAngle) * orbitRadius, player.getY() + 0.6, player.getZ() + Math.sin(counterAngle) * orbitRadius);
				}
			}
		}
	}

	/** Emits one ring particle, rescaled toward the center onto 0.98r when outside it. */
	private static void sendContained(ServerLevel level, SimpleParticleType particle, Vec3 center, float radius, double x, double y, double z) {
		double dx = x - center.x;
		double dy = y - center.y;
		double dz = z - center.z;
		double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
		double maxDist = radius * MAX_DIST_FRAC;
		if (dist > maxDist) {
			double scale = maxDist / dist;
			x = center.x + dx * scale;
			y = center.y + dy * scale;
			z = center.z + dz * scale;
		}

		level.sendParticles(particle, true, false, x, y, z, 1, 0.02, 0.02, 0.02, 0.0);
	}
}

package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldGeometry;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.ParticleTypes;
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
				level.sendParticles(ParticleTypes.WAX_ON, true, false, x, y, z, 1, 0.02, 0.02, 0.02, 0.0);
				if (variant == 1) {
					// The counter-rotating partner ring uses wax-off sparks.
					double counterAngle = -angle + Math.PI / points;
					level.sendParticles(
							ParticleTypes.WAX_OFF,
							true, false,
							player.getX() + Math.cos(counterAngle) * orbitRadius, player.getY() + 0.6, player.getZ() + Math.sin(counterAngle) * orbitRadius,
							1, 0.02, 0.02, 0.02, 0.0);
				}
			}
		}
	}
}

package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Helical columns of reverse-portal motes twisting up from the bubble floor.
 *
 * <ul>
 * <li>v0: 3 columns with a gentle twist</li>
 * <li>v1: 5 columns twisting twice as tightly</li>
 * <li>v2: 7 taller columns counter-twisting against the slow ring drift</li>
 * </ul>
 */
public final class VoidTendrils implements InsideEffectBehavior {
	public static final String ID = "void_tendrils";
	private static final int MAX_POINTS = 128;
	/** Columns stand on a ring at this fraction of the radius around the projector. */
	private static final double RING_FRAC = 0.5;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int columns = switch (variant) {
			case 1 -> 5;
			case 2 -> 7;
			default -> 3;
		};
		double twistRate = switch (variant) {
			case 1 -> 2.4;
			case 2 -> -1.8;
			default -> 1.2;
		};
		double height = radius * (variant == 2 ? 0.8 : 0.6);
		double helixRadius = Math.min(radius * 0.12, 1.5);
		// The ring of columns drifts slowly so tendrils wander around the projector.
		double drift = gameTime / 10.0 * 0.08;
		int pointsPerColumn = ctx.scaleCount(Mth.clamp((int) Math.round(height * def.behaviorStrength()), 5, MAX_POINTS / columns), MAX_POINTS / columns);
		for (int column = 0; column < columns; column++) {
			double baseAngle = drift + Math.PI * 2.0 * column / columns;
			double baseX = center.x + Math.cos(baseAngle) * radius * RING_FRAC;
			double baseZ = center.z + Math.sin(baseAngle) * radius * RING_FRAC;
			for (int i = 0; i < pointsPerColumn; i++) {
				double frac = (double) i / pointsPerColumn;
				double twist = drift * 4.0 + frac * Math.PI * 2.0 * twistRate;
				double dx = baseX - center.x + Math.cos(twist) * helixRadius;
				double dy = 0.2 + frac * height;
				double dz = baseZ - center.z + Math.sin(twist) * helixRadius;
				// The ring offset plus helix wobble can push tall column tops just past
				// the shell at small radii; rescale any point beyond 0.98r back inside.
				double offset = Math.sqrt(dx * dx + dy * dy + dz * dz);
				double maxDist = radius * 0.98;
				if (offset > maxDist) {
					double scale = maxDist / offset;
					dx *= scale;
					dy *= scale;
					dz *= scale;
				}

				// Reverse portal motes drift further upward on their own after spawning.
				level.sendParticles(ParticleTypes.REVERSE_PORTAL, true, false, center.x + dx, center.y + dy, center.z + dz, 1, 0.02, 0.05, 0.02, 0.0);
			}
		}
	}
}

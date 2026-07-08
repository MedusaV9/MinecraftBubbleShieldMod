package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Honey droplets beading off the inside of the upper shell and dripping down.
 * The emitting cap always sits above the center plane, so a dome drips too.
 *
 * <ul>
 * <li>v0: sparse drips from the upper half of the shell</li>
 * <li>v1: a dense drizzle from a broad upper cap</li>
 * <li>v2: a thick crown of drips from the very top of the shell</li>
 * </ul>
 */
public final class HoneyDrip implements InsideEffectBehavior {
	public static final String ID = "honey_drip";

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		// Lowest emitting latitude (fraction of radius above the center plane) and density.
		double minUp = switch (variant) {
			case 1 -> 0.35;
			case 2 -> 0.8;
			default -> 0.55;
		};
		float density = switch (variant) {
			case 1 -> 2.2F;
			case 2 -> 3.0F;
			default -> 0.9F;
		};
		int drips = ctx.scaleCount(Mth.clamp((int) (radius * density * def.behaviorStrength()), 4, 96), 96);
		RandomSource random = level.getRandom();
		for (int i = 0; i < drips; i++) {
			// Random points on the upper spherical cap, just inside the surface.
			double theta = random.nextDouble() * Math.PI * 2.0;
			double up = minUp + random.nextDouble() * (1.0 - minUp);
			double horizontal = Math.sqrt(Math.max(0.0, 1.0 - up * up));
			double shell = radius * (0.88 + random.nextDouble() * 0.08);
			double x = center.x + horizontal * Math.cos(theta) * shell;
			double y = center.y + up * shell;
			double z = center.z + horizontal * Math.sin(theta) * shell;
			level.sendParticles(ParticleTypes.DRIPPING_HONEY, true, false, x, y, z, 1, 0.05, 0.05, 0.05, 0.0);
		}
	}
}

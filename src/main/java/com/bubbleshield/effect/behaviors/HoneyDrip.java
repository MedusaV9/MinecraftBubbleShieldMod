package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
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
 * <li>v3: mournful obsidian tears beading off the cap</li>
 * <li>v4: a sweet nectar drizzle from a broad cap</li>
 * <li>v5: drips plus landed honey pooling at the floor rim</li>
 * <li>v6: a waxy crown of glints and sparse drips at the pole</li>
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
			case 3 -> 0.5;
			case 4 -> 0.4;
			case 6 -> 0.85;
			default -> 0.55;
		};
		float density = switch (variant) {
			case 1 -> 2.2F;
			case 2 -> 3.0F;
			case 3 -> 1.2F;
			case 4 -> 2.4F;
			case 5 -> 1.4F;
			case 6 -> 0.8F;
			default -> 0.9F;
		};
		SimpleParticleType drip = switch (variant) {
			case 3 -> ParticleTypes.DRIPPING_OBSIDIAN_TEAR;
			case 4 -> ParticleTypes.FALLING_NECTAR;
			default -> ParticleTypes.DRIPPING_HONEY;
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
			BehaviorSupport.sendContained(level, drip, shape, center, radius, x, y, z, 1, 0.05, 0.05, 0.05, 0.0);
			if (variant == 6 && i % 3 == 0) {
				BehaviorSupport.sendContained(level, ParticleTypes.WAX_ON, shape, center, radius, x, y - 0.2, z, 1, 0.05, 0.05, 0.05, 0.0);
			}
		}

		if (variant == 5) {
			// The floor pool: landed honey collecting in a ring around the projector.
			double poolRadius = radius * 0.5;
			int pools = ctx.scaleCount(Mth.clamp((int) Math.round(Math.PI * 2.0 * poolRadius / 2.5), 6, 24), 24);
			for (int i = 0; i < pools; i++) {
				double angle = Math.PI * 2.0 * i / pools;
				BehaviorSupport.sendContained(level, ParticleTypes.LANDING_HONEY, shape, center, radius, center.x + Math.cos(angle) * poolRadius, center.y + 0.1, center.z + Math.sin(angle) * poolRadius, 1, 0.2, 0.02, 0.2, 0.0);
			}
		}
	}
}

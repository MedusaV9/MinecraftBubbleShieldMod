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
 * An underwater-feeling veil of water droplets along the shield wall.
 *
 * <p>Only air-safe particles are used: BUBBLE and CURRENT_DOWN self-remove on their
 * first tick outside water (see BubbleParticle / WaterCurrentDownParticle), so they
 * would be invisible inside an air-filled shield.
 *
 * <ul>
 * <li>v0: a splash-droplet curtain hugging the surface</li>
 * <li>v1: curtain plus a falling-water column above the projector</li>
 * <li>v2: fizzy bubble-pop bursts scattered through the interior</li>
 * </ul>
 */
public final class BubbleVeil implements InsideEffectBehavior {
	public static final String ID = "bubble_veil";
	/** Horizontal distance of the curtain from the center, as a fraction of the radius. */
	private static final double CURTAIN_DIST_FRAC = 0.96;
	/** Curtain points stay within this fraction of the radius (inside the shell). */
	private static final double MAX_DIST_FRAC = 0.98;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		if (variant == 2) {
			// Fizz bursts: 8 pockets of popping bubbles, 12 particles each (96/pulse max;
			// context scaling caps at 10 pockets = 120/pulse). BUBBLE_POP works in air.
			RandomSource random = level.getRandom();
			int bursts = ctx.scaleCount(Mth.clamp((int) (4.0F * def.behaviorStrength()) + 4, 4, 8), 10);
			for (int i = 0; i < bursts; i++) {
				double angle = random.nextDouble() * Math.PI * 2.0;
				double dist = Math.sqrt(random.nextDouble()) * radius * 0.8;
				double x = center.x + Math.cos(angle) * dist;
				double y = center.y + 0.5 + random.nextDouble() * radius * 0.5;
				double z = center.z + Math.sin(angle) * dist;
				level.sendParticles(ParticleTypes.BUBBLE_POP, true, false, x, y, z, 12, 0.3, 0.3, 0.3, 0.05);
			}
			return;
		}

		// Curtain: water droplets along the wall at undulating heights (96 points/pulse
		// max). SPLASH hops upward then falls, so the curtain reads aquatic in air.
		int points = ctx.scaleCount(
				Mth.clamp((int) Math.round(Math.PI * 2.0 * radius * def.behaviorStrength()), 16, variant == 1 ? 80 : 96), variant == 1 ? 80 : 96);
		double phase = gameTime / 10.0 * 0.2;
		// Cap the undulation so curtain points stay inside the sphere: at 0.96r
		// horizontally the height above center may be at most sqrt(0.98^2 - 0.96^2)r.
		double maxRise = radius * Math.sqrt(MAX_DIST_FRAC * MAX_DIST_FRAC - CURTAIN_DIST_FRAC * CURTAIN_DIST_FRAC);
		for (int i = 0; i < points; i++) {
			double angle = phase + Math.PI * 2.0 * i / points;
			double x = center.x + Math.cos(angle) * radius * CURTAIN_DIST_FRAC;
			double z = center.z + Math.sin(angle) * radius * CURTAIN_DIST_FRAC;
			double rise = Math.min(0.5 + (Math.sin(angle * 3.0 + phase) + 1.0) * radius * 0.25, maxRise);
			level.sendParticles(ParticleTypes.SPLASH, true, false, x, center.y + rise, z, 1, 0.1, 0.3, 0.1, 0.02);
		}

		if (variant == 1) {
			// Inner column of falling water above the projector, mimicking a downdraft.
			level.sendParticles(ParticleTypes.FALLING_WATER, true, false, center.x, center.y + radius * 0.4, center.z, 16, 0.6, radius * 0.3, 0.6, 0.0);
		}
	}
}

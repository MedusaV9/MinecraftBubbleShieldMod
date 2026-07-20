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
 * <li>v3: nautilus shells streaming inward from the wall</li>
 * <li>v4: a thin curtain over scattered fizz pockets</li>
 * <li>v5: water drips beading off the upper shell</li>
 * <li>v6: a central splash fountain above the projector</li>
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
				// On the smallest shields a pocket at 0.8r out and 0.5 + 0.5r up lands
				// past the shell (~1.02r at radius 4), so contain each burst anchor.
				BehaviorSupport.sendContained(level, ParticleTypes.BUBBLE_POP, shape, center, radius, x, y, z, 12, 0.3, 0.3, 0.3, 0.05);
			}
			return;
		}

		if (variant == 3) {
			// Nautilus shells fly towards a target (count=0 packet form, like ENCHANT):
			// target the projector, spawn offset out at the wall.
			int shells = ctx.scaleCount(Mth.clamp((int) Math.round(radius * 2.0 * def.behaviorStrength()), 8, 64), 64);
			double phase = gameTime / 10.0 * 0.3;
			for (int i = 0; i < shells; i++) {
				double angle = phase + Math.PI * 2.0 * i / shells;
				double wx = Math.cos(angle) * radius * 0.85;
				double wy = 0.4 + (i % 4) * 0.5;
				double wz = Math.sin(angle) * radius * 0.85;
				level.sendParticles(ParticleTypes.NAUTILUS, true, false, center.x, center.y + 1.0, center.z, 0, wx, wy - 1.0, wz, 1.0);
			}
			return;
		}

		if (variant == 4) {
			// Thin curtain (48 points) + 5 fizz pockets x 10 = 98 particles/pulse max.
			int points = ctx.scaleCount(Mth.clamp((int) Math.round(Math.PI * radius * def.behaviorStrength()), 12, 48), 48);
			double curtainPhase = gameTime / 10.0 * 0.2;
			double rise = radius * Math.sqrt(MAX_DIST_FRAC * MAX_DIST_FRAC - CURTAIN_DIST_FRAC * CURTAIN_DIST_FRAC);
			for (int i = 0; i < points; i++) {
				double angle = curtainPhase + Math.PI * 2.0 * i / points;
				double x = center.x + Math.cos(angle) * radius * CURTAIN_DIST_FRAC;
				double z = center.z + Math.sin(angle) * radius * CURTAIN_DIST_FRAC;
				level.sendParticles(ParticleTypes.SPLASH, true, false, x, center.y + Math.min(0.5, rise), z, 1, 0.1, 0.3, 0.1, 0.02);
			}

			RandomSource random = level.getRandom();
			for (int i = 0; i < 5; i++) {
				double angle = random.nextDouble() * Math.PI * 2.0;
				double dist = Math.sqrt(random.nextDouble()) * radius * 0.7;
				level.sendParticles(ParticleTypes.BUBBLE_POP, true, false,
						center.x + Math.cos(angle) * dist, center.y + 0.5 + random.nextDouble() * radius * 0.4, center.z + Math.sin(angle) * dist, 10, 0.25, 0.25, 0.25, 0.05);
			}
			return;
		}

		if (variant == 5) {
			// Drips beading off the upper shell cap, just inside the surface.
			RandomSource random = level.getRandom();
			int drips = ctx.scaleCount(Mth.clamp((int) (radius * 2.0F * def.behaviorStrength()), 8, 96), 96);
			for (int i = 0; i < drips; i++) {
				double theta = random.nextDouble() * Math.PI * 2.0;
				double up = 0.35 + random.nextDouble() * 0.65;
				double horizontal = Math.sqrt(Math.max(0.0, 1.0 - up * up));
				double shell = radius * (0.88 + random.nextDouble() * 0.08);
				level.sendParticles(ParticleTypes.DRIPPING_WATER, true, false,
						center.x + horizontal * Math.cos(theta) * shell, center.y + up * shell, center.z + horizontal * Math.sin(theta) * shell, 1, 0.05, 0.05, 0.05, 0.0);
			}
			return;
		}

		if (variant == 6) {
			// Fountain: a splash jet above the projector plus falling water around it.
			int jet = ctx.scaleCount(Mth.clamp((int) (radius * 3.0F * def.behaviorStrength()), 16, 80), 80);
			level.sendParticles(ParticleTypes.SPLASH, true, false, center.x, center.y + 1.2, center.z, jet, 0.3, radius * 0.25, 0.3, 0.25);
			level.sendParticles(ParticleTypes.FALLING_WATER, true, false, center.x, center.y + radius * 0.45, center.z, Math.min(32, jet / 2), 1.2, radius * 0.2, 1.2, 0.0);
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

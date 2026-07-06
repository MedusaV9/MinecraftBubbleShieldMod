package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * An underwater-feeling veil of bubbles along the shield wall.
 *
 * <ul>
 * <li>v0: a bubble curtain hugging the surface</li>
 * <li>v1: curtain plus a current-down column above the projector</li>
 * <li>v2: fizzy bubble-pop bursts scattered through the interior</li>
 * </ul>
 */
public final class BubbleVeil implements InsideEffectBehavior {
	public static final String ID = "bubble_veil";

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		if (variant == 2) {
			// Fizz bursts: 8 pockets of popping bubbles, 12 particles each (96/pulse max;
			// context scaling caps at 10 pockets = 120/pulse).
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

		// Curtain: bubbles along the wall at undulating heights (96 points/pulse max).
		int points = ctx.scaleCount(
				Mth.clamp((int) Math.round(Math.PI * 2.0 * radius * def.behaviorStrength()), 16, variant == 1 ? 80 : 96), variant == 1 ? 80 : 96);
		double phase = gameTime / 10.0 * 0.2;
		for (int i = 0; i < points; i++) {
			double angle = phase + Math.PI * 2.0 * i / points;
			double x = center.x + Math.cos(angle) * radius * 0.96;
			double z = center.z + Math.sin(angle) * radius * 0.96;
			double y = center.y + 0.5 + (Math.sin(angle * 3.0 + phase) + 1.0) * radius * 0.25;
			level.sendParticles(ParticleTypes.BUBBLE, true, false, x, y, z, 1, 0.1, 0.3, 0.1, 0.02);
		}

		if (variant == 1) {
			// Inner column pulling downward above the projector.
			level.sendParticles(ParticleTypes.CURRENT_DOWN, true, false, center.x, center.y + radius * 0.4, center.z, 16, 0.6, radius * 0.3, 0.6, 0.0);
		}
	}
}

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
 * A sonar sweep: a ring of sculk charge pops expands from the projector to the
 * shield edge over four pulses, then restarts.
 *
 * <ul>
 * <li>v0: single expanding ring</li>
 * <li>v1: two rings sweeping half a cycle apart (continuous sonar)</li>
 * <li>v2: single ring plus one sonic boom at the center per sweep</li>
 * </ul>
 */
public final class EchoPulse implements InsideEffectBehavior {
	public static final String ID = "echo_pulse";
	private static final int MIN_POINTS = 12;
	private static final int MAX_POINTS = 64;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		long phase = gameTime / 10L % 4L;
		sendRing(level, center, radius, def, ctx, phase);
		if (variant == 1) {
			// Second sweep offset by half a cycle so a ring is always mid-expansion.
			sendRing(level, center, radius, def, ctx, (phase + 2L) % 4L);
		} else if (variant == 2 && gameTime % 40L == 0L) {
			// One boom per full sweep, right as the new ring leaves the center.
			level.sendParticles(ParticleTypes.SONIC_BOOM, true, false, center.x, center.y + 1.0, center.z, 1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	private static void sendRing(ServerLevel level, Vec3 center, float radius, EffectDefinition def, ContextState ctx, long phase) {
		double ringRadius = Math.min(radius * (0.2 + 0.26 * phase) * Mth.clamp(def.behaviorStrength(), 0.8F, 1.2F), radius * 0.98);
		// Roughly one pop per 2 blocks of circumference so the sweep stays readable at radius 100.
		int points = ctx.scaleCount(Mth.clamp((int) Math.round(Math.PI * 2.0 * ringRadius / 2.0), MIN_POINTS, MAX_POINTS), MAX_POINTS);
		for (int i = 0; i < points; i++) {
			double angle = Math.PI * 2.0 * i / points;
			double x = center.x + Math.cos(angle) * ringRadius;
			double z = center.z + Math.sin(angle) * ringRadius;
			level.sendParticles(ParticleTypes.SCULK_CHARGE_POP, true, false, x, center.y + 0.3, z, 1, 0.05, 0.05, 0.05, 0.0);
		}
	}
}

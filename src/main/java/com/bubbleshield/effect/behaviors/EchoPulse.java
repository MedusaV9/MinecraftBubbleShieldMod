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
 * <li>v3: a latitude ring climbing the dome from equator to pole</li>
 * <li>v4: dual offset rings with a sonic boom finishing each sweep</li>
 * <li>v5: a contracting ring collapsing from the wall onto the projector</li>
 * <li>v6: crossing rings, one expanding while the other contracts</li>
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
		if (variant == 3) {
			// Climb the shell: the sweep is a latitude ring rising towards the pole.
			double latitude = Math.PI / 2.0 * (0.1 + 0.85 * phase / 3.0);
			double shell = radius * 0.95;
			double ringRadius = Math.cos(latitude) * shell;
			double y = center.y + Math.sin(latitude) * shell;
			int points = ctx.scaleCount(Mth.clamp((int) Math.round(Math.PI * 2.0 * ringRadius / 2.0), MIN_POINTS, MAX_POINTS), MAX_POINTS);
			for (int i = 0; i < points; i++) {
				double angle = Math.PI * 2.0 * i / points;
				BehaviorSupport.sendContained(level, ParticleTypes.SCULK_CHARGE_POP, shape, center, radius,
						center.x + Math.cos(angle) * ringRadius, y, center.z + Math.sin(angle) * ringRadius, 1, 0.05, 0.05, 0.05, 0.0);
			}
			return;
		}

		if (variant == 5) {
			// Collapse: run the sweep phases backwards so the ring closes inward.
			sendRing(level, center, radius, shape, def, ctx, 3L - phase);
			return;
		}

		if (variant == 6) {
			// Crossing sweeps: one ring expands while its mirror contracts.
			sendRing(level, center, radius, shape, def, ctx, phase);
			sendRing(level, center, radius, shape, def, ctx, 3L - phase);
			return;
		}

		sendRing(level, center, radius, shape, def, ctx, phase);
		if (variant == 1) {
			// Second sweep offset by half a cycle so a ring is always mid-expansion.
			sendRing(level, center, radius, shape, def, ctx, (phase + 2L) % 4L);
		} else if (variant == 2 && gameTime % 40L == 0L) {
			// One boom per full sweep, right as the new ring leaves the center.
			level.sendParticles(ParticleTypes.SONIC_BOOM, true, false, center.x, center.y + 1.0, center.z, 1, 0.0, 0.0, 0.0, 0.0);
		} else if (variant == 4) {
			sendRing(level, center, radius, shape, def, ctx, (phase + 1L) % 4L);
			if (gameTime % 40L == 0L) {
				level.sendParticles(ParticleTypes.SONIC_BOOM, true, false, center.x, center.y + 1.0, center.z, 1, 0.0, 0.0, 0.0, 0.0);
			}
		}
	}

	private static void sendRing(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, ContextState ctx, long phase) {
		double ringRadius = Math.min(radius * (0.2 + 0.26 * phase) * Mth.clamp(def.behaviorStrength(), 0.8F, 1.2F), radius * 0.98);
		// Roughly one pop per 2 blocks of circumference so the sweep stays readable at radius 100.
		int points = ctx.scaleCount(Mth.clamp((int) Math.round(Math.PI * 2.0 * ringRadius / 2.0), MIN_POINTS, MAX_POINTS), MAX_POINTS);
		for (int i = 0; i < points; i++) {
			double angle = Math.PI * 2.0 * i / points;
			double x = center.x + Math.cos(angle) * ringRadius;
			double z = center.z + Math.sin(angle) * ringRadius;
			// The widest sweep phase sits ON the 0.98r line, so the extra 0.3 lift
			// would poke through without containment.
			BehaviorSupport.sendContained(level, ParticleTypes.SCULK_CHARGE_POP, shape, center, radius, x, center.y + 0.3, z, 1, 0.05, 0.05, 0.05, 0.0);
		}
	}
}

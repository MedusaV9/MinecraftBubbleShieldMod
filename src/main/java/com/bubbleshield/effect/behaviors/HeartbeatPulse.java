package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * A heartbeat: every 40 ticks a low thump plays and a dust ring expands from
 * the projector to the shield edge over the following pulses.
 */
public final class HeartbeatPulse implements InsideEffectBehavior {
	public static final String ID = "heartbeat_pulse";
	private static final int POINTS = 20;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime) {
		if (gameTime % 10L != 0L) {
			return;
		}

		long phase = gameTime / 10L % 4L;
		if (phase == 0L) {
			level.playSound(null, center.x, center.y, center.z, SoundEvents.WARDEN_HEARTBEAT, SoundSource.AMBIENT, 0.6F, 0.9F);
		}

		double ringRadius = radius * (0.25 + 0.25 * phase);
		DustParticleOptions dust = new DustParticleOptions((phase % 2L == 0L ? def.argbPrimary() : def.argbSecondary()) & 0xFFFFFF, 1.4F);
		for (int i = 0; i < POINTS; i++) {
			double angle = Math.PI * 2.0 * i / POINTS;
			double x = center.x + Math.cos(angle) * ringRadius;
			double z = center.z + Math.sin(angle) * ringRadius;
			level.sendParticles(dust, x, center.y + 0.2, z, 1, 0.05, 0.05, 0.05, 0.0);
		}
	}
}

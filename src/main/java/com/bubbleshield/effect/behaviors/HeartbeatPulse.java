package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A heartbeat: every 40 ticks a low thump plays and a dust ring expands from
 * the projector to the shield edge over the following pulses.
 *
 * <ul>
 * <li>v0: single beat</li>
 * <li>v1: double beat with note particles on each thump</li>
 * <li>v2: deep bass beat (pitch 0.6) with larger rings</li>
 * </ul>
 */
public final class HeartbeatPulse implements InsideEffectBehavior {
	public static final String ID = "heartbeat_pulse";
	private static final int MIN_POINTS = 20;
	private static final int MAX_POINTS = 128;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime) {
		if (gameTime % 10L != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		long phase = gameTime / 10L % 4L;
		// Volume > 1 extends the audible range (~16 * volume blocks), so the thump
		// carries across large bubbles (radius up to 100).
		float volume = Mth.clamp(radius / 12.0F, 0.6F, 8.0F);
		boolean beat = switch (variant) {
			case 1 -> phase == 0L || phase == 1L;
			default -> phase == 0L;
		};
		if (beat) {
			float pitch = switch (variant) {
				case 1 -> phase == 0L ? 0.9F : 1.1F;
				case 2 -> 0.6F;
				default -> 0.9F;
			};
			level.playSound(null, center.x, center.y, center.z, SoundEvents.WARDEN_HEARTBEAT, SoundSource.AMBIENT, volume, pitch);
			if (variant == 1) {
				level.sendParticles(ParticleTypes.NOTE, true, false, center.x, center.y + 2.0, center.z, 3, 0.5, 0.5, 0.5, 0.0);
			}
		}

		double ringRadius = variant == 2 ? radius * (0.3 + 0.3 * phase) : radius * (0.25 + 0.25 * phase);
		float dustScale = variant == 2 ? 2.0F : 1.4F;
		// Keep the point spacing roughly constant along the ring so the pulse stays
		// visible instead of turning sparse at large radii.
		int points = Mth.clamp((int) Math.round(Math.PI * 2.0 * ringRadius / 2.0), MIN_POINTS, MAX_POINTS);
		DustParticleOptions dust = new DustParticleOptions((phase % 2L == 0L ? def.argbPrimary() : def.argbSecondary()) & 0xFFFFFF, dustScale);
		for (int i = 0; i < points; i++) {
			double angle = Math.PI * 2.0 * i / points;
			double x = center.x + Math.cos(angle) * ringRadius;
			double z = center.z + Math.sin(angle) * ringRadius;
			// overrideLimiter=true lifts the 32-block send limit for players inside large bubbles.
			level.sendParticles(dust, true, false, x, center.y + 0.2, z, 1, 0.05, 0.05, 0.05, 0.0);
		}
	}
}

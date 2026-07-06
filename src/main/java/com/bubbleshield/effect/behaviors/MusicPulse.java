package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Note block melodies stepping through a pitch ladder, with note particles.
 *
 * <ul>
 * <li>v0: an ascending harp arpeggio</li>
 * <li>v1: a bell motif</li>
 * <li>v2: a descending chime minor motif</li>
 * </ul>
 */
public final class MusicPulse implements InsideEffectBehavior {
	public static final String ID = "music_pulse";
	/** Roughly a major pentatonic-ish ladder across one octave (note block pitch range). */
	private static final float[] PITCH_LADDER = {0.5F, 0.63F, 0.75F, 1.0F, 1.26F, 1.5F};
	private static final int[] BELL_MOTIF = {0, 3, 5, 3};
	private static final int[] CHIME_MOTIF = {5, 3, 2, 0};

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime) {
		if (gameTime % 10L != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int step = (int) (gameTime / 10L);
		// NOTE_BLOCK_* constants are Holder.Reference<SoundEvent>, hence .value().
		SoundEvent sound;
		int pitchIndex;
		switch (variant) {
			case 1 -> {
				sound = SoundEvents.NOTE_BLOCK_BELL.value();
				pitchIndex = BELL_MOTIF[step % BELL_MOTIF.length];
			}
			case 2 -> {
				sound = SoundEvents.NOTE_BLOCK_CHIME.value();
				pitchIndex = CHIME_MOTIF[step % CHIME_MOTIF.length];
			}
			default -> {
				sound = SoundEvents.NOTE_BLOCK_HARP.value();
				pitchIndex = step % PITCH_LADDER.length;
			}
		}

		float volume = Mth.clamp(radius / 12.0F, 0.6F, 8.0F);
		level.playSound(null, center.x, center.y + 1.0, center.z, sound, SoundSource.AMBIENT, volume, PITCH_LADDER[pitchIndex]);

		int notes = Mth.clamp((int) (radius * def.behaviorStrength()), 4, 24);
		level.sendParticles(ParticleTypes.NOTE, true, false, center.x, center.y + 2.0, center.z, notes, radius * 0.3, 1.0, radius * 0.3, 0.0);
	}
}

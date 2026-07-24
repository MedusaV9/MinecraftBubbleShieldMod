package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

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
 * <li>v3: a skipping pling arpeggio</li>
 * <li>v4: a low bass groove with floor-level notes</li>
 * <li>v5: a xylophone glissando ringed by wide note bursts</li>
 * <li>v6: a slow descending flute lament</li>
 * </ul>
 */
public final class MusicPulse implements InsideEffectBehavior {
	public static final String ID = "music_pulse";
	/** Roughly a major pentatonic-ish ladder across one octave (note block pitch range). */
	private static final float[] PITCH_LADDER = {0.5F, 0.63F, 0.75F, 1.0F, 1.26F, 1.5F};
	private static final int[] BELL_MOTIF = {0, 3, 5, 3};
	private static final int[] CHIME_MOTIF = {5, 3, 2, 0};
	private static final int[] BASS_MOTIF = {0, 0, 2, 1};
	private static final int[] FLUTE_MOTIF = {4, 3, 2, 1, 0, 1};

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
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
			case 3 -> {
				sound = SoundEvents.NOTE_BLOCK_PLING.value();
				// Skip every other rung so the arpeggio leaps across the ladder.
				pitchIndex = (step * 2) % PITCH_LADDER.length;
			}
			case 4 -> {
				sound = SoundEvents.NOTE_BLOCK_BASS.value();
				pitchIndex = BASS_MOTIF[step % BASS_MOTIF.length];
			}
			case 5 -> {
				sound = SoundEvents.NOTE_BLOCK_XYLOPHONE.value();
				// A zig-zag glissando: up the ladder, then back down.
				int span = PITCH_LADDER.length * 2 - 2;
				int cursor = step % span;
				pitchIndex = cursor < PITCH_LADDER.length ? cursor : span - cursor;
			}
			case 6 -> {
				sound = SoundEvents.NOTE_BLOCK_FLUTE.value();
				pitchIndex = FLUTE_MOTIF[step % FLUTE_MOTIF.length];
			}
			default -> {
				sound = SoundEvents.NOTE_BLOCK_HARP.value();
				pitchIndex = step % PITCH_LADDER.length;
			}
		}

		float volume = Mth.clamp(radius / 12.0F, 0.6F, 8.0F);
		level.playSound(null, center.x, center.y + 1.0, center.z, sound, SoundSource.AMBIENT, volume, PITCH_LADDER[pitchIndex]);

		int notes = ctx.scaleCount(Mth.clamp((int) (radius * def.behaviorStrength()), 4, 24), 24);
		if (variant == 4) {
			// Bass notes rumble along the floor instead of floating overhead.
			BehaviorSupport.sendContained(level, ParticleTypes.NOTE, shape, center, radius, center.x, center.y + 0.4, center.z, notes, radius * 0.45, 0.2, radius * 0.45, 0.0);
			return;
		}

		BehaviorSupport.sendContained(level, ParticleTypes.NOTE, shape, center, radius, center.x, center.y + 2.0, center.z, notes, radius * 0.3, 1.0, radius * 0.3, 0.0);
		if (variant == 5) {
			// A wide note ring flares out with every xylophone strike.
			double ringRadius = radius * 0.6;
			int ringNotes = ctx.scaleCount(8, 16);
			for (int i = 0; i < ringNotes; i++) {
				double angle = Math.PI * 2.0 * i / ringNotes;
				BehaviorSupport.sendContained(level, ParticleTypes.NOTE, shape, center, radius, center.x + Math.cos(angle) * ringRadius, center.y + 1.5, center.z + Math.sin(angle) * ringRadius, 1, 0.05, 0.05, 0.05, 0.0);
			}
		}
	}
}

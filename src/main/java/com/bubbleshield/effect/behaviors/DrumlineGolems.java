package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A drumline of squat dust golems at four floor stations around the projector:
 * each figure is a seven-mote palette-dust body (feet, torso, arms, head) whose
 * arms rise on the wind-up and slam down on the hit. The corps alternates
 * beats -- a DUST_PLUME floor thump on even pulses, a GUST shockwave ring on
 * odd ones -- under a note-block bass kick. Purely particles -- no entities,
 * no state, no cleanup.
 *
 * <ul>
 * <li>v0: full corps -- all four golems thump together, then gust together</li>
 * <li>v1: round robin -- one golem plays per beat while the others stand at
 * ease, arms raised</li>
 * <li>v2: heavy stompers -- bigger motes and a poof cloud with every thump,
 * bass an octave down</li>
 * <li>v3: paradiddle -- opposite station pairs swap thump and gust every beat</li>
 * <li>v4: shockwave soloists -- gust beats ripple two expanding rings per
 * station</li>
 * <li>v5: two-tone corps -- alternating golems wear the darker palette tone</li>
 * <li>v6: marching circle -- the whole line drums while slowly orbiting the
 * projector</li>
 * </ul>
 */
public final class DrumlineGolems implements InsideEffectBehavior {
	public static final String ID = "drumline_golems";
	private static final int STATIONS = 4;
	/** Worst case v4 gust beat: 4 x 7 body dust + 4 stations x 2 rings x 8 GUST = 92 particles/pulse. */
	private static final int MAX_RING_POINTS = 8;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		long beat = gameTime / 10L;
		boolean thumpBeat = beat % 2L == 0L;
		double stationDist = radius * 0.5;
		double march = variant == 6 ? gameTime / 10.0 * 0.05 : 0.0;
		float dustSize = (variant == 2 ? 1.4F : 1.1F) * Mth.clamp(def.behaviorStrength(), 0.8F, 1.2F);
		ParticleOptions primaryBody = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, dustSize);
		ParticleOptions secondaryBody = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, dustSize);
		boolean anyHit = false;
		for (int s = 0; s < STATIONS; s++) {
			double angle = march + Math.PI * 0.5 * s;
			double x = center.x + Math.cos(angle) * stationDist;
			double z = center.z + Math.sin(angle) * stationDist;
			double floor = center.y + 0.1;
			boolean plays = switch (variant) {
				case 1 -> beat % STATIONS == s;
				default -> true;
			};
			// v3's paradiddle: opposite pairs swap roles every beat.
			boolean thumps = variant == 3 ? (s % 2 == 0) == thumpBeat : thumpBeat;
			// The golem faces the projector; its drum sits toward the center.
			double fx = -Math.cos(angle);
			double fz = -Math.sin(angle);
			ParticleOptions body = variant == 5 && s % 2 != 0 ? secondaryBody : primaryBody;
			// Arms wind up off the hit and slam down on it.
			double armY = plays && (gameTime / 10L + s) % 2L == 0L ? 0.55 : 0.95;
			// The squat figure: two feet, two torso motes, two arms, one head.
			BehaviorSupport.sendContained(level, body, shape, center, radius, x - fz * 0.3, floor + 0.15, z + fx * 0.3, 1, 0.03, 0.03, 0.03, 0.0);
			BehaviorSupport.sendContained(level, body, shape, center, radius, x + fz * 0.3, floor + 0.15, z - fx * 0.3, 1, 0.03, 0.03, 0.03, 0.0);
			BehaviorSupport.sendContained(level, body, shape, center, radius, x, floor + 0.5, z, 1, 0.05, 0.05, 0.05, 0.0);
			BehaviorSupport.sendContained(level, body, shape, center, radius, x, floor + 0.75, z, 1, 0.05, 0.05, 0.05, 0.0);
			BehaviorSupport.sendContained(level, body, shape, center, radius, x - fz * 0.45, floor + armY, z + fx * 0.45, 1, 0.03, 0.03, 0.03, 0.0);
			BehaviorSupport.sendContained(level, body, shape, center, radius, x + fz * 0.45, floor + armY, z - fx * 0.45, 1, 0.03, 0.03, 0.03, 0.0);
			BehaviorSupport.sendContained(level, body, shape, center, radius, x, floor + 1.1, z, 1, 0.03, 0.03, 0.03, 0.0);
			if (!plays) {
				continue;
			}

			double drumX = x + fx * 0.6;
			double drumZ = z + fz * 0.6;
			if (thumps) {
				anyHit = true;
				BehaviorSupport.sendContained(level, ParticleTypes.DUST_PLUME, shape, center, radius,
						drumX, floor + 0.1, drumZ, ctx.scaleCount(3, 5), 0.15, 0.05, 0.15, 0.0);
				if (variant == 2) {
					BehaviorSupport.sendContained(level, ParticleTypes.POOF, shape, center, radius,
							drumX, floor + 0.2, drumZ, ctx.scaleCount(2, 3), 0.2, 0.1, 0.2, 0.01);
				}
			} else {
				int rings = variant == 4 ? 2 : 1;
				for (int w = 1; w <= rings; w++) {
					double ringR = Mth.clamp(radius * 0.12F, 0.7F, 2.2F) * w;
					int points = ctx.scaleCount(6, MAX_RING_POINTS);
					for (int i = 0; i < points; i++) {
						double ra = Math.PI * 2.0 * i / points;
						BehaviorSupport.sendContained(level, ParticleTypes.GUST, shape, center, radius,
								drumX + Math.cos(ra) * ringR, floor + 0.15, drumZ + Math.sin(ra) * ringR,
								1, 0.02, 0.02, 0.02, 0.0);
					}
				}
			}
		}

		if (anyHit) {
			float volume = Mth.clamp(radius / 14.0F, 0.5F, 3.0F);
			// NOTE_BLOCK_BASS is a Holder.Reference<SoundEvent>, hence .value().
			level.playSound(null, center.x, center.y + 0.5, center.z,
					SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.AMBIENT, volume, variant == 2 ? 0.5F : 0.8F);
		}
	}
}

package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * The queue at purgatory's gate: a dust gate frame stands on a hash-seeded
 * floor spot, and a shuffling line of ash-and-soul spirits advances toward it
 * from across the floor, each spirit swallowed by a reverse-portal flash at
 * the threshold before respawning at the back of the line. The gate posts and
 * lintel are palette dust (primary posts, darker lintel), so the owner color
 * override rebuilds the gate.
 *
 * <ul>
 * <li>v0: five patient penitents on a straight queue</li>
 * <li>v1: a long winding queue (seven spirits on a curved path)</li>
 * <li>v2: a stop-and-go queue (the line freezes between shuffle beats)</li>
 * <li>v3: sculk-marked penitents (sculk-soul shrouds, a pop at admission)</li>
 * <li>v4: twin gates (two shorter queues from opposite sides)</li>
 * <li>v5: the lantern queue (each spirit carries a soul-fire candle)</li>
 * <li>v6: the reluctant queue (spirits shuffle backward one beat in three)</li>
 * </ul>
 */
public final class PurgatoryQueue implements InsideEffectBehavior {
	public static final String ID = "purgatory_queue";
	/** Worst case v5 at full context scale: gate 7 + admission 4 + 9 walking spirits x (ash 2 + soul 2 + pin 1 + candle 1) = 65 particles/pulse. */
	private static final int MAX_SPIRITS = 10;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int base = switch (variant) {
			case 1 -> 7;
			case 4 -> 6; // three per gate
			default -> 5;
		};
		int spirits = ctx.scaleCount(Math.max(3, Math.round(base * def.behaviorStrength())), MAX_SPIRITS);
		long shuffleTicks = 200L;
		// Per-shield identity: the gate spot is seeded from the projector position.
		long shieldSeed = (long) Math.floor(center.x) * 341873128712L + (long) Math.floor(center.z) * 132897987541L;
		int gates = variant == 4 ? 2 : 1;
		ParticleOptions post = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.0F);
		ParticleOptions lintel = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.8F);
		ParticleOptions shroud = variant == 3 ? ParticleTypes.SCULK_SOUL : ParticleTypes.SOUL;
		long pulse = gameTime / 10L;
		for (int g = 0; g < gates; g++) {
			long seed = BehaviorSupport.mix(shieldSeed + g * 151L);
			double gateAngle = BehaviorSupport.hash01(seed) * Math.PI * 2.0 + Math.PI * g;
			double gateDist = radius * 0.55;
			Vec3 gate = new Vec3(
					center.x + Math.cos(gateAngle) * gateDist,
					center.y + 0.2,
					center.z + Math.sin(gateAngle) * gateDist);
			// The queue runs from the bubble center out to the gate.
			double queueLen = radius * 0.55;
			Vec3 lineDir = new Vec3(-Math.cos(gateAngle), 0.0, -Math.sin(gateAngle));
			Vec3 tail = gate.add(lineDir.scale(queueLen));
			double gateHeight = Math.clamp(radius * 0.12, 1.2, 2.5);
			double gateHalfWidth = gateHeight * 0.45;
			Vec3 across = new Vec3(-lineDir.z, 0.0, lineDir.x);
			// The gate frame: two posts (2 motes each) and a darker 3-mote lintel.
			for (int k = 0; k < 2; k++) {
				double h = gateHeight * (0.25 + 0.5 * k);
				BehaviorSupport.sendContained(level, post, shape, center, radius,
						gate.x + across.x * gateHalfWidth, gate.y + h, gate.z + across.z * gateHalfWidth, 1, 0.03, 0.05, 0.03, 0.0);
				BehaviorSupport.sendContained(level, post, shape, center, radius,
						gate.x - across.x * gateHalfWidth, gate.y + h, gate.z - across.z * gateHalfWidth, 1, 0.03, 0.05, 0.03, 0.0);
			}

			for (int k = -1; k <= 1; k++) {
				BehaviorSupport.sendContained(level, lintel, shape, center, radius,
						gate.x + across.x * gateHalfWidth * 0.6 * k, gate.y + gateHeight, gate.z + across.z * gateHalfWidth * 0.6 * k,
						1, 0.03, 0.03, 0.03, 0.0);
			}

			int lineSpirits = gates == 2 ? Math.max(1, spirits / 2) : spirits;
			// One shared shuffle phase: every spirit advances one slot per cycle.
			double phase = (gameTime % shuffleTicks) / (double) shuffleTicks;
			if (variant == 2) {
				// Stop-and-go: motion happens only in the middle of each beat.
				double beats = phase * lineSpirits;
				double inBeat = beats - Math.floor(beats);
				phase = (Math.floor(beats) + Math.clamp((inBeat - 0.35) / 0.3, 0.0, 1.0)) / lineSpirits;
			} else if (variant == 6) {
				// Reluctance: every third shuffle beat dips backward (a sine
				// undershoot, then only half a slot gained) while the other two
				// beats stride 1.25 slots, netting one slot per beat on average
				// so the cycle still recycles seamlessly.
				double beats = phase * lineSpirits;
				long beat = (long) Math.floor(beats);
				double inBeat = beats - beat;
				long inCycle = beat % 3L;
				double before = (beat / 3L) * 3.0 + (inCycle == 0L ? 0.0 : inCycle == 1L ? 1.25 : 2.5);
				double step = inCycle == 2L
						? 0.5 * inBeat - 0.35 * Math.sin(Math.PI * inBeat)
						: 1.25 * inBeat;
				phase = (before + step) / lineSpirits;
			}

			for (int s = 0; s < lineSpirits; s++) {
				// Slot 0 stands at the threshold; the queue recycles behind the tail.
				double slot = (s + phase * lineSpirits) % lineSpirits;
				double f = slot / lineSpirits;
				Vec3 pos = gate.lerp(tail, f);
				if (variant == 1) {
					// The winding queue bows sideways in a gentle S.
					double bow = Math.sin(f * Math.PI * 2.0) * queueLen * 0.15;
					pos = pos.add(across.scale(bow));
				}

				if (f < 0.06) {
					// The admission: the spirit vanishes in a reverse-portal flash.
					BehaviorSupport.sendContained(level, ParticleTypes.REVERSE_PORTAL, shape, center, radius,
							gate.x, gate.y + gateHeight * 0.5, gate.z, 4, 0.15, gateHeight * 0.25, 0.15, 0.02);
					if (variant == 3) {
						BehaviorSupport.sendContained(level, ParticleTypes.SCULK_CHARGE_POP, shape, center, radius,
								gate.x, gate.y + gateHeight * 0.5, gate.z, 1, 0.1, 0.1, 0.1, 0.0);
					}

					continue;
				}

				// The penitent: an ash-shrouded soul column with a dust shroud pin.
				BehaviorSupport.sendContained(level, ParticleTypes.ASH, shape, center, radius,
						pos.x, pos.y + 0.7, pos.z, 2, 0.12, 0.3, 0.12, 0.0);
				BehaviorSupport.sendContained(level, shroud, shape, center, radius,
						pos.x, pos.y + 0.5, pos.z, 2, 0.08, 0.3, 0.08, 0.01);
				if ((pulse + s) % 2L == 0L) {
					BehaviorSupport.sendContained(level, post, shape, center, radius,
							pos.x, pos.y + 1.1, pos.z, 1, 0.04, 0.04, 0.04, 0.0);
				}

				if (variant == 5) {
					// The carried candle, held ahead toward the gate.
					BehaviorSupport.sendContained(level, ParticleTypes.SOUL_FIRE_FLAME, shape, center, radius,
							pos.x - lineDir.x * 0.3, pos.y + 1.0, pos.z - lineDir.z * 0.3, 1, 0.02, 0.03, 0.02, 0.0);
				}
			}
		}
	}
}

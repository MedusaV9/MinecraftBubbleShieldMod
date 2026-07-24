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
 * Hanging chime curtains: strands of palette-dust beads suspended from the
 * upper shell, swaying with a wave that travels around the curtain. Wherever
 * the wave crests, the first cresting bead of each strand throws a NOTE spark
 * and an amethyst chime rings once per pulse. Purely particles -- no entities,
 * no state, no cleanup.
 *
 * <ul>
 * <li>v0: one circular curtain at mid-ring, a steady rolling wave</li>
 * <li>v1: double curtain -- an outer primary ring around an inner ring in the
 * darker tone, swinging in counter-phase</li>
 * <li>v2: spiral curtain -- strand tops wind inward along a spiral and the
 * wave chases them around it</li>
 * <li>v3: cathedral drapes -- longer strands, a slow deep swell, snowflake
 * glints riding each crest</li>
 * <li>v4: shimmer veil -- wax-on glints riding each crest</li>
 * <li>v5: gradient strands -- beads alternate primary and darker tones down
 * each strand</li>
 * <li>v6: storm-swung -- double amplitude, electric sparks at the crests and a
 * louder, lower chime</li>
 * </ul>
 */
public final class ChimeCurtains implements InsideEffectBehavior {
	public static final String ID = "chime_curtains";
	/** Worst case v3/v4/v6: 12 strands x 7 bead dust + 12 x (1 NOTE + 1 extra) crest accents = 108 particles/pulse. */
	private static final int MAX_STRANDS = 12;
	private static final int BEADS = 7;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int strands = ctx.scaleCount(Mth.clamp((int) (radius * 0.35F * def.behaviorStrength()), 6, MAX_STRANDS), MAX_STRANDS);
		double topY = radius * 0.6;
		double botY = radius * (variant == 3 ? 0.05 : 0.15);
		double amp = Mth.clamp(radius * 0.06F, 0.25F, 1.5F) * (variant == 6 ? 2.0 : 1.0);
		double phase = gameTime / 10.0 * (variant == 3 ? 0.35 : 0.7);
		ParticleOptions primaryBead = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 0.8F);
		ParticleOptions secondaryBead = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.8F);
		boolean rang = false;
		for (int k = 0; k < strands; k++) {
			double angle = Math.PI * 2.0 * k / strands;
			double dist = radius * 0.55;
			double waveOffset = 0.0;
			if (variant == 1) {
				// The inner curtain hangs closer in and swings in counter-phase.
				dist = radius * (k % 2 == 0 ? 0.6 : 0.4);
				waveOffset = k % 2 == 0 ? 0.0 : Math.PI;
			} else if (variant == 2) {
				// The spiral: each strand winds a little further in and around.
				angle += k * 0.5;
				dist = radius * (0.6 - 0.25 * k / strands);
			}

			double hx = center.x + Math.cos(angle) * dist;
			double hz = center.z + Math.sin(angle) * dist;
			// The wave swings beads along the curtain's tangent direction.
			double tx = -Math.sin(angle);
			double tz = Math.cos(angle);
			boolean accented = false;
			for (int i = 0; i < BEADS; i++) {
				double drop = (double) i / (BEADS - 1);
				double y = center.y + topY - (topY - botY) * drop;
				// The swing pivots at the hook: deeper beads swing wider.
				double swing = Math.sin(phase - k * 0.9 - i * 0.4 + waveOffset) * amp * (0.3 + 0.7 * drop);
				double crest = Math.sin(phase - k * 0.9 - i * 0.4 + waveOffset);
				double bx = hx + tx * swing;
				double bz = hz + tz * swing;
				ParticleOptions bead = variant == 5 && i % 2 != 0 ? secondaryBead : variant == 1 && k % 2 != 0 ? secondaryBead : primaryBead;
				BehaviorSupport.sendContained(level, bead, shape, center, radius,
						bx, y, bz, 1, 0.02, 0.03, 0.02, 0.0);
				if (!accented && crest > 0.95) {
					// One flourish per strand per pulse keeps the budget bounded.
					accented = true;
					rang = true;
					BehaviorSupport.sendContained(level, ParticleTypes.NOTE, shape, center, radius,
							bx, y + 0.3, bz, 1, 0.05, 0.05, 0.05, 0.0);
					if (variant == 3) {
						BehaviorSupport.sendContained(level, ParticleTypes.SNOWFLAKE, shape, center, radius,
								bx, y - 0.2, bz, 1, 0.06, 0.06, 0.06, 0.0);
					} else if (variant == 4) {
						BehaviorSupport.sendContained(level, ParticleTypes.WAX_ON, shape, center, radius,
								bx, y, bz, 1, 0.08, 0.08, 0.08, 0.0);
					} else if (variant == 6) {
						BehaviorSupport.sendContained(level, ParticleTypes.ELECTRIC_SPARK, shape, center, radius,
								bx, y, bz, 1, 0.08, 0.08, 0.08, 0.02);
					}
				}
			}
		}

		if (rang) {
			float volume = Mth.clamp(radius / 16.0F, 0.3F, 1.2F) * (variant == 6 ? 1.6F : 1.0F);
			float pitch = variant == 6 ? 0.7F : 1.0F + 0.4F * (float) BehaviorSupport.hash01(gameTime / 10L);
			level.playSound(null, center.x, center.y + radius * 0.4, center.z,
					SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.AMBIENT, volume, pitch);
		}
	}
}

package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Creeping graveyard fog: a few clumped banks of cosy smoke drift slowly near
 * the bubble floor, with an occasional soul rising out of a bank as if leaving
 * a grave. Bank anchors are hash-seeded and wander on slow sinusoids; a palette
 * dust mote glows faintly inside each bank for the owner recolor.
 *
 * <ul>
 * <li>v0: plain fog banks</li>
 * <li>v1: will-o'-wisps (a soul-fire light hovering over each bank)</li>
 * <li>v2: a firefly haunt weaving through the fog</li>
 * <li>v3: rolling fog (banks orbit the perimeter)</li>
 * <li>v4: soul harvest (double risers)</li>
 * <li>v5: a white shroud (denser white smoke)</li>
 * <li>v6: breathing fog (bank density pulses on a 100-tick sine)</li>
 * </ul>
 */
public final class GraveyardMist implements InsideEffectBehavior {
	public static final String ID = "graveyard_mist";
	/** Worst case v5: 6 banks x (smoke 5 + dust 1 + extras 2 + risers 2) = 60 particles/pulse. */
	private static final int MAX_BANKS = 6;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int banks = ctx.scaleCount(Mth.clamp(3 + (int) (radius * 0.3F * def.behaviorStrength()), 3, MAX_BANKS), MAX_BANKS);
		long pulse = gameTime / 10L;
		int density = variant == 5 ? 5 : 3;
		if (variant == 6) {
			// The fog inhales and exhales on a 100-tick cycle.
			density = 1 + (int) Math.round(2.0 * (0.5 + 0.5 * Math.sin(Math.PI * 2.0 * (gameTime % 100L) / 100.0)));
		}

		ParticleOptions smoke = variant == 5 ? ParticleTypes.WHITE_SMOKE : ParticleTypes.CAMPFIRE_COSY_SMOKE;
		ParticleOptions ember = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 0.9F);
		for (int b = 0; b < banks; b++) {
			double x;
			double z;
			if (variant == 3) {
				// Rolling fog orbits the perimeter instead of loitering.
				double angle = gameTime * 0.004 + Math.PI * 2.0 * b / banks;
				x = center.x + Math.cos(angle) * radius * 0.65;
				z = center.z + Math.sin(angle) * radius * 0.65;
			} else {
				long seed = BehaviorSupport.mix(b * 977L);
				double angle = BehaviorSupport.hash01(seed) * Math.PI * 2.0;
				double dist = Math.sqrt(BehaviorSupport.hash01(seed + 1L)) * radius * 0.6;
				// A slow local wander keeps each bank creeping around its anchor.
				double wander = Math.sin(gameTime * 0.002 + b * 2.1) * radius * 0.15;
				x = center.x + Math.cos(angle) * dist + wander;
				z = center.z + Math.sin(angle) * dist - wander * 0.6;
			}

			double y = center.y + 0.4;
			BehaviorSupport.sendContained(level, smoke, shape, center, radius,
					x, y, z, density, 0.8, 0.15, 0.8, 0.003);
			BehaviorSupport.sendContained(level, ember, shape, center, radius,
					x, y + 0.1, z, 1, 0.4, 0.1, 0.4, 0.0);
			// The soul risers leave the fog every other/fourth pulse.
			long riserBeat = variant == 4 ? 2L : 4L;
			if ((pulse + b) % riserBeat == 0L) {
				BehaviorSupport.sendContained(level, ParticleTypes.SOUL, shape, center, radius,
						x, y + 0.2, z, variant == 4 ? 2 : 1, 0.15, 0.5, 0.15, 0.02);
			}

			if (variant == 1) {
				BehaviorSupport.sendContained(level, ParticleTypes.SOUL_FIRE_FLAME, shape, center, radius,
						x, y + 1.2, z, 1, 0.05, 0.1, 0.05, 0.0);
			} else if (variant == 2) {
				BehaviorSupport.sendContained(level, ParticleTypes.FIREFLY, shape, center, radius,
						x, y + 0.6, z, 2, 1.0, 0.5, 1.0, 0.02);
			}
		}
	}
}

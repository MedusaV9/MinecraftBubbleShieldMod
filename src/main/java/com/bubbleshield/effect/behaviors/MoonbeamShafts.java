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
 * Moonbeam shafts: tilted columns of end-rod light reaching down from high
 * under the ceiling to a moving footprint on the floor, slowly panning around
 * the interior like searchlights. White-ash motes float suspended inside every
 * beam and a palette dust pool glows where each shaft lands, so the owner
 * color override tints the light. Beam tilt and pan are pure functions of
 * gameTime -- stateless, no fields, dome-safe (everything at or above the
 * center plane).
 *
 * <ul>
 * <li>v0: two slow crossing searchlights</li>
 * <li>v1: three beams panning in lockstep formation</li>
 * <li>v2: one wide floodlight with a broad mote-filled cone</li>
 * <li>v3: nervous scanner (fast pan that reverses direction each sweep)</li>
 * <li>v4: near-vertical calm pillars with barely any tilt</li>
 * <li>v5: gradient beams (dust core fading to the darker second strand)</li>
 * <li>v6: four thin prison-yard beams with glow footprints</li>
 * </ul>
 */
public final class MoonbeamShafts implements InsideEffectBehavior {
	public static final String ID = "moonbeam_shafts";
	/**
	 * Beam-step budget across all shafts; worst case v2: 30 end-rod steps +
	 * 30 cone dust + 30 motes + 4 footprint = 94/pulse (v5's dust-doubled
	 * steps are capped at 24 total: 48 + 30 motes + 8 footprint = 86;
	 * v6: 4 x (7 + 7 + 4 + 1) = 76).
	 */
	private static final int MAX_STEPS = 30;
	/** White-ash motes suspended per beam, capped alongside the steps. */
	private static final int MAX_MOTES = 30;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int beams = switch (variant) {
			case 1 -> 3;
			case 2, 3 -> 1;
			case 6 -> 4;
			default -> 2;
		};
		int stepCap = (variant == 5 ? 24 : MAX_STEPS) / beams;
		int steps = ctx.scaleCount(Mth.clamp((int) (radius * 0.4F * def.behaviorStrength()), 4, stepCap), stepCap);
		// Pan speed in radians per tick; v3 reverses each full sweep.
		double panRate = switch (variant) {
			case 3 -> 0.03;
			case 4 -> 0.003;
			default -> 0.008;
		};
		double pan = gameTime * panRate;
		if (variant == 3) {
			// The ping-pong sweep: continuous triangle wave, so the scanner turns
			// around at each end instead of jumping.
			double cycle = pan % (Math.PI * 4.0);
			pan = cycle < Math.PI * 2.0 ? cycle : Math.PI * 4.0 - cycle;
		}

		// The beam leans out by this fraction of the radius at the floor.
		double tiltFrac = switch (variant) {
			case 2 -> 0.3;
			case 4 -> 0.06;
			default -> 0.4;
		};
		double topY = center.y + radius * 0.6;
		double floorY = center.y + 0.15;
		double beamHeight = topY - floorY;
		ParticleOptions dust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, variant == 6 ? 0.8F : 1.1F);
		ParticleOptions fadeDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.9F);
		int motes = ctx.scaleCount(Mth.clamp((int) (radius * 0.35F * def.behaviorStrength()), 4, MAX_MOTES / beams), MAX_MOTES / beams);
		for (int b = 0; b < beams; b++) {
			double beamPhase = Math.PI * 2.0 * b / beams;
			// v0's pair crosses: the beams counter-pan into each other.
			double beamPan = pan * (variant == 0 && b == 1 ? -1.0 : 1.0) + beamPhase;
			// The lamp head circles high near the axis; the footprint pans wide.
			double headX = center.x + Math.cos(beamPan * 0.5) * radius * 0.12;
			double headZ = center.z + Math.sin(beamPan * 0.5) * radius * 0.12;
			double footX = center.x + Math.cos(beamPan) * radius * tiltFrac;
			double footZ = center.z + Math.sin(beamPan) * radius * tiltFrac;
			for (int i = 0; i < steps; i++) {
				double t = i / (double) Math.max(1, steps - 1);
				double x = Mth.lerp(t, headX, footX);
				double y = topY - beamHeight * t;
				double z = Mth.lerp(t, headZ, footZ);
				double girth = variant == 2 ? 0.35 : variant == 6 ? 0.06 : 0.12;
				BehaviorSupport.sendContained(level, ParticleTypes.END_ROD, shape, center, radius,
						x, y, z, 1, girth, 0.05, girth, 0.0);
				if (variant == 5) {
					// The gradient core: palette dust up top fading to the darker
					// strand toward the floor.
					BehaviorSupport.sendContained(level, t < 0.5 ? dust : fadeDust, shape, center, radius,
							x, y - 0.1, z, 1, girth, 0.08, girth, 0.0);
				} else if (variant == 2) {
					// The floodlight's cone haze rides every step on palette dust.
					BehaviorSupport.sendContained(level, dust, shape, center, radius,
							x, y - 0.15, z, 1, girth * 1.5, 0.1, girth * 1.5, 0.0);
				}
			}

			// White-ash motes drifting suspended inside the beam's length.
			for (int m = 0; m < motes; m++) {
				double t = BehaviorSupport.hash01(BehaviorSupport.mix(gameTime / 10L * 389L + b * 31L + m));
				BehaviorSupport.sendContained(level, ParticleTypes.WHITE_ASH, shape, center, radius,
						Mth.lerp(t, headX, footX), topY - beamHeight * t, Mth.lerp(t, headZ, footZ),
						1, 0.15, 0.15, 0.15, 0.0);
			}

			// The footprint pool: a palette dust glow where the shaft lands (this is
			// the guaranteed every-pulse recolor accent for v0/v1/v3/v4/v6).
			BehaviorSupport.sendContained(level, dust, shape, center, radius,
					footX, floorY + 0.1, footZ, ctx.scaleCount(2, 4), 0.3, 0.05, 0.3, 0.0);
			if (variant == 6) {
				BehaviorSupport.sendContained(level, ParticleTypes.GLOW, shape, center, radius,
						footX, floorY + 0.2, footZ, 1, 0.15, 0.05, 0.15, 0.0);
			}
		}
	}
}

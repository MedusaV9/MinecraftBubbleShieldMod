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
 * Soul wells sunk into the bubble floor: three to four dust-ringed pits parked
 * on hash-seeded floor spots, each breathing on its own phase — a gentle
 * contained soul fountain on the inhale, a white-smoke exhale as the fountain
 * settles. The rim rings are palette dust (with a darker inner lip), so the
 * owner color override restones every well.
 *
 * <ul>
 * <li>v0: three calm wells on slow, offset breaths</li>
 * <li>v1: four crowded wells (tight rims, quicker breaths)</li>
 * <li>v2: geyser wells (tall fountains, a bubble-pop burst at the crest)</li>
 * <li>v3: smoldering wells (soul-fire flickers riding the fountain)</li>
 * <li>v4: whispering wells (sculk-soul fountains, ash on the exhale)</li>
 * <li>v5: a chorus line (all wells share one synchronized breath)</li>
 * <li>v6: a grand caldera (one wide center well ringed by three satellites)</li>
 * </ul>
 */
public final class SoulWells implements InsideEffectBehavior {
	public static final String ID = "soul_wells";
	/** Worst case v2 at full context scale (inhale half, exhale is exclusive): 6 wells x (rim 6 + lip 1 + fountain 4 + crest 2) = 78 particles/pulse. */
	private static final int MAX_WELLS = 6;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int base = variant == 1 || variant == 6 ? 4 : 3;
		int wells = ctx.scaleCount(Math.max(3, Math.round(base * def.behaviorStrength())), MAX_WELLS);
		long breathTicks = variant == 1 ? 40L : 80L;
		// Per-shield identity: well spots are seeded from the projector position.
		long shieldSeed = (long) Math.floor(center.x) * 341873128712L + (long) Math.floor(center.z) * 132897987541L;
		ParticleOptions rim = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 0.9F);
		ParticleOptions lip = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.7F);
		ParticleOptions fountain = variant == 4 ? ParticleTypes.SCULK_SOUL : ParticleTypes.SOUL;
		ParticleOptions exhale = variant == 4 ? ParticleTypes.ASH : ParticleTypes.WHITE_SMOKE;
		int rimPoints = variant == 1 ? 4 : 6;
		for (int w = 0; w < wells; w++) {
			// v6 parks well 0 wide at the center; satellites keep their spots.
			boolean caldera = variant == 6 && w == 0;
			Vec3 spot = caldera
					? new Vec3(center.x, center.y + 0.15, center.z)
					: wellSpot(center, radius, shieldSeed, w);
			double pitRadius = Math.clamp(radius * (caldera ? 0.22 : 0.08), caldera ? 0.9 : 0.45, caldera ? 3.5 : 1.4);
			// v5 breathes in unison; the rest offset each well by a phase slice.
			long phase = variant == 5 ? gameTime : gameTime + w * (breathTicks / Math.max(1, wells));
			double breath = (phase % breathTicks) / (double) breathTicks;
			long pulse = gameTime / 10L;
			for (int k = 0; k < (caldera ? rimPoints + 2 : rimPoints); k++) {
				// The rim ring turns slowly so the stones read as distinct.
				double angle = pulse * 0.12 + Math.PI * 2.0 * k / (caldera ? rimPoints + 2 : rimPoints);
				BehaviorSupport.sendContained(level, rim, shape, center, radius,
						spot.x + Math.cos(angle) * pitRadius, spot.y + 0.1, spot.z + Math.sin(angle) * pitRadius,
						1, 0.03, 0.02, 0.03, 0.0);
			}

			// The darker inner lip mote, opposite the rim's leading stone.
			double lipAngle = pulse * 0.12 + Math.PI;
			BehaviorSupport.sendContained(level, lip, shape, center, radius,
					spot.x + Math.cos(lipAngle) * pitRadius * 0.55, spot.y + 0.1, spot.z + Math.sin(lipAngle) * pitRadius * 0.55,
					1, 0.03, 0.02, 0.03, 0.0);
			if (breath < 0.5) {
				// The inhale: a gentle fountain climbing out of the pit.
				double climb = breath * 2.0;
				double crest = Math.clamp(radius * (variant == 2 ? 0.4 : 0.22), 0.8, variant == 2 ? 6.0 : 3.0);
				BehaviorSupport.sendContained(level, fountain, shape, center, radius,
						spot.x, spot.y + 0.2 + crest * climb * 0.5, spot.z,
						variant == 2 ? 4 : 3, pitRadius * 0.25, crest * climb * 0.3 + 0.05, pitRadius * 0.25, 0.02);
				if (variant == 3) {
					BehaviorSupport.sendContained(level, ParticleTypes.SOUL_FIRE_FLAME, shape, center, radius,
							spot.x, spot.y + 0.25 + crest * climb * 0.6, spot.z, 1, 0.06, 0.1, 0.06, 0.01);
				} else if (variant == 2 && breath >= 0.375) {
					// The geyser crest pops just before the exhale turns.
					BehaviorSupport.sendContained(level, ParticleTypes.BUBBLE_POP, shape, center, radius,
							spot.x, spot.y + 0.2 + crest, spot.z, 2, 0.15, 0.1, 0.15, 0.02);
				}
			} else {
				// The exhale: the settled well breathes smoke out over the rim.
				BehaviorSupport.sendContained(level, exhale, shape, center, radius,
						spot.x, spot.y + 0.45, spot.z, 2, pitRadius * 0.4, 0.25, pitRadius * 0.4, 0.01);
			}
		}
	}

	/**
	 * The hash-seeded floor spot for one well: within 0.3r..0.72r of the axis
	 * (wells never crowd the projector), slightly above the center plane
	 * (dome-safe by construction).
	 */
	private static Vec3 wellSpot(Vec3 center, float radius, long shieldSeed, int well) {
		long seed = BehaviorSupport.mix(shieldSeed + well * 577L);
		double angle = BehaviorSupport.hash01(seed) * Math.PI * 2.0;
		double dist = radius * (0.3 + 0.42 * BehaviorSupport.hash01(seed + 1L));
		return new Vec3(center.x + Math.cos(angle) * dist, center.y + 0.15, center.z + Math.sin(angle) * dist);
	}
}

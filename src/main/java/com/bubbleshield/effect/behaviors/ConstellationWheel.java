package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A rotating star map hung under the upper shell: hash-seeded constellations
 * of end-rod stars joined by sampled dust star-lanes, the whole wheel turning
 * slowly while one sign at a time flares bright (a firework glint plus a
 * palette dust accent, advancing every 40 ticks). Stateless: every star
 * offset derives from {@link BehaviorSupport#hash01}, so the sky keeps its
 * shape while it turns and every shield shows the same constellations.
 *
 * <ul>
 * <li>v0: four classic four-star constellations on a slow wheel</li>
 * <li>v1: six compact three-star signs on a fast wheel</li>
 * <li>v2: gradient star-lanes (primary-to-secondary transition dust)</li>
 * <li>v3: twin counter-rotating rings of three signs, the upper ring drawn in
 * the darker second strand</li>
 * <li>v4: a low, wide planetarium spread with glow stars</li>
 * <li>v5: the flaring sign throws a crit spark spray</li>
 * <li>v6: a dense five-sign wheel with thin star-lanes</li>
 * </ul>
 */
public final class ConstellationWheel implements InsideEffectBehavior {
	public static final String ID = "constellation_wheel";
	/**
	 * Per-pulse budget, worst case v6: 5 signs x 5 stars = 25 star motes (+5 on
	 * the flaring sign's count-2 stars) + 5 x 4 edges x 4 lane samples = 80 dust
	 * + 1 palette accent + 4 flare fireworks = 115 particles (v5 tops out at
	 * 16 + 4 + 48 + 1 + 4 + 8 crit = 81); always &lt;= 128.
	 */
	private static final int MAX_EDGE_SAMPLES = 4;
	private static final int MAX_FLARE = 4;
	private static final int MAX_FLARE_SPRAY = 8;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int rings = variant == 3 ? 2 : 1;
		int perRing = switch (variant) {
			case 1 -> 6;
			case 3 -> 3;
			case 6 -> 5;
			default -> 4;
		};
		int stars = switch (variant) {
			case 1, 3 -> 3;
			case 6 -> 5;
			default -> 4;
		};
		double wheelSpeed = (variant == 1 ? 0.12 : 0.045) * def.behaviorStrength();
		int lineSamples = ctx.scaleCount(variant == 6 ? 3 : 2, MAX_EDGE_SAMPLES);
		double mapDist = radius * (variant == 4 ? 0.66 : 0.55);
		double baseFrac = variant == 4 ? 0.3 : variant == 3 ? 0.38 : 0.46;
		int total = rings * perRing;
		int flare = (int) ((gameTime / 40L) % total);
		ParticleOptions star = variant == 4 ? ParticleTypes.GLOW : ParticleTypes.END_ROD;
		ParticleOptions primaryLane = switch (variant) {
			case 2 -> new DustColorTransitionOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF,
					ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 1.1F);
			case 6 -> new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 0.7F);
			default -> new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.0F);
		};
		// The v3 counter-ring draws its lanes in the darker second strand.
		ParticleOptions counterLane = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 1.0F);
		ParticleOptions accent = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.3F);
		int sign = 0;
		for (int ring = 0; ring < rings; ring++) {
			double spin = (ring == 1 ? -1.0 : 1.0) * gameTime / 10.0 * wheelSpeed + ring * 0.7;
			ParticleOptions lane = ring == 1 ? counterLane : primaryLane;
			for (int k = 0; k < perRing; k++, sign++) {
				long seedBase = BehaviorSupport.mix(797L * (sign + 1L));
				double angle = spin + Math.PI * 2.0 * k / perRing;
				double anchorY = center.y + radius * (baseFrac + 0.1 * BehaviorSupport.hash01(seedBase)
						+ (ring == 1 ? 0.08 : 0.0));
				boolean flaring = sign == flare;
				Vec3 prev = null;
				for (int s = 0; s < stars; s++) {
					Vec3 p = starPos(center, radius, angle, mapDist, anchorY, seedBase + s * 7L);
					BehaviorSupport.sendContained(level, star, shape, center, radius,
							p.x, p.y, p.z, flaring ? 2 : 1, 0.05, 0.05, 0.05, 0.0);
					if (prev != null) {
						// The star-lane: dust samples strung between consecutive stars.
						for (int i = 1; i <= lineSamples; i++) {
							double t = (double) i / (lineSamples + 1);
							BehaviorSupport.sendContained(level, lane, shape, center, radius,
									Mth.lerp(t, prev.x, p.x), Mth.lerp(t, prev.y, p.y), Mth.lerp(t, prev.z, p.z),
									1, 0.02, 0.02, 0.02, 0.0);
						}
					}

					prev = p;
				}

				if (flaring) {
					// The flare: a palette accent and a firework glint at the sign
					// anchor, every pulse (exactly one sign is always flaring).
					double ax = center.x + Math.cos(angle) * mapDist;
					double az = center.z + Math.sin(angle) * mapDist;
					BehaviorSupport.sendContained(level, accent, shape, center, radius,
							ax, anchorY, az, 1, 0.06, 0.06, 0.06, 0.0);
					BehaviorSupport.sendContained(level, ParticleTypes.FIREWORK, shape, center, radius,
							ax, anchorY + radius * 0.03, az, ctx.scaleCount(2, MAX_FLARE), 0.1, 0.1, 0.1, 0.04);
					if (variant == 5) {
						BehaviorSupport.sendContained(level, ParticleTypes.CRIT, shape, center, radius,
								ax, anchorY, az, ctx.scaleCount(4, MAX_FLARE_SPRAY), 0.15, 0.15, 0.15, 0.12);
					}
				}
			}
		}
	}

	/**
	 * One hash-seeded star of a constellation: a stable offset (azimuth, ring
	 * distance, height) around the sign anchor, so the pattern keeps its shape
	 * while the wheel turns. Stars sit at 0.5-0.71r horizontally and
	 * 0.26-0.6r above the center plane (dome-safe), staying within ~0.85r of
	 * the center before the containment sweep.
	 */
	private static Vec3 starPos(Vec3 center, float radius, double angle, double mapDist, double anchorY, long seed) {
		double a = angle + (BehaviorSupport.hash01(seed) - 0.5) * 0.5;
		double d = mapDist + (BehaviorSupport.hash01(seed + 1L) - 0.5) * radius * 0.1;
		double y = anchorY + (BehaviorSupport.hash01(seed + 2L) - 0.5) * radius * 0.08;
		return new Vec3(center.x + Math.cos(a) * d, y, center.z + Math.sin(a) * d);
	}
}

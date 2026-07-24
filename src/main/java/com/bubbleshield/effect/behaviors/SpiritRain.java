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
 * Reverse spirit rain: luminous droplets condense low over the floor and rise
 * floor-to-ceiling in tall curtain sheets, each glow head trailing a splash
 * streak beneath it. Air-safe by construction (GLOW/SPLASH/END_ROD/dust only,
 * never BUBBLE), stateless, and dome-safe: every anchor sits at or above the
 * center plane. A palette dust mist pools at the floor every pulse so the
 * owner color override always recolors the weather.
 *
 * <ul>
 * <li>v0: three soft drizzle curtains of rising glow drops</li>
 * <li>v1: dense monsoon (four curtains, the full droplet budget)</li>
 * <li>v2: two counter-rotating curtain walls</li>
 * <li>v3: fast end-rod needle rain with long splash streaks</li>
 * <li>v4: slow fat palette-dust drops with a glow shimmer</li>
 * <li>v5: ceiling-burst rain (drops pop into wide splashes at the top)</li>
 * <li>v6: one helical curtain spiraling up around the axis</li>
 * </ul>
 */
public final class SpiritRain implements InsideEffectBehavior {
	public static final String ID = "spirit_rain";
	/**
	 * Droplet budget; worst case v5: 24 drops x (head + streak) + 24 x 2 ceiling
	 * pops + 8 accents + 8 floor mist = 112/pulse (v1: 36 x 2 + 12 + 8 = 92).
	 */
	private static final int MAX_DROPS = 36;
	/** Floor-mist budget (the guaranteed every-pulse palette accent). */
	private static final int MAX_MIST = 8;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int curtains = switch (variant) {
			case 1 -> 4;
			case 2 -> 2;
			case 6 -> 1;
			default -> 3;
		};
		int cap = variant == 1 ? MAX_DROPS : 24;
		int drops = ctx.scaleCount(Mth.clamp((int) (radius * (variant == 1 ? 0.9F : 0.5F) * def.behaviorStrength()), 6, cap), cap);
		// Per-tick rise fraction: needles race, fat drops crawl.
		double riseSpeed = switch (variant) {
			case 3 -> 0.02;
			case 4 -> 0.005;
			default -> 0.01;
		};
		double drift = gameTime * 0.002;
		double floorY = center.y + 0.2;
		double ceilingRise = radius * 0.6 - 0.2;
		ParticleOptions dust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, variant == 4 ? 1.4F : 0.9F);
		ParticleOptions mistDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.8F);
		// The condensation mist pooling at the floor: the guaranteed palette accent,
		// on the darker second strand so an owner recolor tints BOTH layers.
		BehaviorSupport.sendContained(level, mistDust, shape, center, radius,
				center.x, floorY + 0.1, center.z, ctx.scaleCount(4, MAX_MIST), radius * 0.4, 0.08, radius * 0.4, 0.0);
		for (int i = 0; i < drops; i++) {
			int curtain = i % curtains;
			long seed = BehaviorSupport.mix(curtain * 8191L + i * 13L);
			double rise = frac(gameTime * riseSpeed + BehaviorSupport.hash01(seed));
			double x;
			double z;
			if (variant == 6) {
				// The helix: each drop corkscrews up around the axis as it rises.
				double angle = drift * 3.0 + rise * Math.PI * 2.0 + BehaviorSupport.hash01(seed + 1L) * 0.4;
				double dist = radius * (0.35 + 0.15 * BehaviorSupport.hash01(seed + 2L));
				x = center.x + Math.cos(angle) * dist;
				z = center.z + Math.sin(angle) * dist;
			} else {
				// Curtain sheets: chords through the center, slowly turning; v2's
				// walls counter-rotate against each other.
				double yaw = Math.PI * curtain / curtains + (variant == 2 && curtain % 2 == 1 ? -drift : drift);
				double along = (BehaviorSupport.hash01(seed + 1L) - 0.5) * radius * 1.1;
				double lateral = (BehaviorSupport.hash01(seed + 2L) - 0.5) * radius * 0.1;
				x = center.x + Math.cos(yaw) * along - Math.sin(yaw) * lateral;
				z = center.z + Math.sin(yaw) * along + Math.cos(yaw) * lateral;
			}

			double y = floorY + rise * ceilingRise;
			ParticleOptions head = switch (variant) {
				case 3 -> ParticleTypes.END_ROD;
				case 4 -> dust;
				default -> ParticleTypes.GLOW;
			};
			BehaviorSupport.sendContained(level, head, shape, center, radius, x, y, z, 1, 0.03, 0.08, 0.03, 0.01);
			// The rising streak trails BELOW the head so the rain reads upward.
			BehaviorSupport.sendContained(level, ParticleTypes.SPLASH, shape, center, radius,
					x, Math.max(floorY, y - (variant == 3 ? 0.5 : 0.3)), z, 1, 0.03, variant == 3 ? 0.35 : 0.2, 0.03, 0.02);
			if (i % 3 == 0) {
				BehaviorSupport.sendContained(level, dust, shape, center, radius, x, y - 0.15, z, 1, 0.06, 0.1, 0.06, 0.0);
			}

			if (variant == 4 && i % 2 == 0) {
				BehaviorSupport.sendContained(level, ParticleTypes.GLOW, shape, center, radius, x, y + 0.12, z, 1, 0.05, 0.05, 0.05, 0.0);
			} else if (variant == 5 && rise > 0.88) {
				// The ceiling burst: the drop pops into a wider splash at the top.
				BehaviorSupport.sendContained(level, ParticleTypes.SPLASH, shape, center, radius, x, y + 0.15, z, 2, 0.25, 0.08, 0.25, 0.05);
			}
		}
	}

	/** The fractional part of a phase (drop rise cycles loop on this). */
	private static double frac(double x) {
		return x - Math.floor(x);
	}
}

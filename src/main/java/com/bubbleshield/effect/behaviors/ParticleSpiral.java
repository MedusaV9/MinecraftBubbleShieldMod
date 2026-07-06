package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;

import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Dust helices climbing the upper hemisphere of the shield.
 *
 * <ul>
 * <li>v0: a double helix in primary/secondary dust</li>
 * <li>v1: a faster quad helix</li>
 * <li>v2: a single wide ribbon built from chains of color-transition dust</li>
 * </ul>
 */
public final class ParticleSpiral implements InsideEffectBehavior {
	public static final String ID = "particle_spiral";
	private static final int MIN_POINTS = 16;
	private static final int MAX_POINTS = 64;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime) {
		if (gameTime % 10L != 0L) {
			return;
		}

		switch (def.behaviorVariant()) {
			case 1 -> tickQuadHelix(level, center, radius, def, gameTime);
			case 2 -> tickRibbon(level, center, radius, def, gameTime);
			default -> tickDoubleHelix(level, center, radius, def, gameTime);
		}
	}

	/** v0: the original double helix (semantics unchanged from the 10-behavior era). */
	private static void tickDoubleHelix(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime) {
		DustParticleOptions primary = new DustParticleOptions(def.argbPrimary() & 0xFFFFFF, 1.0F);
		DustParticleOptions secondary = new DustParticleOptions(def.argbSecondary() & 0xFFFFFF, 1.0F);
		// Scale helix density with the bubble size so the strands stay visible at radius 100.
		// Each point emits two particles (one per strand), so 64 points = 128 particles/pulse.
		int points = Mth.clamp((int) Math.round(radius * 1.5), MIN_POINTS, MAX_POINTS);
		double phase = gameTime / 10.0 * 0.4;
		for (int i = 0; i < points; i++) {
			double frac = i / (double) points;
			double height = frac * radius;
			double ringRadius = Math.sqrt(Math.max(0.0, radius * radius - height * height));
			double angle = phase + frac * Math.PI * 4.0;
			double x = center.x + Math.cos(angle) * ringRadius;
			double z = center.z + Math.sin(angle) * ringRadius;
			// overrideLimiter=true lifts the 32-block send limit for players inside large bubbles.
			level.sendParticles(primary, true, false, x, center.y + height, z, 1, 0.0, 0.0, 0.0, 0.0);
			level.sendParticles(secondary, true, false, center.x - (x - center.x), center.y + height, center.z - (z - center.z), 1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	/** v1: four strands twisting twice as fast; 32 points x 4 strands = 128 particles/pulse max. */
	private static void tickQuadHelix(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime) {
		DustParticleOptions primary = new DustParticleOptions(def.argbPrimary() & 0xFFFFFF, 1.0F);
		DustParticleOptions secondary = new DustParticleOptions(def.argbSecondary() & 0xFFFFFF, 1.0F);
		int points = Mth.clamp((int) Math.round(radius * 1.2 * def.behaviorStrength()), 12, MAX_POINTS / 2);
		double phase = gameTime / 10.0 * 0.8;
		for (int i = 0; i < points; i++) {
			double frac = i / (double) points;
			double height = frac * radius;
			double ringRadius = Math.sqrt(Math.max(0.0, radius * radius - height * height));
			for (int strand = 0; strand < 4; strand++) {
				double angle = phase + frac * Math.PI * 4.0 + strand * (Math.PI / 2.0);
				double x = center.x + Math.cos(angle) * ringRadius;
				double z = center.z + Math.sin(angle) * ringRadius;
				level.sendParticles(strand % 2 == 0 ? primary : secondary, true, false, x, center.y + height, z, 1, 0.0, 0.0, 0.0, 0.0);
			}
		}
	}

	/** v2: one slow wide ribbon; each point is a 3-particle dust chain across the ribbon width. */
	private static void tickRibbon(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime) {
		DustColorTransitionOptions ribbon = new DustColorTransitionOptions(
				def.argbPrimary() & 0xFFFFFF, def.argbSecondary() & 0xFFFFFF, Mth.clamp(def.behaviorStrength() + 0.3F, 1.1F, 1.8F));
		// 42 points x 3 chain particles = 126 particles/pulse max.
		int points = Mth.clamp((int) Math.round(radius * 1.5 * def.behaviorStrength()), MIN_POINTS, 42);
		double phase = gameTime / 10.0 * 0.5;
		for (int i = 0; i < points; i++) {
			double frac = i / (double) points;
			double height = frac * radius;
			double ringRadius = Math.sqrt(Math.max(0.0, radius * radius - height * height));
			double angle = phase + frac * Math.PI * 2.0;
			double x = center.x + Math.cos(angle) * ringRadius;
			double z = center.z + Math.sin(angle) * ringRadius;
			// Tangent direction along the ring gives the ribbon its width.
			double tx = -Math.sin(angle);
			double tz = Math.cos(angle);
			for (int k = -1; k <= 1; k++) {
				level.sendParticles(ribbon, true, false, x + tx * k * 0.45, center.y + height, z + tz * k * 0.45, 1, 0.0, 0.0, 0.0, 0.0);
			}
		}
	}
}

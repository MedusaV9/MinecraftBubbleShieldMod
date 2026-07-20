package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Dust helices climbing the upper hemisphere of the shield. The raw helix math
 * rides the sphere surface itself (ringRadius² + height² = r²), which lies past
 * the 0.98r containment line, so every emission is routed through
 * {@link BehaviorSupport#sendContained} and ends up just inside the wall.
 *
 * <ul>
 * <li>v0: a double helix in primary/secondary dust</li>
 * <li>v1: a faster quad helix</li>
 * <li>v2: a single wide ribbon built from chains of color-transition dust</li>
 * <li>v3: a triple helix of glow motes</li>
 * <li>v4: a descending double helix in secondary dust</li>
 * <li>v5: a conical dust spiral narrowing towards the pole</li>
 * <li>v6: two interleaved narrow transition-dust ribbons</li>
 * </ul>
 */
public final class ParticleSpiral implements InsideEffectBehavior {
	public static final String ID = "particle_spiral";
	private static final int MIN_POINTS = 16;
	private static final int MAX_POINTS = 64;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		switch (def.behaviorVariant()) {
			case 1 -> tickQuadHelix(level, center, radius, shape, def, gameTime, ctx);
			case 2 -> tickRibbon(level, center, radius, shape, def, gameTime, ctx);
			case 3 -> tickGlowHelix(level, center, radius, shape, def, gameTime, ctx);
			case 4 -> tickFallingHelix(level, center, radius, shape, def, gameTime, ctx);
			case 5 -> tickConicalSpiral(level, center, radius, shape, def, gameTime, ctx);
			case 6 -> tickTwinRibbons(level, center, radius, shape, def, gameTime, ctx);
			default -> tickDoubleHelix(level, center, radius, shape, def, gameTime, ctx);
		}
	}

	/** v0: the original double helix (10-behavior-era path, now contained onto 0.98r). */
	private static void tickDoubleHelix(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		DustParticleOptions primary = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.0F);
		DustParticleOptions secondary = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 1.0F);
		// Scale helix density with the bubble size so the strands stay visible at radius 100.
		// Each point emits two particles (one per strand), so 64 points = 128 particles/pulse.
		int points = ctx.scaleCount(Mth.clamp((int) Math.round(radius * 1.5), MIN_POINTS, MAX_POINTS), MAX_POINTS);
		double phase = gameTime / 10.0 * 0.4;
		for (int i = 0; i < points; i++) {
			double frac = i / (double) points;
			double height = frac * radius;
			double ringRadius = Math.sqrt(Math.max(0.0, radius * radius - height * height));
			double angle = phase + frac * Math.PI * 4.0;
			double x = center.x + Math.cos(angle) * ringRadius;
			double z = center.z + Math.sin(angle) * ringRadius;
			// overrideLimiter=true lifts the 32-block send limit for players inside large bubbles.
			BehaviorSupport.sendContained(level, primary, shape, center, radius, x, center.y + height, z, 1, 0.0, 0.0, 0.0, 0.0);
			BehaviorSupport.sendContained(level, secondary, shape, center, radius, center.x - (x - center.x), center.y + height, center.z - (z - center.z), 1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	/** v1: four strands twisting twice as fast; 32 points x 4 strands = 128 particles/pulse max. */
	private static void tickQuadHelix(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		DustParticleOptions primary = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.0F);
		DustParticleOptions secondary = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 1.0F);
		int points = ctx.scaleCount(Mth.clamp((int) Math.round(radius * 1.2 * def.behaviorStrength()), 12, MAX_POINTS / 2), MAX_POINTS / 2);
		double phase = gameTime / 10.0 * 0.8;
		for (int i = 0; i < points; i++) {
			double frac = i / (double) points;
			double height = frac * radius;
			double ringRadius = Math.sqrt(Math.max(0.0, radius * radius - height * height));
			for (int strand = 0; strand < 4; strand++) {
				double angle = phase + frac * Math.PI * 4.0 + strand * (Math.PI / 2.0);
				double x = center.x + Math.cos(angle) * ringRadius;
				double z = center.z + Math.sin(angle) * ringRadius;
				BehaviorSupport.sendContained(level, strand % 2 == 0 ? primary : secondary, shape, center, radius, x, center.y + height, z, 1, 0.0, 0.0, 0.0, 0.0);
			}
		}
	}

	/** v3: three strands of glow motes climbing the upper hemisphere; 42 points x 3 = 126/pulse max. */
	private static void tickGlowHelix(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		int points = ctx.scaleCount(Mth.clamp((int) Math.round(radius * 1.4 * def.behaviorStrength()), 12, 42), 42);
		double phase = gameTime / 10.0 * 0.5;
		for (int i = 0; i < points; i++) {
			double frac = i / (double) points;
			double height = frac * radius;
			double ringRadius = Math.sqrt(Math.max(0.0, radius * radius - height * height));
			for (int strand = 0; strand < 3; strand++) {
				double angle = phase + frac * Math.PI * 3.0 + strand * (Math.PI * 2.0 / 3.0);
				double x = center.x + Math.cos(angle) * ringRadius;
				double z = center.z + Math.sin(angle) * ringRadius;
				BehaviorSupport.sendContained(level, ParticleTypes.GLOW, shape, center, radius, x, center.y + height, z, 1, 0.0, 0.0, 0.0, 0.0);
			}
		}
	}

	/** v4: a double helix winding downward from the pole in secondary dust; 64 x 2 = 128/pulse max. */
	private static void tickFallingHelix(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		DustParticleOptions primary = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 0.9F);
		DustParticleOptions secondary = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 1.2F);
		int points = ctx.scaleCount(Mth.clamp((int) Math.round(radius * 1.5 * def.behaviorStrength()), MIN_POINTS, MAX_POINTS), MAX_POINTS);
		double phase = -gameTime / 10.0 * 0.45;
		for (int i = 0; i < points; i++) {
			double frac = i / (double) points;
			// Height runs from the pole down to the center plane as frac grows.
			double height = (1.0 - frac) * radius;
			double ringRadius = Math.sqrt(Math.max(0.0, radius * radius - height * height));
			double angle = phase + frac * Math.PI * 4.0;
			double x = center.x + Math.cos(angle) * ringRadius;
			double z = center.z + Math.sin(angle) * ringRadius;
			BehaviorSupport.sendContained(level, secondary, shape, center, radius, x, center.y + height, z, 1, 0.0, 0.0, 0.0, 0.0);
			BehaviorSupport.sendContained(level, primary, shape, center, radius, center.x - (x - center.x), center.y + height, center.z - (z - center.z), 1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	/** v5: a single cone spiral whose ring radius shrinks linearly towards the pole. */
	private static void tickConicalSpiral(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		DustParticleOptions dust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.1F);
		int points = ctx.scaleCount(Mth.clamp((int) Math.round(radius * 2.5 * def.behaviorStrength()), MIN_POINTS, MAX_POINTS * 2), MAX_POINTS * 2);
		double phase = gameTime / 10.0 * 0.6;
		for (int i = 0; i < points; i++) {
			double frac = i / (double) points;
			double height = frac * radius * 0.9;
			// Straight cone instead of the spherical bulge: radius tapers linearly.
			double ringRadius = radius * 0.85 * (1.0 - frac);
			double angle = phase + frac * Math.PI * 6.0;
			double x = center.x + Math.cos(angle) * ringRadius;
			double z = center.z + Math.sin(angle) * ringRadius;
			BehaviorSupport.sendContained(level, dust, shape, center, radius, x, center.y + height, z, 1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	/** v6: two narrow transition-dust ribbons offset half a turn; 21 points x 2 ribbons x 3 = 126 max. */
	private static void tickTwinRibbons(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		DustColorTransitionOptions ribbon = new DustColorTransitionOptions(
				ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 1.0F);
		int points = ctx.scaleCount(Mth.clamp((int) Math.round(radius * 1.2 * def.behaviorStrength()), 10, 21), 21);
		double phase = gameTime / 10.0 * 0.35;
		for (int ribbonIndex = 0; ribbonIndex < 2; ribbonIndex++) {
			for (int i = 0; i < points; i++) {
				double frac = i / (double) points;
				double height = frac * radius;
				double ringRadius = Math.sqrt(Math.max(0.0, radius * radius - height * height));
				double angle = phase + frac * Math.PI * 2.0 + ribbonIndex * Math.PI;
				double x = center.x + Math.cos(angle) * ringRadius;
				double z = center.z + Math.sin(angle) * ringRadius;
				double tx = -Math.sin(angle);
				double tz = Math.cos(angle);
				for (int k = -1; k <= 1; k++) {
					BehaviorSupport.sendContained(level, ribbon, shape, center, radius, x + tx * k * 0.3, center.y + height, z + tz * k * 0.3, 1, 0.0, 0.0, 0.0, 0.0);
				}
			}
		}
	}

	/** v2: one slow wide ribbon; each point is a 3-particle dust chain across the ribbon width. */
	private static void tickRibbon(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		DustColorTransitionOptions ribbon = new DustColorTransitionOptions(
				ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, Mth.clamp(def.behaviorStrength() + 0.3F, 1.1F, 1.8F));
		// 42 points x 3 chain particles = 126 particles/pulse max.
		int points = ctx.scaleCount(Mth.clamp((int) Math.round(radius * 1.5 * def.behaviorStrength()), MIN_POINTS, 42), 42);
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
				BehaviorSupport.sendContained(level, ribbon, shape, center, radius, x + tx * k * 0.45, center.y + height, z + tz * k * 0.45, 1, 0.0, 0.0, 0.0, 0.0);
			}
		}
	}
}

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
 * Dust structures hugging the shield surface.
 *
 * <ul>
 * <li>v0: a slowly rotating dust ring at chest height</li>
 * <li>v1: two counter-rotating rings, dust below and end rod motes above</li>
 * <li>v2: a grid of color-transition dust across the upper dome cap</li>
 * <li>v3: a breathing ring whose radius swells and shrinks with time</li>
 * <li>v4: a meridian arc sweeping over the top plus a static equator ring</li>
 * <li>v5: three stacked rings rotating in alternating directions</li>
 * <li>v6: a polar crown ring of alternating end rod motes and dust</li>
 * </ul>
 */
public final class ParticleDome implements InsideEffectBehavior {
	public static final String ID = "particle_dome";
	private static final int MIN_POINTS = 24;
	private static final int MAX_POINTS = 128;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		switch (def.behaviorVariant()) {
			case 1 -> tickCounterRings(level, center, radius, def, gameTime, ctx);
			case 2 -> tickDomeCap(level, center, radius, def, gameTime, ctx);
			case 3 -> tickBreathingRing(level, center, radius, def, gameTime, ctx);
			case 4 -> tickMeridianArc(level, center, radius, def, gameTime, ctx);
			case 5 -> tickStackedRings(level, center, radius, def, gameTime, ctx);
			case 6 -> tickPolarCrown(level, center, radius, def, gameTime, ctx);
			default -> tickSingleRing(level, center, radius, def, gameTime, ctx);
		}
	}

	/** v0: the original single rotating ring (semantics unchanged from the 10-behavior era). */
	private static void tickSingleRing(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime, ContextState ctx) {
		DustParticleOptions primary = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.0F);
		DustParticleOptions secondary = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.7F);
		// Keep the point spacing roughly constant (one point per ~2 blocks of circumference)
		// so the ring does not look sparse at radius 100.
		int points = ctx.scaleCount(Mth.clamp((int) Math.round(Math.PI * 2.0 * radius / 2.0), MIN_POINTS, MAX_POINTS), MAX_POINTS);
		double phase = gameTime / 10.0 * 0.3;
		for (int i = 0; i < points; i++) {
			double angle = phase + Math.PI * 2.0 * i / points;
			double x = center.x + Math.cos(angle) * radius;
			double z = center.z + Math.sin(angle) * radius;
			// overrideLimiter=true lifts the 32-block send limit so players anywhere inside
			// a large bubble still see the ring.
			level.sendParticles(i % 2 == 0 ? primary : secondary, true, false, x, center.y + 1.0, z, 1, 0.05, 0.05, 0.05, 0.0);
		}
	}

	/** v1: two counter-rotating rings; primary dust at chest height, end rod motes above. */
	private static void tickCounterRings(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime, ContextState ctx) {
		DustParticleOptions primary = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.0F);
		// Each loop pass emits two particles (one per ring), so cap points at half the budget.
		int points = ctx.scaleCount(Mth.clamp((int) Math.round(Math.PI * radius * def.behaviorStrength()), MIN_POINTS / 2, MAX_POINTS / 2), MAX_POINTS / 2);
		double phase = gameTime / 10.0 * 0.3;
		for (int i = 0; i < points; i++) {
			double angle = Math.PI * 2.0 * i / points;
			double x1 = center.x + Math.cos(phase + angle) * radius;
			double z1 = center.z + Math.sin(phase + angle) * radius;
			level.sendParticles(primary, true, false, x1, center.y + 1.0, z1, 1, 0.05, 0.05, 0.05, 0.0);
			double x2 = center.x + Math.cos(-phase + angle) * radius;
			double z2 = center.z + Math.sin(-phase + angle) * radius;
			level.sendParticles(ParticleTypes.END_ROD, true, false, x2, center.y + 2.2, z2, 1, 0.05, 0.05, 0.05, 0.0);
		}
	}

	/** v3: one chest-height transition-dust ring whose radius breathes between 0.45r and 0.95r. */
	private static void tickBreathingRing(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime, ContextState ctx) {
		DustColorTransitionOptions dust = new DustColorTransitionOptions(
				ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 1.2F);
		double breath = 0.7 + 0.25 * Math.sin(gameTime / 10.0 * 0.5);
		double ringRadius = radius * breath;
		int points = ctx.scaleCount(Mth.clamp((int) Math.round(Math.PI * 2.0 * ringRadius / 2.0), MIN_POINTS, MAX_POINTS), MAX_POINTS);
		double phase = gameTime / 10.0 * 0.2;
		for (int i = 0; i < points; i++) {
			double angle = phase + Math.PI * 2.0 * i / points;
			double x = center.x + Math.cos(angle) * ringRadius;
			double z = center.z + Math.sin(angle) * ringRadius;
			level.sendParticles(dust, true, false, x, center.y + 1.2, z, 1, 0.05, 0.05, 0.05, 0.0);
		}
	}

	/** v4: a rotating pole-to-pole meridian arc of primary dust over a fixed secondary equator ring. */
	private static void tickMeridianArc(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime, ContextState ctx) {
		DustParticleOptions primary = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.3F);
		DustParticleOptions secondary = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.8F);
		double azimuth = gameTime / 10.0 * 0.4;
		double shell = radius * 0.95;
		// Arc: latitude 0 -> 180 degrees over the top, in the plane picked by the azimuth.
		int arcPoints = ctx.scaleCount(Mth.clamp((int) Math.round(Math.PI * shell / 2.0), 12, MAX_POINTS / 2), MAX_POINTS / 2);
		for (int i = 0; i <= arcPoints; i++) {
			double latitude = Math.PI * i / arcPoints;
			double y = center.y + Math.sin(latitude) * shell;
			double horizontal = Math.cos(latitude) * shell;
			level.sendParticles(primary, true, false, center.x + Math.cos(azimuth) * horizontal, y, center.z + Math.sin(azimuth) * horizontal, 1, 0.05, 0.05, 0.05, 0.0);
		}

		int equatorPoints = ctx.scaleCount(Mth.clamp((int) Math.round(Math.PI * 2.0 * shell / 4.0), 12, MAX_POINTS / 2), MAX_POINTS / 2);
		for (int i = 0; i < equatorPoints; i++) {
			double angle = Math.PI * 2.0 * i / equatorPoints;
			level.sendParticles(secondary, true, false, center.x + Math.cos(angle) * shell, center.y + 0.3, center.z + Math.sin(angle) * shell, 1, 0.05, 0.05, 0.05, 0.0);
		}
	}

	/** v5: three stacked dust rings at rising heights, adjacent rings spinning opposite ways. */
	private static void tickStackedRings(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime, ContextState ctx) {
		double phase = gameTime / 10.0 * 0.3;
		int rows = 3;
		for (int row = 0; row < rows; row++) {
			double height = radius * (0.15 + 0.25 * row);
			double ringRadius = Math.sqrt(Math.max(0.0, radius * radius * 0.9 - height * height));
			DustParticleOptions dust = new DustParticleOptions(
					(row % 2 == 0 ? ctx.pickColor(def.argbPrimary(), def.argbSecondary()) : ctx.secondaryColor(def.argbSecondary())) & 0xFFFFFF, 1.0F);
			int points = ctx.scaleCount(Mth.clamp((int) Math.round(Math.PI * 2.0 * ringRadius / 2.5), 8, MAX_POINTS / rows), MAX_POINTS / rows);
			double direction = row % 2 == 0 ? 1.0 : -1.0;
			for (int i = 0; i < points; i++) {
				double angle = phase * direction + Math.PI * 2.0 * i / points;
				double x = center.x + Math.cos(angle) * ringRadius;
				double z = center.z + Math.sin(angle) * ringRadius;
				level.sendParticles(dust, true, false, x, center.y + height, z, 1, 0.05, 0.05, 0.05, 0.0);
			}
		}
	}

	/** v6: a high-latitude crown ring near the pole, alternating end rod motes and dust. */
	private static void tickPolarCrown(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime, ContextState ctx) {
		DustParticleOptions dust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.4F);
		double latitude = Math.toRadians(75.0);
		double shell = radius * 0.95;
		double ringRadius = Math.cos(latitude) * shell;
		double y = center.y + Math.sin(latitude) * shell;
		int points = ctx.scaleCount(Mth.clamp((int) Math.round(Math.PI * 2.0 * ringRadius / 1.2), 12, MAX_POINTS), MAX_POINTS);
		double phase = gameTime / 10.0 * 0.5;
		for (int i = 0; i < points; i++) {
			double angle = phase + Math.PI * 2.0 * i / points;
			double x = center.x + Math.cos(angle) * ringRadius;
			double z = center.z + Math.sin(angle) * ringRadius;
			if (i % 2 == 0) {
				level.sendParticles(ParticleTypes.END_ROD, true, false, x, y, z, 1, 0.03, 0.03, 0.03, 0.0);
			} else {
				level.sendParticles(dust, true, false, x, y, z, 1, 0.05, 0.05, 0.05, 0.0);
			}
		}
	}

	/** v2: latitude rings of color-transition dust forming a slowly spinning dome-cap grid. */
	private static void tickDomeCap(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime, ContextState ctx) {
		DustColorTransitionOptions dust = new DustColorTransitionOptions(
				ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, Mth.clamp(def.behaviorStrength(), 0.8F, 1.5F));
		double spin = gameTime / 10.0 * 0.15;
		int latRows = 4;
		int sent = 0;
		for (int row = 1; row <= latRows && sent < MAX_POINTS; row++) {
			// Latitude from near the pole (row 1) down towards the equator.
			double latitude = Math.PI / 2.0 * row / (latRows + 1);
			double y = center.y + Math.cos(latitude) * radius;
			double ringRadius = Math.sin(latitude) * radius;
			int points = ctx.scaleCount(Mth.clamp((int) Math.round(Math.PI * 2.0 * ringRadius / 3.0), 6, 32), 32);
			for (int i = 0; i < points && sent < MAX_POINTS; i++) {
				double angle = spin + Math.PI * 2.0 * i / points;
				double x = center.x + Math.cos(angle) * ringRadius;
				double z = center.z + Math.sin(angle) * ringRadius;
				level.sendParticles(dust, true, false, x, y, z, 1, 0.05, 0.05, 0.05, 0.0);
				sent++;
			}
		}
	}
}

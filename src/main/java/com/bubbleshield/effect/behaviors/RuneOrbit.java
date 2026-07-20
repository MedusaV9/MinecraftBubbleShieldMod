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
 * A ring of "rune stations" orbiting the projector at eye height: each station
 * is a tight cluster of glyph particles, and one station at a time flares
 * bright as the activation marches around the circle.
 *
 * <ul>
 * <li>v0: five enchant-glyph stations, glow flare</li>
 * <li>v1: seven stations on a wider, slower ring</li>
 * <li>v2: five palette-dust stations, firework flare</li>
 * <li>v3: three heavy stations orbiting fast</li>
 * <li>v4: five scrape-glyph stations counter-rotating</li>
 * <li>v5: five stations bobbing vertically as they orbit</li>
 * <li>v6: two stacked rings of four, flares alternating between rings</li>
 * </ul>
 */
public final class RuneOrbit implements InsideEffectBehavior {
	public static final String ID = "rune_orbit";
	private static final int MAX_POINTS = 96;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int stations = switch (variant) {
			case 1 -> 7;
			case 3 -> 3;
			case 6 -> 4;
			default -> 5;
		};
		int rings = variant == 6 ? 2 : 1;
		double orbitDist = radius * (variant == 1 ? 0.75 : 0.55) * Mth.clamp(def.behaviorStrength(), 0.8F, 1.2F);
		double speed = switch (variant) {
			case 1 -> 0.06;
			case 3 -> 0.35;
			case 4 -> -0.12;
			default -> 0.12;
		};
		double orbit = gameTime / 10.0 * speed;
		long flare = gameTime / 10L;
		int budget = MAX_POINTS / (stations * rings);
		int glyphs = ctx.scaleCount(6, budget);
		for (int ring = 0; ring < rings; ring++) {
			double ringY = center.y + 1.2 + ring * 1.6;
			for (int station = 0; station < stations; station++) {
				double angle = orbit + Math.PI * 2.0 * station / stations + (ring == 1 ? Math.PI / stations : 0.0);
				double bob = variant == 5 ? Math.sin(gameTime / 10.0 * 0.8 + station) * 0.6 : 0.0;
				double x = center.x + Math.cos(angle) * orbitDist;
				double y = ringY + bob;
				double z = center.z + Math.sin(angle) * orbitDist;
				ParticleOptions glyph = variant == 2
						? new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.0F)
						: variant == 4 ? ParticleTypes.SCRAPE : ParticleTypes.ENCHANT;
				level.sendParticles(glyph, true, false, x, y, z, glyphs, 0.15, 0.2, 0.15, 0.0);
				// The activation flare marches one station (or ring) per pulse.
				boolean flared = variant == 6
						? flare % 2L == ring && flare / 2L % stations == station
						: flare % stations == station;
				if (flared) {
					level.sendParticles(variant == 2 ? ParticleTypes.FIREWORK : ParticleTypes.GLOW, true, false,
							x, y + 0.2, z, ctx.scaleCount(4, 8), 0.1, 0.1, 0.1, 0.02);
				}
			}
		}
	}
}

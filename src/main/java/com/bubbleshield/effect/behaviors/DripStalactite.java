package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Cave-ceiling drips: fixed "stalactite" points hang just under the shield
 * ceiling and shed slow drips; every few pulses a drip audibly lands. Drip
 * anchors hash from the station index so they stay put as the shield ticks.
 *
 * <ul>
 * <li>v0: six dripstone-water stations</li>
 * <li>v1: six dripstone-lava stations with the lava drip sound</li>
 * <li>v2: eight plain water drips, denser and silent</li>
 * <li>v3: six honey drips (slow, sticky)</li>
 * <li>v4: six obsidian-tear drips</li>
 * <li>v5: nectar drips falling from a central bloom point</li>
 * <li>v6: alternating water and lava stations</li>
 * </ul>
 */
public final class DripStalactite implements InsideEffectBehavior {
	public static final String ID = "drip_stalactite";

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(20L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int stations = variant == 2 ? 8 : variant == 5 ? 1 : 6;
		int drips = ctx.scaleCount(variant == 2 ? 3 : 2, 6);
		double ceiling = radius * 0.8 * Mth.clamp(def.behaviorStrength(), 0.8F, 1.1F);
		for (int station = 0; station < stations; station++) {
			// Deterministic pseudo-random anchor per station: drips repeat in place.
			double hash = station * 2654435761.0 % 1.0e6 / 1.0e6;
			double angle = Math.PI * 2.0 * station / stations + hash;
			double dist = variant == 5 ? 0.0 : radius * (0.25 + 0.45 * hash);
			double hang = Math.sqrt(Math.max(0.0, ceiling * ceiling - dist * dist));
			double x = center.x + Math.cos(angle) * dist;
			double y = center.y + Math.min(hang, ceiling) - 0.3;
			double z = center.z + Math.sin(angle) * dist;
			SimpleParticleType drip = switch (variant) {
				case 1 -> ParticleTypes.DRIPPING_DRIPSTONE_LAVA;
				case 2 -> ParticleTypes.DRIPPING_WATER;
				case 3 -> ParticleTypes.DRIPPING_HONEY;
				case 4 -> ParticleTypes.DRIPPING_OBSIDIAN_TEAR;
				case 5 -> ParticleTypes.FALLING_NECTAR;
				case 6 -> station % 2 == 0 ? ParticleTypes.DRIPPING_DRIPSTONE_WATER : ParticleTypes.DRIPPING_DRIPSTONE_LAVA;
				default -> ParticleTypes.DRIPPING_DRIPSTONE_WATER;
			};
			level.sendParticles(drip, true, false, x, y, z, drips, 0.1, 0.05, 0.1, 0.0);
		}

		if (variant != 2 && variant != 5 && gameTime % 100L == 0L) {
			// One audible drip landing per five pulses, from a rotating station.
			long which = gameTime / 100L % stations;
			double angle = Math.PI * 2.0 * which / stations;
			boolean lava = variant == 1 || (variant == 6 && which % 2L == 1L);
			level.playSound(null, center.x + Math.cos(angle) * radius * 0.4, center.y + 0.2, center.z + Math.sin(angle) * radius * 0.4,
					lava ? SoundEvents.POINTED_DRIPSTONE_DRIP_LAVA : SoundEvents.POINTED_DRIPSTONE_DRIP_WATER,
					SoundSource.BLOCKS, 0.6F, 1.0F);
		}
	}
}

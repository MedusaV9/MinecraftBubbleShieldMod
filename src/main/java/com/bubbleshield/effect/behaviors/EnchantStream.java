package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Enchantment glyphs streaming through the bubble. Uses the count=0 particle
 * packet form: the enchant particle spawns at {@code target + offset} and flies
 * towards {@code target} (see FlyTowardsPositionParticle).
 *
 * <ul>
 * <li>v0: glyphs streaming inward from the shield wall to the projector</li>
 * <li>v1: glyphs streaming outward from the projector to the wall</li>
 * <li>v2: a glyph fountain rising from the projector</li>
 * </ul>
 */
public final class EnchantStream implements InsideEffectBehavior {
	public static final String ID = "enchant_stream";

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		// Each loop pass sends exactly one glyph (count=0 packet == a single particle).
		int glyphs = ctx.scaleCount(Mth.clamp((int) Math.round(radius * 3.0 * def.behaviorStrength()), 12, 96), 96);
		double phase = gameTime / 10.0 * 0.25;
		for (int i = 0; i < glyphs; i++) {
			double angle = phase + Math.PI * 2.0 * i / glyphs;
			double wallHeight = 0.4 + (i % 4) * 0.5;
			double wx = center.x + Math.cos(angle) * radius * 0.85;
			double wy = center.y + wallHeight;
			double wz = center.z + Math.sin(angle) * radius * 0.85;
			switch (variant) {
				case 1 ->
					// Fly outward: target the wall point, spawn offset back at the projector.
					level.sendParticles(ParticleTypes.ENCHANT, true, false, wx, wy, wz, 0,
							center.x - wx, center.y + 1.2 - wy, center.z - wz, 1.0);
				case 2 -> {
					// Fountain: target a point up in the dome, spawn offset down at the projector.
					double topY = center.y + radius * (0.5 + 0.4 * (i % 3) / 2.0);
					double tx = center.x + Math.cos(angle) * radius * 0.3;
					double tz = center.z + Math.sin(angle) * radius * 0.3;
					level.sendParticles(ParticleTypes.ENCHANT, true, false, tx, topY, tz, 0,
							center.x - tx, center.y + 0.8 - topY, center.z - tz, 1.0);
				}
				default ->
					// Fly inward: target the projector, spawn offset out at the wall point.
					level.sendParticles(ParticleTypes.ENCHANT, true, false, center.x, center.y + 1.2, center.z, 0,
							wx - center.x, wy - (center.y + 1.2), wz - center.z, 1.0);
			}
		}
	}
}

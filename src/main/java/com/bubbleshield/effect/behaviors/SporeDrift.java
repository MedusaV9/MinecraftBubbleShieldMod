package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Ambient spores drifting through the whole bubble interior.
 *
 * <ul>
 * <li>v0: mycelium motes</li>
 * <li>v1: warped (teal) spores</li>
 * <li>v2: crimson (red) spores</li>
 * </ul>
 */
public final class SporeDrift implements InsideEffectBehavior {
	public static final String ID = "spore_drift";

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		SimpleParticleType particle = switch (def.behaviorVariant()) {
			case 1 -> ParticleTypes.WARPED_SPORE;
			case 2 -> ParticleTypes.CRIMSON_SPORE;
			default -> ParticleTypes.MYCELIUM;
		};
		int count = ctx.scaleCount(Mth.clamp((int) (radius * 2.0F * def.behaviorStrength()), 16, 128), 128);
		level.sendParticles(
				particle,
				true, false,
				center.x, center.y + radius * 0.35, center.z,
				count,
				radius * 0.6, radius * 0.3, radius * 0.6,
				0.0
		);
	}
}

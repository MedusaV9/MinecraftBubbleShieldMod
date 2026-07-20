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
 * A slow fire-tornado of embers: helical strands climb from the floor around
 * the projector, tightening as they rise, shedding the odd stray spark.
 *
 * <ul>
 * <li>v0: two flame strands</li>
 * <li>v1: three small-flame strands, tighter and faster</li>
 * <li>v2: two soul-fire strands (cold blue)</li>
 * <li>v3: two copper-fire strands (green) with lava spits at the base</li>
 * <li>v4: one thick strand climbing the shield wall itself</li>
 * <li>v5: two ember strands falling downward instead (reverse spiral)</li>
 * <li>v6: three alternating flame/soul strands</li>
 * </ul>
 */
public final class EmberSpiral implements InsideEffectBehavior {
	public static final String ID = "ember_spiral";
	private static final int MAX_POINTS = 96;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int strands = variant == 1 || variant == 6 ? 3 : variant == 4 ? 1 : 2;
		double climb = gameTime / 10.0 * (variant == 1 ? 0.9 : 0.45);
		double height = radius * (variant == 4 ? 0.95 : 0.8) * Mth.clamp(def.behaviorStrength(), 0.8F, 1.2F);
		double baseGirth = radius * (variant == 4 ? 0.9 : variant == 1 ? 0.25 : 0.4);
		int budget = MAX_POINTS / strands;
		int points = ctx.scaleCount(Mth.clamp((int) (height / 0.35), 8, budget), budget);
		for (int strand = 0; strand < strands; strand++) {
			SimpleParticleType ember = switch (variant) {
				case 1 -> ParticleTypes.SMALL_FLAME;
				case 2 -> ParticleTypes.SOUL_FIRE_FLAME;
				case 3 -> ParticleTypes.COPPER_FIRE_FLAME;
				case 6 -> strand % 2 == 0 ? ParticleTypes.FLAME : ParticleTypes.SOUL_FIRE_FLAME;
				default -> ParticleTypes.FLAME;
			};
			double strandPhase = Math.PI * 2.0 * strand / strands;
			for (int i = 0; i < points; i++) {
				double t = (double) i / points;
				// v5 runs the climb in reverse: sparks sink from the crown to the floor.
				double rise = variant == 5 ? 1.0 - t : t;
				double angle = climb + strandPhase + t * Math.PI * 4.0;
				// The funnel tightens toward the top (or stays on the wall for v4).
				double girth = variant == 4
						? Math.sqrt(Math.max(0.0, 1.0 - rise * rise)) * baseGirth
						: baseGirth * (1.0 - 0.75 * rise) + 0.2;
				level.sendParticles(ember, true, false,
						center.x + Math.cos(angle) * girth, center.y + 0.2 + height * rise, center.z + Math.sin(angle) * girth,
						1, 0.02, 0.02, 0.02, 0.0);
			}
		}

		if (variant == 3 && gameTime % 40L == 0L) {
			level.sendParticles(ParticleTypes.LAVA, true, false, center.x, center.y + 0.3, center.z, ctx.scaleCount(3, 6), 0.4, 0.1, 0.4, 0.0);
		}
	}
}

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
 * Floor vents on an eruption rota: each vent simmers quietly, then blasts a
 * particle column upward when its turn comes around, with a steam hiss.
 *
 * <ul>
 * <li>v0: three splash geysers with cloud steam</li>
 * <li>v1: four smaller poof vents, faster rota</li>
 * <li>v2: three white-smoke fumaroles (no eruption jet, tall simmer)</li>
 * <li>v3: two big campfire-smoke chimneys</li>
 * <li>v4: three bubble-pop vents with a bubble-column burst</li>
 * <li>v5: three soul-flame vents (silent, eerie)</li>
 * <li>v6: five micro-vents popping in quick succession</li>
 * </ul>
 */
public final class GeyserVents implements InsideEffectBehavior {
	public static final String ID = "geyser_vents";
	private static final int MAX_JET = 48;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int vents = switch (variant) {
			case 1 -> 4;
			case 3 -> 2;
			case 6 -> 5;
			default -> 3;
		};
		long rotaPeriod = variant == 1 || variant == 6 ? 2L : 4L;
		long pulse = gameTime / 10L;
		double ventDist = radius * 0.5 * Mth.clamp(def.behaviorStrength(), 0.8F, 1.2F);
		for (int vent = 0; vent < vents; vent++) {
			double angle = Math.PI * 2.0 * vent / vents + 0.7;
			double x = center.x + Math.cos(angle) * ventDist;
			double z = center.z + Math.sin(angle) * ventDist;
			SimpleParticleType simmer = switch (variant) {
				case 2 -> ParticleTypes.WHITE_SMOKE;
				case 3 -> ParticleTypes.CAMPFIRE_COSY_SMOKE;
				case 4 -> ParticleTypes.BUBBLE_POP;
				case 5 -> ParticleTypes.SOUL_FIRE_FLAME;
				default -> ParticleTypes.CLOUD;
			};
			double simmerRise = variant == 2 ? 0.08 : 0.02;
			level.sendParticles(simmer, true, false, x, center.y + 0.2, z, ctx.scaleCount(3, 8), 0.15, 0.1, 0.15, simmerRise);
			if (variant == 2) {
				continue;
			}

			// Eruption rota: one vent blasts per pulse window, cycling around the ring.
			if (pulse / rotaPeriod % vents != vent || pulse % rotaPeriod != 0L) {
				continue;
			}

			SimpleParticleType jet = switch (variant) {
				case 1 -> ParticleTypes.POOF;
				case 3 -> ParticleTypes.CAMPFIRE_SIGNAL_SMOKE;
				case 4 -> ParticleTypes.BUBBLE_COLUMN_UP;
				case 5 -> ParticleTypes.SOUL;
				default -> ParticleTypes.SPLASH;
			};
			double jetHeight = Math.min(radius * 0.7, variant == 6 ? 2.0 : 5.0);
			int steps = ctx.scaleCount(Mth.clamp((int) (jetHeight / 0.4), 4, MAX_JET), MAX_JET);
			for (int i = 0; i < steps; i++) {
				double y = center.y + 0.3 + jetHeight * i / steps;
				level.sendParticles(jet, true, false, x, y, z, 1, 0.1, 0.05, 0.1, 0.15);
			}

			if (variant != 5) {
				level.playSound(null, x, center.y + 0.5, z,
						variant == 4 ? SoundEvents.BUBBLE_COLUMN_BUBBLE_POP : SoundEvents.FIRE_EXTINGUISH,
						SoundSource.BLOCKS, 0.4F, variant == 6 ? 1.6F : 0.9F);
			}
		}
	}
}

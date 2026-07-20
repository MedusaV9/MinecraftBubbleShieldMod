package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Charged orbs drifting through the bubble: each orb is a bright core wrapped
 * in a crackle of sparks, building charge until it discharges a spark burst
 * and (in some variants) an arc towards its neighbour orb.
 *
 * <ul>
 * <li>v0: three end-rod orbs, spark discharge every fourth pulse</li>
 * <li>v1: four smaller glow orbs, faster discharge</li>
 * <li>v2: three palette-dust orbs with firework discharge</li>
 * <li>v3: two big orbs that arc a spark line between them on discharge</li>
 * <li>v4: three orbs falling as they charge, snapping back up on discharge</li>
 * <li>v5: five silent micro-orbs, constant gentle crackle</li>
 * <li>v6: three wax-off orbs with a bell-resonance discharge</li>
 * </ul>
 */
public final class StaticOrbs implements InsideEffectBehavior {
	public static final String ID = "static_orbs";
	private static final int MAX_ARC = 32;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int orbs = switch (variant) {
			case 1 -> 4;
			case 3 -> 2;
			case 5 -> 5;
			default -> 3;
		};
		long chargePeriod = variant == 1 ? 2L : 4L;
		long pulse = gameTime / 10L;
		boolean discharging = variant != 5 && pulse % chargePeriod == 0L;
		double drift = gameTime / 10.0 * 0.07;
		double orbitDist = radius * 0.5 * Mth.clamp(def.behaviorStrength(), 0.8F, 1.2F);
		double[][] pos = new double[orbs][3];
		for (int orb = 0; orb < orbs; orb++) {
			double angle = drift + Math.PI * 2.0 * orb / orbs;
			double charge = pulse % chargePeriod / (double) chargePeriod;
			double sag = variant == 4 ? charge * radius * 0.25 : 0.0;
			pos[orb][0] = center.x + Math.cos(angle) * orbitDist;
			pos[orb][1] = center.y + radius * 0.45 - sag + Math.sin(drift * 2.0 + orb) * 0.4;
			pos[orb][2] = center.z + Math.sin(angle) * orbitDist;
			ParticleOptions core = switch (variant) {
				case 1 -> ParticleTypes.GLOW;
				case 2 -> new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.4F);
				case 6 -> ParticleTypes.WAX_OFF;
				default -> ParticleTypes.END_ROD;
			};
			level.sendParticles(core, true, false, pos[orb][0], pos[orb][1], pos[orb][2], ctx.scaleCount(2, 4), 0.05, 0.05, 0.05, 0.0);
			int crackle = ctx.scaleCount(discharging ? 8 : 2, 12);
			level.sendParticles(ParticleTypes.ELECTRIC_SPARK, true, false, pos[orb][0], pos[orb][1], pos[orb][2], crackle, 0.3, 0.3, 0.3, variant == 2 && discharging ? 0.1 : 0.02);
			if (discharging && variant == 2) {
				level.sendParticles(ParticleTypes.FIREWORK, true, false, pos[orb][0], pos[orb][1], pos[orb][2], ctx.scaleCount(5, 10), 0.15, 0.15, 0.15, 0.08);
			}
		}

		if (discharging && variant == 3) {
			// The arc: a spark line between the two orbs.
			int steps = ctx.scaleCount(Mth.clamp((int) (orbitDist * 2.0 / 0.5), 6, MAX_ARC), MAX_ARC);
			for (int i = 0; i <= steps; i++) {
				double t = (double) i / steps;
				level.sendParticles(ParticleTypes.ELECTRIC_SPARK, true, false,
						Mth.lerp(t, pos[0][0], pos[1][0]),
						Mth.lerp(t, pos[0][1], pos[1][1]) + Math.sin(t * Math.PI * 3.0 + pulse) * 0.2,
						Mth.lerp(t, pos[0][2], pos[1][2]), 1, 0.03, 0.03, 0.03, 0.0);
			}
		}

		if (discharging && variant == 6 && gameTime % 40L == 0L) {
			level.playSound(null, center.x, center.y + radius * 0.45, center.z, SoundEvents.BELL_RESONATE, SoundSource.BLOCKS, 0.25F, 1.8F);
		}
	}
}

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
 * Suspended glass shards: glinting motes hanging on a mid-height shell that
 * catch the light in slow unison, with a soft chime as each glint wave passes.
 *
 * <ul>
 * <li>v0: snowflake glints on a golden-angle shell</li>
 * <li>v1: end-rod glints, no chime</li>
 * <li>v2: crit glints with a double chime per wave</li>
 * <li>v3: firework sparkles on a tighter shell</li>
 * <li>v4: enchanted-hit glints drifting upward</li>
 * <li>v5: electric sparks with a resonant bell instead of the chime</li>
 * <li>v6: wax-on glints where alternating halves of the interleaved shard
 * indices pulse on alternating waves (even indices one wave, odd the next)</li>
 * </ul>
 */
public final class GlassShards implements InsideEffectBehavior {
	public static final String ID = "glass_shards";
	private static final int MAX_POINTS = 96;
	private static final double GOLDEN_ANGLE = Math.PI * (3.0 - Math.sqrt(5.0));

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(20L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		SimpleParticleType particle = switch (variant) {
			case 1 -> ParticleTypes.END_ROD;
			case 2 -> ParticleTypes.CRIT;
			case 3 -> ParticleTypes.FIREWORK;
			case 4 -> ParticleTypes.ENCHANTED_HIT;
			case 5 -> ParticleTypes.ELECTRIC_SPARK;
			case 6 -> ParticleTypes.WAX_ON;
			default -> ParticleTypes.SNOWFLAKE;
		};
		double shell = radius * (variant == 3 ? 0.45 : 0.65) * Mth.clamp(def.behaviorStrength(), 0.8F, 1.2F);
		int points = ctx.scaleCount(Mth.clamp((int) (shell * shell * 3.0), 16, MAX_POINTS), MAX_POINTS);
		long wave = gameTime / 20L;
		for (int i = 0; i < points; i++) {
			// Golden-angle sphere so the shards look scattered yet stable frame-to-frame.
			double lat = Math.acos(1.0 - 2.0 * (i + 0.5) / points);
			double lon = GOLDEN_ANGLE * i;
			double y = Math.cos(lat) * shell;
			if (variant == 6 && ((i % 2 == 0) != (wave % 2L == 0L))) {
				continue;
			}

			double upDrift = variant == 4 ? 0.05 : 0.0;
			// Only a slice of the shards glints each wave, marching around the shell.
			if ((i + wave) % 4L != 0L) {
				continue;
			}

			BehaviorSupport.sendContained(level, particle, shape, center, radius, center.x + Math.sin(lat) * Math.cos(lon) * shell,
					center.y + Math.abs(y),
					center.z + Math.sin(lat) * Math.sin(lon) * shell,
					1, 0.02, 0.02, 0.02, upDrift);
		}

		if (variant != 1 && gameTime % 80L == 0L) {
			if (variant == 5) {
				level.playSound(null, center.x, center.y + 1.0, center.z, SoundEvents.BELL_RESONATE, SoundSource.BLOCKS, 0.3F, 1.4F);
			} else {
				level.playSound(null, center.x, center.y + 1.0, center.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.5F, 1.0F);
				if (variant == 2) {
					level.playSound(null, center.x, center.y + 1.0, center.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.5F, 1.5F);
				}
			}
		}
	}
}

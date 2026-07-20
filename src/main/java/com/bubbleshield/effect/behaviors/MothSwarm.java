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
 * A moth swarm chasing a wandering lantern-point: the invisible lantern
 * traces a slow Lissajous path through the bubble and the swarm flutters in a
 * loose cloud around it, with a marker mote at the lantern itself.
 *
 * <ul>
 * <li>v0: white-ash moths around a glow lantern</li>
 * <li>v1: firefly moths, no lantern marker</li>
 * <li>v2: ash moths around a soul-fire lantern</li>
 * <li>v3: a tight fast swarm of mycelium motes</li>
 * <li>v4: two lanterns tracing mirrored paths</li>
 * <li>v5: cherry-petal moths drifting lazily</li>
 * <li>v6: enchant glyphs streaming into the lantern</li>
 * </ul>
 */
public final class MothSwarm implements InsideEffectBehavior {
	public static final String ID = "moth_swarm";
	private static final int MAX_MOTHS = 64;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		SimpleParticleType moth = switch (variant) {
			case 1 -> ParticleTypes.FIREFLY;
			case 2 -> ParticleTypes.ASH;
			case 3 -> ParticleTypes.MYCELIUM;
			case 5 -> ParticleTypes.CHERRY_LEAVES;
			case 6 -> ParticleTypes.ENCHANT;
			default -> ParticleTypes.WHITE_ASH;
		};
		int lanterns = variant == 4 ? 2 : 1;
		double t = gameTime / 10.0 * (variant == 3 ? 0.5 : variant == 5 ? 0.1 : 0.2);
		double reach = radius * 0.6 * Mth.clamp(def.behaviorStrength(), 0.8F, 1.2F);
		double cloud = variant == 3 ? 0.5 : 1.2;
		int budget = MAX_MOTHS / lanterns;
		int moths = ctx.scaleCount(variant == 3 ? 24 : 14, budget);
		for (int lantern = 0; lantern < lanterns; lantern++) {
			double sign = lantern == 0 ? 1.0 : -1.0;
			// Lissajous wander keeps the lantern inside the upper hemisphere (dome-safe),
			// but when both horizontal sines co-peak the raw path reaches ~1.18r; contain
			// the lantern so the swarm, marker and glyph stream all stay inside the shell.
			Vec3 lanternPos = BehaviorSupport.containPoint(center, radius, new Vec3(
					center.x + Math.sin(t * 1.3) * reach * sign,
					center.y + radius * (0.35 + 0.25 * Math.sin(t * 0.7)),
					center.z + Math.sin(t * 1.7 + 1.0) * reach * sign));
			double lx = lanternPos.x;
			double ly = lanternPos.y;
			double lz = lanternPos.z;
			if (variant == 6) {
				// Glyphs use the count=0 fly-towards packet form, streaming into the lantern.
				// The glyph SPAWNS at lantern + offset (FlyTowardsPositionParticle starts at
				// pos + delta and flies to pos), so the spawn ring is contained too: a
				// lantern near the wall plus the 2.0 ring reach would start glyphs outside.
				for (int i = 0; i < moths; i++) {
					double angle = Math.PI * 2.0 * i / moths;
					Vec3 spawn = BehaviorSupport.containPoint(shape, center, radius, new Vec3(
							lx + Math.cos(angle) * 2.0, ly + 0.6 * Math.sin(t + i), lz + Math.sin(angle) * 2.0));
					level.sendParticles(ParticleTypes.ENCHANT, true, false, lx, ly, lz, 0,
							spawn.x - lx, spawn.y - ly, spawn.z - lz, 1.0);
				}
			} else {
				level.sendParticles(moth, true, false, lx, ly, lz, moths, cloud, cloud * 0.6, cloud, 0.01);
			}

			if (variant != 1) {
				SimpleParticleType marker = variant == 2 ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.GLOW;
				level.sendParticles(marker, true, false, lx, ly, lz, 1, 0.02, 0.02, 0.02, 0.0);
			}
		}
	}
}

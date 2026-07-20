package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SculkChargeParticleOptions;
import net.minecraft.core.particles.ShriekParticleOption;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * Warden-flavored ghost pulses: every few pulses a sonic boom flashes at a
 * hash-picked interior point, ringed by sculk pops and a dark dust afterimage
 * that lingers between booms. The SONIC_BOOM particle is purely visual -- it
 * carries no damage.
 *
 * <ul>
 * <li>v0: a single boom on a six-pulse beat</li>
 * <li>v1: twin opposing booms (the point and its mirror)</li>
 * <li>v2: stacked shriek rings (delays 0/5/10) instead of the pop ring</li>
 * <li>v3: heartbeat-synced booms (four-pulse beat, soft warden heartbeat)</li>
 * <li>v4: a rising boom column (three booms stacked vertically)</li>
 * <li>v5: a sculk bloom (sculk-charge ring around the boom)</li>
 * <li>v6: whisper mode (no boom; shriek and pops only)</li>
 * </ul>
 */
public final class SonicGhosts implements InsideEffectBehavior {
	public static final String ID = "sonic_ghosts";
	/** Worst case v4: 3 booms + pop ring 8 + afterimage 3 = 14 particles on a boom pulse. */
	private static final int POP_RING = 8;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		long pulse = gameTime / 10L;
		long beat = variant == 3 ? 4L : 6L;
		// The apparition point holds still for one whole beat, then re-rolls.
		Vec3 p = boomPoint(center, radius, gameTime / (beat * 10L), variant);
		ParticleOptions afterimage = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 1.2F);
		// The afterimage haunts the point on every pulse, boom or not (recolor accent).
		BehaviorSupport.sendContained(level, afterimage, shape, center, radius,
				p.x, p.y, p.z, ctx.scaleCount(3, 6), 0.25, 0.35, 0.25, 0.0);
		if (pulse % beat != 0L) {
			return;
		}

		if (variant != 6) {
			BehaviorSupport.sendContained(level, ParticleTypes.SONIC_BOOM, shape, center, radius,
					p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
		}

		switch (variant) {
			case 1 -> BehaviorSupport.sendContained(level, ParticleTypes.SONIC_BOOM, shape, center, radius,
					2.0 * center.x - p.x, p.y, 2.0 * center.z - p.z, 1, 0.0, 0.0, 0.0, 0.0);
			case 2 -> {
				// Stacked shrieks rise with increasing client-side delay.
				for (int i = 0; i < 3; i++) {
					BehaviorSupport.sendContained(level, new ShriekParticleOption(i * 5), shape, center, radius,
							p.x, p.y + 0.3 * i, p.z, 1, 0.0, 0.0, 0.0, 0.0);
				}
			}
			case 3 -> level.playSound(null, p.x, p.y, p.z, SoundEvents.WARDEN_HEARTBEAT, SoundSource.AMBIENT, 0.35F, 0.9F);
			case 4 -> {
				for (int i = 1; i <= 2; i++) {
					BehaviorSupport.sendContained(level, ParticleTypes.SONIC_BOOM, shape, center, radius,
							p.x, p.y + radius * 0.2 * i, p.z, 1, 0.0, 0.0, 0.0, 0.0);
				}
			}
			case 6 -> BehaviorSupport.sendContained(level, new ShriekParticleOption(0), shape, center, radius,
					p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
			default -> {
			}
		}

		if (variant != 2) {
			// The pop ring around the boom point; v5 blooms sculk charges instead.
			ParticleOptions ring = variant == 5 ? new SculkChargeParticleOptions(0.5F) : ParticleTypes.SCULK_CHARGE_POP;
			for (int i = 0; i < POP_RING; i++) {
				double angle = Math.PI * 2.0 * i / POP_RING;
				BehaviorSupport.sendContained(level, ring, shape, center, radius,
						p.x + Math.cos(angle) * 0.8, p.y, p.z + Math.sin(angle) * 0.8, 1, 0.02, 0.02, 0.02, 0.0);
			}
		}
	}

	/** The hash-picked interior apparition point: within 0.6r horizontally, 0.15r..0.5r up. */
	private static Vec3 boomPoint(Vec3 center, float radius, long beatIndex, int variant) {
		long seed = BehaviorSupport.mix(beatIndex * 31L + variant);
		double angle = BehaviorSupport.hash01(seed) * Math.PI * 2.0;
		double dist = Math.sqrt(BehaviorSupport.hash01(seed + 1L)) * radius * 0.6;
		double y = radius * (0.15 + 0.35 * BehaviorSupport.hash01(seed + 2L));
		return new Vec3(center.x + Math.cos(angle) * dist, center.y + y, center.z + Math.sin(angle) * dist);
	}
}

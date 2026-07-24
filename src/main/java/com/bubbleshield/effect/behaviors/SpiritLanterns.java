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
 * Ghost lanterns bobbing on hash-seeded anchors: each one is a wax-on core
 * with a small-flame halo over a palette dust tassel. On a deterministic
 * per-lantern cycle a lantern is blown out (a white-smoke thread, with a soft
 * extinguish hiss for the first lantern) and relit with a flame flash. The
 * dust tassel is emitted in every lifecycle phase, so the owner color
 * override always recolors the set.
 *
 * <ul>
 * <li>v0: seven drifting lanterns at mid-height</li>
 * <li>v1: a dense festival string (up to twelve lanterns)</li>
 * <li>v2: floor lanterns parked low on an even 0.65r ring</li>
 * <li>v3: sky lanterns rising slowly and wrapping back to the floor</li>
 * <li>v4: paired lanterns (every anchor mirrored through the axis)</li>
 * <li>v5: a gusty night (short cycles, twice the blow-out smoke)</li>
 * <li>v6: will-o'-wisps (a glow halo instead of the small flame)</li>
 * </ul>
 */
public final class SpiritLanterns implements InsideEffectBehavior {
	public static final String ID = "spirit_lanterns";
	/** Worst case v1: 15 lanterns x relight pulse (flame 2 + core 1 + tassel dust 1) = 60 particles/pulse (v4 mirrors 7 x 2 x 4 = 56). */
	private static final int MAX_LANTERNS = 15;
	/** v4's mirrored pairs double every anchor, so the anchor count is capped lower. */
	private static final int MAX_PAIRED_ANCHORS = 7;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int base = switch (variant) {
			case 1 -> Mth.clamp((int) (8.0F * def.behaviorStrength()), 8, 12);
			case 2 -> 6;
			case 4 -> 5;
			default -> 7;
		};
		int lanterns = ctx.scaleCount(base, variant == 4 ? MAX_PAIRED_ANCHORS : MAX_LANTERNS);
		// Anchors re-scatter once a minute (1200 ticks); the lifecycle runs on its own cycle.
		long epoch = gameTime / 1200L;
		long cycle = variant == 5 ? 120L : 240L;
		ParticleOptions tassel = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 0.7F);
		ParticleOptions dimTassel = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.5F);
		for (int i = 0; i < lanterns; i++) {
			Vec3 pos = anchorPoint(center, radius, i, lanterns, epoch, variant, gameTime);
			long offset = (long) (BehaviorSupport.hash01(BehaviorSupport.mix(i * 911L + variant)) * cycle);
			long phase = Math.floorMod(gameTime + offset, cycle);
			emitLantern(level, shape, center, radius, variant, pos, phase, cycle, i, tassel, dimTassel);
			if (variant == 4) {
				// The paired twin hangs mirrored through the center axis.
				Vec3 mirrored = new Vec3(2.0 * center.x - pos.x, pos.y, 2.0 * center.z - pos.z);
				emitLantern(level, shape, center, radius, variant, mirrored, phase, cycle, i, tassel, dimTassel);
			}
		}
	}

	private static void emitLantern(ServerLevel level, ShieldShape shape, Vec3 center, float radius,
			int variant, Vec3 pos, long phase, long cycle, int index, ParticleOptions tassel, ParticleOptions dimTassel) {
		long smokeStart = cycle - 50L;
		long relightStart = cycle - 10L;
		if (phase >= relightStart) {
			// The relight flash: a flame flare as the core catches again.
			BehaviorSupport.sendContained(level, ParticleTypes.FLAME, shape, center, radius,
					pos.x, pos.y, pos.z, 2, 0.08, 0.08, 0.08, 0.02);
			BehaviorSupport.sendContained(level, ParticleTypes.WAX_ON, shape, center, radius,
					pos.x, pos.y, pos.z, 1, 0.05, 0.05, 0.05, 0.0);
			BehaviorSupport.sendContained(level, tassel, shape, center, radius,
					pos.x, pos.y - 0.3, pos.z, 1, 0.05, 0.05, 0.05, 0.0);
			return;
		}

		if (phase >= smokeStart) {
			// The blown-out lantern: only a smoke thread and a dimmed tassel.
			BehaviorSupport.sendContained(level, ParticleTypes.WHITE_SMOKE, shape, center, radius,
					pos.x, pos.y + 0.1, pos.z, variant == 5 ? 2 : 1, 0.03, 0.1, 0.03, 0.02);
			BehaviorSupport.sendContained(level, dimTassel, shape, center, radius,
					pos.x, pos.y - 0.3, pos.z, 1, 0.05, 0.05, 0.05, 0.0);
			if (index == 0 && phase < smokeStart + 10L) {
				level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.AMBIENT, 0.25F, 1.4F);
			}

			return;
		}

		// The lit lantern: wax-on core, its halo flame, and the palette tassel.
		BehaviorSupport.sendContained(level, ParticleTypes.WAX_ON, shape, center, radius,
				pos.x, pos.y, pos.z, 1, 0.06, 0.06, 0.06, 0.0);
		ParticleOptions halo = variant == 6 ? ParticleTypes.GLOW : ParticleTypes.SMALL_FLAME;
		BehaviorSupport.sendContained(level, halo, shape, center, radius,
				pos.x, pos.y + 0.15, pos.z, 1, 0.04, 0.04, 0.04, 0.0);
		BehaviorSupport.sendContained(level, tassel, shape, center, radius,
				pos.x, pos.y - 0.3, pos.z, 1, 0.05, 0.05, 0.05, 0.0);
	}

	/**
	 * The hash-seeded lantern anchor: within 0.7r horizontally and 0.15r..0.45r
	 * above the center plane (dome-safe, max reach ~0.83r), with a slow
	 * sinusoidal bob. v2 parks an even ring near the floor; v3 rises higher on
	 * a tighter 0.5r footprint and wraps instead of bobbing.
	 */
	private static Vec3 anchorPoint(Vec3 center, float radius, int index, int lanterns, long epoch, int variant, long gameTime) {
		long seed = BehaviorSupport.mix(epoch * 977L + index * 13L + variant);
		double angle;
		double dist;
		if (variant == 2) {
			angle = Math.PI * 2.0 * index / lanterns + BehaviorSupport.hash01(seed) * 0.4;
			dist = radius * 0.65;
		} else {
			angle = BehaviorSupport.hash01(seed) * Math.PI * 2.0;
			dist = Math.sqrt(BehaviorSupport.hash01(seed + 1L)) * radius * (variant == 3 ? 0.5 : 0.7);
		}

		double y;
		if (variant == 2) {
			y = 0.35;
		} else if (variant == 3) {
			// The sky lantern: a wrapping rise from 0.1r up to 0.65r.
			double rise = (BehaviorSupport.hash01(seed + 2L) + gameTime / 900.0) % 1.0;
			y = radius * (0.1 + 0.55 * rise);
		} else {
			y = radius * (0.15 + 0.3 * BehaviorSupport.hash01(seed + 2L))
					+ Math.sin(gameTime * 0.06 + index * 1.9) * 0.3;
		}

		return new Vec3(center.x + Math.cos(angle) * dist, center.y + y, center.z + Math.sin(angle) * dist);
	}
}

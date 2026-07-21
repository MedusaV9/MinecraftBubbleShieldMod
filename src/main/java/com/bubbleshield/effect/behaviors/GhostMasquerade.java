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
 * A ghostly masquerade ball: two-tone dust pairs (a primary-tone dancer and a
 * darker-tone partner) spin around each other while every pair waltzes around
 * the projector on a mid-height ring. The dance keeps 3/4 time -- one pulse in
 * three is the downbeat, marked by a NOTE flourish over each pair and a soft
 * harp strike. Purely particles -- no entities, no state, no cleanup.
 *
 * <ul>
 * <li>v0: the classic waltz -- six pairs on one ring, harp downbeats</li>
 * <li>v1: grand ballroom -- pairs split across two tiers, the upper tier a
 * quarter turn ahead</li>
 * <li>v2: slow minuet -- half-speed turns under an end-rod candelabra glow,
 * chime downbeats</li>
 * <li>v3: spinning dip -- couples bob downward into the downbeat and rise
 * through the off-beats</li>
 * <li>v4: masked crowd -- more, smaller couples veiled in white smoke</li>
 * <li>v5: mirrored quadrille -- alternating couples circle the floor in
 * opposite directions</li>
 * <li>v6: ghost train -- couples trail white-smoke wisps and the flourish
 * cascades pair by pair instead of striking at once</li>
 * </ul>
 */
public final class GhostMasquerade implements InsideEffectBehavior {
	public static final String ID = "ghost_masquerade";
	/** Worst case v4 downbeat: 12 pairs x (2 dust + 1 smoke + 1 note) = 48 particles/pulse. */
	private static final int MAX_PAIRS = 12;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int base = Mth.clamp((int) (radius * 0.3F * def.behaviorStrength()), 4, variant == 4 ? 10 : 8);
		int pairs = ctx.scaleCount(variant == 4 ? base + 2 : base, variant == 4 ? MAX_PAIRS : 8);
		long pulse = gameTime / 10L;
		boolean downbeat = pulse % 3L == 0L;
		double floorTurn = gameTime / 10.0 * (variant == 2 ? 0.03 : 0.06);
		double coupleSpin = gameTime / 10.0 * (variant == 2 ? 0.35 : 0.7);
		double ringDist = radius * 0.5;
		double armLength = Mth.clamp(radius * 0.08F, 0.35F, 1.2F);
		float dustSize = variant == 4 ? 0.7F : 1.0F;
		ParticleOptions dancer = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, dustSize);
		ParticleOptions partner = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, dustSize);
		for (int p = 0; p < pairs; p++) {
			// v5 sends every other couple around the floor the opposite way.
			double direction = variant == 5 && p % 2 != 0 ? -1.0 : 1.0;
			double floorAngle = direction * floorTurn + Math.PI * 2.0 * p / pairs;
			double height = radius * 0.3;
			if (variant == 1) {
				// The upper tier dances a quarter turn ahead of the lower one.
				height = radius * (p % 2 == 0 ? 0.2 : 0.45);
				floorAngle += p % 2 == 0 ? 0.0 : Math.PI * 0.5;
			} else if (variant == 3) {
				// The dip: sink into the downbeat, rise back through the bar.
				height -= radius * 0.08 * Math.cos(Math.PI * 2.0 * (pulse % 3L) / 3.0);
			}

			double px = center.x + Math.cos(floorAngle) * ringDist;
			double pz = center.z + Math.sin(floorAngle) * ringDist;
			double py = center.y + height;
			double spin = coupleSpin + p * 1.7;
			double ax = Math.cos(spin) * armLength;
			double az = Math.sin(spin) * armLength;
			BehaviorSupport.sendContained(level, dancer, shape, center, radius,
					px + ax, py, pz + az, 1, 0.03, 0.06, 0.03, 0.0);
			BehaviorSupport.sendContained(level, partner, shape, center, radius,
					px - ax, py, pz - az, 1, 0.03, 0.06, 0.03, 0.0);
			if (variant == 2) {
				BehaviorSupport.sendContained(level, ParticleTypes.END_ROD, shape, center, radius,
						px, py + 0.8, pz, 1, 0.05, 0.05, 0.05, 0.0);
			} else if (variant == 4) {
				BehaviorSupport.sendContained(level, ParticleTypes.WHITE_SMOKE, shape, center, radius,
						px, py + 0.3, pz, 1, 0.15, 0.1, 0.15, 0.0);
			} else if (variant == 6) {
				// The train: a wisp hanging behind the couple's line of travel.
				BehaviorSupport.sendContained(level, ParticleTypes.WHITE_SMOKE, shape, center, radius,
						center.x + Math.cos(floorAngle - 0.25) * ringDist, py, center.z + Math.sin(floorAngle - 0.25) * ringDist,
						1, 0.05, 0.05, 0.05, 0.0);
			}

			// v6 cascades the flourish around the ring, one couple per pulse.
			boolean flourish = variant == 6 ? pulse % pairs == p : downbeat;
			if (flourish) {
				BehaviorSupport.sendContained(level, ParticleTypes.NOTE, shape, center, radius,
						px, py + 0.6, pz, 1, 0.05, 0.05, 0.05, 0.0);
			}
		}

		if (downbeat) {
			float volume = Mth.clamp(radius / 16.0F, 0.4F, 2.0F);
			// NOTE_BLOCK_* constants are Holder.Reference<SoundEvent>, hence .value().
			level.playSound(null, center.x, center.y + radius * 0.3, center.z,
					variant == 2 ? SoundEvents.NOTE_BLOCK_CHIME.value() : SoundEvents.NOTE_BLOCK_HARP.value(),
					SoundSource.AMBIENT, volume, variant == 2 ? 0.75F : 1.0F);
		}
	}
}

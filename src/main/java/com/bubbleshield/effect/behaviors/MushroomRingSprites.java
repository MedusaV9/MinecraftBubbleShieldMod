package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A fairy ring on the bubble floor: dust mushroom caps sprout, swell and shrink
 * back down on hash-staggered growth cycles (primary-dust caps over
 * secondary-dust stems), while SPORE_BLOSSOM_AIR motes drift lazily inside the
 * circle -- purely particles, no entities, no state, no cleanup.
 *
 * <ul>
 * <li>v0: classic fairy ring (staggered caps, a gentle spore drift inside)</li>
 * <li>v1: twin concentric rings (the inner ring grows in counter-phase)</li>
 * <li>v2: waltzing ring (the whole circle slowly rotates as it sprouts)</li>
 * <li>v3: elder toadstools (fewer, larger caps; a mycelium puff at full bloom)</li>
 * <li>v4: spore storm (doubled spore drift, a composter glimmer at each cap's peak)</li>
 * <li>v5: firefly-lit ring (fireflies hovering over the sprouting caps)</li>
 * <li>v6: sprite dance (happy-villager sprites hopping cap to cap around the ring)</li>
 * </ul>
 */
public final class MushroomRingSprites implements InsideEffectBehavior {
	public static final String ID = "mushroom_ring_sprites";
	/** One full sprout-and-shrink growth cycle per cap, hash-staggered per slot. */
	private static final long GROW_TICKS = 120L;
	/**
	 * Worst case v4 (countMult maxed): 12 caps x (cap 1 + stem 1 + peak glimmer 1)
	 * + 16 spores = 52 particles/pulse; v1 peaks at 12x2 outer + 6x2 inner + 8
	 * spores = 44, every other variant lower -- all well under 128.
	 */
	private static final int MAX_CAPS = 12;
	private static final int MAX_SPORES = 16;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int capColor = ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF;
		int stemColor = ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF;
		int baseCaps = Mth.clamp((int) (radius * 0.5F * def.behaviorStrength()), 6, variant == 3 ? 6 : 10);
		int caps = ctx.scaleCount(baseCaps, variant == 3 ? 8 : MAX_CAPS);
		double ringR = radius * 0.55;
		double floorY = center.y + radius * 0.06;
		// v2 waltzes: the whole circle drifts around the center.
		double spin = variant == 2 ? gameTime * 0.008 : 0.0;
		for (int i = 0; i < caps; i++) {
			double angle = Math.PI * 2.0 * i / Math.max(1, caps) + spin;
			double cx = center.x + Math.cos(angle) * ringR;
			double cz = center.z + Math.sin(angle) * ringR;
			emitCap(level, shape, center, radius, variant, cx, floorY, cz, i, gameTime, capColor, stemColor);
			if (variant == 1 && i % 2 == 0) {
				// The counter-phase inner ring: half the caps, half the radius.
				double ix = center.x + Math.cos(-angle) * ringR * 0.5;
				double iz = center.z + Math.sin(-angle) * ringR * 0.5;
				emitCap(level, shape, center, radius, variant, ix, floorY, iz, i + 64, gameTime + GROW_TICKS / 2L, capColor, stemColor);
			}
		}

		// The drift inside the circle: spores wander a slow hash-seeded orbit.
		int spores = ctx.scaleCount(variant == 4 ? 8 : 4, variant == 4 ? MAX_SPORES : 8);
		BehaviorSupport.sendContained(level, ParticleTypes.SPORE_BLOSSOM_AIR, shape, center, radius,
				center.x, floorY + radius * 0.25, center.z, spores, ringR * 0.35, radius * 0.15, ringR * 0.35, 0.0);
		if (variant == 5) {
			for (int f = 0; f < ctx.scaleCount(2, 4); f++) {
				double fa = gameTime * 0.02 + Math.PI * 2.0 * f / 4.0;
				BehaviorSupport.sendContained(level, ParticleTypes.FIREFLY, shape, center, radius,
						center.x + Math.cos(fa) * ringR, floorY + radius * 0.2, center.z + Math.sin(fa) * ringR,
						1, 0.15, 0.1, 0.15, 0.0);
			}
		} else if (variant == 6) {
			// Sprites hop one cap slot per pulse, arcing over the gap between caps.
			for (int s = 0; s < ctx.scaleCount(2, 3); s++) {
				long hop = gameTime / 10L + s * 4L;
				double t = (gameTime % 10L) / 10.0;
				double a0 = Math.PI * 2.0 * hop / Math.max(1, caps);
				double a1 = Math.PI * 2.0 * (hop + 1L) / Math.max(1, caps);
				double a = Mth.lerp(t, a0, a1);
				double arc = Math.sin(t * Math.PI) * radius * 0.12;
				BehaviorSupport.sendContained(level, ParticleTypes.HAPPY_VILLAGER, shape, center, radius,
						center.x + Math.cos(a) * ringR, floorY + 0.3 + arc, center.z + Math.sin(a) * ringR,
						1, 0.05, 0.05, 0.05, 0.0);
			}
		}
	}

	/** One cap at its current growth stage: a primary cap mote over a secondary stem mote. */
	private static void emitCap(ServerLevel level, ShieldShape shape, Vec3 center, float radius,
			int variant, double x, double floorY, double z, int slot, long gameTime, int capColor, int stemColor) {
		// Hash-staggered growth: each slot sprouts and shrinks on its own phase.
		long phase = (gameTime + (long) (BehaviorSupport.hash01(slot * 613L) * GROW_TICKS)) % GROW_TICKS;
		double growth = 1.0 - Math.abs(phase / (double) GROW_TICKS * 2.0 - 1.0);
		float capSize = (float) ((variant == 3 ? 0.8 : 0.5) + growth * (variant == 3 ? 1.4 : 0.9));
		double capY = floorY + 0.15 + growth * (variant == 3 ? 0.7 : 0.4);
		BehaviorSupport.sendContained(level, new DustParticleOptions(capColor, capSize), shape, center, radius,
				x, capY, z, 1, 0.03, 0.02, 0.03, 0.0);
		BehaviorSupport.sendContained(level, new DustParticleOptions(stemColor, 0.6F), shape, center, radius,
				x, floorY + growth * 0.1, z, 1, 0.02, 0.05, 0.02, 0.0);
		boolean atPeak = growth > 0.9;
		if (atPeak && variant == 3) {
			BehaviorSupport.sendContained(level, ParticleTypes.MYCELIUM, shape, center, radius,
					x, capY + 0.2, z, 1, 0.2, 0.1, 0.2, 0.0);
		} else if (atPeak && variant == 4) {
			BehaviorSupport.sendContained(level, ParticleTypes.COMPOSTER, shape, center, radius,
					x, capY + 0.2, z, 1, 0.1, 0.1, 0.1, 0.0);
		}
	}
}

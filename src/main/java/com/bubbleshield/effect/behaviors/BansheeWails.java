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
 * A keening banshee: a soul-and-glow spiral climbs from a hash-seeded floor
 * root to its apex and, on the wail beat, the apex flashes a (purely visual)
 * SONIC_BOOM ringed by a dust shockwave. The spiral strand itself is palette
 * dust and the shockwave uses the secondary strand color, so the owner color
 * override recolors the whole apparition.
 *
 * <ul>
 * <li>v0: a lone keener on a six-pulse wail</li>
 * <li>v1: quick keening (four-pulse cadence)</li>
 * <li>v2: twin banshees (the column and wail mirrored through the axis)</li>
 * <li>v3: a high shriek (apex at 0.7r)</li>
 * <li>v4: a low moan (apex at 0.3r, a wide shockwave on an eight-pulse beat)</li>
 * <li>v5: a double shockwave (two concentric dust rings per wail)</li>
 * <li>v6: a chorus spiral (a second secondary-color dust strand)</li>
 * </ul>
 */
public final class BansheeWails implements InsideEffectBehavior {
	public static final String ID = "banshee_wails";
	/** Worst case v2: 12 spiral steps x 2 columns x (soul 1 + strand dust 1 + glow 1) = 72, plus the wail pulse 2 x (boom 1 + ring 10) = 22 -> 94 particles/pulse. */
	private static final int MAX_SPIRAL_STEPS = 12;
	/** Dust points per wail shockwave ring. */
	private static final int WAIL_RING_POINTS = 10;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		long pulse = gameTime / 10L;
		long beat = variant == 1 ? 4L : variant == 4 ? 8L : 6L;
		double apexFrac = variant == 3 ? 0.7 : variant == 4 ? 0.3 : 0.5;
		// The banshee holds its ground for one whole wail cycle, then re-roots.
		Vec3 root = rootPoint(center, radius, gameTime / (beat * 10L), variant);
		int base = Mth.clamp((int) (radius * 1.5F * def.behaviorStrength()), 5, 9);
		int steps = ctx.scaleCount(base, MAX_SPIRAL_STEPS);
		double apexHeight = radius * apexFrac;
		ParticleOptions strand = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 0.8F);
		ParticleOptions chorus = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.8F);
		ParticleOptions shockwave = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 1.2F);
		emitColumn(level, shape, center, radius, variant, root, steps, apexHeight, gameTime, strand, chorus);
		if (variant == 2) {
			// The twin keens the same spiral reflected through the center axis.
			Vec3 mirrored = new Vec3(2.0 * center.x - root.x, root.y, 2.0 * center.z - root.z);
			emitColumn(level, shape, center, radius, variant, mirrored, steps, apexHeight, gameTime, strand, chorus);
		}

		if (pulse % beat != 0L) {
			return;
		}

		Vec3 apex = new Vec3(root.x, root.y + apexHeight, root.z);
		emitWail(level, shape, center, radius, variant, apex, shockwave);
		if (variant == 2) {
			emitWail(level, shape, center, radius, variant,
					new Vec3(2.0 * center.x - apex.x, apex.y, 2.0 * center.z - apex.z), shockwave);
		}

		level.playSound(null, apex.x, apex.y, apex.z, SoundEvents.BELL_RESONATE, SoundSource.AMBIENT, 0.3F, 0.55F);
	}

	private static void emitColumn(ServerLevel level, ShieldShape shape, Vec3 center, float radius,
			int variant, Vec3 root, int steps, double apexHeight, long gameTime, ParticleOptions strand, ParticleOptions chorus) {
		for (int s = 0; s < steps; s++) {
			double frac = (s + 0.5) / steps;
			// The spiral narrows as it climbs, twisting slowly between pulses.
			double angle = frac * Math.PI * 4.0 + gameTime * 0.015;
			double spiralRadius = radius * 0.12 * (1.0 - 0.5 * frac);
			double x = root.x + Math.cos(angle) * spiralRadius;
			double y = root.y + frac * apexHeight;
			double z = root.z + Math.sin(angle) * spiralRadius;
			BehaviorSupport.sendContained(level, ParticleTypes.SOUL, shape, center, radius,
					x, y, z, 1, 0.04, 0.08, 0.04, 0.02);
			// The recolor accent: the strand itself is palette dust, every variant.
			BehaviorSupport.sendContained(level, strand, shape, center, radius,
					x, y, z, 1, 0.05, 0.05, 0.05, 0.0);
			if (variant == 6) {
				// The chorus: a second strand winding on the opposite side.
				BehaviorSupport.sendContained(level, chorus, shape, center, radius,
						root.x - Math.cos(angle) * spiralRadius, y, root.z - Math.sin(angle) * spiralRadius,
						1, 0.05, 0.05, 0.05, 0.0);
			} else {
				BehaviorSupport.sendContained(level, ParticleTypes.GLOW, shape, center, radius,
						x, y + 0.15, z, 1, 0.04, 0.04, 0.04, 0.0);
			}
		}
	}

	private static void emitWail(ServerLevel level, ShieldShape shape, Vec3 center, float radius,
			int variant, Vec3 apex, ParticleOptions shockwave) {
		BehaviorSupport.sendContained(level, ParticleTypes.SONIC_BOOM, shape, center, radius,
				apex.x, apex.y, apex.z, 1, 0.0, 0.0, 0.0, 0.0);
		int rings = variant == 5 ? 2 : 1;
		for (int r = 0; r < rings; r++) {
			double ringRadius = radius * (variant == 4 ? 0.3 : 0.18) + r * radius * 0.1;
			for (int i = 0; i < WAIL_RING_POINTS; i++) {
				double angle = Math.PI * 2.0 * i / WAIL_RING_POINTS + r * Math.PI / WAIL_RING_POINTS;
				BehaviorSupport.sendContained(level, shockwave, shape, center, radius,
						apex.x + Math.cos(angle) * ringRadius, apex.y, apex.z + Math.sin(angle) * ringRadius,
						1, 0.03, 0.03, 0.03, 0.0);
			}
		}
	}

	/** The hash-seeded column root: within 0.35r horizontally, just above the floor plane (dome-safe). */
	private static Vec3 rootPoint(Vec3 center, float radius, long cycleIndex, int variant) {
		long seed = BehaviorSupport.mix(cycleIndex * 131L + variant);
		double angle = BehaviorSupport.hash01(seed) * Math.PI * 2.0;
		double dist = Math.sqrt(BehaviorSupport.hash01(seed + 1L)) * radius * 0.35;
		return new Vec3(center.x + Math.cos(angle) * dist, center.y + 0.2, center.z + Math.sin(angle) * dist);
	}
}

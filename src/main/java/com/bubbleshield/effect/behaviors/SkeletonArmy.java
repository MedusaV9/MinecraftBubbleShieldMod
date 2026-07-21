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
 * Ranks of bone-dust skeleton figures marching a phalanx arc around the
 * projector: each figure is a compact silhouette (striding legs, torso,
 * secondary-strand ribcage, skull) bobbing on the march beat, with crit
 * flecks kicked up at the feet on every step. The bones are palette dust
 * (author the palette bone-white), so the owner recolor retints the army.
 * Pure particles -- the phalanx is redrawn every pulse from deterministic
 * math.
 *
 * <ul>
 * <li>v0: a single rank on a slow circuit</li>
 * <li>v1: a double phalanx (two staggered ranks)</li>
 * <li>v2: a shield wall (tight spacing, a secondary dust pavise before each figure)</li>
 * <li>v3: wither guard (raised skulls trailing smoke)</li>
 * <li>v4: a drummer corps (note over the mid-file on the beat, bass thump)</li>
 * <li>v5: the charge (double march speed, crit sparks every pulse)</li>
 * <li>v6: honor files advancing toward the center and back out</li>
 * </ul>
 */
public final class SkeletonArmy implements InsideEffectBehavior {
	public static final String ID = "skeleton_army";
	/** Worst case v2: 12 figures x (legs 2 + torso 1 + ribs 1 + skull 1 + pavise 1 + crit 1) = 84 particles/pulse. */
	private static final int MAX_FIGURES = 12;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int perRank = Mth.clamp((int) (radius * 0.5F * def.behaviorStrength()), 3, 6);
		int figures = ctx.scaleCount(variant == 1 ? perRank * 2 : perRank, MAX_FIGURES);
		double sweep = gameTime * (variant == 5 ? 0.022 : 0.011);
		double spacing = variant == 2 ? 0.24 : 0.38;
		long beat = gameTime / 10L;
		double h = Math.min(radius * 0.45, 1.9 * Mth.clamp(def.behaviorStrength(), 0.8F, 1.3F));
		ParticleOptions boneDust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 0.9F);
		ParticleOptions skullDust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.2F);
		ParticleOptions ribsDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.6F);
		ParticleOptions paviseDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 1.4F);
		for (int i = 0; i < figures; i++) {
			int rank = variant == 1 ? i % 2 : 0;
			int file = variant == 1 ? i / 2 : i;
			double angle;
			double dist;
			if (variant == 6) {
				// Honor files: fixed spokes, advancing in and back out.
				angle = Math.PI * 2.0 * i / figures;
				dist = radius * (0.5 + 0.15 * Math.sin(gameTime * 0.02 + i));
			} else {
				angle = sweep + file * spacing;
				dist = radius * (0.52 + 0.12 * rank);
			}

			double x = center.x + Math.cos(angle) * dist;
			double z = center.z + Math.sin(angle) * dist;
			double heading = angle + Math.PI / 2.0;
			double hx = Math.cos(heading);
			double hz = Math.sin(heading);
			// The march bob: alternate files rise on alternate beats.
			double y = center.y + 0.15 + 0.06 * h * ((beat + i) % 2L);
			int stride = ((beat + i) % 2L == 0L ? 1 : -1);
			for (int side = -1; side <= 1; side += 2) {
				// Striding legs: one forward, one back, swapping every beat.
				BehaviorSupport.sendContained(level, boneDust, shape, center, radius,
						x - hz * side * 0.06 * h + hx * side * stride * 0.07 * h, y + 0.2 * h,
						z + hx * side * 0.06 * h + hz * side * stride * 0.07 * h,
						1, 0.02, 0.05, 0.02, 0.0);
			}

			BehaviorSupport.sendContained(level, boneDust, shape, center, radius,
					x, y + 0.48 * h, z, 1, 0.03, 0.05, 0.03, 0.0);
			BehaviorSupport.sendContained(level, ribsDust, shape, center, radius,
					x, y + 0.56 * h, z, 1, 0.04, 0.02, 0.04, 0.0);
			BehaviorSupport.sendContained(level, skullDust, shape, center, radius,
					x, y + (variant == 3 ? 0.85 : 0.74) * h, z, 1, 0.02, 0.02, 0.02, 0.0);
			if (variant == 3) {
				BehaviorSupport.sendContained(level, ParticleTypes.SMOKE, shape, center, radius,
						x, y + 0.95 * h, z, 1, 0.05, 0.08, 0.05, 0.005);
			} else if (variant == 2) {
				// The pavise: a broad secondary dust shield held toward the march.
				BehaviorSupport.sendContained(level, paviseDust, shape, center, radius,
						x + hx * 0.14 * h, y + 0.5 * h, z + hz * 0.14 * h, 1, 0.02, 0.08, 0.02, 0.0);
			}

			if (variant == 5) {
				BehaviorSupport.sendContained(level, ParticleTypes.CRIT, shape, center, radius,
						x, y + 0.08 * h, z, 2, 0.12, 0.05, 0.12, 0.05);
			} else if (beat % 2L == 0L) {
				// The step beat: crit flecks kicked up at the feet.
				BehaviorSupport.sendContained(level, ParticleTypes.CRIT, shape, center, radius,
						x, y + 0.08 * h, z, 1, 0.1, 0.05, 0.1, 0.03);
			}
		}

		if (variant == 4) {
			// The drummer: a note over the mid-file, a bass thump every fourth pulse.
			double midAngle = sweep + (figures / 2.0) * spacing;
			double nx = center.x + Math.cos(midAngle) * radius * 0.52;
			double nz = center.z + Math.sin(midAngle) * radius * 0.52;
			BehaviorSupport.sendContained(level, ParticleTypes.NOTE, shape, center, radius,
					nx, center.y + 0.15 + h, nz, 1, 0.05, 0.05, 0.05, 0.0);
			if (gameTime % 40L == 0L) {
				level.playSound(null, nx, center.y + 0.15 + 0.5 * h, nz,
						SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.AMBIENT, 0.5F, 0.7F);
			}
		}
	}
}

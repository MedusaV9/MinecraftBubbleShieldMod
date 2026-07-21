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
import net.minecraft.world.phys.Vec3;

/**
 * Ghost owls roosting on hash-seeded high anchors: each owl perches as a pair
 * of glow eyes over a palette dust breast, then -- in the last quarter of its
 * roost cycle -- dives along a parabola toward the floor and up to its next
 * perch, trailing end-rod wisps, and re-perches. Dives are staggered across
 * the parliament (unless synchronized), so something is always moving. Pure
 * particles, no entities, no state, no cleanup.
 *
 * <ul>
 * <li>v0: three roosting owls</li>
 * <li>v1: five restless owls (short roosts, frequent dives)</li>
 * <li>v2: one great horned owl (wide end-rod wing-tips, secondary-dust ear tufts)</li>
 * <li>v3: snowy owls (a snowflake downdraft under every dive)</li>
 * <li>v4: a synchronized parliament (all owls dive together)</li>
 * <li>v5: ember owls (soul-fire dive trails)</li>
 * <li>v6: a hooting roost (note motes and a low flute hoot on a slow beat)</li>
 * </ul>
 */
public final class WispOwls implements InsideEffectBehavior {
	public static final String ID = "wisp_owls";
	/** Worst case v3 at ctx-max owls, all diving: 5 owls x (body 1 + trail 3 + breast dust 1 + downdraft 1) = 30 particles/pulse. */
	private static final int MAX_OWLS = 5;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int owls = ctx.scaleCount(switch (variant) {
			case 1 -> 5;
			case 2 -> 1;
			case 4 -> 4;
			default -> 3;
		}, MAX_OWLS);
		long roostTicks = variant == 1 ? 80L : 160L;
		ParticleOptions breastDust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, variant == 2 ? 1.1F : 0.7F);
		for (int o = 0; o < owls; o++) {
			// Staggered roost clocks so dives ripple around; v4 shares one clock.
			long clock = gameTime + (variant == 4 ? 0L : o * roostTicks / Math.max(1, owls));
			long cycle = clock / roostTicks;
			double phase = (clock % roostTicks) / (double) roostTicks;
			Vec3 perch = perchPoint(center, radius, o, cycle);
			if (phase < 0.75) {
				// Perched: glow eyes over the dust breast.
				BehaviorSupport.sendContained(level, ParticleTypes.GLOW, shape, center, radius,
						perch.x - 0.12, perch.y, perch.z, 1, 0.01, 0.01, 0.01, 0.0);
				BehaviorSupport.sendContained(level, ParticleTypes.GLOW, shape, center, radius,
						perch.x + 0.12, perch.y, perch.z, 1, 0.01, 0.01, 0.01, 0.0);
				BehaviorSupport.sendContained(level, breastDust, shape, center, radius,
						perch.x, perch.y - 0.25, perch.z, 1, 0.05, 0.08, 0.05, 0.0);
				if (variant == 2) {
					// The great horned owl: wing-tips and secondary-dust ear tufts.
					double span = 0.6 * Math.min(1.0, radius * 0.15);
					BehaviorSupport.sendContained(level, ParticleTypes.END_ROD, shape, center, radius,
							perch.x - span, perch.y - 0.1, perch.z, 1, 0.03, 0.03, 0.03, 0.0);
					BehaviorSupport.sendContained(level, ParticleTypes.END_ROD, shape, center, radius,
							perch.x + span, perch.y - 0.1, perch.z, 1, 0.03, 0.03, 0.03, 0.0);
					BehaviorSupport.sendContained(level,
							new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.7F),
							shape, center, radius, perch.x, perch.y + 0.25, perch.z, 2, 0.12, 0.03, 0.12, 0.0);
				} else if (variant == 6 && phase < 0.1) {
					BehaviorSupport.sendContained(level, ParticleTypes.NOTE, shape, center, radius,
							perch.x, perch.y + 0.4, perch.z, 1, 0.05, 0.05, 0.05, 0.0);
				}

				continue;
			}

			// The dive: a parabola from this perch to the next, dipping low.
			Vec3 next = perchPoint(center, radius, o, cycle + 1L);
			double u = (phase - 0.75) / 0.25;
			ParticleOptions trail = variant == 5 ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.END_ROD;
			Vec3 pos = divePoint(center, radius, perch, next, u);
			BehaviorSupport.sendContained(level, ParticleTypes.GLOW, shape, center, radius,
					pos.x, pos.y, pos.z, 1, 0.05, 0.05, 0.05, 0.01);
			for (int k = 1; k <= 3; k++) {
				Vec3 behind = divePoint(center, radius, perch, next, Math.max(0.0, u - 0.09 * k));
				BehaviorSupport.sendContained(level, trail, shape, center, radius,
						behind.x, behind.y, behind.z, 1, 0.02, 0.02, 0.02, 0.0);
			}

			// The recolor accent rides the dive too, so every pulse carries palette dust.
			BehaviorSupport.sendContained(level, breastDust, shape, center, radius,
					pos.x, pos.y - 0.2, pos.z, 1, 0.05, 0.05, 0.05, 0.0);
			if (variant == 3) {
				BehaviorSupport.sendContained(level, ParticleTypes.SNOWFLAKE, shape, center, radius,
						pos.x, Math.max(center.y + 0.2, pos.y - 0.6), pos.z, 1, 0.1, 0.05, 0.1, 0.01);
			}
		}

		if (variant == 6 && gameTime % 80L == 0L) {
			// The hoot: a low flute pling from the roost height (Holder, hence .value()).
			level.playSound(null, center.x, center.y + radius * 0.5, center.z,
					SoundEvents.NOTE_BLOCK_FLUTE.value(), SoundSource.AMBIENT, 0.35F, 0.55F);
		}
	}

	/** A point on the dive parabola: lerp between perches, dipping toward the floor mid-flight. */
	private static Vec3 divePoint(Vec3 center, float radius, Vec3 from, Vec3 to, double u) {
		Vec3 pos = from.lerp(to, u);
		double low = center.y + radius * 0.12;
		double dip = Math.max(0.0, (from.y + to.y) * 0.5 - low);
		return new Vec3(pos.x, pos.y - dip * 4.0 * u * (1.0 - u), pos.z);
	}

	/** The hash-seeded high roost anchor: within 0.5r horizontally, 0.4r..0.62r above the plane. */
	private static Vec3 perchPoint(Vec3 center, float radius, int owl, long cycle) {
		long seed = BehaviorSupport.mix(cycle * 769L + owl * 13L);
		double angle = BehaviorSupport.hash01(seed) * Math.PI * 2.0;
		double dist = Math.sqrt(BehaviorSupport.hash01(seed + 1L)) * radius * 0.5;
		double y = radius * (0.4 + 0.22 * BehaviorSupport.hash01(seed + 2L));
		return new Vec3(center.x + Math.cos(angle) * dist, center.y + y, center.z + Math.sin(angle) * dist);
	}
}

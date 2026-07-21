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
 * Barnacled drowned spirits wading a slow ring around the projector: each
 * spirit is a hunched dust silhouette (striding legs, a forward-leaning
 * torso and head, two secondary-strand barnacle crusts) with splashes at its
 * feet, a dolphin mote arcing ahead and a nautilus fleck swimming into its
 * chest (NAUTILUS is a fly-toward particle; {@link BehaviorSupport#sendContained}
 * routes it through the dip-safe fly-toward path). Bodies are palette dust,
 * so the owner recolor retints the procession. Pure particles, no entities.
 *
 * <ul>
 * <li>v0: four spirits on a slow wade</li>
 * <li>v1: the tide surge (faster wade, doubled splashes)</li>
 * <li>v2: trident bearers (an electric-spark glint at the raised hand)</li>
 * <li>v3: the deep procession (glow-ink shadows trailing each spirit)</li>
 * <li>v4: counter-rotating double ring</li>
 * <li>v5: abyssal chorus (squid-ink breath, a distant drowned groan)</li>
 * <li>v6: the nautilus crown (flecks converging above the procession)</li>
 * </ul>
 */
public final class DrownedProcession implements InsideEffectBehavior {
	public static final String ID = "drowned_procession";
	/** Worst case v1: 8 spirits x (body dust 5 + barnacles 2 + splash 2 + dolphin 1 + nautilus 1) = 88 particles/pulse. */
	private static final int MAX_SPIRITS = 8;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int spirits = ctx.scaleCount(variant == 4 ? 6 : 4, MAX_SPIRITS);
		double omega = variant == 1 ? 0.02 : 0.008;
		double h = Math.min(radius * 0.42, 1.8 * Mth.clamp(def.behaviorStrength(), 0.8F, 1.3F));
		ParticleOptions bodyDust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.0F);
		ParticleOptions barnacleDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.5F);
		for (int i = 0; i < spirits; i++) {
			int ring = variant == 4 ? i % 2 : 0;
			double dir = ring == 1 ? -1.0 : 1.0;
			double angle = dir * gameTime * omega + Math.PI * 2.0 * i / spirits;
			double dist = radius * (variant == 4 ? (ring == 0 ? 0.45 : 0.62) : 0.55);
			double x = center.x + Math.cos(angle) * dist;
			double z = center.z + Math.sin(angle) * dist;
			double heading = angle + dir * Math.PI / 2.0;
			double hx = Math.cos(heading);
			double hz = Math.sin(heading);
			// The wade bob never dips below the floor plane.
			double y = center.y + 0.15 + 0.04 * h * (1.0 + Math.sin(gameTime * 0.05 + i));
			for (int side = -1; side <= 1; side += 2) {
				BehaviorSupport.sendContained(level, bodyDust, shape, center, radius,
						x - hz * side * 0.06 * h, y + 0.2 * h, z + hx * side * 0.06 * h, 1, 0.02, 0.05, 0.02, 0.0);
			}

			// The hunch: torso and head lean forward along the wade heading.
			BehaviorSupport.sendContained(level, bodyDust, shape, center, radius,
					x + hx * 0.06 * h, y + 0.45 * h, z + hz * 0.06 * h, 1, 0.03, 0.05, 0.03, 0.0);
			BehaviorSupport.sendContained(level, bodyDust, shape, center, radius,
					x + hx * 0.12 * h, y + 0.62 * h, z + hz * 0.12 * h, 1, 0.03, 0.05, 0.03, 0.0);
			BehaviorSupport.sendContained(level, bodyDust, shape, center, radius,
					x + hx * 0.18 * h, y + 0.72 * h, z + hz * 0.18 * h, 1, 0.02, 0.02, 0.02, 0.0);
			// Barnacle crusts stuck at hash-fixed spots on the torso.
			long seed = BehaviorSupport.mix(i * 53L);
			double crust = BehaviorSupport.hash01(seed) * Math.PI * 2.0;
			BehaviorSupport.sendContained(level, barnacleDust, shape, center, radius,
					x + Math.cos(crust) * 0.08 * h, y + 0.5 * h, z + Math.sin(crust) * 0.08 * h, 1, 0.0, 0.0, 0.0, 0.0);
			BehaviorSupport.sendContained(level, barnacleDust, shape, center, radius,
					x + Math.cos(crust + 2.1) * 0.08 * h, y + 0.65 * h, z + Math.sin(crust + 2.1) * 0.08 * h,
					1, 0.0, 0.0, 0.0, 0.0);
			BehaviorSupport.sendContained(level, ParticleTypes.SPLASH, shape, center, radius,
					x, y + 0.05, z, variant == 1 ? 2 : 1, 0.2, 0.05, 0.2, 0.05);
			BehaviorSupport.sendContained(level, ParticleTypes.DOLPHIN, shape, center, radius,
					x + hx * 0.5, y + 0.35 * h + 0.15 * h * Math.sin(gameTime * 0.1 + i), z + hz * 0.5,
					1, 0.1, 0.1, 0.1, 0.01);
			// The nautilus fleck swims into the chest from behind (count=0 fly-toward form).
			BehaviorSupport.sendContained(level, ParticleTypes.NAUTILUS, shape, center, radius,
					x + hx * 0.1 * h, y + 0.55 * h, z + hz * 0.1 * h, 0, -hx * 0.8, 0.4, -hz * 0.8, 1.0);
			if (variant == 2) {
				BehaviorSupport.sendContained(level, ParticleTypes.ELECTRIC_SPARK, shape, center, radius,
						x - hz * 0.1 * h, y + 0.55 * h, z + hx * 0.1 * h, 1, 0.03, 0.08, 0.03, 0.02);
			} else if (variant == 3) {
				BehaviorSupport.sendContained(level, ParticleTypes.GLOW_SQUID_INK, shape, center, radius,
						x - hx * 0.4, y + 0.3 * h, z - hz * 0.4, 1, 0.1, 0.1, 0.1, 0.005);
			} else if (variant == 5) {
				BehaviorSupport.sendContained(level, ParticleTypes.SQUID_INK, shape, center, radius,
						x + hx * 0.2 * h, y + 0.6 * h, z + hz * 0.2 * h, 1, 0.05, 0.05, 0.05, 0.005);
			}
		}

		if (variant == 6) {
			// The crown: five flecks converge on a point above the procession.
			double crownY = center.y + Math.min(radius * 0.5, 2.5);
			for (int k = 0; k < 5; k++) {
				double angle = gameTime * 0.03 + Math.PI * 2.0 * k / 5.0;
				BehaviorSupport.sendContained(level, ParticleTypes.NAUTILUS, shape, center, radius,
						center.x, crownY, center.z, 0, Math.cos(angle) * 1.2, 0.3, Math.sin(angle) * 1.2, 1.0);
			}
		} else if (variant == 5 && gameTime % 80L == 0L) {
			level.playSound(null, center.x, center.y + 0.5 * h, center.z,
					SoundEvents.DROWNED_AMBIENT, SoundSource.AMBIENT, 0.25F, 0.9F);
		}
	}
}

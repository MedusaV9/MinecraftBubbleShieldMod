package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Comets on inclined orbits: bright heads race around the bubble trailing a
 * fading dust tail laid out behind them along the orbital path. Orbits are
 * true tilted circles (constant distance from the center) mirrored into the
 * upper hemisphere so they stay dome-safe and inside the wall.
 *
 * <ul>
 * <li>v0: two comets, firework heads, palette-dust tails</li>
 * <li>v1: three faster comets with shorter tails</li>
 * <li>v2: one grand slow comet with a long two-tone tail</li>
 * <li>v3: two end-rod comets on steeply inclined orbits</li>
 * <li>v4: two comets whose tails sparkle (enchanted-hit mixed in)</li>
 * <li>v5: three slow comets skimming the floor plane</li>
 * <li>v6: two flame comets leaving smoke tails</li>
 * </ul>
 */
public final class CometTails implements InsideEffectBehavior {
	public static final String ID = "comet_tails";
	private static final int MAX_TAIL = 48;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int comets = variant == 1 || variant == 5 ? 3 : variant == 2 ? 1 : 2;
		double speed = switch (variant) {
			case 1 -> 0.5;
			case 2 -> 0.08;
			case 5 -> 0.12;
			default -> 0.25;
		};
		int tailSteps = ctx.scaleCount(switch (variant) {
			case 1 -> 8;
			case 2 -> 24;
			default -> 12;
		}, MAX_TAIL / comets);
		double orbitRadius = radius * 0.7 * Mth.clamp(def.behaviorStrength(), 0.8F, 1.2F);
		// Orbit inclination (sine of the tilt): v5 skims the floor plane, v3 climbs steeply.
		double sinTilt = switch (variant) {
			case 3 -> 0.85;
			case 5 -> 0.05;
			default -> 0.35;
		};
		double cosTilt = Math.sqrt(1.0 - sinTilt * sinTilt);
		for (int comet = 0; comet < comets; comet++) {
			double yaw = Math.PI * 2.0 * comet / comets;
			double head = gameTime / 10.0 * speed + yaw * 1.7;
			ParticleOptions headParticle = switch (variant) {
				case 3 -> ParticleTypes.END_ROD;
				case 6 -> ParticleTypes.FLAME;
				default -> ParticleTypes.FIREWORK;
			};
			ParticleOptions tailParticle = switch (variant) {
				case 2 -> new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 1.0F);
				case 6 -> ParticleTypes.SMOKE;
				default -> new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.0F);
			};
			for (int i = 0; i <= tailSteps; i++) {
				// i=0 is the head; the tail trails backwards along the orbit.
				Vec3 p = orbitPoint(center, orbitRadius, head - i * 0.07, yaw, sinTilt, cosTilt);
				if (i == 0) {
					BehaviorSupport.sendContained(level, headParticle, shape, center, radius, p.x, p.y, p.z, 2, 0.05, 0.05, 0.05, 0.01);
				} else {
					BehaviorSupport.sendContained(level, tailParticle, shape, center, radius, p.x, p.y, p.z, 1, 0.03, 0.03, 0.03, 0.0);
					if (variant == 4 && i % 3 == 0) {
						BehaviorSupport.sendContained(level, ParticleTypes.ENCHANTED_HIT, shape, center, radius, p.x, p.y, p.z, 1, 0.05, 0.05, 0.05, 0.0);
					}
				}
			}

			if (variant == 2) {
				// The grand comet gets a second, brighter inner tail strand.
				ParticleOptions inner = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.3F);
				for (int i = 1; i <= tailSteps / 2; i++) {
					Vec3 p = orbitPoint(center, orbitRadius, head - i * 0.05, yaw, sinTilt, cosTilt);
					BehaviorSupport.sendContained(level, inner, shape, center, radius, p.x, p.y, p.z, 1, 0.02, 0.02, 0.02, 0.0);
				}
			}
		}
	}

	/**
	 * A point on a tilted circular orbit of the given radius around {@code center},
	 * yawed per comet, with the vertical component mirrored into the upper
	 * hemisphere ({@code |y|} keeps the distance to the center unchanged).
	 */
	private static Vec3 orbitPoint(Vec3 center, double orbitRadius, double angle, double yaw, double sinTilt, double cosTilt) {
		double ca = Math.cos(angle);
		double sa = Math.sin(angle);
		double localY = sa * sinTilt;
		double localZ = sa * cosTilt;
		double x = ca * Math.cos(yaw) - localZ * Math.sin(yaw);
		double z = ca * Math.sin(yaw) + localZ * Math.cos(yaw);
		return new Vec3(center.x + x * orbitRadius, center.y + Math.abs(localY) * orbitRadius, center.z + z * orbitRadius);
	}
}

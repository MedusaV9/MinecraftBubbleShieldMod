package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.TrailParticleOption;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Will-o'-the-wisp orbs on slow elliptical orbits: each orb is a soul-fire
 * core wrapped in a smoke wisp and a palette dust halo, dragging a TRAIL
 * comet-tail from its previous pulse position (the trail particle lerps from
 * the packet position to the option's target over its duration).
 *
 * <ul>
 * <li>v0: three steady orbs</li>
 * <li>v1: seven flickering orbs</li>
 * <li>v2: witch-lights (a witch-magic halo)</li>
 * <li>v3: a chasing pair orbiting each other while orbiting the center</li>
 * <li>v4: sinking orbs that drift to the floor and pop</li>
 * <li>v5: a candle field (static hash grid of small flames)</li>
 * <li>v6: one eclipse orb with a squid-ink corona</li>
 * </ul>
 */
public final class WraithOrbs implements InsideEffectBehavior {
	public static final String ID = "wraith_orbs";
	/** Worst case v1: 7 orbs x (core 1 + wisp 1 + halo 2 + trail 1) = 35 particles/pulse. */
	private static final int MAX_ORBS = 9;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int dustRgb = ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF;
		ParticleOptions halo = new DustParticleOptions(dustRgb, variant == 6 ? 1.2F : 0.8F);
		if (variant == 5) {
			emitCandleField(level, shape, center, radius, ctx, gameTime, halo);
			return;
		}

		int orbs = ctx.scaleCount(switch (variant) {
			case 1 -> 7;
			case 2 -> 4;
			case 3 -> 2;
			case 4 -> 4;
			case 6 -> 1;
			default -> 3;
		}, variant == 6 ? 1 : MAX_ORBS);
		long pulse = gameTime / 10L;
		for (int o = 0; o < orbs; o++) {
			Vec3 pos = orbPoint(center, radius, def, o, orbs, gameTime, variant);
			if (variant == 1 && BehaviorSupport.hash01(BehaviorSupport.mix(pulse * 53L + o)) < 0.3) {
				// This orb's flicker frame: only the smoke wisp survives.
				BehaviorSupport.sendContained(level, ParticleTypes.SMOKE, shape, center, radius,
						pos.x, pos.y, pos.z, 1, 0.08, 0.08, 0.08, 0.01);
				continue;
			}

			BehaviorSupport.sendContained(level, ParticleTypes.SOUL_FIRE_FLAME, shape, center, radius,
					pos.x, pos.y, pos.z, variant == 6 ? 2 : 1, 0.05, 0.05, 0.05, 0.0);
			BehaviorSupport.sendContained(level, ParticleTypes.SMOKE, shape, center, radius,
					pos.x, pos.y + 0.25, pos.z, 1, 0.06, 0.1, 0.06, 0.01);
			BehaviorSupport.sendContained(level, variant == 2 ? ParticleTypes.WITCH : halo, shape, center, radius,
					pos.x, pos.y, pos.z, variant == 6 ? 3 : 2, 0.2, 0.2, 0.2, 0.0);
			if (variant == 4) {
				long cycle = (pulse + o * 2L) % 8L;
				if (cycle == 7L) {
					// The sunk orb pops on the floor before respawning high.
					BehaviorSupport.sendContained(level, ParticleTypes.POOF, shape, center, radius,
							pos.x, pos.y, pos.z, 2, 0.15, 0.1, 0.15, 0.01);
				}
			} else if (variant == 6) {
				// The eclipse corona: an ink ring around the single big orb.
				for (int i = 0; i < 6; i++) {
					double angle = Math.PI * 2.0 * i / 6.0 + gameTime * 0.01;
					BehaviorSupport.sendContained(level, ParticleTypes.SQUID_INK, shape, center, radius,
							pos.x + Math.cos(angle) * 0.6, pos.y + 0.1 * Math.sin(angle * 3.0), pos.z + Math.sin(angle) * 0.6,
							1, 0.02, 0.02, 0.02, 0.0);
				}
			}

			// The comet tail: a trail flying from last pulse's position to the current one.
			Vec3 prev = BehaviorSupport.containPoint(shape, center, radius,
					orbPoint(center, radius, def, o, orbs, gameTime - 10L, variant));
			Vec3 target = BehaviorSupport.containPoint(shape, center, radius, pos);
			BehaviorSupport.sendContained(level, new TrailParticleOption(target, dustRgb, 8), shape, center, radius, prev.x, prev.y, prev.z, 1, 0.02, 0.02, 0.02, 0.0);
		}
	}

	/** v5: a static grid of small candle flames, blinking in hash patterns. */
	private static void emitCandleField(ServerLevel level, ShieldShape shape, Vec3 center, float radius,
			ContextState ctx, long gameTime, ParticleOptions halo) {
		int candles = ctx.scaleCount(MAX_ORBS, MAX_ORBS);
		long pulse = gameTime / 10L;
		for (int c = 0; c < candles; c++) {
			long seed = BehaviorSupport.mix(c * 269L);
			double angle = BehaviorSupport.hash01(seed) * Math.PI * 2.0;
			double dist = Math.sqrt(BehaviorSupport.hash01(seed + 1L)) * radius * 0.7;
			double x = center.x + Math.cos(angle) * dist;
			double z = center.z + Math.sin(angle) * dist;
			if (BehaviorSupport.hash01(BehaviorSupport.mix(pulse * 71L + c)) < 0.2) {
				continue; // this candle's blink-out frame
			}

			BehaviorSupport.sendContained(level, ParticleTypes.SMALL_FLAME, shape, center, radius,
					x, center.y + 0.3, z, 1, 0.02, 0.05, 0.02, 0.0);
			BehaviorSupport.sendContained(level, halo, shape, center, radius,
					x, center.y + 0.45, z, 1, 0.05, 0.05, 0.05, 0.0);
		}
	}

	/** The orb's position on its per-orb ellipse (v3 pairs waltz; v4 sinks over its cycle). */
	private static Vec3 orbPoint(Vec3 center, float radius, EffectDefinition def, int orb, int orbs, long gameTime, int variant) {
		double strength = Mth.clamp(def.behaviorStrength(), 0.8F, 1.3F);
		if (variant == 3) {
			// The chasing pair: a shared barycenter orbits the center; the two orbs
			// waltz around it in opposite phases.
			double baryAngle = gameTime * 0.012;
			double bx = center.x + Math.cos(baryAngle) * radius * 0.5 * strength;
			double bz = center.z + Math.sin(baryAngle) * radius * 0.5 * strength;
			double waltz = gameTime * 0.05 + orb * Math.PI;
			return new Vec3(
					bx + Math.cos(waltz) * radius * 0.15,
					center.y + radius * (0.35 + 0.1 * Math.sin(gameTime * 0.02)),
					bz + Math.sin(waltz) * radius * 0.15);
		}

		long seed = BehaviorSupport.mix(orb * 131L);
		double a = radius * (0.4 + 0.25 * BehaviorSupport.hash01(seed)) * strength;
		double b = a * 0.6;
		double yaw = BehaviorSupport.hash01(seed + 1L) * Math.PI * 2.0;
		double omega = (orb % 2 == 0 ? 1.0 : -1.0) * 0.008;
		double angle = gameTime * omega + BehaviorSupport.hash01(seed + 2L) * Math.PI * 2.0;
		double ex = Math.cos(angle) * a;
		double ez = Math.sin(angle) * b;
		double x = center.x + ex * Math.cos(yaw) - ez * Math.sin(yaw);
		double z = center.z + ex * Math.sin(yaw) + ez * Math.cos(yaw);
		double heightFrac = 0.25 + 0.2 * Math.sin(gameTime * 0.01 + orb);
		if (variant == 4) {
			// Sinking: 0.6r down to 0.1r over an eight-pulse cycle, then back up top.
			long cycle = (gameTime / 10L + orb * 2L) % 8L;
			heightFrac = 0.6 - 0.5 * (cycle + (gameTime % 10L) / 10.0) / 8.0;
		}

		return new Vec3(x, center.y + radius * heightFrac, z);
	}
}

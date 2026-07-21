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
 * A clockwork orrery: mini comets (firework heads, snowflake tails) swing on
 * true elliptical orbits around a central palette-dust sun sitting at the
 * ellipse focus, visibly speeding up through perihelion (a stateless
 * equation-of-center approximation of Kepler's second law). Purely particles,
 * no state: phases derive from gameTime and per-comet constants.
 *
 * <ul>
 * <li>v0: two comets on medium ellipses</li>
 * <li>v1: three swift comets with short tails</li>
 * <li>v2: two stately comets trailing long two-tone tails (secondary inner
 * strand)</li>
 * <li>v3: two comets on steeply inclined orbits climbing over the sun</li>
 * <li>v4: a binary heart -- two dust suns circling the barycenter</li>
 * <li>v5: an icy orrery with a snowflake halo around the sun</li>
 * <li>v6: three glittering comets with enchanted-hit flecks in the tails</li>
 * </ul>
 */
public final class CometOrrery implements InsideEffectBehavior {
	public static final String ID = "comet_orrery";
	/**
	 * Per-pulse budget, worst case v6: 3 comets x (2 firework head + 12
	 * snowflake tail + 1 dust mote + 4 flecks) = 57 + sun 8 + corona 6 = 71
	 * particles (v2: 2 x 15 + 12 inner strand + 14 sun/corona = 56, v5 adds a
	 * 10-flake halo); always &lt;= 128.
	 */
	private static final int MAX_TAIL = 12;
	private static final int MAX_SUN = 8;
	private static final int MAX_CORONA = 6;
	private static final int MAX_HALO = 10;
	/** Orbit eccentricity flavor: focus offset fraction of the semi-major axis. */
	private static final double ECCENTRICITY = 0.7;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int comets = variant == 1 || variant == 6 ? 3 : 2;
		double speed = switch (variant) {
			case 1 -> 0.35;
			case 2 -> 0.09;
			default -> 0.18;
		};
		int tailSteps = ctx.scaleCount(switch (variant) {
			case 1 -> 4;
			case 2 -> 10;
			default -> 6;
		}, MAX_TAIL);
		// Ellipse axes: aphelion a*(1+e) = 0.61r from the sun at strength 1
		// (0.73r at the 1.2 clamp), perihelion a*(1-e) = 0.11r.
		double a = radius * 0.36 * Mth.clamp(def.behaviorStrength(), 0.8F, 1.2F);
		double b = a * 0.7;
		double focus = a * ECCENTRICITY;
		double tilt = variant == 3 ? 0.25 : 0.08;
		Vec3 sun = new Vec3(center.x, center.y + radius * 0.35, center.z);
		ParticleOptions sunDust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.4F);
		ParticleOptions coronaDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.9F);
		if (variant == 4) {
			// The binary heart: both suns circle the barycenter on the center axis.
			double swing = gameTime / 10.0 * 0.3;
			double sep = radius * 0.1;
			BehaviorSupport.sendContained(level, sunDust, shape, center, radius,
					sun.x + Math.cos(swing) * sep, sun.y, sun.z + Math.sin(swing) * sep,
					ctx.scaleCount(4, MAX_SUN), radius * 0.04, radius * 0.04, radius * 0.04, 0.0);
			BehaviorSupport.sendContained(level, coronaDust, shape, center, radius,
					sun.x - Math.cos(swing) * sep, sun.y, sun.z - Math.sin(swing) * sep,
					ctx.scaleCount(4, MAX_SUN), radius * 0.04, radius * 0.04, radius * 0.04, 0.0);
		} else {
			// The palette-dust sun, plus its darker second-strand corona.
			BehaviorSupport.sendContained(level, sunDust, shape, center, radius,
					sun.x, sun.y, sun.z, ctx.scaleCount(4, MAX_SUN), radius * 0.05, radius * 0.05, radius * 0.05, 0.0);
			BehaviorSupport.sendContained(level, coronaDust, shape, center, radius,
					sun.x, sun.y, sun.z, ctx.scaleCount(3, MAX_CORONA), radius * 0.1, radius * 0.04, radius * 0.1, 0.0);
		}

		if (variant == 5) {
			BehaviorSupport.sendContained(level, ParticleTypes.SNOWFLAKE, shape, center, radius,
					sun.x, sun.y + radius * 0.06, sun.z, ctx.scaleCount(6, MAX_HALO), radius * 0.12, radius * 0.03, radius * 0.12, 0.0);
		}

		ParticleOptions mote = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 0.6F);
		ParticleOptions innerStrand = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.8F);
		for (int comet = 0; comet < comets; comet++) {
			double yaw = Math.PI * 2.0 * comet / comets + comet * 0.9;
			double mean = gameTime / 10.0 * speed + Math.PI * 2.0 * comet / comets;
			Vec3 head = orbitPoint(sun, radius, a, b, focus, tilt, yaw, mean);
			BehaviorSupport.sendContained(level, ParticleTypes.FIREWORK, shape, center, radius,
					head.x, head.y, head.z, 2, 0.04, 0.04, 0.04, 0.01);
			// The recolor accent: one palette dust mote riding every comet head.
			BehaviorSupport.sendContained(level, mote, shape, center, radius,
					head.x, head.y - 0.1, head.z, 1, 0.05, 0.05, 0.05, 0.0);
			for (int i = 1; i <= tailSteps; i++) {
				Vec3 p = orbitPoint(sun, radius, a, b, focus, tilt, yaw, mean - i * 0.09);
				BehaviorSupport.sendContained(level, ParticleTypes.SNOWFLAKE, shape, center, radius,
						p.x, p.y, p.z, 1, 0.03, 0.03, 0.03, 0.0);
				if (variant == 6 && i % 3 == 0) {
					BehaviorSupport.sendContained(level, ParticleTypes.ENCHANTED_HIT, shape, center, radius,
							p.x, p.y, p.z, 1, 0.05, 0.05, 0.05, 0.0);
				}
			}

			if (variant == 2) {
				// The stately comets carry a brighter inner tail strand.
				for (int i = 1; i <= tailSteps / 2; i++) {
					Vec3 p = orbitPoint(sun, radius, a, b, focus, tilt, yaw, mean - i * 0.06);
					BehaviorSupport.sendContained(level, innerStrand, shape, center, radius,
							p.x, p.y, p.z, 1, 0.02, 0.02, 0.02, 0.0);
				}
			}
		}
	}

	/**
	 * A point on an elliptical orbit whose focus is the sun: the mean anomaly is
	 * corrected by the first-order equation of center ({@code M + 2e sin M}) so
	 * comets visibly sprint through perihelion, the ellipse is yawed per comet
	 * and gently inclined ({@code sin} lift; the lowest dip is sun.y - 0.25r =
	 * center.y + 0.1r, dome-safe). Aphelion reach is at most ~0.73r
	 * horizontally from the center axis, within the ~0.85r anchor envelope.
	 */
	private static Vec3 orbitPoint(Vec3 sun, float radius, double a, double b, double focus, double tilt, double yaw, double mean) {
		double anomaly = mean + 2.0 * ECCENTRICITY * Math.sin(mean);
		double lx = a * Math.cos(anomaly) - focus;
		double lz = b * Math.sin(anomaly);
		double x = lx * Math.cos(yaw) - lz * Math.sin(yaw);
		double z = lx * Math.sin(yaw) + lz * Math.cos(yaw);
		double y = sun.y + Math.sin(anomaly) * tilt * radius;
		return new Vec3(sun.x + x, y, sun.z + z);
	}
}

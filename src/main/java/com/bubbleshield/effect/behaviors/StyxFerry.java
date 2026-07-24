package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * A ferry across the Styx: a dust-outlined gondola gliding floor chords
 * between hash-seeded rim moorings, a soul wake trailing the stern, a
 * soul-fire prow lantern, and a reverse-portal fade as the hull reaches the
 * wall. Moorings derive from the shield center (no state), so every shield
 * runs its own crossings; the hull outline is palette dust, so the owner
 * color override repaints the boat.
 *
 * <ul>
 * <li>v0: the lone ferryman on slow crossings</li>
 * <li>v1: twin ferries on offset crossings</li>
 * <li>v2: a laden barge (long hull, dense outline, slow)</li>
 * <li>v3: a regatta of three skiffs (short hulls, brisk crossings)</li>
 * <li>v4: ghost passengers (soul columns standing amidships)</li>
 * <li>v5: a storm crossing (ash spray whipping the hull, faster)</li>
 * <li>v6: the endless circuit (the gondola rides a perimeter ring)</li>
 * </ul>
 */
public final class StyxFerry implements InsideEffectBehavior {
	public static final String ID = "styx_ferry";
	/** Worst case v4 at full context scale: 3 ferries x (hull 6 + trim 2 + lantern 1 + wake 2 + fade 3 + passengers 4) = 54 particles/pulse. */
	private static final int MAX_FERRIES = 3;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int base = switch (variant) {
			case 1 -> 2;
			case 3 -> 3;
			default -> 1;
		};
		int ferries = ctx.scaleCount(Math.max(1, Math.round(base * def.behaviorStrength())), MAX_FERRIES);
		long voyageTicks = switch (variant) {
			case 2 -> 160L;
			case 3 -> 60L;
			case 5 -> 60L;
			default -> 100L;
		};
		// Per-shield identity: moorings are seeded from the projector position.
		long shieldSeed = (long) Math.floor(center.x) * 341873128712L + (long) Math.floor(center.z) * 132897987541L;
		double half = Math.clamp(radius * 0.14, 0.6, 2.2);
		int hullPoints = variant == 2 ? 8 : variant == 3 ? 4 : 6;
		ParticleOptions hull = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, variant == 2 ? 1.1F : 0.9F);
		ParticleOptions trim = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.7F);
		for (int f = 0; f < ferries; f++) {
			// v1's second ferry crosses half a voyage out of phase.
			long phased = gameTime + f * (voyageTicks / 2L);
			double t = (phased % voyageTicks) / (double) voyageTicks;
			Vec3 pos;
			Vec3 dir;
			if (variant == 6) {
				// The circuit: the gondola rides a 0.7r ring, bow on the tangent.
				double angle = gameTime * 0.015 + Math.PI * 2.0 * f / ferries;
				pos = new Vec3(
						center.x + Math.cos(angle) * radius * 0.7,
						center.y + 0.35,
						center.z + Math.sin(angle) * radius * 0.7);
				dir = new Vec3(-Math.sin(angle), 0.0, Math.cos(angle));
			} else {
				long voyage = phased / voyageTicks;
				Vec3 from = moor(center, radius, shieldSeed, f, voyage);
				Vec3 to = moor(center, radius, shieldSeed, f, voyage + 1L);
				pos = from.lerp(to, t);
				double dx = to.x - from.x;
				double dz = to.z - from.z;
				double len = Math.sqrt(dx * dx + dz * dz);
				dir = len < 1.0e-4 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(dx / len, 0.0, dz / len);
			}

			Vec3 perp = new Vec3(-dir.z, 0.0, dir.x);
			for (int k = 0; k < hullPoints; k++) {
				// The hull line, rising toward prow and stern like a gondola.
				double off = -half + 2.0 * half * k / (hullPoints - 1);
				double rise = 0.18 * (off / half) * (off / half);
				BehaviorSupport.sendContained(level, hull, shape, center, radius,
						pos.x + dir.x * off, pos.y + 0.15 + rise, pos.z + dir.z * off, 1, 0.03, 0.03, 0.03, 0.0);
			}

			// The gunwale trim in the darker strand.
			BehaviorSupport.sendContained(level, trim, shape, center, radius,
					pos.x + perp.x * 0.25, pos.y + 0.3, pos.z + perp.z * 0.25, 1, 0.03, 0.03, 0.03, 0.0);
			BehaviorSupport.sendContained(level, trim, shape, center, radius,
					pos.x - perp.x * 0.25, pos.y + 0.3, pos.z - perp.z * 0.25, 1, 0.03, 0.03, 0.03, 0.0);
			// The prow lantern.
			BehaviorSupport.sendContained(level, ParticleTypes.SOUL_FIRE_FLAME, shape, center, radius,
					pos.x + dir.x * (half + 0.35), pos.y + 0.55, pos.z + dir.z * (half + 0.35), 1, 0.02, 0.03, 0.02, 0.0);
			// The soul wake off the stern.
			BehaviorSupport.sendContained(level, ParticleTypes.SOUL, shape, center, radius,
					pos.x - dir.x * (half + 0.3), pos.y + 0.25, pos.z - dir.z * (half + 0.3), 2, 0.12, 0.15, 0.12, 0.02);
			if (variant == 4) {
				// Two soul-column passengers standing amidships.
				BehaviorSupport.sendContained(level, ParticleTypes.SOUL, shape, center, radius,
						pos.x + dir.x * half * 0.3, pos.y + 0.8, pos.z + dir.z * half * 0.3, 2, 0.05, 0.25, 0.05, 0.01);
				BehaviorSupport.sendContained(level, ParticleTypes.SOUL, shape, center, radius,
						pos.x - dir.x * half * 0.3, pos.y + 0.8, pos.z - dir.z * half * 0.3, 2, 0.05, 0.25, 0.05, 0.01);
			} else if (variant == 5) {
				BehaviorSupport.sendContained(level, ParticleTypes.ASH, shape, center, radius,
						pos.x, pos.y + 0.5, pos.z, 2, 0.5, 0.3, 0.5, 0.0);
			}

			if (variant == 6) {
				// The circuit sheds a portal shimmer behind it every fourth pulse.
				if ((gameTime / 10L) % 4L == 0L) {
					BehaviorSupport.sendContained(level, ParticleTypes.REVERSE_PORTAL, shape, center, radius,
							pos.x - dir.x * (half + 0.6), pos.y + 0.5, pos.z - dir.z * (half + 0.6), 2, 0.15, 0.3, 0.15, 0.02);
				}
			} else if (t < 0.12 || t > 0.88) {
				// The mooring fade: the hull dissolves into the wall haze.
				BehaviorSupport.sendContained(level, ParticleTypes.REVERSE_PORTAL, shape, center, radius,
						pos.x, pos.y + 0.6, pos.z, 3, 0.2, 0.4, 0.2, 0.02);
			}
		}
	}

	/**
	 * The hash-seeded rim mooring for one ferry and voyage: on a 0.78r floor
	 * circle, slightly above the center plane (dome-safe by construction).
	 */
	private static Vec3 moor(Vec3 center, float radius, long shieldSeed, int ferry, long voyage) {
		long seed = BehaviorSupport.mix(shieldSeed + voyage * 733L + ferry * 31L);
		double angle = BehaviorSupport.hash01(seed) * Math.PI * 2.0;
		return new Vec3(
				center.x + Math.cos(angle) * radius * 0.78,
				center.y + 0.35,
				center.z + Math.sin(angle) * radius * 0.78);
	}
}

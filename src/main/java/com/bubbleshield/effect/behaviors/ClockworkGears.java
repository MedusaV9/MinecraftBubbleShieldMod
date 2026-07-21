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
 * Meshed clockwork suspended over the projector: dust-outlined cog wheels turn
 * against each other (neighbours counter-rotate; unequal wheels spin inversely
 * to their radius so the rims stay meshed), WAX_ON glints wander the rims and
 * SCRAPE sparks grind out of every mesh point. Purely stateless -- all phases
 * derive from gameTime and {@link BehaviorSupport#hash01}.
 *
 * <ul>
 * <li>v0: two meshed wheels in a vertical plane</li>
 * <li>v1: a three-wheel train, alternating spin</li>
 * <li>v2: a big driver meshed with a fast pinion (2:1 wheel ratio)</li>
 * <li>v3: escapement ticking -- rotation advances in discrete steps</li>
 * <li>v4: a flat planetary plate -- a central sun wheel with two orbiting satellites</li>
 * <li>v5: one heavy double-rim wheel turning slowly (secondary inner rim strand)</li>
 * <li>v6: skeleton clock -- one wheel driving a swinging dust pendulum</li>
 * </ul>
 */
public final class ClockworkGears implements InsideEffectBehavior {
	public static final String ID = "clockwork_gears";
	/** Worst case v1: 96 teeth + 3 hubs + 3 wax glints + 2 meshes x 6 scrape = 114 particles/pulse. */
	private static final int MAX_TEETH = 96;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int gears = switch (variant) {
			case 1, 4 -> 3;
			case 5, 6 -> 1;
			default -> 2;
		};
		double spin = variant == 3
				// The escapement: rotation advances one cog-step every other pulse.
				? gameTime / 10L / 2L * (Math.PI / 8.0)
				: gameTime / 10.0 * (variant == 5 ? 0.05 : 0.18);
		// baseR <= 0.216r and plateY 0.35r keep the widest 3-wheel train's rim
		// inside ~0.79r (hub |x| <= 0.42r, hypot with 0.35r, +rim) at r=4..100.
		double baseR = radius * 0.18 * Mth.clamp(def.behaviorStrength(), 0.7F, 1.2F);
		double plateY = center.y + radius * (variant == 6 ? 0.55 : 0.35);
		ParticleOptions rimDust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 0.9F);
		ParticleOptions hubDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 1.1F);
		long pulse = gameTime / 10L;
		double[] radii = new double[gears];
		for (int g = 0; g < gears; g++) {
			radii[g] = variant == 2 ? (g == 0 ? baseR * 1.2 : baseR * 0.6) : baseR;
		}

		Vec3[] hubs = new Vec3[gears];
		if (variant == 4) {
			// The planetary plate lies flat; two satellites orbit the sun wheel.
			double orbitAng = gameTime / 10.0 * 0.05;
			double orbitDist = (radii[0] + radii[1]) * 0.97;
			hubs[0] = new Vec3(center.x, plateY, center.z);
			hubs[1] = new Vec3(center.x + Math.cos(orbitAng) * orbitDist, plateY, center.z + Math.sin(orbitAng) * orbitDist);
			hubs[2] = new Vec3(center.x - Math.cos(orbitAng) * orbitDist, plateY, center.z - Math.sin(orbitAng) * orbitDist);
		} else {
			// A meshed train laid out along X in a vertical plane through the center.
			double[] xs = new double[gears];
			for (int g = 1; g < gears; g++) {
				xs[g] = xs[g - 1] + (radii[g - 1] + radii[g]) * 0.97;
			}

			for (int g = 0; g < gears; g++) {
				hubs[g] = new Vec3(center.x + xs[g] - xs[gears - 1] / 2.0, plateY, center.z);
			}
		}

		int budget = MAX_TEETH / gears;
		for (int g = 0; g < gears; g++) {
			double dir = g % 2 == 0 ? 1.0 : -1.0;
			double ang0 = spin * dir * (baseR / radii[g]);
			int teeth = ctx.scaleCount(Mth.clamp((int) (radii[g] * 10.0), 12, budget), budget);
			for (int k = 0; k < teeth; k++) {
				double a = ang0 + Math.PI * 2.0 * k / teeth;
				// Alternating outer/inner points give the rim its cog profile.
				double rr = radii[g] * (k % 2 == 0 ? 1.0 : 0.8);
				Vec3 p = gearPoint(hubs[g], a, rr, variant == 4);
				ParticleOptions dust = variant == 5 && k % 2 != 0 ? hubDust : rimDust;
				BehaviorSupport.sendContained(level, dust, shape, center, radius, p.x, p.y, p.z, 1, 0.02, 0.02, 0.02, 0.0);
			}

			// The hub accent, one shade darker (the second palette strand).
			BehaviorSupport.sendContained(level, hubDust, shape, center, radius,
					hubs[g].x, hubs[g].y, hubs[g].z, 1, 0.05, 0.05, 0.05, 0.0);
			// A polish glint wanders the rim, one hash-picked tooth per pulse.
			double glintA = ang0 + Math.PI * 2.0 * BehaviorSupport.hash01(pulse * 31L + g);
			Vec3 glint = gearPoint(hubs[g], glintA, radii[g], variant == 4);
			BehaviorSupport.sendContained(level, ParticleTypes.WAX_ON, shape, center, radius,
					glint.x, glint.y, glint.z, 1, 0.05, 0.05, 0.05, 0.0);
		}

		for (int m = 1; m < gears; m++) {
			// SCRAPE grinds where the teeth mesh (planetary satellites mesh on the sun).
			int a = variant == 4 ? 0 : m - 1;
			Vec3 mesh = hubs[a].lerp(hubs[m], radii[a] / (radii[a] + radii[m]));
			BehaviorSupport.sendContained(level, ParticleTypes.SCRAPE, shape, center, radius,
					mesh.x, mesh.y, mesh.z, ctx.scaleCount(3, 6), 0.06, 0.06, 0.06, 0.01);
		}

		if (variant == 6) {
			// The skeleton clock's pendulum swings below the single wheel.
			double swing = Math.sin(gameTime / 10.0 * 0.5) * 0.55;
			double rodLen = radius * 0.3;
			Vec3 bob = hubs[0].add(Math.sin(swing) * rodLen, -Math.cos(swing) * rodLen, 0.0);
			for (int k = 1; k <= 4; k++) {
				Vec3 rod = hubs[0].lerp(bob, k / 4.0);
				BehaviorSupport.sendContained(level, rimDust, shape, center, radius, rod.x, rod.y, rod.z, 1, 0.02, 0.02, 0.02, 0.0);
			}

			BehaviorSupport.sendContained(level, ParticleTypes.WAX_ON, shape, center, radius,
					bob.x, bob.y, bob.z, 1, 0.04, 0.04, 0.04, 0.0);
			// The escapement pallet clicks against the wheel's crown.
			BehaviorSupport.sendContained(level, ParticleTypes.SCRAPE, shape, center, radius,
					hubs[0].x, hubs[0].y + radii[0], hubs[0].z, ctx.scaleCount(2, 4), 0.04, 0.04, 0.04, 0.01);
		}
	}

	/** A rim point: in the vertical X-Y plane, or flat in X-Z for the planetary plate. */
	private static Vec3 gearPoint(Vec3 hub, double angle, double dist, boolean horizontal) {
		return horizontal
				? new Vec3(hub.x + Math.cos(angle) * dist, hub.y, hub.z + Math.sin(angle) * dist)
				: new Vec3(hub.x + Math.cos(angle) * dist, hub.y + Math.sin(angle) * dist, hub.z);
	}
}

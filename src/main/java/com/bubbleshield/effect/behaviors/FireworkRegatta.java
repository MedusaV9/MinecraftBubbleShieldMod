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
 * A firework regatta: hash-seeded launch stations take turns firing mini-bursts
 * (a FIREWORK spark core wrapped in a palette-dust star pattern drawn on a
 * vertical plane facing the projector). Idle stations keep a single dust pip
 * glowing so the fleet stays visible between shots; stations re-anchor every
 * 600 ticks. Purely particles -- no entities, no state, no cleanup.
 *
 * <ul>
 * <li>v0: ring salutes -- each shot blooms into a dust ring, stations rotating</li>
 * <li>v1: heart shells -- the classic cardioid outline in palette dust</li>
 * <li>v2: five-point star shells -- a starburst outline walked edge by edge</li>
 * <li>v3: twin volley -- two stations fire per pulse with tighter rings</li>
 * <li>v4: grand finale cadence -- a lone comet rises between shots, then every
 * 8th pulse the whole line fires smaller rings at once</li>
 * <li>v5: two-tone double ring -- an outer primary ring around an inner ring in
 * the darker palette tone</li>
 * <li>v6: willow cascade -- a ring burst trailing dust streamers that drip
 * below the shell</li>
 * </ul>
 */
public final class FireworkRegatta implements InsideEffectBehavior {
	public static final String ID = "firework_regatta";
	/** Worst case v4 finale: 6 stations x (5 FIREWORK core + 12 ring dust) = 102 particles/pulse. */
	private static final int MAX_STATIONS = 6;
	/** Stations re-roll their anchors every 600 ticks (one regatta "heat"). */
	private static final long HEAT_TICKS = 600L;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int stations = Mth.clamp((int) (radius * 0.25F * def.behaviorStrength()), 3, MAX_STATIONS);
		long pulse = gameTime / 10L;
		long heat = gameTime / HEAT_TICKS;
		ParticleOptions primaryDust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.0F);
		ParticleOptions secondaryDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.8F);
		if (variant == 4 && pulse % 8L != 0L) {
			// Between finale volleys a lone comet climbs from a rotating station.
			long seed = BehaviorSupport.mix(heat * 89L + pulse % stations);
			double angle = BehaviorSupport.hash01(seed) * Math.PI * 2.0;
			double dist = radius * (0.3 + 0.2 * BehaviorSupport.hash01(seed + 1L));
			double climb = (pulse % 8L) / 8.0;
			double x = center.x + Math.cos(angle) * dist;
			double z = center.z + Math.sin(angle) * dist;
			double y = center.y + radius * (0.1 + 0.4 * climb);
			BehaviorSupport.sendContained(level, ParticleTypes.FIREWORK, shape, center, radius,
					x, y, z, 2, 0.05, 0.1, 0.05, 0.02);
			BehaviorSupport.sendContained(level, primaryDust, shape, center, radius,
					x, y - 0.2, z, 1, 0.04, 0.08, 0.04, 0.0);
			return;
		}

		boolean finale = variant == 4;
		int firing = variant == 3 ? 2 : finale ? stations : 1;
		float volume = Mth.clamp(radius / 14.0F, 0.5F, 3.0F);
		level.playSound(null, center.x, center.y + radius * 0.3, center.z,
				SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.AMBIENT, volume, finale ? 0.6F : 0.8F);
		for (int s = 0; s < stations; s++) {
			long seed = BehaviorSupport.mix(heat * 89L + s);
			double angle = BehaviorSupport.hash01(seed) * Math.PI * 2.0;
			double dist = radius * (0.3 + 0.2 * BehaviorSupport.hash01(seed + 1L));
			double x = center.x + Math.cos(angle) * dist;
			double z = center.z + Math.sin(angle) * dist;
			boolean fires = finale || (int) ((pulse + s) % stations) < firing;
			if (!fires) {
				// The idle pip keeps the anchored boat visible between shots.
				BehaviorSupport.sendContained(level, secondaryDust, shape, center, radius,
						x, center.y + radius * 0.1, z, 1, 0.05, 0.05, 0.05, 0.0);
				continue;
			}

			double y = center.y + radius * (0.35 + 0.15 * BehaviorSupport.hash01(seed + 2L));
			int core = ctx.scaleCount(finale ? 3 : 5, finale ? 5 : 8);
			BehaviorSupport.sendContained(level, ParticleTypes.FIREWORK, shape, center, radius,
					x, y, z, core, 0.2, 0.2, 0.2, 0.08);
			double patternR = Mth.clamp(radius * (variant == 3 || finale ? 0.1F : 0.16F), 0.8F, 3.5F);
			// The pattern plane faces the projector: u is the horizontal tangent.
			double ux = -Math.sin(angle);
			double uz = Math.cos(angle);
			int points = ctx.scaleCount(finale ? 8 : 12, finale ? 12 : 18);
			for (int i = 0; i < points; i++) {
				double px;
				double py;
				double frac = (double) i / points;
				if (variant == 1) {
					// The cardioid: x = 16 sin^3 t, y = 13cos t - 5cos2t - 2cos3t - cos4t.
					double t = frac * Math.PI * 2.0;
					px = Math.pow(Math.sin(t), 3.0);
					py = (13.0 * Math.cos(t) - 5.0 * Math.cos(2.0 * t) - 2.0 * Math.cos(3.0 * t) - Math.cos(4.0 * t)) / 16.0;
				} else if (variant == 2) {
					// Walk the 10-vertex star outline (alternating outer/inner tips).
					double walk = frac * 10.0;
					int edge = (int) walk;
					double along = walk - edge;
					double a0 = Math.PI * edge / 5.0 - Math.PI * 0.5;
					double a1 = Math.PI * (edge + 1) / 5.0 - Math.PI * 0.5;
					double r0 = edge % 2 == 0 ? 1.0 : 0.45;
					double r1 = edge % 2 == 0 ? 0.45 : 1.0;
					double x0 = Math.cos(a0) * r0;
					double y0 = Math.sin(a0) * r0;
					px = x0 + (Math.cos(a1) * r1 - x0) * along;
					py = y0 + (Math.sin(a1) * r1 - y0) * along;
				} else {
					double t = frac * Math.PI * 2.0;
					double ringScale = variant == 5 && i % 2 != 0 ? 0.55 : 1.0;
					px = Math.cos(t) * ringScale;
					py = Math.sin(t) * ringScale;
				}

				ParticleOptions dust = variant == 5 && i % 2 != 0 ? secondaryDust : primaryDust;
				BehaviorSupport.sendContained(level, dust, shape, center, radius,
						x + ux * px * patternR, y + py * patternR, z + uz * px * patternR,
						1, 0.02, 0.02, 0.02, 0.0);
			}

			if (variant == 6) {
				// The willow: streamers dripping below the shell, fading darker.
				int streamers = ctx.scaleCount(4, 6);
				for (int w = 0; w < streamers; w++) {
					double wa = Math.PI * 2.0 * w / streamers;
					for (int d = 1; d <= 3; d++) {
						BehaviorSupport.sendContained(level, d == 1 ? primaryDust : secondaryDust, shape, center, radius,
								x + Math.cos(wa) * patternR * (0.5 + 0.15 * d),
								y - patternR * 0.35 * d,
								z + Math.sin(wa) * patternR * (0.5 + 0.15 * d),
								1, 0.03, 0.05, 0.03, 0.0);
					}
				}
			}
		}
	}
}

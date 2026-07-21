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
 * A reaper stationed on the bubble floor: a hooded figure built from smoke
 * shoulders, a palette dust cowl and a glow gaze, sweeping an end-rod scythe
 * blade through a half-moon arc in front of it, the blade leaving a brief
 * glow after-image along the arc it just cleared. The station is hash-seeded
 * from the shield center (no state); the cowl and blade tip carry both
 * palette strands, so the owner color override redrapes the figure.
 *
 * <ul>
 * <li>v0: the patient reaper (slow sweeps, long pauses at each arc end)</li>
 * <li>v1: twin reapers stationed across the bubble, sweeping in antiphase</li>
 * <li>v2: the harvester (fast sweeps, crit chaff flying off the blade)</li>
 * <li>v3: the soul-taker (soul wisps rising from the swept arc)</li>
 * <li>v4: the tall wraith (a longer blade on a higher figure)</li>
 * <li>v5: the turning watch (the whole station rotates between sweeps)</li>
 * <li>v6: the mirrored dance (one reaper, two opposed blades sweeping at once)</li>
 * </ul>
 */
public final class ReaperScythe implements InsideEffectBehavior {
	public static final String ID = "reaper_scythe";
	/** Worst case v6 at full context scale: 2 reapers x (figure 6 + 2 blades x (blade 5 + tip 1 + ghost 3)) = 48 particles/pulse. */
	private static final int MAX_REAPERS = 2;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int reapers = ctx.scaleCount(Math.max(1, Math.round((variant == 1 ? 2 : 1) * def.behaviorStrength())), MAX_REAPERS);
		long sweepTicks = variant == 0 ? 120L : variant == 2 ? 40L : 80L;
		double bladeLen = Math.clamp(radius * (variant == 4 ? 0.32 : 0.24), 0.9, variant == 4 ? 5.0 : 3.5);
		double figureHeight = variant == 4 ? 2.2 : 1.7;
		// Per-shield identity: stations are seeded from the projector position.
		long shieldSeed = (long) Math.floor(center.x) * 341873128712L + (long) Math.floor(center.z) * 132897987541L;
		ParticleOptions cowl = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.0F);
		ParticleOptions tip = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.7F);
		for (int r = 0; r < reapers; r++) {
			long station = variant == 5 ? gameTime / (sweepTicks * 2L) : 0L;
			long seed = BehaviorSupport.mix(shieldSeed + station * 269L + r * 97L);
			double stationAngle = BehaviorSupport.hash01(seed) * Math.PI * 2.0 + (variant == 1 ? Math.PI * r : 0.0);
			double stationDist = radius * (0.35 + 0.25 * BehaviorSupport.hash01(seed + 1L));
			Vec3 pos = new Vec3(
					center.x + Math.cos(stationAngle) * stationDist,
					center.y + 0.2,
					center.z + Math.sin(stationAngle) * stationDist);
			// The figure faces the bubble center; the half-moon opens that way.
			double facing = Math.atan2(center.z - pos.z, center.x - pos.x);
			// The sweep: t runs 0..1 across the half-moon, then reverses. v0's
			// eased curve lingers at the arc ends; v1's second reaper antiphases.
			long phased = gameTime + (variant == 1 ? r * sweepTicks : 0L);
			double raw = (phased % (sweepTicks * 2L)) / (double) sweepTicks;
			double sweepDir = raw <= 1.0 ? 1.0 : -1.0;
			double t = raw > 1.0 ? 2.0 - raw : raw;
			if (variant == 0) {
				t = 0.5 - 0.5 * Math.cos(t * Math.PI);
			}

			double blade = facing - Math.PI * 0.5 + Math.PI * t;
			// The hooded figure: smoke shoulders, dust cowl, a glow gaze.
			BehaviorSupport.sendContained(level, ParticleTypes.SMOKE, shape, center, radius,
					pos.x, pos.y + figureHeight * 0.5, pos.z, 3, 0.15, figureHeight * 0.3, 0.15, 0.01);
			BehaviorSupport.sendContained(level, cowl, shape, center, radius,
					pos.x, pos.y + figureHeight, pos.z, 2, 0.12, 0.1, 0.12, 0.0);
			BehaviorSupport.sendContained(level, ParticleTypes.GLOW, shape, center, radius,
					pos.x + Math.cos(facing) * 0.15, pos.y + figureHeight - 0.05, pos.z + Math.sin(facing) * 0.15,
					1, 0.03, 0.03, 0.03, 0.0);
			emitBlade(level, shape, center, radius, variant, pos, blade, bladeLen, figureHeight, sweepDir, tip);
			if (variant == 6) {
				// The mirrored second blade sweeps the opposite half-moon.
				emitBlade(level, shape, center, radius, variant, pos, blade + Math.PI, bladeLen, figureHeight, sweepDir, tip);
			}
		}
	}

	private static void emitBlade(ServerLevel level, ShieldShape shape, Vec3 center, float radius,
			int variant, Vec3 pos, double blade, double bladeLen, double figureHeight, double sweepDir, ParticleOptions tip) {
		double edgeY = pos.y + figureHeight * 0.55;
		int bladePoints = 5;
		for (int k = 1; k <= bladePoints; k++) {
			double reach = bladeLen * k / bladePoints;
			BehaviorSupport.sendContained(level, ParticleTypes.END_ROD, shape, center, radius,
					pos.x + Math.cos(blade) * reach, edgeY, pos.z + Math.sin(blade) * reach, 1, 0.02, 0.02, 0.02, 0.0);
		}

		// The darker palette gleam on the blade tip.
		BehaviorSupport.sendContained(level, tip, shape, center, radius,
				pos.x + Math.cos(blade) * bladeLen, edgeY + 0.1, pos.z + Math.sin(blade) * bladeLen, 1, 0.04, 0.04, 0.04, 0.0);
		// The after-image: glow hanging on the arc the blade just cleared.
		for (int g = 1; g <= 3; g++) {
			double ghost = blade - sweepDir * 0.22 * g;
			double reach = bladeLen * (1.0 - 0.12 * g);
			BehaviorSupport.sendContained(level, ParticleTypes.GLOW, shape, center, radius,
					pos.x + Math.cos(ghost) * reach, edgeY, pos.z + Math.sin(ghost) * reach, 1, 0.03, 0.03, 0.03, 0.0);
		}

		if (variant == 2) {
			// The harvest chaff flung off the mid-blade.
			BehaviorSupport.sendContained(level, ParticleTypes.CRIT, shape, center, radius,
					pos.x + Math.cos(blade) * bladeLen * 0.6, edgeY + 0.1, pos.z + Math.sin(blade) * bladeLen * 0.6,
					2, 0.2, 0.1, 0.2, Mth.clamp((float) bladeLen * 0.03F, 0.02F, 0.1F));
		} else if (variant == 3) {
			// The taken souls rising from the freshly swept arc.
			double ghost = blade - sweepDir * 0.5;
			BehaviorSupport.sendContained(level, ParticleTypes.SOUL, shape, center, radius,
					pos.x + Math.cos(ghost) * bladeLen * 0.7, edgeY - 0.3, pos.z + Math.sin(ghost) * bladeLen * 0.7,
					2, 0.1, 0.25, 0.1, 0.02);
		}
	}
}

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
 * Three arcane turbine columns around the projector: each column stacks
 * spinning dust vane tiers (blades drawn as radial dust arms, neighbouring
 * columns counter-rotate) while an ELECTRIC_SPARK discharge ring climbs the
 * column and crowns off at the top. Stateless -- vane phases and ring heights
 * derive from gameTime; the crown jitter uses {@link BehaviorSupport#hash01}.
 *
 * <ul>
 * <li>v0: three columns, three vane tiers, one climbing ring each</li>
 * <li>v1: overdrive -- vanes spin fast, rings climb twice as quick</li>
 * <li>v2: heavy industry -- four-blade vanes in the darker secondary strand</li>
 * <li>v3: staggered rings -- the three columns' discharges climb out of phase</li>
 * <li>v4: tall stacks -- four tiers, slimmer vanes</li>
 * <li>v5: idle spin-down -- slow vanes, rings replaced by a soft glow crown</li>
 * <li>v6: resonant pair -- two wider columns trading a spark arc at the top</li>
 * </ul>
 */
public final class ArcaneTurbines implements InsideEffectBehavior {
	public static final String ID = "arcane_turbines";
	/** Worst case v4: 3 columns x (4 tiers x 3 blades x 2 dust + shaft 4 + ring 6 + crown 2) = 120 particles/pulse. */
	private static final int MAX_VANE_POINTS = 72;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int columns = variant == 6 ? 2 : 3;
		int tiers = variant == 4 ? 4 : 3;
		int blades = variant == 2 ? 4 : 3;
		double strength = Mth.clamp(def.behaviorStrength(), 0.7F, 1.3F);
		double stand = radius * (variant == 6 ? 0.42 : 0.5);
		double columnBase = center.y + radius * 0.08;
		double columnTop = center.y + radius * (variant == 4 ? 0.6 : 0.5);
		double vaneReach = radius * (variant == 4 ? 0.08 : 0.12) * strength;
		double spin = gameTime / 10.0 * (variant == 1 ? 0.5 : variant == 5 ? 0.05 : 0.22);
		long pulse = gameTime / 10L;
		ParticleOptions vaneDust = new DustParticleOptions((variant == 2
				? ctx.secondaryColor(def.argbSecondary())
				: ctx.pickColor(def.argbPrimary(), def.argbSecondary())) & 0xFFFFFF, 0.9F);
		ParticleOptions shaftDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 1.0F);
		int budget = MAX_VANE_POINTS / (columns * tiers);
		int perBlade = Math.max(1, budget / blades);
		// The ring climbs base->top over 8 pulses (4 in overdrive), per column.
		long climbPeriod = variant == 1 ? 4L : 8L;
		for (int c = 0; c < columns; c++) {
			double columnAngle = Math.PI * 2.0 * c / columns;
			double cx = center.x + Math.cos(columnAngle) * stand;
			double cz = center.z + Math.sin(columnAngle) * stand;
			double dir = c % 2 == 0 ? 1.0 : -1.0;
			for (int tier = 0; tier < tiers; tier++) {
				double ty = columnBase + (columnTop - columnBase) * (tier + 0.5) / tiers;
				// Alternate tiers offset half a blade so the stack reads as geared.
				double tierPhase = spin * dir + (tier % 2 == 0 ? 0.0 : Math.PI / blades);
				for (int b = 0; b < blades; b++) {
					double a = tierPhase + Math.PI * 2.0 * b / blades;
					for (int k = 1; k <= perBlade; k++) {
						double d = vaneReach * k / perBlade;
						BehaviorSupport.sendContained(level, vaneDust, shape, center, radius,
								cx + Math.cos(a) * d, ty, cz + Math.sin(a) * d, 1, 0.02, 0.02, 0.02, 0.0);
					}
				}
			}

			// The shaft: darker palette points running up the column axis.
			int shaftPoints = ctx.scaleCount(4, 6);
			for (int k = 0; k < shaftPoints; k++) {
				double sy = columnBase + (columnTop - columnBase) * (k + 0.5) / shaftPoints;
				BehaviorSupport.sendContained(level, shaftDust, shape, center, radius,
						cx, sy, cz, 1, 0.02, 0.02, 0.02, 0.0);
			}

			// The discharge ring climbing the column (v3 staggers the columns).
			double climb = ((pulse + (variant == 3 ? c * climbPeriod / 3L : 0L)) % climbPeriod) / (double) climbPeriod;
			double ringY = columnBase + (columnTop - columnBase) * climb;
			if (variant != 5) {
				BehaviorSupport.sendContained(level, ParticleTypes.ELECTRIC_SPARK, shape, center, radius,
						cx, ringY, cz, ctx.scaleCount(4, 6), vaneReach * 0.5, 0.02, vaneReach * 0.5, 0.0);
			}

			if (climb >= 1.0 - 1.0 / climbPeriod) {
				// The crown-off at the top of the climb.
				long seed = BehaviorSupport.mix(pulse * 89L + c);
				double jx = (BehaviorSupport.hash01(seed) - 0.5) * 0.2;
				double jz = (BehaviorSupport.hash01(seed + 1L) - 0.5) * 0.2;
				BehaviorSupport.sendContained(level, variant == 5 ? ParticleTypes.GLOW : ParticleTypes.ELECTRIC_SPARK,
						shape, center, radius, cx + jx, columnTop + 0.2, cz + jz, ctx.scaleCount(2, 4), 0.08, 0.06, 0.08, 0.02);
			}
		}

		if (variant == 6 && pulse % 4L == 0L) {
			// The resonant pair trade an arc between their crowns every 4th pulse.
			double topY = columnTop + 0.1;
			Vec3 a = new Vec3(center.x + stand, topY, center.z);
			Vec3 b = new Vec3(center.x - stand, topY, center.z);
			for (int k = 0; k <= 4; k++) {
				Vec3 p = a.lerp(b, k / 4.0);
				// A shallow arc sagging toward the middle keeps it clear of the crowns.
				double sag = Math.sin(Math.PI * k / 4.0) * radius * 0.05;
				BehaviorSupport.sendContained(level, ParticleTypes.ELECTRIC_SPARK, shape, center, radius,
						p.x, p.y + sag, p.z, 1, 0.03, 0.03, 0.03, 0.01);
			}
		}
	}
}

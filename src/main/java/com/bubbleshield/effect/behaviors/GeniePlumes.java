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
 * Dust bottle outlines parked on the floor that uncork cosy campfire-smoke
 * plumes: each plume rises and twists into a glow-eyed smoke torso, holds its
 * pose, then gets sucked back down an inward spiral into the bottle neck. The
 * whole cycle is phase-derived per bottle -- no fields, no entities, no
 * cleanup; the bottles are palette dust so the owner recolor re-glazes them.
 *
 * <ul>
 * <li>v0: three bottles wishing in unison on a slow cycle</li>
 * <li>v1: staggered wishes (each bottle a third of a cycle apart)</li>
 * <li>v2: twin-plume bottles (two intertwined smoke strands per plume)</li>
 * <li>v3: mischief (enchanted-hit flecks the moment a torso forms)</li>
 * <li>v4: heavy smoke (brooding large-smoke plumes instead of cosy smoke)</li>
 * <li>v5: gilded bottles (secondary-dust necks, wax-off glints on uncork)</li>
 * <li>v6: one grand genie (a single big bottle, taller plume, four eye glints)</li>
 * </ul>
 */
public final class GeniePlumes implements InsideEffectBehavior {
	public static final String ID = "genie_plumes";
	/** Worst case v2 in the torso phase: 3 bottles x (8 outline dust + 16 twin-plume smoke + 3 torso smoke + 2 eyes) = 87 particles/pulse. */
	private static final int MAX_PLUME_PUFFS = 8;
	/** One full wish cycle: corked, uncork, rise, torso, suck-back. */
	private static final long CYCLE_TICKS = 160L;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int bottles = variant == 6 ? 1 : 3;
		double ringDist = variant == 6 ? 0.0 : radius * 0.45;
		double bottleH = Mth.clamp(radius * (variant == 6 ? 0.12F : 0.08F), 0.6F, 2.0F);
		double plumeTop = radius * (variant == 6 ? 0.6 : 0.45);
		ParticleOptions glassDust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.0F);
		ParticleOptions neckDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.8F);
		ParticleOptions plume = variant == 4 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.CAMPFIRE_COSY_SMOKE;
		for (int b = 0; b < bottles; b++) {
			double angle = Math.PI * 2.0 * b / bottles + BehaviorSupport.hash01(BehaviorSupport.mix(b * 71L)) * 0.5;
			Vec3 base = new Vec3(center.x + Math.cos(angle) * ringDist, center.y + 0.1, center.z + Math.sin(angle) * ringDist);
			// v1 staggers the wishes so one bottle is always mid-performance.
			long phaseShift = variant == 1 ? b * (CYCLE_TICKS / 3L) : 0L;
			double phase = ((gameTime + phaseShift) % CYCLE_TICKS) / (double) CYCLE_TICKS;
			emitBottle(level, shape, center, radius, variant, base, bottleH, glassDust, neckDust);
			if (phase < 0.15) {
				continue; // Corked: just the bottle waiting on the floor.
			}

			if (phase < 0.2 && variant == 5) {
				// The uncork glint on the gilded lip.
				BehaviorSupport.sendContained(level, ParticleTypes.WAX_OFF, shape, center, radius,
						base.x, base.y + bottleH + 0.2, base.z, 1, 0.1, 0.05, 0.1, 0.0);
			}

			// The plume climbs while rising (0.15..0.6), stands tall during the
			// torso pose (0.6..0.8), and drains back down while sucked (0.8..1).
			double reach = phase < 0.6 ? (phase - 0.15) / 0.45 : phase < 0.8 ? 1.0 : (1.0 - phase) / 0.2;
			int puffs = ctx.scaleCount(4, MAX_PLUME_PUFFS);
			boolean sucking = phase >= 0.8;
			for (int k = 0; k < puffs; k++) {
				double t = (k + 0.5) / puffs * reach;
				// Rising: the strand twists upward; sucked: it spirals inward
				// and down toward the neck, tightening as it falls.
				double twist = gameTime / 10.0 * 0.5 + t * (sucking ? 9.0 : 5.0);
				double swirlR = (sucking ? 0.7 * (1.0 - t) : 0.25 + 0.35 * t) * (1.0 + radius * 0.01);
				double y = base.y + bottleH + t * (plumeTop - bottleH);
				emitStrand(level, shape, center, radius, plume, base, twist, swirlR, y);
				if (variant == 2) {
					emitStrand(level, shape, center, radius, plume, base, twist + Math.PI, swirlR, y);
				}
			}

			if (phase >= 0.6 && phase < 0.8) {
				// The torso pose: shoulders, chest and glow eyes at the crown.
				double topY = base.y + bottleH + reach * (plumeTop - bottleH);
				BehaviorSupport.sendContained(level, plume, shape, center, radius,
						base.x, topY - 0.4, base.z, 2, 0.35, 0.15, 0.35, 0.005);
				BehaviorSupport.sendContained(level, plume, shape, center, radius,
						base.x, topY - 0.9, base.z, 1, 0.2, 0.15, 0.2, 0.005);
				int eyes = variant == 6 ? 4 : 2;
				for (int e = 0; e < eyes; e++) {
					double eyeA = Math.PI * 2.0 * e / eyes + gameTime / 10.0 * 0.1;
					BehaviorSupport.sendContained(level, ParticleTypes.GLOW, shape, center, radius,
							base.x + Math.cos(eyeA) * 0.15, topY, base.z + Math.sin(eyeA) * 0.15, 1, 0.0, 0.0, 0.0, 0.0);
				}

				if (variant == 3 && phase < 0.65) {
					BehaviorSupport.sendContained(level, ParticleTypes.ENCHANTED_HIT, shape, center, radius,
							base.x, topY - 0.2, base.z, 2, 0.25, 0.25, 0.25, 0.02);
				}
			}
		}
	}

	/** One smoke puff of a plume strand swirling around the bottle axis. */
	private static void emitStrand(ServerLevel level, ShieldShape shape, Vec3 center, float radius,
			ParticleOptions plume, Vec3 base, double twist, double swirlR, double y) {
		BehaviorSupport.sendContained(level, plume, shape, center, radius,
				base.x + Math.cos(twist) * swirlR, y, base.z + Math.sin(twist) * swirlR, 1, 0.03, 0.03, 0.03, 0.003);
	}

	/** The bottle outline: a dust belly ring, one shoulder mote and the neck lip -- every pulse, every variant. */
	private static void emitBottle(ServerLevel level, ShieldShape shape, Vec3 center, float radius, int variant,
			Vec3 base, double bottleH, ParticleOptions glassDust, ParticleOptions neckDust) {
		double bellyR = bottleH * 0.35;
		for (int i = 0; i < 6; i++) {
			double a = Math.PI * 2.0 * i / 6.0;
			BehaviorSupport.sendContained(level, glassDust, shape, center, radius,
					base.x + Math.cos(a) * bellyR, base.y + bottleH * 0.3, base.z + Math.sin(a) * bellyR, 1, 0.0, 0.0, 0.0, 0.0);
		}

		BehaviorSupport.sendContained(level, glassDust, shape, center, radius,
				base.x, base.y + bottleH * 0.7, base.z, 1, 0.05, 0.03, 0.05, 0.0);
		// The gilded variant renders the neck lip in the secondary color.
		BehaviorSupport.sendContained(level, variant == 5 ? neckDust : glassDust, shape, center, radius,
				base.x, base.y + bottleH, base.z, 1, 0.02, 0.02, 0.02, 0.0);
	}
}

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
 * A veil of parallel meteors skimming under the ceiling: shallow crit-trailed
 * end-rod streaks all sliding along one hash-picked shower heading (re-aimed
 * every 200 ticks), each burning out in a poof at the end of its run. A
 * palette dust ember rides every head; stateless -- lanes, phases and headings
 * all derive from {@link BehaviorSupport#hash01}.
 *
 * <ul>
 * <li>v0: five medium streaks on staggered lanes</li>
 * <li>v1: a dense fast shower of eight short streaks</li>
 * <li>v2: three slow bolides with long trails and heavy poofs</li>
 * <li>v3: a crossed double veil (alternate streaks fly the mirrored
 * heading)</li>
 * <li>v4: high skimmers hugging the ceiling on a nearly flat glide</li>
 * <li>v5: a radiant fan -- streaks spread outward from one hash-picked
 * radiant</li>
 * <li>v6: a glitter veil with wax-off glints along every trail</li>
 * </ul>
 */
public final class MeteorShowerVeil implements InsideEffectBehavior {
	public static final String ID = "meteor_shower_veil";
	/**
	 * Per-pulse budget, worst case v2 at the 10-streak cap: 10 x (1 end-rod
	 * head + 1 dust ember + 5 crit + 1 tail-tip dust + 4 poof in the burnout
	 * window) = 120 particles (v6 tops at 10 x (1 + 1 + 3 + 1 + 1 wax-off +
	 * 2 poof) = 90); always &lt;= 128.
	 */
	private static final int MAX_STREAKS = 10;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int streaks = ctx.scaleCount(switch (variant) {
			case 1 -> 8;
			case 2 -> 3;
			default -> 5;
		}, MAX_STREAKS);
		int trailLen = variant == 2 ? 5 : variant == 1 ? 2 : 3;
		long runTicks = switch (variant) {
			case 1 -> 30L;
			case 2 -> 80L;
			default -> 50L;
		};
		// The shower re-aims every 200 ticks; all lanes share the heading.
		long aim = gameTime / 200L;
		double heading = BehaviorSupport.hash01(BehaviorSupport.mix(aim)) * Math.PI * 2.0;
		double slope = variant == 4 ? 0.04 : 0.15;
		double heightFrac = variant == 4 ? 0.55 : 0.45;
		double lateralFrac = variant == 4 ? 0.3 : 0.45;
		ParticleOptions ember = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 0.8F);
		ParticleOptions tailTip = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.6F);
		for (int s = 0; s < streaks; s++) {
			double dirAz = heading;
			if (variant == 3 && s % 2 == 1) {
				// The crossed veil: odd lanes fly the mirrored heading.
				dirAz = heading + Math.PI * 0.6;
			} else if (variant == 5) {
				// The radiant fan: each lane leaves the radiant on its own spoke.
				dirAz = heading + Math.PI * 2.0 * s / streaks;
			}

			long laneSeed = BehaviorSupport.mix(aim * 131L + s * 17L + 3L);
			// Staggered lane phases so the veil never fires in lockstep.
			double t = (gameTime / (double) runTicks + BehaviorSupport.hash01(laneSeed)) % 1.0;
			Vec3 dir = new Vec3(Math.cos(dirAz), -slope, Math.sin(dirAz));
			Vec3 start;
			double span;
			if (variant == 5) {
				start = new Vec3(
						center.x + Math.cos(heading) * radius * 0.2,
						center.y + radius * 0.55,
						center.z + Math.sin(heading) * radius * 0.2);
				span = radius * 0.45;
			} else {
				// The lane: offset perpendicular to the heading, entering half a
				// span upwind; worst anchors stay within ~0.85r of the center.
				double lat = (BehaviorSupport.hash01(laneSeed + 1L) - 0.5) * 2.0 * radius * lateralFrac;
				double lift = radius * (heightFrac + 0.08 * BehaviorSupport.hash01(laneSeed + 2L));
				span = radius * 0.9;
				start = new Vec3(
						center.x - Math.sin(dirAz) * lat - dir.x * span * 0.5,
						center.y + lift,
						center.z + Math.cos(dirAz) * lat - dir.z * span * 0.5);
			}

			// dir's horizontal part is unit-length, so span measures the glide.
			Vec3 head = start.add(dir.x * span * t, dir.y * span * t, dir.z * span * t);
			BehaviorSupport.sendContained(level, ParticleTypes.END_ROD, shape, center, radius,
					head.x, head.y, head.z, 1, 0.03, 0.03, 0.03, 0.01);
			// The recolor accent: one palette dust ember on every meteor head.
			BehaviorSupport.sendContained(level, ember, shape, center, radius,
					head.x, head.y - 0.08, head.z, 1, 0.04, 0.04, 0.04, 0.0);
			double step = radius * 0.05;
			for (int i = 1; i <= trailLen; i++) {
				BehaviorSupport.sendContained(level, ParticleTypes.CRIT, shape, center, radius,
						head.x - dir.x * step * i, head.y + slope * step * i, head.z - dir.z * step * i,
						1, 0.03, 0.03, 0.03, 0.0);
				if (variant == 6 && i == trailLen) {
					BehaviorSupport.sendContained(level, ParticleTypes.WAX_OFF, shape, center, radius,
							head.x - dir.x * step * i, head.y + slope * step * i + 0.1, head.z - dir.z * step * i,
							1, 0.05, 0.05, 0.05, 0.0);
				}
			}

			// The cooling tail tip, drawn in the darker second strand.
			BehaviorSupport.sendContained(level, tailTip, shape, center, radius,
					head.x - dir.x * step * (trailLen + 1), head.y + slope * step * (trailLen + 1), head.z - dir.z * step * (trailLen + 1),
					1, 0.04, 0.04, 0.04, 0.0);
			if (t > 1.0 - 10.0 / runTicks) {
				// The burnout: the last pulse of every run (t advances by at most
				// 10/runTicks per pulse) poofs the meteor out at the end.
				BehaviorSupport.sendContained(level, ParticleTypes.POOF, shape, center, radius,
						head.x, head.y, head.z, variant == 2 ? 4 : 2, 0.1, 0.1, 0.1, 0.02);
			}
		}
	}
}

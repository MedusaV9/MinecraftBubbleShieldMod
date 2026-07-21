package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A heartbeat: every 40 ticks a low thump plays and a dust ring expands from
 * the projector to the shield edge over the following pulses.
 *
 * <ul>
 * <li>v0: single beat</li>
 * <li>v1: double beat with note particles on each thump</li>
 * <li>v2: deep bass beat (pitch 0.6) with larger rings</li>
 * <li>v3: triple beat climbing in pitch across the sweep</li>
 * <li>v4: syncopated off-beat with a color-transition dust ring</li>
 * <li>v5: single beat bursting hearts from the projector</li>
 * <li>v6: subterranean beat (pitch 0.5) driving twin nested rings</li>
 * </ul>
 */
public final class HeartbeatPulse implements InsideEffectBehavior {
	public static final String ID = "heartbeat_pulse";
	private static final int MIN_POINTS = 20;
	private static final int MAX_POINTS = 128;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		long phase = gameTime / 10L % 4L;
		// Volume > 1 extends the audible range (~16 * volume blocks), so the thump
		// carries across large bubbles (radius up to 100).
		float volume = Mth.clamp(radius / 12.0F, 0.6F, 8.0F);
		boolean beat = switch (variant) {
			case 1 -> phase == 0L || phase == 1L;
			case 3 -> phase <= 2L;
			case 4 -> phase == 0L || phase == 2L;
			default -> phase == 0L;
		};
		if (beat) {
			float pitch = switch (variant) {
				case 1 -> phase == 0L ? 0.9F : 1.1F;
				case 2 -> 0.6F;
				case 3 -> 0.8F + 0.2F * phase;
				case 4 -> phase == 0L ? 1.0F : 0.7F;
				case 6 -> 0.5F;
				default -> 0.9F;
			};
			level.playSound(null, center.x, center.y, center.z, SoundEvents.WARDEN_HEARTBEAT, SoundSource.AMBIENT, volume, pitch);
			if (variant == 1) {
				BehaviorSupport.sendContained(level, ParticleTypes.NOTE, shape, center, radius, center.x, center.y + 2.0, center.z, 3, 0.5, 0.5, 0.5, 0.0);
			} else if (variant == 5) {
				BehaviorSupport.sendContained(level, ParticleTypes.HEART, shape, center, radius, center.x, center.y + 1.5, center.z, ctx.scaleCount(6, 12), 0.8, 0.6, 0.8, 0.0);
			}
		}

		if (variant == 4) {
			// Off-beat sweep drawn in gradient dust instead of the flat two-color scheme.
			double ringRadius = Math.min(radius * (0.25 + 0.25 * phase), radius * 0.98);
			DustColorTransitionOptions dust = new DustColorTransitionOptions(
					ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 1.4F);
			int points = ctx.scaleCount(Mth.clamp((int) Math.round(Math.PI * 2.0 * ringRadius / 2.0), MIN_POINTS, MAX_POINTS), MAX_POINTS);
			for (int i = 0; i < points; i++) {
				double angle = Math.PI * 2.0 * i / points;
				// The widest sweep phase sits ON the 0.98r line; the 0.2 lift would
				// nudge it past the shell without containment.
				BehaviorSupport.sendContained(level, dust, shape, center, radius,
						center.x + Math.cos(angle) * ringRadius, center.y + 0.2, center.z + Math.sin(angle) * ringRadius, 1, 0.05, 0.05, 0.05, 0.0);
			}
			return;
		}

		if (variant == 6) {
			// Twin nested rings expanding together, the inner at 60% of the outer radius.
			double outer = Math.min(radius * (0.3 + 0.28 * phase), radius * 0.98);
			DustParticleOptions dust = new DustParticleOptions(
					(phase % 2L == 0L ? ctx.pickColor(def.argbPrimary(), def.argbSecondary()) : ctx.secondaryColor(def.argbSecondary())) & 0xFFFFFF, 2.2F);
			for (int ring = 0; ring < 2; ring++) {
				double ringRadius = ring == 0 ? outer : outer * 0.6;
				int points = ctx.scaleCount(Mth.clamp((int) Math.round(Math.PI * 2.0 * ringRadius / 2.5), MIN_POINTS / 2, MAX_POINTS / 2), MAX_POINTS / 2);
				for (int i = 0; i < points; i++) {
					double angle = Math.PI * 2.0 * i / points;
					BehaviorSupport.sendContained(level, dust, shape, center, radius,
							center.x + Math.cos(angle) * ringRadius, center.y + 0.2, center.z + Math.sin(angle) * ringRadius, 1, 0.05, 0.05, 0.05, 0.0);
				}
			}
			return;
		}

		// Cap the expanding ring inside the shell: v2's last phase used to reach 1.2r.
		// v2 stays distinct from v0 via its larger dust scale and deeper beat pitch.
		double ringRadius = Math.min(variant == 2 ? radius * (0.3 + 0.3 * phase) : radius * (0.25 + 0.25 * phase), radius * 0.98);
		float dustScale = variant == 2 ? 2.0F : 1.4F;
		// Keep the point spacing roughly constant along the ring so the pulse stays
		// visible instead of turning sparse at large radii.
		int points = ctx.scaleCount(Mth.clamp((int) Math.round(Math.PI * 2.0 * ringRadius / 2.0), MIN_POINTS, MAX_POINTS), MAX_POINTS);
		DustParticleOptions dust = new DustParticleOptions(
				(phase % 2L == 0L ? ctx.pickColor(def.argbPrimary(), def.argbSecondary()) : ctx.secondaryColor(def.argbSecondary())) & 0xFFFFFF, dustScale);
		for (int i = 0; i < points; i++) {
			double angle = Math.PI * 2.0 * i / points;
			double x = center.x + Math.cos(angle) * ringRadius;
			double z = center.z + Math.sin(angle) * ringRadius;
			// Contained: the widest phase sits ON the 0.98r line, so the 0.2 lift
			// would poke it just past the shell. (sendContained also keeps the
			// overrideLimiter=true send form for players inside large bubbles.)
			BehaviorSupport.sendContained(level, dust, shape, center, radius, x, center.y + 0.2, z, 1, 0.05, 0.05, 0.05, 0.0);
		}
	}
}

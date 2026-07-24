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
 * Tall dust enderman silhouettes teleporting between hash-cycled floor slots:
 * each stalker holds its slot wreathed in portal flecks, then blinks away in
 * a reverse-portal burst at the old slot and a portal burst at the new one.
 * Bodies are palette dust with secondary-strand eyes turned toward the
 * projector, so the owner recolor retints both. Pure particles, no entities.
 *
 * <ul>
 * <li>v0: three calm stalkers on long holds</li>
 * <li>v1: a restless pack of four (half the hold time)</li>
 * <li>v2: synchronized blink (all teleport on one beat, portal flare at the center)</li>
 * <li>v3: one towering phantasm</li>
 * <li>v4: mirror pairs (every stalker has a twin reflected through the axis)</li>
 * <li>v5: screamers (a sonic-boom ring where each stalker rematerializes)</li>
 * <li>v6: portal storm (reverse-portal fountains under every stalker)</li>
 * </ul>
 */
public final class EndermanStalkers implements InsideEffectBehavior {
	public static final String ID = "enderman_stalkers";
	/** Worst case v4 swap pulse: 3 stalkers x 2 mirrored x (body 7 + eyes 2 + flecks 2 + swap bursts 6) = 102 particles/pulse. */
	private static final int MAX_STALKERS = 4;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int stalkers = ctx.scaleCount(switch (variant) {
			case 1 -> 4;
			case 3 -> 1;
			default -> 3;
		}, variant == 3 ? 2 : variant == 4 ? 3 : MAX_STALKERS);
		long hold = variant == 1 ? 30L : 60L;
		double h = Math.min(radius * 0.55, (variant == 3 ? 3.2 : 2.6) * Mth.clamp(def.behaviorStrength(), 0.8F, 1.3F));
		ParticleOptions bodyDust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 0.9F);
		ParticleOptions headDust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.2F);
		ParticleOptions eyeDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.5F);
		for (int s = 0; s < stalkers; s++) {
			// v2 drops the per-stalker stagger, so every blink lands on one beat.
			long offset = variant == 2 ? 0L : s * 17L;
			long slot = (gameTime + offset) / hold;
			boolean swapPulse = (gameTime + offset) % hold < 10L;
			Vec3 anchor = slotPoint(center, radius, s, slot);
			Vec3 old = slotPoint(center, radius, s, slot - 1L);
			emitStalker(level, shape, center, radius, variant, anchor, old, swapPulse, h, bodyDust, headDust, eyeDust);
			if (variant == 4) {
				// The mirror twin haunts the point reflected through the center axis.
				emitStalker(level, shape, center, radius, variant, mirror(center, anchor), mirror(center, old),
						swapPulse, h, bodyDust, headDust, eyeDust);
			}

			if (swapPulse && s == 0) {
				if (variant == 2) {
					// The shared blink flare over the projector.
					BehaviorSupport.sendContained(level, ParticleTypes.PORTAL, shape, center, radius,
							center.x, center.y + h * 0.6, center.z, 6, radius * 0.2, h * 0.2, radius * 0.2, 0.05);
				}

				level.playSound(null, anchor.x, anchor.y + h * 0.5, anchor.z,
						SoundEvents.ENDERMAN_TELEPORT, SoundSource.AMBIENT, 0.3F, 0.9F);
			}
		}
	}

	/** One silhouette at its slot: legs, torso, arms, head, gaze eyes, portal flecks, plus the blink bursts on a swap pulse. */
	private static void emitStalker(ServerLevel level, ShieldShape shape, Vec3 center, float radius, int variant,
			Vec3 anchor, Vec3 old, boolean swapPulse, double h,
			ParticleOptions bodyDust, ParticleOptions headDust, ParticleOptions eyeDust) {
		// The eyes sit perpendicular to the line of sight toward the center.
		double gaze = Math.atan2(center.z - anchor.z, center.x - anchor.x) + Math.PI / 2.0;
		double gx = Math.cos(gaze);
		double gz = Math.sin(gaze);
		for (int side = -1; side <= 1; side += 2) {
			BehaviorSupport.sendContained(level, bodyDust, shape, center, radius,
					anchor.x + gx * side * 0.05 * h, anchor.y + 0.18 * h, anchor.z + gz * side * 0.05 * h,
					1, 0.02, 0.05, 0.02, 0.0);
			// The long arms hang at the torso's flanks.
			BehaviorSupport.sendContained(level, bodyDust, shape, center, radius,
					anchor.x + gx * side * 0.14 * h, anchor.y + 0.5 * h, anchor.z + gz * side * 0.14 * h,
					1, 0.02, 0.08, 0.02, 0.0);
		}

		BehaviorSupport.sendContained(level, bodyDust, shape, center, radius,
				anchor.x, anchor.y + 0.42 * h, anchor.z, 1, 0.03, 0.06, 0.03, 0.0);
		BehaviorSupport.sendContained(level, bodyDust, shape, center, radius,
				anchor.x, anchor.y + 0.62 * h, anchor.z, 1, 0.03, 0.06, 0.03, 0.0);
		BehaviorSupport.sendContained(level, headDust, shape, center, radius,
				anchor.x, anchor.y + 0.82 * h, anchor.z, 1, 0.03, 0.03, 0.03, 0.0);
		BehaviorSupport.sendContained(level, eyeDust, shape, center, radius,
				anchor.x + gx * 0.07 * h, anchor.y + 0.78 * h, anchor.z + gz * 0.07 * h, 1, 0.0, 0.0, 0.0, 0.0);
		BehaviorSupport.sendContained(level, eyeDust, shape, center, radius,
				anchor.x - gx * 0.07 * h, anchor.y + 0.78 * h, anchor.z - gz * 0.07 * h, 1, 0.0, 0.0, 0.0, 0.0);
		BehaviorSupport.sendContained(level, ParticleTypes.PORTAL, shape, center, radius,
				anchor.x, anchor.y + 0.5 * h, anchor.z, 2, 0.15, 0.3 * h, 0.15, 0.02);
		if (swapPulse) {
			// Blink out where the stalker stood, blink in where it stands now.
			BehaviorSupport.sendContained(level, ParticleTypes.REVERSE_PORTAL, shape, center, radius,
					old.x, old.y + 0.5 * h, old.z, 3, 0.15, 0.3 * h, 0.15, 0.03);
			BehaviorSupport.sendContained(level, ParticleTypes.PORTAL, shape, center, radius,
					anchor.x, anchor.y + 0.5 * h, anchor.z, 3, 0.15, 0.3 * h, 0.15, 0.03);
			if (variant == 5) {
				BehaviorSupport.sendContained(level, ParticleTypes.SONIC_BOOM, shape, center, radius,
						anchor.x, anchor.y + 0.6 * h, anchor.z, 1, 0.0, 0.0, 0.0, 0.0);
			}
		}

		if (variant == 6) {
			BehaviorSupport.sendContained(level, ParticleTypes.REVERSE_PORTAL, shape, center, radius,
					anchor.x, anchor.y + 0.1 * h, anchor.z, 2, 0.1, 0.15 * h, 0.1, 0.02);
		}
	}

	private static Vec3 mirror(Vec3 center, Vec3 pos) {
		return new Vec3(2.0 * center.x - pos.x, pos.y, 2.0 * center.z - pos.z);
	}

	/** The hash-cycled stalker slot: within 0.6r horizontally, on the floor plane. */
	private static Vec3 slotPoint(Vec3 center, float radius, int stalker, long slot) {
		long seed = BehaviorSupport.mix(slot * 613L + stalker * 41L);
		double angle = BehaviorSupport.hash01(seed) * Math.PI * 2.0;
		double dist = Math.sqrt(BehaviorSupport.hash01(seed + 1L)) * radius * 0.6;
		return new Vec3(center.x + Math.cos(angle) * dist, center.y + 0.1, center.z + Math.sin(angle) * dist);
	}
}

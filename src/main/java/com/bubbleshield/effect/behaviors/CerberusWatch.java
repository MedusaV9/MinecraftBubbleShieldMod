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
 * A three-headed hound silhouette crouched beside the projector: a palette
 * dust body with three necks fanning forward, each head crowned by soul-fire
 * flames. The heads swivel in sequence -- only the head "on watch" flares
 * fully, sweeping its gaze side to side -- and lava drips fall from the jaws on
 * the growl beat. Pure phase-derived particles: no entities, no fields, no
 * cleanup; the owner recolor repaints hide and collars.
 *
 * <ul>
 * <li>v0: the watchdog at rest, heads swiveling in slow sequence</li>
 * <li>v1: restless pacing (the den drifts between hash-seeded spots)</li>
 * <li>v2: ember-eyed (a glow eye pair on every head)</li>
 * <li>v3: triple alert (all three heads flare together on the beat)</li>
 * <li>v4: shadow hound (a large-smoke shroud rolls off the body)</li>
 * <li>v5: audible growl (a low warden-heartbeat rumble on the growl beat)</li>
 * <li>v6: hellhound (doubled head-flames and an extra jaw drip)</li>
 * </ul>
 */
public final class CerberusWatch implements InsideEffectBehavior {
	public static final String ID = "cerberus_watch";
	/** Conservative worst case (v6 growl beat, every head counted as flared): body 10 + collars 3 + 3 heads x (2 neck + 6 flame + 2 drips) = 43 particles/pulse. */
	private static final int MAX_BODY_MOTES = 10;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		long beat = gameTime / 10L;
		boolean growl = beat % 4L == 0L;
		// The den sits beside the projector; v1 pads between hash-seeded spots.
		double denAngle = variant == 1
				? BehaviorSupport.hash01(BehaviorSupport.mix(gameTime / 80L)) * Math.PI * 2.0
				: Math.PI * 0.25;
		double denDist = Mth.clamp(radius * 0.25F, 1.0F, 3.0F);
		Vec3 haunch = new Vec3(center.x + Math.cos(denAngle) * denDist, center.y + 0.5, center.z + Math.sin(denAngle) * denDist);
		// The hound faces the projector, necks fanning toward it.
		double facing = Math.atan2(center.z - haunch.z, center.x - haunch.x);
		ParticleOptions hideDust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.2F);
		ParticleOptions collarDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.9F);
		int body = ctx.scaleCount(6, MAX_BODY_MOTES);
		for (int i = 0; i < body; i++) {
			// The crouched body: motes along the spine from haunch to shoulder.
			double t = i / (double) Math.max(1, body - 1);
			BehaviorSupport.sendContained(level, hideDust, shape, center, radius,
					haunch.x + Math.cos(facing) * t * 1.2,
					haunch.y + 0.25 * Math.sin(t * Math.PI),
					haunch.z + Math.sin(facing) * t * 1.2, 1, 0.05, 0.05, 0.05, 0.0);
		}

		if (variant == 4) {
			BehaviorSupport.sendContained(level, ParticleTypes.LARGE_SMOKE, shape, center, radius,
					haunch.x, haunch.y + 0.3, haunch.z, ctx.scaleCount(2, 4), 0.4, 0.3, 0.4, 0.01);
		}

		// Which head is on watch this beat (heads swivel in sequence).
		int watching = (int) (beat % 3L);
		Vec3 shoulder = new Vec3(haunch.x + Math.cos(facing) * 1.2, haunch.y + 0.35, haunch.z + Math.sin(facing) * 1.2);
		for (int h = 0; h < 3; h++) {
			double neck = facing + (h - 1) * 0.6;
			Vec3 head = new Vec3(shoulder.x + Math.cos(neck) * 0.8, shoulder.y + 0.5, shoulder.z + Math.sin(neck) * 0.8);
			// Two neck motes climbing from the shoulder to the head.
			for (int k = 1; k <= 2; k++) {
				Vec3 seg = shoulder.lerp(head, k / 3.0);
				BehaviorSupport.sendContained(level, hideDust, shape, center, radius,
						seg.x, seg.y, seg.z, 1, 0.03, 0.03, 0.03, 0.0);
			}

			// The collar: one secondary-dust mote at the neck root, every head.
			BehaviorSupport.sendContained(level, collarDust, shape, center, radius,
					shoulder.x, shoulder.y + 0.1, shoulder.z, 1, 0.03, 0.03, 0.03, 0.0);
			boolean flaring = variant == 3 || h == watching;
			// The gaze sweep: the watching head's flame crown swings side to side.
			double sweep = flaring ? Math.sin(gameTime / 10.0 * 0.8 + h) * 0.3 : 0.0;
			int flames = flaring ? (variant == 6 ? 6 : 3) : 1;
			for (int k = 0; k < flames; k++) {
				BehaviorSupport.sendContained(level, ParticleTypes.SOUL_FIRE_FLAME, shape, center, radius,
						head.x + Math.cos(neck + Math.PI / 2.0) * sweep,
						head.y + 0.15 + 0.08 * k,
						head.z + Math.sin(neck + Math.PI / 2.0) * sweep, 1, 0.04, 0.04, 0.04, 0.01);
			}

			if (variant == 2) {
				double eyes = neck + Math.PI / 2.0;
				BehaviorSupport.sendContained(level, ParticleTypes.GLOW, shape, center, radius,
						head.x + Math.cos(eyes) * 0.12, head.y, head.z + Math.sin(eyes) * 0.12, 1, 0.0, 0.0, 0.0, 0.0);
				BehaviorSupport.sendContained(level, ParticleTypes.GLOW, shape, center, radius,
						head.x - Math.cos(eyes) * 0.12, head.y, head.z - Math.sin(eyes) * 0.12, 1, 0.0, 0.0, 0.0, 0.0);
			}

			if (growl) {
				// Lava drips off the jaws on the growl beat.
				int drips = variant == 6 ? 2 : 1;
				for (int k = 0; k < drips; k++) {
					BehaviorSupport.sendContained(level, ParticleTypes.LAVA, shape, center, radius,
							head.x + Math.cos(neck) * 0.25, head.y - 0.15 - 0.1 * k, head.z + Math.sin(neck) * 0.25,
							1, 0.03, 0.03, 0.03, 0.0);
				}
			}
		}

		if (variant == 5 && growl) {
			level.playSound(null, haunch.x, haunch.y, haunch.z, SoundEvents.WARDEN_HEARTBEAT, SoundSource.AMBIENT, 0.4F, 0.6F);
		}
	}
}

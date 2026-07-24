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
 * Bouncing squash-and-stretch slime blobs hopping between hash-seeded floor
 * waypoints: each blob is a dust cluster (a fat core, four skirt motes and a
 * secondary-strand sheen cap) that flattens wide on the ground and pulls tall
 * at the hop's apex, splatting slime flecks on every landing. Bodies are
 * palette dust (author the palette slime-green), so the owner recolor retints
 * the troupe. Pure particles, no entities.
 *
 * <ul>
 * <li>v0: three medium slimes on wandering hops</li>
 * <li>v1: a big slime with two half-size splits in tow</li>
 * <li>v2: a swarm of six minis</li>
 * <li>v3: honey slimes drooling dripping-honey flecks</li>
 * <li>v4: bouncy castle (higher, longer hops with cloud puffs on landing)</li>
 * <li>v5: magma slimes (flame spits on every landing)</li>
 * <li>v6: a synchronized troupe bouncing a ring, a note at every apex</li>
 * </ul>
 */
public final class SlimeGhosts implements InsideEffectBehavior {
	public static final String ID = "slime_ghosts";
	/** Worst case v4 landing pulse: 8 blobs x (blob dust 6 + splat 3 + cloud 2) = 88 particles/pulse. */
	private static final int MAX_BLOBS = 8;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int blobs = ctx.scaleCount(switch (variant) {
			case 2 -> 6;
			case 6 -> 5;
			default -> 3;
		}, MAX_BLOBS);
		long hopTicks = variant == 4 ? 30L : 20L;
		double s = Math.min(radius * 0.2, 0.9 * Mth.clamp(def.behaviorStrength(), 0.8F, 1.3F));
		double hopHeight = Math.min(radius * 0.35, variant == 4 ? 2.2 : 1.4);
		ParticleOptions coreDust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.6F);
		ParticleOptions skinDust = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 0.9F);
		ParticleOptions sheenDust = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.7F);
		for (int b = 0; b < blobs; b++) {
			// A multiple of the pulse period, so every blob's landings land ON a pulse.
			long offset = variant == 6 ? 0L : b * 10L;
			long hop = (gameTime + offset) / hopTicks;
			double t = ((gameTime + offset) % hopTicks) / (double) hopTicks;
			double bs = s * (variant == 1 ? (b == 0 ? 1.7 : 0.6) : variant == 2 ? 0.6 : 1.0);
			Vec3 from = hopPoint(center, radius, b, hop, variant, blobs);
			Vec3 to = hopPoint(center, radius, b, hop + 1L, variant, blobs);
			double x = Mth.lerp(t, from.x, to.x);
			double z = Mth.lerp(t, from.z, to.z);
			double air = 4.0 * t * (1.0 - t);
			double ground = center.y + 0.15;
			double y = ground + hopHeight * air;
			// Squash on the ground (wide + flat), stretch in the air (narrow + tall).
			double w = bs * (1.2 - 0.4 * air);
			double v = bs * (0.55 + 0.55 * air);
			double head = Math.atan2(to.z - from.z, to.x - from.x);
			BehaviorSupport.sendContained(level, coreDust, shape, center, radius,
					x, y + 0.5 * v, z, 1, 0.04, 0.04, 0.04, 0.0);
			for (int k = 0; k < 4; k++) {
				double angle = head + Math.PI * 0.25 + Math.PI * 0.5 * k;
				BehaviorSupport.sendContained(level, skinDust, shape, center, radius,
						x + Math.cos(angle) * w, y + 0.2 * bs, z + Math.sin(angle) * w, 1, 0.02, 0.02, 0.02, 0.0);
			}

			BehaviorSupport.sendContained(level, sheenDust, shape, center, radius,
					x, y + v, z, 1, 0.03, 0.02, 0.03, 0.0);
			if (t == 0.0) {
				// The landing: slime flecks splat outward at the floor.
				BehaviorSupport.sendContained(level, ParticleTypes.ITEM_SLIME, shape, center, radius,
						x, ground + 0.1, z, 3, 0.35 * bs, 0.05, 0.35 * bs, 0.02);
				if (variant == 4) {
					BehaviorSupport.sendContained(level, ParticleTypes.CLOUD, shape, center, radius,
							x, ground + 0.1, z, 2, 0.3 * bs, 0.05, 0.3 * bs, 0.01);
				} else if (variant == 5) {
					BehaviorSupport.sendContained(level, ParticleTypes.FLAME, shape, center, radius,
							x, ground + 0.15, z, 2, 0.2 * bs, 0.05, 0.2 * bs, 0.02);
				}

				if (b == 0 && hop % 2L == 0L) {
					level.playSound(null, x, ground, z, SoundEvents.SLIME_SQUISH, SoundSource.AMBIENT, 0.3F, 1.0F);
				}
			}

			if (variant == 3) {
				BehaviorSupport.sendContained(level, ParticleTypes.DRIPPING_HONEY, shape, center, radius,
						x, y + 0.3 * v, z, 2, 0.15 * bs, 0.05, 0.15 * bs, 0.0);
			} else if (variant == 6 && t == 0.5) {
				BehaviorSupport.sendContained(level, ParticleTypes.NOTE, shape, center, radius,
						x, y + v + 0.3, z, 1, 0.05, 0.05, 0.05, 0.0);
			}
		}
	}

	/**
	 * The hash-seeded landing waypoint for one blob and hop: within 0.6r
	 * horizontally, on the floor plane. v6 bounces a slowly advancing ring
	 * instead, so the troupe stays in formation.
	 */
	private static Vec3 hopPoint(Vec3 center, float radius, int blob, long hop, int variant, int blobs) {
		if (variant == 6) {
			double angle = Math.PI * 2.0 * blob / blobs + hop * 0.4;
			return new Vec3(center.x + Math.cos(angle) * radius * 0.5, center.y + 0.15,
					center.z + Math.sin(angle) * radius * 0.5);
		}

		long seed = BehaviorSupport.mix(hop * 641L + blob * 13L);
		double angle = BehaviorSupport.hash01(seed) * Math.PI * 2.0;
		double dist = Math.sqrt(BehaviorSupport.hash01(seed + 1L)) * radius * 0.6;
		return new Vec3(center.x + Math.cos(angle) * dist, center.y + 0.15, center.z + Math.sin(angle) * dist);
	}
}

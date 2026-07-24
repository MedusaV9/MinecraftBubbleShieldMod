package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Roaming ghost columns wandering the bubble floor: each spirit walks between
 * hash-seeded waypoints (poof-rematerializing at each new leg), its body a
 * rising smoke column with a glow head and palette dust footprints. Waypoints
 * derive from the shield center, so every shield hosts its own spirits.
 *
 * <ul>
 * <li>v0: two slow spirits</li>
 * <li>v1: five restless spirits (short legs, white smoke)</li>
 * <li>v2: a poltergeist that flings cobweb puffs sideways</li>
 * <li>v3: warped spirits (warped-spore bodies)</li>
 * <li>v4: crimson spirits (crimson-spore bodies)</li>
 * <li>v5: child spirits (half height, quick)</li>
 * <li>v6: a procession walking to the center and back</li>
 * </ul>
 */
public final class WanderingSpirits implements InsideEffectBehavior {
	public static final String ID = "wandering_spirits";
	/** Worst case v1: 5 spirits x (poof 2 + body 3 + head 1 + footprint 1 + extras 2) = 45 particles/pulse. */
	private static final int MAX_SPIRITS = 5;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int spirits = ctx.scaleCount(switch (variant) {
			case 0 -> 2;
			case 1 -> 5;
			case 5 -> 4;
			default -> 3;
		}, MAX_SPIRITS);
		long legTicks = variant == 1 || variant == 5 ? 40L : 80L;
		long leg = gameTime / legTicks;
		double t = (gameTime % legTicks) / (double) legTicks;
		// Per-shield identity: waypoints are seeded from the projector position.
		long shieldSeed = (long) Math.floor(center.x) * 341873128712L + (long) Math.floor(center.z) * 132897987541L;
		ParticleOptions body = switch (variant) {
			case 1, 5 -> ParticleTypes.WHITE_SMOKE;
			case 3 -> ParticleTypes.WARPED_SPORE;
			case 4 -> ParticleTypes.CRIMSON_SPORE;
			default -> ParticleTypes.CLOUD;
		};
		ParticleOptions footprint = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 0.7F);
		double height = variant == 5 ? 0.6 : 1.2;
		for (int s = 0; s < spirits; s++) {
			Vec3 from = waypoint(center, radius, shieldSeed, s, leg, variant);
			Vec3 to = waypoint(center, radius, shieldSeed, s, leg + 1L, variant);
			Vec3 pos = from.lerp(to, t);
			if (t < 0.125) {
				// Rematerialize: the spirit poofs in at the start of each leg.
				BehaviorSupport.sendContained(level, ParticleTypes.POOF, shape, center, radius,
						from.x, from.y + height * 0.5, from.z, 2, 0.2, 0.3, 0.2, 0.01);
			}

			BehaviorSupport.sendContained(level, body, shape, center, radius,
					pos.x, pos.y + height * 0.5, pos.z, variant == 5 ? 2 : 3, 0.12, height * 0.4, 0.12, 0.01);
			BehaviorSupport.sendContained(level, ParticleTypes.GLOW, shape, center, radius,
					pos.x, pos.y + height, pos.z, 1, 0.05, 0.05, 0.05, 0.0);
			BehaviorSupport.sendContained(level, footprint, shape, center, radius,
					pos.x, pos.y + 0.1, pos.z, 1, 0.1, 0.02, 0.1, 0.0);
			if (variant == 2) {
				// Poltergeist: cobweb puffs flung sideways off the body. ITEM_COBWEB's
				// client provider (BreakingItemParticle.CobwebProvider) drops the
				// velocity args, so the fling would read as a static hover; the
				// ITEM form (BreakingItemParticle.Provider) forwards them (verified
				// in the 26.2 client sources), making the sideways speed visible.
				BehaviorSupport.sendContained(level, new ItemParticleOption(ParticleTypes.ITEM, Items.COBWEB), shape, center, radius,
						pos.x, pos.y + height * 0.6, pos.z, 2, 0.6, 0.1, 0.6, 0.15);
			}
		}
	}

	/**
	 * The hash-seeded floor waypoint for one spirit and leg (within 0.7r
	 * horizontally, on the center plane). v6 alternates perimeter and center, so
	 * the procession walks in and back out.
	 */
	private static Vec3 waypoint(Vec3 center, float radius, long shieldSeed, int spirit, long leg, int variant) {
		if (variant == 6 && leg % 2L == 1L) {
			return new Vec3(center.x, center.y + 0.2, center.z);
		}

		long seed = BehaviorSupport.mix(shieldSeed + leg * 421L + spirit * 17L);
		double angle = BehaviorSupport.hash01(seed) * Math.PI * 2.0;
		double dist = (variant == 6 ? 0.65 : Math.sqrt(BehaviorSupport.hash01(seed + 1L)) * 0.7) * radius;
		return new Vec3(center.x + Math.cos(angle) * dist, center.y + 0.2, center.z + Math.sin(angle) * dist);
	}
}

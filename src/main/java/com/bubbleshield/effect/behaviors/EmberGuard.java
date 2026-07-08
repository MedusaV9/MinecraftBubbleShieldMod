package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldGeometry;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Grants Fire Resistance to every player standing inside the shield.
 *
 * <ul>
 * <li>v0: Fire Resistance</li>
 * <li>v1: Fire Resistance plus a small-flame ring circling the projector</li>
 * <li>v2: Fire Resistance plus dripping-lava accents drifting down mid-bubble</li>
 * </ul>
 */
public final class EmberGuard implements InsideEffectBehavior {
	public static final String ID = "ember_guard";
	private static final int DURATION_TICKS = 60;
	/** Emitted points stay within this fraction of the radius (inside the shell). */
	private static final double MAX_DIST_FRAC = 0.98;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		AABB box = AABB.ofSize(center, radius * 2.0, radius * 2.0, radius * 2.0);
		for (Player player : level.getEntitiesOfClass(Player.class, box)) {
			if (!ShieldGeometry.isInside(shape, center, radius, player.position())) {
				continue;
			}

			player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, DURATION_TICKS, 0));
		}

		if (variant == 1) {
			// A rotating ring of small flames around the projector at knee height.
			double ringRadius = radius * 0.45;
			int points = ctx.scaleCount(Mth.clamp((int) Math.round(Math.PI * 2.0 * ringRadius / 1.5), 8, 48), 48);
			double phase = gameTime / 10.0 * 0.3;
			for (int i = 0; i < points; i++) {
				double angle = phase + Math.PI * 2.0 * i / points;
				double x = center.x + Math.cos(angle) * ringRadius;
				double z = center.z + Math.sin(angle) * ringRadius;
				level.sendParticles(ParticleTypes.SMALL_FLAME, true, false, x, center.y + 0.5, z, 1, 0.05, 0.05, 0.05, 0.0);
			}
		} else if (variant == 2) {
			// Sparse lava droplets drifting down from mid-bubble height. The gaussian
			// spread of a single count>0 sendParticles call is unbounded (offsets are
			// nextGaussian() * dist), so sample the spread here instead and rescale any
			// point past 0.98r back inside the shell, like the other behaviors.
			int count = ctx.scaleCount(Mth.clamp((int) (radius * 1.2F * def.behaviorStrength()), 6, 32), 32);
			RandomSource random = level.getRandom();
			double maxDist = radius * MAX_DIST_FRAC;
			for (int i = 0; i < count; i++) {
				double dx = random.nextGaussian() * radius * 0.45;
				double dy = radius * 0.5 + random.nextGaussian() * radius * 0.2;
				double dz = random.nextGaussian() * radius * 0.45;
				double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
				if (dist > maxDist) {
					double scale = maxDist / dist;
					dx *= scale;
					dy *= scale;
					dz *= scale;
				}

				level.sendParticles(ParticleTypes.DRIPPING_LAVA, true, false, center.x + dx, center.y + dy, center.z + dz, 1, 0.0, 0.0, 0.0, 0.0);
			}
		}
	}
}

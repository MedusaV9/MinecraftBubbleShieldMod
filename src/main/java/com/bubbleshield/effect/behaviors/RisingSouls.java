package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Columns of soul particles rising from the bubble floor.
 *
 * <ul>
 * <li>v0: sculk soul columns</li>
 * <li>v1: sculk soul columns popping at their apex</li>
 * <li>v2: a dense rise of nether souls</li>
 * </ul>
 */
public final class RisingSouls implements InsideEffectBehavior {
	public static final String ID = "rising_souls";

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		SimpleParticleType particle = variant == 2 ? ParticleTypes.SOUL : ParticleTypes.SCULK_SOUL;
		int perColumn = variant == 2 ? 4 : 3;
		// v1 adds one apex pop per column; worst case 24 * (4 + 1) = 120 particles/pulse.
		int columns = ctx.scaleCount(Mth.clamp((int) (radius * (variant == 2 ? 1.6F : 1.0F) * def.behaviorStrength()), 4, 24), 24);
		RandomSource random = level.getRandom();
		for (int i = 0; i < columns; i++) {
			double angle = random.nextDouble() * Math.PI * 2.0;
			double dist = Math.sqrt(random.nextDouble()) * radius * 0.8;
			double x = center.x + Math.cos(angle) * dist;
			double z = center.z + Math.sin(angle) * dist;
			// Both soul particle types drift upward on their own once spawned near the floor.
			level.sendParticles(particle, true, false, x, center.y + 0.3, z, perColumn, 0.15, 0.5, 0.15, 0.02);
			if (variant == 1) {
				// Keep the apex pop inside the shell: a column base at 0.8r horizontally
				// used to put the fixed 0.6r-high apex at ~1.0r, straddling the wall.
				double ax = x - center.x;
				double ay = radius * 0.6;
				double az = z - center.z;
				double apexDist = Math.sqrt(ax * ax + ay * ay + az * az);
				double maxDist = radius * 0.98;
				if (apexDist > maxDist) {
					double scale = maxDist / apexDist;
					ax *= scale;
					ay *= scale;
					az *= scale;
				}

				level.sendParticles(ParticleTypes.SCULK_CHARGE_POP, true, false, center.x + ax, center.y + ay, center.z + az, 1, 0.1, 0.1, 0.1, 0.0);
			}
		}
	}
}

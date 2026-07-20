package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldGeometry;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Grants a strong jump buff to every player standing inside the shield.
 *
 * <ul>
 * <li>v0: Jump Boost II</li>
 * <li>v1: Jump Boost II plus cloud puffs at the players' feet</li>
 * <li>v2: Jump Boost II plus Slow Falling for soft landings</li>
 * <li>v3: Jump Boost III</li>
 * <li>v4: Jump Boost II plus Speed I</li>
 * <li>v5: Jump Boost II plus poof launch puffs</li>
 * <li>v6: a short burst of Jump Boost IV with Slow Falling</li>
 * </ul>
 */
public final class LeapAura implements InsideEffectBehavior {
	public static final String ID = "leap_aura";
	private static final int DURATION_TICKS = 60;

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

			int amplifier = switch (variant) {
				case 3 -> 2;
				case 6 -> 3;
				default -> 1;
			};
			player.addEffect(new MobEffectInstance(MobEffects.JUMP_BOOST, variant == 6 ? 30 : DURATION_TICKS, amplifier));
			if (variant == 1) {
				level.sendParticles(ParticleTypes.CLOUD, true, false, player.getX(), player.getY() + 0.1, player.getZ(), ctx.scaleCount(3, 8), 0.3, 0.1, 0.3, 0.01);
			} else if (variant == 2 || variant == 6) {
				player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, DURATION_TICKS, 0));
			} else if (variant == 4) {
				player.addEffect(new MobEffectInstance(MobEffects.SPEED, DURATION_TICKS, 0));
			} else if (variant == 5) {
				level.sendParticles(ParticleTypes.POOF, true, false, player.getX(), player.getY() + 0.1, player.getZ(), ctx.scaleCount(2, 6), 0.2, 0.05, 0.2, 0.01);
			}
		}
	}
}

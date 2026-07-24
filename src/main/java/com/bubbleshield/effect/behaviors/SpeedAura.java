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
 * Grants brief movement buffs to every player standing inside the shield.
 *
 * <ul>
 * <li>v0: Speed I</li>
 * <li>v1: Speed II plus Jump Boost I</li>
 * <li>v2: Speed I plus Haste I</li>
 * <li>v3: Speed II</li>
 * <li>v4: Speed I plus Dolphin's Grace</li>
 * <li>v5: Speed I plus cloud puffs trailing at the players' feet</li>
 * <li>v6: a short burst of Speed III</li>
 * </ul>
 */
public final class SpeedAura implements InsideEffectBehavior {
	public static final String ID = "speed_aura";
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
				case 1, 3 -> 1;
				case 6 -> 2;
				default -> 0;
			};
			player.addEffect(new MobEffectInstance(MobEffects.SPEED, variant == 6 ? 30 : DURATION_TICKS, amplifier));
			if (variant == 1) {
				player.addEffect(new MobEffectInstance(MobEffects.JUMP_BOOST, DURATION_TICKS, 0));
			} else if (variant == 2) {
				player.addEffect(new MobEffectInstance(MobEffects.HASTE, DURATION_TICKS, 0));
			} else if (variant == 4) {
				player.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, DURATION_TICKS, 0));
			} else if (variant == 5) {
				BehaviorSupport.sendContained(level, ParticleTypes.CLOUD, shape, center, radius, player.getX(), player.getY() + 0.1, player.getZ(), ctx.scaleCount(3, 8), 0.3, 0.05, 0.3, 0.01);
			}
		}
	}
}

package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;

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
 * </ul>
 */
public final class SpeedAura implements InsideEffectBehavior {
	public static final String ID = "speed_aura";
	private static final int DURATION_TICKS = 60;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime) {
		if (gameTime % 10L != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		AABB box = AABB.ofSize(center, radius * 2.0, radius * 2.0, radius * 2.0);
		for (Player player : level.getEntitiesOfClass(Player.class, box)) {
			if (player.position().distanceTo(center) > radius) {
				continue;
			}

			player.addEffect(new MobEffectInstance(MobEffects.SPEED, DURATION_TICKS, variant == 1 ? 1 : 0));
			if (variant == 1) {
				player.addEffect(new MobEffectInstance(MobEffects.JUMP_BOOST, DURATION_TICKS, 0));
			} else if (variant == 2) {
				player.addEffect(new MobEffectInstance(MobEffects.HASTE, DURATION_TICKS, 0));
			}
		}
	}
}

package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Applies brief Slowness to every hostile monster caught inside the shield.
 */
public final class SlowHostiles implements InsideEffectBehavior {
	public static final String ID = "slow_hostiles";
	private static final int DURATION_TICKS = 60;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime) {
		if (gameTime % 10L != 0L) {
			return;
		}

		AABB box = AABB.ofSize(center, radius * 2.0, radius * 2.0, radius * 2.0);
		for (Monster monster : level.getEntitiesOfClass(Monster.class, box)) {
			if (monster.position().distanceTo(center) <= radius) {
				monster.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, DURATION_TICKS, 1));
			}
		}
	}
}

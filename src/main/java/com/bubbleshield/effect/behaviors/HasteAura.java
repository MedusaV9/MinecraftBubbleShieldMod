package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Grants brief mining buffs to every player standing inside the shield.
 *
 * <ul>
 * <li>v0: Haste I</li>
 * <li>v1: Haste II</li>
 * <li>v2: Haste I plus Conduit Power</li>
 * </ul>
 */
public final class HasteAura implements InsideEffectBehavior {
	public static final String ID = "haste_aura";
	private static final int DURATION_TICKS = 60;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		AABB box = AABB.ofSize(center, radius * 2.0, radius * 2.0, radius * 2.0);
		for (Player player : level.getEntitiesOfClass(Player.class, box)) {
			if (player.position().distanceTo(center) > radius) {
				continue;
			}

			player.addEffect(new MobEffectInstance(MobEffects.HASTE, DURATION_TICKS, variant == 1 ? 1 : 0));
			if (variant == 2) {
				player.addEffect(new MobEffectInstance(MobEffects.CONDUIT_POWER, DURATION_TICKS, 0));
			}
		}
	}
}

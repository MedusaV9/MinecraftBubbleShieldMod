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
 * Grants Luck to every player standing inside the shield.
 *
 * <ul>
 * <li>v0: Luck</li>
 * <li>v1: Luck plus happy-villager sparkles above each player</li>
 * <li>v2: Luck plus Hero of the Village</li>
 * <li>v3: Luck II</li>
 * <li>v4: Luck plus composter glints raining over each player</li>
 * <li>v5: Luck plus Hero of the Village II</li>
 * <li>v6: Luck plus a celebration burst of notes every few seconds</li>
 * </ul>
 */
public final class LuckyCharm implements InsideEffectBehavior {
	public static final String ID = "lucky_charm";
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

			player.addEffect(new MobEffectInstance(MobEffects.LUCK, DURATION_TICKS, variant == 3 ? 1 : 0));
			if (variant == 1) {
				BehaviorSupport.sendContained(level, ParticleTypes.HAPPY_VILLAGER, shape, center, radius, player.getX(), player.getY() + 1.8, player.getZ(), ctx.scaleCount(3, 10), 0.3, 0.3, 0.3, 0.0);
			} else if (variant == 2) {
				player.addEffect(new MobEffectInstance(MobEffects.HERO_OF_THE_VILLAGE, DURATION_TICKS, 0));
			} else if (variant == 4) {
				BehaviorSupport.sendContained(level, ParticleTypes.COMPOSTER, shape, center, radius, player.getX(), player.getY() + 2.0, player.getZ(), ctx.scaleCount(4, 10), 0.4, 0.2, 0.4, 0.0);
			} else if (variant == 5) {
				player.addEffect(new MobEffectInstance(MobEffects.HERO_OF_THE_VILLAGE, DURATION_TICKS, 1));
			} else if (variant == 6 && gameTime % 40L == 0L) {
				BehaviorSupport.sendContained(level, ParticleTypes.NOTE, shape, center, radius, player.getX(), player.getY() + 2.2, player.getZ(), ctx.scaleCount(6, 12), 0.6, 0.4, 0.6, 0.0);
				BehaviorSupport.sendContained(level, ParticleTypes.HAPPY_VILLAGER, shape, center, radius, player.getX(), player.getY() + 1.5, player.getZ(), ctx.scaleCount(4, 8), 0.5, 0.5, 0.5, 0.0);
			}
		}
	}
}

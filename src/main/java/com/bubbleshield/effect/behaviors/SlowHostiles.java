package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Applies brief debuffs to every hostile monster caught inside the shield.
 *
 * <ul>
 * <li>v0: Slowness II</li>
 * <li>v1: Slowness I plus Weakness I</li>
 * <li>v2: Slowness III plus Mining Fatigue I</li>
 * </ul>
 */
public final class SlowHostiles implements InsideEffectBehavior {
	public static final String ID = "slow_hostiles";
	private static final int DURATION_TICKS = 60;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime) {
		if (gameTime % 10L != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		// Query Mob + Enemy instead of Monster: hostiles like Ghast, Phantom, Slime,
		// MagmaCube, Hoglin, Shulker and the Ender Dragon implement Enemy but do not
		// extend Monster.
		AABB box = AABB.ofSize(center, radius * 2.0, radius * 2.0, radius * 2.0);
		for (Mob mob : level.getEntitiesOfClass(Mob.class, box, e -> e instanceof Enemy)) {
			if (mob.position().distanceTo(center) > radius) {
				continue;
			}

			int slownessAmplifier = switch (variant) {
				case 1 -> 0;
				case 2 -> 2;
				default -> 1;
			};
			mob.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, DURATION_TICKS, slownessAmplifier));
			if (variant == 1) {
				mob.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, DURATION_TICKS, 0));
			} else if (variant == 2) {
				mob.addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, DURATION_TICKS, 0));
			}
		}
	}
}

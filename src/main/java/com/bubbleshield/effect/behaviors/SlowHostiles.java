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
 * <li>v3: Slowness II plus Blindness</li>
 * <li>v4: Slowness I plus Glowing, marking intruders through walls</li>
 * <li>v5: Slowness II plus a squid-ink drip on each hostile</li>
 * <li>v6: a short but heavy Slowness V clamp</li>
 * </ul>
 */
public final class SlowHostiles implements InsideEffectBehavior {
	public static final String ID = "slow_hostiles";
	private static final int DURATION_TICKS = 60;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		// Query Mob + Enemy instead of Monster: hostiles like Ghast, Phantom, Slime,
		// MagmaCube, Hoglin, Shulker and the Ender Dragon implement Enemy but do not
		// extend Monster.
		AABB box = AABB.ofSize(center, radius * 2.0, radius * 2.0, radius * 2.0);
		for (Mob mob : level.getEntitiesOfClass(Mob.class, box, e -> e instanceof Enemy)) {
			if (!ShieldGeometry.isInside(shape, center, radius, mob.position())) {
				continue;
			}

			int slownessAmplifier = switch (variant) {
				case 1, 4 -> 0;
				case 2 -> 2;
				case 6 -> 4;
				default -> 1;
			};
			mob.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, variant == 6 ? 30 : DURATION_TICKS, slownessAmplifier));
			if (variant == 1) {
				mob.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, DURATION_TICKS, 0));
			} else if (variant == 2) {
				mob.addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, DURATION_TICKS, 0));
			} else if (variant == 3) {
				mob.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, DURATION_TICKS, 0));
			} else if (variant == 4) {
				mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, DURATION_TICKS, 0));
			} else if (variant == 5) {
				BehaviorSupport.sendContained(level, ParticleTypes.SQUID_INK, shape, center, radius, mob.getX(), mob.getY() + mob.getBbHeight(), mob.getZ(), ctx.scaleCount(3, 8), 0.2, 0.3, 0.2, 0.01);
			}
		}
	}
}

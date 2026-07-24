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
 * Grants brief mining buffs to every player standing inside the shield.
 *
 * <ul>
 * <li>v0: Haste I</li>
 * <li>v1: Haste II</li>
 * <li>v2: Haste I plus Conduit Power</li>
 * <li>v3: Haste I plus crit stars at the players' hands</li>
 * <li>v4: Haste II plus Speed I</li>
 * <li>v5: Haste I plus copper-scrape glints</li>
 * <li>v6: a short burst of Haste III</li>
 * </ul>
 */
public final class HasteAura implements InsideEffectBehavior {
	public static final String ID = "haste_aura";
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
				case 1, 4 -> 1;
				case 6 -> 2;
				default -> 0;
			};
			player.addEffect(new MobEffectInstance(MobEffects.HASTE, variant == 6 ? 30 : DURATION_TICKS, amplifier));
			if (variant == 2) {
				player.addEffect(new MobEffectInstance(MobEffects.CONDUIT_POWER, DURATION_TICKS, 0));
			} else if (variant == 3) {
				BehaviorSupport.sendContained(level, ParticleTypes.CRIT, shape, center, radius, player.getX(), player.getY() + 1.0, player.getZ(), ctx.scaleCount(3, 8), 0.4, 0.3, 0.4, 0.05);
			} else if (variant == 4) {
				player.addEffect(new MobEffectInstance(MobEffects.SPEED, DURATION_TICKS, 0));
			} else if (variant == 5) {
				BehaviorSupport.sendContained(level, ParticleTypes.SCRAPE, shape, center, radius, player.getX(), player.getY() + 1.2, player.getZ(), ctx.scaleCount(2, 6), 0.3, 0.4, 0.3, 0.0);
			}
		}
	}
}

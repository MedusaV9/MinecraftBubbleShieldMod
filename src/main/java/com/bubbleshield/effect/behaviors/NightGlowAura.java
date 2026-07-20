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
 * Grants night-time comfort buffs to every player standing inside the shield.
 *
 * <ul>
 * <li>v0: Night Vision</li>
 * <li>v1: Night Vision plus Slow Falling</li>
 * <li>v2: Night Vision plus Luck</li>
 * <li>v3: Night Vision plus Glowing, so companions stay visible</li>
 * <li>v4: Night Vision plus an end rod halo above each player</li>
 * <li>v5: Night Vision plus Speed I</li>
 * <li>v6: Night Vision plus Jump Boost I</li>
 * </ul>
 */
public final class NightGlowAura implements InsideEffectBehavior {
	public static final String ID = "night_glow_aura";
	private static final int DURATION_TICKS = 60;
	/**
	 * Night Vision blinks client-side when under 200 ticks remain, so it gets a longer
	 * duration than the other aura effects (still refreshed every 10 ticks).
	 */
	private static final int NIGHT_VISION_DURATION_TICKS = 260;

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

			player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, NIGHT_VISION_DURATION_TICKS, 0));
			if (variant == 1) {
				player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, DURATION_TICKS, 0));
			} else if (variant == 2) {
				player.addEffect(new MobEffectInstance(MobEffects.LUCK, DURATION_TICKS, 0));
			} else if (variant == 3) {
				player.addEffect(new MobEffectInstance(MobEffects.GLOWING, DURATION_TICKS, 0));
			} else if (variant == 4) {
				level.sendParticles(ParticleTypes.END_ROD, true, false, player.getX(), player.getY() + 2.4, player.getZ(), ctx.scaleCount(2, 6), 0.3, 0.1, 0.3, 0.0);
			} else if (variant == 5) {
				player.addEffect(new MobEffectInstance(MobEffects.SPEED, DURATION_TICKS, 0));
			} else if (variant == 6) {
				player.addEffect(new MobEffectInstance(MobEffects.JUMP_BOOST, DURATION_TICKS, 0));
			}
		}
	}
}

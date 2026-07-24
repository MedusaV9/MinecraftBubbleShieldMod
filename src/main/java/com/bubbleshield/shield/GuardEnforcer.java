package com.bubbleshield.shield;

import com.bubbleshield.effect.EffectRegistry;
import com.bubbleshield.effect.GuardStyle;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Applies the shield effect's {@link GuardStyle} (boundary retaliation) to a player
 * the barrier just expelled. Only the ServerLevel-aware call sites invoke this;
 * {@link ShieldLogic#applyPlayerBarrier} itself only touches the level through the
 * player's own {@code level()} (Y clamp + collision-free spot probing).
 */
public final class GuardEnforcer {
	/** Extra outward horizontal delta the GUST style adds on top of the plain pushback. */
	public static final double GUST_EXTRA_PUSH = 0.8;

	private GuardEnforcer() {
	}

	/**
	 * Retaliates against {@code pushedPlayer}, who was just expelled from the shield
	 * centered at {@code center}, according to the current effect's guard style.
	 */
	public static void apply(ServerLevel level, Vec3 center, ShieldState state, Player pushedPlayer) {
		GuardStyle guard = EffectRegistry.get(state.effectId).guard();
		switch (guard) {
			case GUST -> {
				// Fling the intruder further outward (they were just teleported to the
				// boundary with cleared momentum, so this is the net launch velocity).
				Vec3 away = pushedPlayer.position().subtract(center);
				Vec3 horizontal = new Vec3(away.x, 0.0, away.z);
				horizontal = horizontal.lengthSqr() < 1.0E-6 ? new Vec3(1.0, 0.0, 0.0) : horizontal.normalize();
				pushedPlayer.setDeltaMovement(pushedPlayer.getDeltaMovement().add(horizontal.scale(GUST_EXTRA_PUSH)));
				pushedPlayer.hurtMarked = true;
				level.sendParticles(ParticleTypes.GUST, true, false,
						pushedPlayer.getX(), pushedPlayer.getY() + 1.0, pushedPlayer.getZ(), 2, 0.3, 0.3, 0.3, 0.0);
			}
			case SLOW -> pushedPlayer.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 100, 2));
			case BLIND -> pushedPlayer.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0));
			case DARK -> pushedPlayer.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 100, 0));
			case GLOW -> pushedPlayer.addEffect(new MobEffectInstance(MobEffects.GLOWING, 200, 0));
			case STING -> {
				pushedPlayer.hurtServer(level, level.damageSources().magic(), 1.0F);
				level.sendParticles(ParticleTypes.CRIT, true, false,
						pushedPlayer.getX(), pushedPlayer.getY() + 1.0, pushedPlayer.getZ(), 12, 0.3, 0.3, 0.3, 0.1);
			}
			case NONE -> {
			}
		}
	}
}

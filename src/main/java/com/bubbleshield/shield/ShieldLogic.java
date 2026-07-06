package com.bubbleshield.shield;

import java.util.UUID;

import com.bubbleshield.effect.ContextModifier;
import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.ContextProfile;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.EffectRegistry;
import com.bubbleshield.effect.InsideEffectBehavior;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

/**
 * Server-authoritative shield behaviour: fuel drain, projectile interception,
 * player barrier and shield break/cooldown handling.
 */
public final class ShieldLogic {
	public static final float MIN_RADIUS = 4.0F;
	/** Absorb damage for arrows (and unclassified projectiles): the pre-existing behaviour. */
	public static final float PROJECTILE_DAMAGE = 5.0F;
	/** Tridents are too heavy to absorb; they are reverse-deflected instead. */
	public static final float TRIDENT_DAMAGE = 4.0F;
	/** Fireballs/wither skulls/wind charges are reverse-deflected (no explosion inside). */
	public static final float HURTING_PROJECTILE_DAMAGE = 8.0F;
	/** Thrown items (snowballs, potions, ender pearls) and shulker bullets fizzle out. */
	public static final float THROWN_DAMAGE = 2.0F;
	/** 30 minutes. */
	public static final long BREAK_COOLDOWN_TICKS = 36000L;
	public static final int TICKS_PER_FUEL_SECOND = 20;
	public static final double PUSHBACK_MARGIN = 0.75;
	/** A tier-1+ shield regenerates once every 2 seconds while active and fueled. */
	public static final int REGEN_PERIOD_TICKS = 40;
	public static final float TIER_1_REGEN_PER_PULSE = 1.0F;
	public static final float TIER_2_REGEN_PER_PULSE = 2.5F;

	private ShieldLogic() {
	}

	/**
	 * @return the current shield radius; shrinks with health but never below {@link #MIN_RADIUS} while active.
	 */
	public static float currentRadius(ShieldState state) {
		if (!state.active || state.maxHealth <= 0.0F) {
			return 0.0F;
		}

		return Math.max(MIN_RADIUS, state.targetRadius * state.health / state.maxHealth);
	}

	public static boolean canActivate(ShieldState state, long gameTime) {
		return state.fuelSeconds > 0 && gameTime >= state.cooldownUntil;
	}

	/**
	 * Pure barrier decision: should this shield block the given player/projectile owner?
	 *
	 * @return true when the subject is neither the owner nor whitelisted by name (case-insensitive) or UUID.
	 */
	public static boolean shouldBlock(ShieldState state, @Nullable String name, @Nullable UUID uuid, boolean isOwner) {
		if (isOwner) {
			return false;
		}

		if (uuid != null && (uuid.equals(state.ownerUuid) || state.whitelistUuids.contains(uuid))) {
			return false;
		}

		if (name != null) {
			for (String whitelisted : state.whitelistNames) {
				if (whitelisted.equalsIgnoreCase(name)) {
					return false;
				}
			}
		}

		return true;
	}

	/**
	 * Applies damage to the shield using the default break cooldown (tier 0/1).
	 *
	 * @return true if the shield broke.
	 */
	public static boolean applyDamage(ShieldState state, float amount, long gameTime) {
		return applyDamage(state, amount, gameTime, BREAK_COOLDOWN_TICKS);
	}

	/**
	 * Applies damage to the shield. When health is depleted the shield breaks: it deactivates,
	 * health is restored for the next activation and the given cooldown starts.
	 *
	 * @param cooldownTicks break cooldown to apply; see {@link #breakCooldownTicks(int)}.
	 * @return true if the shield broke.
	 */
	public static boolean applyDamage(ShieldState state, float amount, long gameTime, long cooldownTicks) {
		state.health -= amount;
		if (state.health <= 0.0F) {
			state.active = false;
			state.health = state.maxHealth;
			state.cooldownUntil = gameTime + cooldownTicks;
			return true;
		}

		return false;
	}

	/** @return the break cooldown for the given shield tier; tier 2 halves the default. */
	public static long breakCooldownTicks(int tier) {
		return tier >= 2 ? BREAK_COOLDOWN_TICKS / 2 : BREAK_COOLDOWN_TICKS;
	}

	/**
	 * Runs one server tick of shield logic for the projector at {@code pos}.
	 *
	 * @param tier the projector's upgrade-core tier (0..2); drives regeneration and break cooldown.
	 * @return true if the shield state changed and should be saved/synced.
	 */
	public static boolean serverTick(ServerLevel level, BlockPos pos, ShieldState state, int tier) {
		if (!state.active) {
			return false;
		}

		boolean changed = false;
		long gameTime = level.getGameTime();

		// Active shield consumes 1 fuel-second per 20 ticks.
		if (gameTime % TICKS_PER_FUEL_SECOND == 0) {
			state.fuelSeconds--;
			changed = true;
			if (state.fuelSeconds <= 0) {
				state.fuelSeconds = 0;
				state.active = false;
				return true;
			}
		}

		// Fueled regeneration: tier-1+ shields heal every 2 seconds, each pulse
		// burning one extra fuel-second on top of the runtime drain.
		if (tier >= 1 && state.health < state.maxHealth && state.fuelSeconds > 0 && gameTime % REGEN_PERIOD_TICKS == 0) {
			state.health = Math.min(state.maxHealth, state.health + (tier == 1 ? TIER_1_REGEN_PER_PULSE : TIER_2_REGEN_PER_PULSE));
			state.fuelSeconds = Math.max(0, state.fuelSeconds - 1);
			changed = true;
			if (state.fuelSeconds <= 0) {
				state.active = false;
				return true;
			}
		}

		Vec3 center = Vec3.atCenterOf(pos);
		double radius = currentRadius(state);
		double areaSize = 2.0 * radius + 8.0;
		AABB area = AABB.ofSize(center, areaSize, areaSize, areaSize);

		if (interceptProjectiles(level, pos, center, radius, state, area, breakCooldownTicks(tier))) {
			changed = true;
		}

		if (state.active) {
			// Recompute: interceptions may have shrunk the shield.
			double barrierRadius = currentRadius(state);
			for (Player player : level.getEntitiesOfClass(Player.class, area)) {
				if (applyPlayerBarrier(center, barrierRadius, state, player)) {
					GuardEnforcer.apply(level, center, state, player);
					changed = true;
				}
			}

			tickInsideEffect(level, center, currentRadius(state), state, gameTime);
		}

		return changed;
	}

	/**
	 * Runs the selected effect's ambient inside behaviour (particles, auras, sounds)
	 * and its looping ambient sound, modulated by the effect's context profile.
	 */
	private static void tickInsideEffect(ServerLevel level, Vec3 center, float radius, ShieldState state, long gameTime) {
		EffectDefinition def = EffectRegistry.get(state.effectId);
		if (def == null) {
			return;
		}

		ContextState ctx = computeContext(level, center, radius, state, def);
		InsideEffectBehavior behavior = InsideEffectBehavior.get(def.insideBehaviorId());
		if (behavior != null) {
			behavior.tick(level, center, radius, def, gameTime, ctx);
		}

		if (ctx.extraSparks() && gameTime % 10L == 0L) {
			// STORM_CHARGED while raining: a small electric sprinkle across the interior.
			level.sendParticles(ParticleTypes.ELECTRIC_SPARK, true, false,
					center.x, center.y + radius * 0.4, center.z, 8, radius * 0.45, radius * 0.3, radius * 0.45, 0.02);
		}

		playAmbientSound(level, center, radius, def, gameTime);
	}

	/**
	 * Gathers the context inputs for {@link ContextModifier#compute}: day/night and rain
	 * from the level, the shield health fraction from the state, and (only when the
	 * profile needs it) a count of players currently inside the shield.
	 */
	private static ContextState computeContext(ServerLevel level, Vec3 center, float radius, ShieldState state, EffectDefinition def) {
		float healthFrac = state.maxHealth > 0.0F ? state.health / state.maxHealth : 0.0F;
		int playersInside = 0;
		if (def.context() == ContextProfile.CROWD_SCALE) {
			AABB box = AABB.ofSize(center, radius * 2.0, radius * 2.0, radius * 2.0);
			for (Player player : level.getEntitiesOfClass(Player.class, box)) {
				if (ShieldGeometry.isInside(state.shape, center, radius, player.position())) {
					playersInside++;
				}
			}
		}

		return ContextModifier.compute(def.context(), level.isDarkOutside(), level.isRaining(), playersInside, healthFrac);
	}

	/**
	 * Plays the effect's ambient sound at the projector center every
	 * {@code ambientPeriodTicks}. Volume scales with the radius (volume &gt; 1 extends
	 * the audible range ~16 * volume blocks, same trick as the heartbeat behaviour).
	 */
	private static void playAmbientSound(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime) {
		if (gameTime % def.ambientPeriodTicks() != 0L) {
			return;
		}

		SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getValue(Identifier.parse("minecraft:" + def.ambientSoundId()));
		if (sound == null) {
			return;
		}

		float volume = Mth.clamp(radius / 12.0F, 0.6F, 8.0F);
		level.playSound(null, center.x, center.y, center.z, sound, SoundSource.AMBIENT, volume, def.ambientPitch());
	}

	private static boolean interceptProjectiles(ServerLevel level, BlockPos pos, Vec3 center, double radius, ShieldState state, AABB area, long breakCooldownTicks) {
		boolean changed = false;

		for (Projectile projectile : level.getEntitiesOfClass(Projectile.class, area)) {
			if (!state.active) {
				break;
			}

			Vec3 prev = new Vec3(projectile.xo, projectile.yo, projectile.zo);
			Vec3 current = projectile.position();
			// Only inbound boundary crossings trigger; a projectile that was deflected
			// back out (both positions inside, then inside->outside) is never re-hit.
			if (!ShieldGeometry.crossedInto(state.shape, center, radius, prev, current)) {
				continue;
			}

			Entity owner = projectile.getOwner();
			if (owner != null && !shouldBlock(state, ownerName(owner), owner.getUUID(), false)) {
				continue;
			}

			// Type-specific interaction. ThrownTrident extends AbstractArrow, so it must
			// be matched first; everything unclassified keeps the legacy absorb behaviour.
			float damage;
			if (projectile instanceof ThrownTrident) {
				projectile.deflect(ProjectileDeflection.REVERSE, null, null, false);
				damage = TRIDENT_DAMAGE;
			} else if (projectile instanceof AbstractHurtingProjectile) {
				projectile.deflect(ProjectileDeflection.REVERSE, null, null, false);
				damage = HURTING_PROJECTILE_DAMAGE;
			} else if (projectile instanceof ThrowableItemProjectile || projectile instanceof ShulkerBullet) {
				projectile.discard();
				damage = THROWN_DAMAGE;
			} else {
				// AbstractArrow (non-trident) and any other projectile: absorb.
				projectile.discard();
				damage = PROJECTILE_DAMAGE;
			}

			boolean broke = applyDamage(state, damage, level.getGameTime(), breakCooldownTicks);
			changed = true;

			level.sendParticles(ParticleTypes.CRIT, current.x, current.y, current.z, 20, 0.3, 0.3, 0.3, 0.1);
			level.playSound(null, pos, SoundEvents.SHIELD_BLOCK.value(), SoundSource.BLOCKS, 1.0F, 1.0F);
			if (broke) {
				level.playSound(null, pos, SoundEvents.SHIELD_BREAK.value(), SoundSource.BLOCKS, 1.0F, 1.0F);
			}
		}

		return changed;
	}

	/**
	 * Pushes any non-whitelisted, non-owner player currently inside the shield back out,
	 * regardless of how they got in (walking, teleporting, riding, or pre-existing).
	 * Leaving is always allowed. Spectators are ignored.
	 *
	 * @return true if the player was pushed back.
	 */
	public static boolean applyPlayerBarrier(Vec3 center, double radius, ShieldState state, Player player) {
		if (player.isSpectator()) {
			return false;
		}

		UUID uuid = player.getUUID();
		boolean isOwner = uuid.equals(state.ownerUuid);
		if (!shouldBlock(state, player.getGameProfile().name(), uuid, isOwner)) {
			return false;
		}

		Vec3 current = player.position();
		if (!ShieldGeometry.isInside(state.shape, center, radius, current)) {
			return false;
		}

		// Push mostly horizontally so players are not flung into the sky or the ground.
		Vec3 direction = current.subtract(center);
		Vec3 horizontal = new Vec3(direction.x, 0.0, direction.z);
		horizontal = horizontal.lengthSqr() < 1.0E-6 ? new Vec3(1.0, 0.0, 0.0) : horizontal.normalize();
		Vec3 target = center.add(horizontal.scale(radius + PUSHBACK_MARGIN));

		// Riding players are moved via their root vehicle so the whole stack leaves the shield.
		Entity mover = player.getRootVehicle();
		double targetY = Mth.clamp(mover.getY(), player.level().getMinY(), player.level().getMaxY());
		mover.teleportTo(target.x, targetY, target.z);
		mover.setDeltaMovement(Vec3.ZERO);
		player.setDeltaMovement(Vec3.ZERO);
		return true;
	}

	/**
	 * Runs one barrier pass over all players near the projector, expelling any
	 * non-whitelisted player standing inside. Used right after activation.
	 *
	 * @return true if at least one player was pushed out.
	 */
	public static boolean expelBlockedPlayers(ServerLevel level, BlockPos pos, ShieldState state) {
		Vec3 center = Vec3.atCenterOf(pos);
		double radius = currentRadius(state);
		double areaSize = 2.0 * radius + 8.0;
		AABB area = AABB.ofSize(center, areaSize, areaSize, areaSize);

		boolean pushed = false;
		for (Player player : level.getEntitiesOfClass(Player.class, area)) {
			if (applyPlayerBarrier(center, radius, state, player)) {
				GuardEnforcer.apply(level, center, state, player);
				pushed = true;
			}
		}

		return pushed;
	}

	private static @Nullable String ownerName(Entity owner) {
		return owner instanceof Player player ? player.getGameProfile().name() : null;
	}
}

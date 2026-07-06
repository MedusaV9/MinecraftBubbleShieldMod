package com.bubbleshield.shield;

import java.util.UUID;

import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.EffectRegistry;
import com.bubbleshield.effect.InsideEffectBehavior;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

/**
 * Server-authoritative shield behaviour: fuel drain, projectile interception,
 * player barrier and shield break/cooldown handling.
 */
public final class ShieldLogic {
	public static final float MIN_RADIUS = 4.0F;
	public static final float PROJECTILE_DAMAGE = 5.0F;
	/** 30 minutes. */
	public static final long BREAK_COOLDOWN_TICKS = 36000L;
	public static final int TICKS_PER_FUEL_SECOND = 20;
	public static final double PUSHBACK_MARGIN = 0.75;

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
	 * Applies damage to the shield. When health is depleted the shield breaks: it deactivates,
	 * health is restored for the next activation and a cooldown starts.
	 *
	 * @return true if the shield broke.
	 */
	public static boolean applyDamage(ShieldState state, float amount, long gameTime) {
		state.health -= amount;
		if (state.health <= 0.0F) {
			state.active = false;
			state.health = state.maxHealth;
			state.cooldownUntil = gameTime + BREAK_COOLDOWN_TICKS;
			return true;
		}

		return false;
	}

	/**
	 * Runs one server tick of shield logic for the projector at {@code pos}.
	 *
	 * @return true if the shield state changed and should be saved/synced.
	 */
	public static boolean serverTick(ServerLevel level, BlockPos pos, ShieldState state) {
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

		Vec3 center = Vec3.atCenterOf(pos);
		double radius = currentRadius(state);
		double areaSize = 2.0 * radius + 8.0;
		AABB area = AABB.ofSize(center, areaSize, areaSize, areaSize);

		if (interceptProjectiles(level, pos, center, radius, state, area)) {
			changed = true;
		}

		if (state.active) {
			// Recompute: interceptions may have shrunk the shield.
			double barrierRadius = currentRadius(state);
			for (Player player : level.getEntitiesOfClass(Player.class, area)) {
				if (applyPlayerBarrier(center, barrierRadius, state, player)) {
					changed = true;
				}
			}

			tickInsideEffect(level, center, currentRadius(state), state.effectId, gameTime);
		}

		return changed;
	}

	/**
	 * Runs the selected effect's ambient inside behaviour (particles, auras, sounds).
	 */
	private static void tickInsideEffect(ServerLevel level, Vec3 center, float radius, int effectId, long gameTime) {
		EffectDefinition def = EffectRegistry.get(effectId);
		if (def == null) {
			return;
		}

		// TODO(S2): dispatch def.insideBehaviorId() directly once all 25 behaviors exist.
		InsideEffectBehavior behavior = InsideEffectBehavior.get(EffectRegistry.resolvedBehaviorId(def));
		if (behavior == null) {
			return;
		}

		behavior.tick(level, center, radius, def, gameTime);
	}

	private static boolean interceptProjectiles(ServerLevel level, BlockPos pos, Vec3 center, double radius, ShieldState state, AABB area) {
		boolean changed = false;

		for (Projectile projectile : level.getEntitiesOfClass(Projectile.class, area)) {
			if (!state.active) {
				break;
			}

			Vec3 prev = new Vec3(projectile.xo, projectile.yo, projectile.zo);
			Vec3 current = projectile.position();
			double prevDist = prev.distanceTo(center);
			double curDist = current.distanceTo(center);
			if (!(prevDist > radius && curDist <= radius)) {
				continue;
			}

			Entity owner = projectile.getOwner();
			if (owner != null && !shouldBlock(state, ownerName(owner), owner.getUUID(), false)) {
				continue;
			}

			projectile.discard();
			boolean broke = applyDamage(state, PROJECTILE_DAMAGE, level.getGameTime());
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
		if (current.distanceTo(center) > radius) {
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
				pushed = true;
			}
		}

		return pushed;
	}

	private static @Nullable String ownerName(Entity owner) {
		return owner instanceof Player player ? player.getGameProfile().name() : null;
	}
}

package com.bubbleshield.net;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.shield.ShieldLogic;
import com.bubbleshield.shield.ShieldState;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.StringUtil;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

/**
 * Server-side networking: validates C2S shield requests and replicates shield state to clients.
 */
public final class ServerNet {
	/** A request is only honoured when the sender stands within this distance of the projector. */
	public static final double MAX_INTERACT_DISTANCE = 8.0;
	public static final int MIN_DIAMETER = 8;
	public static final int MAX_DIAMETER = 200;
	public static final int MIN_EFFECT_ID = 0;
	public static final int MAX_EFFECT_ID = 49;
	/** Hard cap on whitelist entries to keep payloads and NBT bounded. */
	public static final int MAX_WHITELIST_SIZE = 64;

	/**
	 * Loaded shield projectors per level, used to sync existing shields to joining players.
	 * Only touched from the server thread.
	 */
	private static final Map<ServerLevel, Set<BubbleShieldBlockEntity>> LOADED_SHIELDS = new IdentityHashMap<>();

	private ServerNet() {
	}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(ShieldPayloads.SetSettingsC2S.TYPE, (payload, ctx) -> {
			BubbleShieldBlockEntity shield = validatedShield(ctx.player(), payload.pos());
			if (shield == null || !isOwner(ctx.player(), shield)) {
				return;
			}

			int diameter = Mth.clamp(payload.diameter(), MIN_DIAMETER, MAX_DIAMETER);
			int effectId = Mth.clamp(payload.effectId(), MIN_EFFECT_ID, MAX_EFFECT_ID);
			shield.setSettings(diameter, effectId);
		});

		ServerPlayNetworking.registerGlobalReceiver(ShieldPayloads.WhitelistModifyC2S.TYPE, (payload, ctx) -> {
			BubbleShieldBlockEntity shield = validatedShield(ctx.player(), payload.pos());
			if (shield == null || !isOwner(ctx.player(), shield)) {
				return;
			}

			String name = payload.name().trim();
			if (name.isEmpty() || !StringUtil.isValidPlayerName(name)) {
				return;
			}

			if (payload.add()) {
				if (shield.getShieldState().whitelistNames.size() >= MAX_WHITELIST_SIZE) {
					return;
				}

				shield.whitelistAdd(ctx.server(), name);
			} else {
				shield.whitelistRemove(ctx.server(), name);
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(ShieldPayloads.SetActiveC2S.TYPE, (payload, ctx) -> {
			BubbleShieldBlockEntity shield = validatedShield(ctx.player(), payload.pos());
			if (shield == null || !isOwner(ctx.player(), shield)) {
				return;
			}

			shield.setActive(payload.active());
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			backfillWhitelistUuid(handler.player);

			ServerLevel level = handler.player.level();
			Set<BubbleShieldBlockEntity> shields = LOADED_SHIELDS.get(level);
			if (shields == null) {
				return;
			}

			for (BubbleShieldBlockEntity shield : shields) {
				if (shield.getShieldState().active) {
					ServerPlayNetworking.send(handler.player, syncPayload(shield));
				}
			}
		});

		// LOADED_SHIELDS holds ServerLevel keys; clear on unload/stop so integrated-server
		// restarts do not leak levels or stale block entities.
		ServerLevelEvents.UNLOAD.register((server, level) -> LOADED_SHIELDS.remove(level));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> LOADED_SHIELDS.clear());
	}

	/**
	 * Resolves name-only whitelist entries for a joining player: any loaded shield whose
	 * whitelist contains the player's name (case-insensitively) but not their UUID gets
	 * the UUID backfilled, so the barrier and client edge-dissolve recognise them.
	 */
	private static void backfillWhitelistUuid(ServerPlayer player) {
		String name = player.getGameProfile().name();
		UUID uuid = player.getUUID();
		for (Set<BubbleShieldBlockEntity> shields : LOADED_SHIELDS.values()) {
			for (BubbleShieldBlockEntity shield : shields) {
				ShieldState state = shield.getShieldState();
				if (!state.whitelistUuids.contains(uuid) && containsIgnoreCase(state.whitelistNames, name)) {
					state.whitelistUuids.add(uuid);
					shield.markUpdated();
				}
			}
		}
	}

	private static boolean containsIgnoreCase(Set<String> names, String name) {
		for (String existing : names) {
			if (existing.equalsIgnoreCase(name)) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Broadcasts the current state of the given shield to every player in its level.
	 * Called from the block entity whenever the shield state changes.
	 */
	public static void syncShield(BubbleShieldBlockEntity shield) {
		if (!(shield.getLevel() instanceof ServerLevel level)) {
			return;
		}

		ShieldPayloads.ShieldSyncS2C payload = syncPayload(shield);
		for (ServerPlayer player : PlayerLookup.level(level)) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	/** Broadcasts to every player in the shield's level that the shield is gone. */
	public static void broadcastRemove(BubbleShieldBlockEntity shield) {
		if (!(shield.getLevel() instanceof ServerLevel level)) {
			return;
		}

		ShieldPayloads.ShieldRemoveS2C payload = new ShieldPayloads.ShieldRemoveS2C(shield.getBlockPos());
		for (ServerPlayer player : PlayerLookup.level(level)) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	/** Registers a loaded shield block entity. Server thread only. */
	public static void trackShield(BubbleShieldBlockEntity shield) {
		if (shield.getLevel() instanceof ServerLevel level) {
			LOADED_SHIELDS.computeIfAbsent(level, l -> Collections.newSetFromMap(new IdentityHashMap<>())).add(shield);
		}
	}

	/** Unregisters a shield block entity that is being removed. Server thread only. */
	public static void untrackShield(BubbleShieldBlockEntity shield) {
		if (shield.getLevel() instanceof ServerLevel level) {
			Set<BubbleShieldBlockEntity> shields = LOADED_SHIELDS.get(level);
			if (shields != null) {
				shields.remove(shield);
				if (shields.isEmpty()) {
					LOADED_SHIELDS.remove(level);
				}
			}
		}
	}

	private static ShieldPayloads.ShieldSyncS2C syncPayload(BubbleShieldBlockEntity shield) {
		ShieldState state = shield.getShieldState();
		long gameTime = shield.getLevel() != null ? shield.getLevel().getGameTime() : 0L;
		long cooldownTicks = Math.max(0L, state.cooldownUntil - gameTime);
		float healthFrac = state.maxHealth > 0.0F ? state.health / state.maxHealth : 0.0F;
		return new ShieldPayloads.ShieldSyncS2C(
			shield.getBlockPos(),
			state.active,
			state.effectId,
			state.targetRadius,
			ShieldLogic.currentRadius(state),
			healthFrac,
			List.copyOf(state.whitelistUuids),
			List.copyOf(state.whitelistNames),
			(int) (cooldownTicks / ShieldLogic.TICKS_PER_FUEL_SECOND),
			Optional.ofNullable(state.ownerUuid)
		);
	}

	/**
	 * @return the shield block entity at {@code pos} if the chunk is already loaded and the
	 * player is close enough, otherwise null. Distance and load checks run BEFORE the
	 * block entity lookup so a client-supplied position can never force a chunk load.
	 */
	private static @Nullable BubbleShieldBlockEntity validatedShield(ServerPlayer player, BlockPos pos) {
		if (player.position().distanceTo(Vec3.atCenterOf(pos)) > MAX_INTERACT_DISTANCE) {
			return null;
		}

		if (!player.level().isLoaded(pos)) {
			return null;
		}

		if (!(player.level().getBlockEntity(pos) instanceof BubbleShieldBlockEntity shield)) {
			return null;
		}

		return shield;
	}

	/**
	 * Mutating requests are owner-only. A shield without a recorded owner (e.g. placed
	 * before this rule existed) is claimed by the first interacting player; afterwards
	 * only an exact UUID match is accepted.
	 */
	private static boolean isOwner(ServerPlayer player, BubbleShieldBlockEntity shield) {
		UUID owner = shield.getShieldState().ownerUuid;
		if (owner == null) {
			shield.setOwner(player);
			return true;
		}

		return owner.equals(player.getUUID());
	}
}

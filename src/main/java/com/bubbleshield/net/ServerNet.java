package com.bubbleshield.net;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.shield.ShieldLogic;
import com.bubbleshield.shield.ShieldState;

import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
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
			if (shield == null) {
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

			if (payload.add()) {
				shield.whitelistAdd(ctx.server(), payload.name());
			} else {
				shield.whitelistRemove(payload.name());
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(ShieldPayloads.SetActiveC2S.TYPE, (payload, ctx) -> {
			BubbleShieldBlockEntity shield = validatedShield(ctx.player(), payload.pos());
			if (shield == null) {
				return;
			}

			shield.setActive(payload.active());
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
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
			(int) (cooldownTicks / ShieldLogic.TICKS_PER_FUEL_SECOND)
		);
	}

	/**
	 * @return the shield block entity at {@code pos} if it exists and the player is close enough, otherwise null.
	 */
	private static @Nullable BubbleShieldBlockEntity validatedShield(ServerPlayer player, BlockPos pos) {
		if (!(player.level().getBlockEntity(pos) instanceof BubbleShieldBlockEntity shield)) {
			return null;
		}

		if (player.position().distanceTo(Vec3.atCenterOf(pos)) > MAX_INTERACT_DISTANCE) {
			return null;
		}

		return shield;
	}

	/** Whitelist edits are owner-only; a shield without a recorded owner accepts edits from anyone nearby. */
	private static boolean isOwner(ServerPlayer player, BubbleShieldBlockEntity shield) {
		UUID owner = shield.getShieldState().ownerUuid;
		return owner == null || owner.equals(player.getUUID());
	}
}

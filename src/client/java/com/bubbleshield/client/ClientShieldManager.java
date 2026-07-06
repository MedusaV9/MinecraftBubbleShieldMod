package com.bubbleshield.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.bubbleshield.net.ShieldPayloads;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import org.jspecify.annotations.Nullable;

/**
 * Client-side replica of all shields synced from the server, keyed by dimension + projector
 * position so shields at equal coordinates in different dimensions never collide.
 *
 * <p>Consumers should go through {@link #currentDimensionShields()} / {@link #get(BlockPos)}
 * so shields synced from another dimension are never rendered or applied locally.
 */
public final class ClientShieldManager {
	/** Immutable client-side snapshot of one shield, mirroring {@link ShieldPayloads.ShieldSyncS2C}. */
	public record ClientShield(
		BlockPos pos,
		ResourceKey<Level> dimension,
		ShieldPayloads.ShieldVisual visual,
		List<UUID> whitelist,
		List<String> whitelistNames,
		int cooldownSeconds,
		Optional<UUID> ownerUuid
	) {
		// Flat accessors kept for renderer/screen-fx consumers; they delegate into the
		// nested visual record synced from the server.
		public boolean active() {
			return this.visual.active();
		}

		public int effectId() {
			return this.visual.effectId();
		}

		public float targetRadius() {
			return this.visual.targetRadius();
		}

		public float currentRadius() {
			return this.visual.currentRadius();
		}

		public float healthFrac() {
			return this.visual.healthFrac();
		}

		public int tier() {
			return this.visual.tier();
		}
	}

	private static final Map<GlobalPos, ClientShield> SHIELDS = new HashMap<>();

	private ClientShieldManager() {
	}

	public static Map<GlobalPos, ClientShield> shields() {
		return SHIELDS;
	}

	/** The shield at {@code pos} in the local player's current dimension, or {@code null}. */
	public static @Nullable ClientShield get(BlockPos pos) {
		ClientLevel level = Minecraft.getInstance().level;
		return level == null ? null : SHIELDS.get(new GlobalPos(level.dimension(), pos));
	}

	/** Shields in the local player's current dimension; other dimensions are filtered out. */
	public static List<ClientShield> currentDimensionShields() {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null || SHIELDS.isEmpty()) {
			return List.of();
		}

		ResourceKey<Level> dimension = level.dimension();
		List<ClientShield> shields = new ArrayList<>();
		for (ClientShield shield : SHIELDS.values()) {
			if (shield.dimension().equals(dimension)) {
				shields.add(shield);
			}
		}

		return shields;
	}

	public static void register() {
		// Fabric invokes play payload handlers on the render thread, but marshal via
		// client.execute() anyway; it runs inline when already on the client thread.
		ClientPlayNetworking.registerGlobalReceiver(ShieldPayloads.ShieldSyncS2C.TYPE, (payload, context) -> context.client().execute(() ->
			SHIELDS.put(new GlobalPos(payload.dimension(), payload.pos()), new ClientShield(
				payload.pos(),
				payload.dimension(),
				payload.visual(),
				payload.whitelist(),
				payload.whitelistNames(),
				payload.cooldownSeconds(),
				payload.ownerUuid()
			))
		));

		ClientPlayNetworking.registerGlobalReceiver(ShieldPayloads.ShieldRemoveS2C.TYPE, (payload, context) -> context.client().execute(() ->
			SHIELDS.remove(new GlobalPos(payload.dimension(), payload.pos()))
		));

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(SHIELDS::clear));

		// A new ClientLevel (dimension change, respawn) invalidates the replica; the server
		// re-sends the new level's shields via AFTER_PLAYER_CHANGE_LEVEL / AFTER_RESPAWN.
		ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> SHIELDS.clear());
	}
}

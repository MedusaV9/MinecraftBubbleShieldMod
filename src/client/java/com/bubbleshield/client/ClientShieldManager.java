package com.bubbleshield.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bubbleshield.net.ShieldPayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.core.BlockPos;

/**
 * Client-side replica of all shields synced from the server, keyed by projector position.
 */
public final class ClientShieldManager {
	/** Immutable client-side snapshot of one shield, mirroring {@link ShieldPayloads.ShieldSyncS2C}. */
	public record ClientShield(
		BlockPos pos,
		boolean active,
		int effectId,
		float targetRadius,
		float currentRadius,
		float healthFrac,
		List<UUID> whitelist,
		int cooldownSeconds
	) {
	}

	private static final Map<BlockPos, ClientShield> SHIELDS = new HashMap<>();

	private ClientShieldManager() {
	}

	public static Map<BlockPos, ClientShield> shields() {
		return SHIELDS;
	}

	public static void register() {
		// Fabric invokes play payload handlers on the render thread, but marshal via
		// client.execute() anyway; it runs inline when already on the client thread.
		ClientPlayNetworking.registerGlobalReceiver(ShieldPayloads.ShieldSyncS2C.TYPE, (payload, context) -> context.client().execute(() ->
			SHIELDS.put(payload.pos(), new ClientShield(
				payload.pos(),
				payload.active(),
				payload.effectId(),
				payload.targetRadius(),
				payload.currentRadius(),
				payload.healthFrac(),
				payload.whitelist(),
				payload.cooldownSeconds()
			))
		));

		ClientPlayNetworking.registerGlobalReceiver(ShieldPayloads.ShieldRemoveS2C.TYPE, (payload, context) -> context.client().execute(() ->
			SHIELDS.remove(payload.pos())
		));

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(SHIELDS::clear));
	}
}

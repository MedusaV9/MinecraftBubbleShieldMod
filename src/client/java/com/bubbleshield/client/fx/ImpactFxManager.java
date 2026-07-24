package com.bubbleshield.client.fx;

import com.bubbleshield.net.ShieldPayloads;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.core.GlobalPos;

/**
 * Client entry point of the impact-feedback pipeline: receives
 * {@link ShieldPayloads.ImpactBatchS2C} batches, fans the entries into the
 * {@link ImpactTracker} store (consumed by the flash now; WP-Dyn's mesh
 * deformation later) and reconciles server-confirmed CONTACT presses into the
 * {@link ContactFlash} predictor. Also owns the shared END_CLIENT_TICK hook
 * driving tracker pruning, flash prediction and the {@link ProximityHum}
 * watcher, plus the disconnect/level-change resets (mirroring
 * {@code ClientShieldManager.register}'s lifecycle pattern).
 */
public final class ImpactFxManager {
	private ImpactFxManager() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(ShieldPayloads.ImpactBatchS2C.TYPE, (payload, context) -> context.client().execute(() -> {
			GlobalPos pos = new GlobalPos(payload.dimension(), payload.pos());
			for (ShieldPayloads.ImpactEntry entry : payload.entries()) {
				int kind = entry.kind() & 0xFF;
				if (kind >= ShieldPayloads.ImpactEntry.KIND_COUNT) {
					// Forward compatibility: a newer server's unknown kinds are skipped.
					continue;
				}

				ImpactTracker.addServerImpact(pos, entry.dir(), entry.strengthUnsigned() / 255.0F, kind);
				if (kind == ShieldPayloads.ImpactEntry.KIND_CONTACT) {
					ContactFlash.onServerContact(pos, entry.dir());
				}
			}
		}));

		ClientTickEvents.END_CLIENT_TICK.register(ImpactFxManager::endClientTick);

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(ImpactFxManager::resetAll));
		// A new ClientLevel (dimension change, respawn) invalidates all replicated
		// state; the tracker refills from fresh batches, the flash re-predicts.
		ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> resetAll());
	}

	private static void endClientTick(Minecraft mc) {
		ImpactTracker.endClientTick(mc);
		ContactFlash.tick(mc);
		ProximityHum.tick(mc);
	}

	private static void resetAll() {
		ImpactTracker.clear();
		ContactFlash.reset();
		ProximityHum.stop();
	}
}

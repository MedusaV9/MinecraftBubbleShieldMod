package com.bubbleshield.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.bubbleshield.client.fx.ImpactTracker;
import com.bubbleshield.net.ShieldPayloads;
import com.bubbleshield.shield.ShieldGeometry;
import com.bubbleshield.shield.ShieldShape;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

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
		Optional<UUID> ownerUuid,
		String customName
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

		/** The synced absolute max health; 0 means "unknown" (HUD falls back to tier-only). */
		public float maxHealth() {
			return this.visual.maxHealth();
		}

		public int tier() {
			return this.visual.tier();
		}

		/** The synced {@link com.bubbleshield.shield.ShieldShape} ordinal. */
		public int shape() {
			return this.visual.shape();
		}

		/**
		 * The synced owner-picked recolor: -1 = authored palette, otherwise an opaque
		 * ARGB (negative as a signed int — compare against -1, never with {@code >= 0}).
		 */
		public int colorOverride() {
			return this.visual.colorOverride();
		}

		/** The synced {@link com.bubbleshield.shield.BeamStyle} ordinal. */
		public int beamStyle() {
			return this.visual.beamStyle();
		}

		/**
		 * Client-side mirror of {@code ShieldLogic.shouldBlock} over the synced
		 * replica (owner, whitelist UUIDs, whitelist names — case-insensitive):
		 * true when this shield's barrier blocks the given player. Drives the
		 * contact-flash prediction; the server remains authoritative and its
		 * CONTACT batch entries reconcile any misprediction.
		 */
		public boolean blocks(UUID uuid, String name) {
			if (this.ownerUuid.isPresent() && this.ownerUuid.get().equals(uuid)) {
				return false;
			}

			if (this.whitelist.contains(uuid)) {
				return false;
			}

			for (String whitelisted : this.whitelistNames) {
				if (whitelisted.equalsIgnoreCase(name)) {
					return false;
				}
			}

			return true;
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

	/**
	 * The first active shield whose bubble contains the local player, or {@code null}.
	 * Containment is shape-aware ({@link ShieldGeometry#isInside} with the synced shape),
	 * so standing under a dome's open bottom does not count as "inside". Shared by the
	 * in-bubble screen effect and the HUD status element.
	 */
	public static @Nullable ClientShield findSurroundingShield(Minecraft mc) {
		if (mc.player == null) {
			return null;
		}

		Vec3 playerPos = mc.player.position();
		for (ClientShield shield : currentDimensionShields()) {
			if (!shield.active()) {
				continue;
			}

			Vec3 center = Vec3.atCenterOf(shield.pos());
			if (ShieldGeometry.isInside(ShieldShape.byOrdinal(shield.shape()), center, shield.currentRadius(), playerPos)) {
				return shield;
			}
		}

		return null;
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
				payload.ownerUuid(),
				payload.customName()
			))
		));

		ClientPlayNetworking.registerGlobalReceiver(ShieldPayloads.ShieldRemoveS2C.TYPE, (payload, context) -> context.client().execute(() -> {
			GlobalPos pos = new GlobalPos(payload.dimension(), payload.pos());
			SHIELDS.remove(pos);
			// Fabric allows one global receiver per payload type, so the impact
			// store's per-shield cleanup rides this receiver rather than its own.
			ImpactTracker.remove(pos);
		}));

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(SHIELDS::clear));

		// A new ClientLevel (dimension change, respawn) invalidates the replica; the server
		// re-sends the new level's shields via AFTER_PLAYER_CHANGE_LEVEL / AFTER_RESPAWN.
		ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> SHIELDS.clear());
	}
}

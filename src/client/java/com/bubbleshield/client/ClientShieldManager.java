package com.bubbleshield.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.bubbleshield.client.fx.ApertureTracker;
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
	/**
	 * WP-Evt break ghost: after a BREAK impact arrives, the renderer keeps drawing
	 * the (now inactive / radius-0) shield for up to this many client ticks at its
	 * last-known active radius, so the collapse omni-pulse is actually visible.
	 * Must stay at or below {@code ImpactTracker.TTL_TICKS} (40): the pulse
	 * animation reads the tracked BREAK entry.
	 */
	public static final int BREAK_GHOST_TICKS = 30;

	/** Immutable client-side snapshot of one shield, mirroring {@link ShieldPayloads.ShieldSyncS2C}. */
	public record ClientShield(
		BlockPos pos,
		ResourceKey<Level> dimension,
		ShieldPayloads.ShieldVisual visual,
		List<UUID> whitelist,
		List<String> whitelistNames,
		int cooldownSeconds,
		Optional<UUID> ownerUuid,
		String customName,
		/**
		 * Client bookkeeping (not on the wire): the last synced currentRadius
		 * observed while active — the pre-break size the break ghost renders at
		 * (the break's own sync already carries active=false / radius 0).
		 */
		float lastActiveRadius,
		/**
		 * Client bookkeeping (not on the wire): the {@code ImpactTracker} client
		 * tick the break ghost stays visible until; 0 = no ghost armed. Set by
		 * {@link #onBreakImpact} when a BREAK batch entry arrives.
		 */
		long breakGhostUntilTick
	) {
		/** Copy with the break ghost armed until {@code untilTick}; everything else unchanged. */
		ClientShield withBreakGhostUntil(long untilTick) {
			return new ClientShield(this.pos, this.dimension, this.visual, this.whitelist, this.whitelistNames,
					this.cooldownSeconds, this.ownerUuid, this.customName, this.lastActiveRadius, untilTick);
		}

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

	/**
	 * Arms the break ghost on the shield at {@code pos}: called by
	 * {@code ImpactFxManager} when an {@code ImpactBatchS2C} carries a BREAK entry.
	 * The server flushes the break's sync (active=false, radius 0) BEFORE the
	 * batch, so by the time the BREAK arrives the replica is already inactive —
	 * arming here (rather than on the active flip) is what makes the ghost
	 * reachable at all. {@code lastActiveRadius} was tracked by the sync receiver
	 * while the shield was still up.
	 */
	public static void onBreakImpact(GlobalPos pos) {
		ClientShield shield = SHIELDS.get(pos);
		if (shield != null) {
			SHIELDS.put(pos, shield.withBreakGhostUntil(ImpactTracker.currentTick() + BREAK_GHOST_TICKS));
		}
	}

	/**
	 * Prunes replicas of REMOVED projectors that were only retained for their
	 * break ghost, once the ghost expires. Called from
	 * {@code ImpactFxManager.endClientTick} (this class has no tick hook of its
	 * own, mirroring the shared-receiver pattern).
	 */
	public static void endClientTick() {
		if (REMOVED_GHOSTS.isEmpty()) {
			return;
		}

		long now = ImpactTracker.currentTick();
		REMOVED_GHOSTS.removeIf(pos -> {
			ClientShield shield = SHIELDS.get(pos);
			if (shield != null && shield.breakGhostUntilTick() > now) {
				return false;
			}

			SHIELDS.remove(pos);
			ImpactTracker.remove(pos);
			return true;
		});
	}

	/**
	 * Projectors removed by {@code ShieldRemoveS2C} whose replica is retained for
	 * an unexpired break ghost; swept by {@link #endClientTick}. Client thread only.
	 */
	private static final Set<GlobalPos> REMOVED_GHOSTS = new HashSet<>();

	public static void register() {
		// Fabric invokes play payload handlers on the render thread, but marshal via
		// client.execute() anyway; it runs inline when already on the client thread.
		ClientPlayNetworking.registerGlobalReceiver(ShieldPayloads.ShieldSyncS2C.TYPE, (payload, context) -> context.client().execute(() -> {
			GlobalPos globalPos = new GlobalPos(payload.dimension(), payload.pos());
			ClientShield previous = SHIELDS.get(globalPos);
			// Break-ghost bookkeeping: remember the radius while active, carry the
			// armed ghost window across later syncs of the downed shield.
			float lastActiveRadius = payload.visual().active() && payload.visual().currentRadius() > 0.0F
					? payload.visual().currentRadius()
					: previous != null ? previous.lastActiveRadius() : 0.0F;
			long breakGhostUntilTick = previous != null ? previous.breakGhostUntilTick() : 0L;
			// A fresh sync means the projector exists again; stop any pending
			// removed-ghost sweep from dropping the new replica.
			REMOVED_GHOSTS.remove(globalPos);
			SHIELDS.put(globalPos, new ClientShield(
				payload.pos(),
				payload.dimension(),
				payload.visual(),
				payload.whitelist(),
				payload.whitelistNames(),
				payload.cooldownSeconds(),
				payload.ownerUuid(),
				payload.customName(),
				lastActiveRadius,
				breakGhostUntilTick
			));
		}));

		ClientPlayNetworking.registerGlobalReceiver(ShieldPayloads.ShieldRemoveS2C.TYPE, (payload, context) -> context.client().execute(() -> {
			GlobalPos pos = new GlobalPos(payload.dimension(), payload.pos());
			// A projector broken mid-ghost keeps its replica (and BREAK impact
			// history) until the ghost expires; endClientTick sweeps it then.
			ClientShield shield = SHIELDS.get(pos);
			if (shield != null && shield.breakGhostUntilTick() > ImpactTracker.currentTick()) {
				REMOVED_GHOSTS.add(pos);
			} else {
				SHIELDS.remove(pos);
				// Fabric allows one global receiver per payload type, so the impact
				// and aperture stores' per-shield cleanup rides this receiver rather
				// than their own.
				ImpactTracker.remove(pos);
			}

			ApertureTracker.remove(pos);
		}));

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			SHIELDS.clear();
			REMOVED_GHOSTS.clear();
		}));

		// A new ClientLevel (dimension change, respawn) invalidates the replica; the server
		// re-sends the new level's shields via AFTER_PLAYER_CHANGE_LEVEL / AFTER_RESPAWN.
		ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> {
			SHIELDS.clear();
			REMOVED_GHOSTS.clear();
		});
	}
}

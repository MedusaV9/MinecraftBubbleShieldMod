package com.bubbleshield.client.fx;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import com.bubbleshield.client.ClientShieldManager;
import com.bubbleshield.client.ClientShieldManager.ClientShield;
import com.bubbleshield.net.ShieldPayloads;
import com.bubbleshield.shield.ShieldGeometry;
import com.bubbleshield.shield.ShieldShape;
import com.bubbleshield.shield.SurfaceWaveMath;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.GlobalPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side animator of the whitelisted-player wall apertures (WP-Dyn): per
 * shield and per pass-through-entitled player (owner or whitelisted) it keeps
 * one {@link Entry} whose hole radius eases toward fully open
 * ({@code SurfaceWaveMath.HOLE_R_MAX}) while the player is near the wall and
 * back to zero when they leave, with hysteresis so the hole never flickers at
 * the threshold. {@code ShieldRenderer} reads the animated radius each frame;
 * all state stepping happens here on END_CLIENT_TICK (dt 0.05 s, registered by
 * {@link ImpactFxManager}).
 *
 * <p><b>Passage detection:</b> an {@code isInside} flip (shape-aware,
 * {@link ShieldGeometry#isInside}) appends a client-local PASSAGE impact to the
 * {@link ImpactTracker} (zero-latency ripple; the server's own PASSAGE batch
 * entry arrives a tick or two later and superposes a second, clamped ripple —
 * a documented, deliberate redundancy) and rings the passage whoosh.
 *
 * <p><b>Sounds</b> (local, at the player's projected wall point, at most one
 * per {@link #SOUND_RATE_TICKS} ticks per entry): the aperture opening past
 * {@value #SOUND_CROSSING_RADIUS} rings AMETHYST_CLUSTER_PLACE (0.6/0.8),
 * closing back below it AMETHYST_CLUSTER_BREAK (0.5/0.9), and a passage flip
 * BREEZE_WHIRL (0.5/1.4).
 *
 * <p>Lifecycle mirrors {@link ImpactTracker}: entries for vanished shields or
 * players are dropped each tick, one shield's state dies with its
 * {@code ShieldRemoveS2C} ({@link #remove}, called from
 * {@code ClientShieldManager}'s receiver) and everything clears on disconnect /
 * level change ({@link ImpactFxManager}'s reset).
 */
public final class ApertureTracker {
	/** Client tick length in seconds; the smoothing step's dt. */
	private static final float TICK_SECONDS = 0.05F;
	/** Minimum ticks between two sounds from the same entry. */
	private static final int SOUND_RATE_TICKS = 10;
	/** The animated hole radius whose crossing (up = open, down = close) rings a sound. */
	private static final float SOUND_CROSSING_RADIUS = 0.42F;
	/** Entries closed below this radius are dropped entirely. */
	private static final float REMOVE_BELOW_RADIUS = 0.01F;
	/** Effective strength of the client-local PASSAGE ripple. */
	private static final float PASSAGE_STRENGTH = 0.6F;

	/** Mutable per-(shield, player) aperture state. */
	private static final class Entry {
		float holeR;
		boolean open;
		boolean lastInside;
		boolean insideSeeded;
		long lastSoundTick = Long.MIN_VALUE;
	}

	private static final Map<GlobalPos, Map<UUID, Entry>> APERTURES = new HashMap<>();

	private ApertureTracker() {
	}

	/**
	 * The animated hole radius for {@code player}'s aperture on the shield at
	 * {@code pos}, or 0 when there is none (read by the renderer every frame).
	 */
	public static float holeRadius(GlobalPos pos, UUID player) {
		Map<UUID, Entry> entries = APERTURES.get(pos);
		if (entries == null) {
			return 0.0F;
		}

		Entry entry = entries.get(player);
		return entry != null ? entry.holeR : 0.0F;
	}

	/** Drops one shield's apertures (its {@code ShieldRemoveS2C} arrived). */
	public static void remove(GlobalPos pos) {
		APERTURES.remove(pos);
	}

	/** Drops everything (disconnect / level change). */
	public static void clear() {
		APERTURES.clear();
	}

	/** Advances every aperture one tick; runs on END_CLIENT_TICK. */
	static void endClientTick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null) {
			clear();
			return;
		}

		long tick = ImpactTracker.currentTick();
		for (ClientShield shield : ClientShieldManager.currentDimensionShields()) {
			GlobalPos globalPos = new GlobalPos(shield.dimension(), shield.pos());
			if (!shield.active() || (shield.whitelist().isEmpty() && shield.ownerUuid().isEmpty())) {
				APERTURES.remove(globalPos);
				continue;
			}

			Vec3 center = Vec3.atCenterOf(shield.pos());
			float radius = shield.currentRadius();
			ShieldShape shape = ShieldShape.byOrdinal(shield.shape());
			Map<UUID, Entry> entries = APERTURES.get(globalPos);
			Map<UUID, Entry> ticked = null;
			for (AbstractClientPlayer player : level.players()) {
				boolean isOwner = shield.ownerUuid().map(player.getUUID()::equals).orElse(false);
				if (!isOwner && !shield.whitelist().contains(player.getUUID())) {
					continue;
				}

				// Beyond the close edge with no live entry: nothing to animate.
				Vec3 mid = player.position().add(0.0, player.getBbHeight() * 0.5, 0.0);
				double centerDist = mid.distanceTo(center);
				boolean hasEntry = entries != null && entries.containsKey(player.getUUID());
				if (!hasEntry && centerDist > radius + SurfaceWaveMath.APERTURE_CLOSE_DIST) {
					continue;
				}

				if (entries == null) {
					entries = new HashMap<>();
					APERTURES.put(globalPos, entries);
				}

				Entry entry = entries.computeIfAbsent(player.getUUID(), u -> new Entry());
				if (ticked == null) {
					ticked = new HashMap<>();
				}

				ticked.put(player.getUUID(), entry);
				tickEntry(level, shield, shape, center, radius, mid, player.position(), entry, tick);
			}

			// Entries whose player vanished (logout, dimension change) ease shut
			// silently so the hole never freezes open.
			if (entries != null) {
				for (Map.Entry<UUID, Entry> mapEntry : entries.entrySet()) {
					if (ticked == null || !ticked.containsKey(mapEntry.getKey())) {
						Entry entry = mapEntry.getValue();
						entry.open = false;
						entry.holeR = SurfaceWaveMath.apertureRadiusStep(entry.holeR, 0.0F, TICK_SECONDS, false);
					}
				}
			}

			pruneClosed(globalPos);
		}

		// Shields that stopped syncing (or changed dimension) lose their state.
		Iterator<GlobalPos> stale = APERTURES.keySet().iterator();
		while (stale.hasNext()) {
			GlobalPos pos = stale.next();
			ClientShield shield = ClientShieldManager.shields().get(pos);
			if (shield == null || !shield.active()) {
				stale.remove();
			}
		}
	}

	private static void tickEntry(ClientLevel level, ClientShield shield, ShieldShape shape, Vec3 center, float radius,
			Vec3 playerMid, Vec3 playerFeet, Entry entry, long tick) {
		// Hysteresis: open within radius + 5.5 of the center (spherical, matching
		// the legacy dissolve's proximity test), close only beyond radius + 6.5.
		double centerDist = playerMid.distanceTo(center);
		if (!entry.open && centerDist <= radius + SurfaceWaveMath.APERTURE_OPEN_DIST) {
			entry.open = true;
		} else if (entry.open && centerDist > radius + SurfaceWaveMath.APERTURE_CLOSE_DIST) {
			entry.open = false;
		}

		float target = entry.open ? SurfaceWaveMath.HOLE_R_MAX : 0.0F;
		float previous = entry.holeR;
		entry.holeR = SurfaceWaveMath.apertureRadiusStep(previous, target, TICK_SECONDS, entry.open);

		Vec3 outward = centerDist > 1.0e-4 ? playerMid.subtract(center).scale(1.0 / centerDist) : new Vec3(1.0, 0.0, 0.0);
		Vec3 wallPoint = center.add(outward.scale(radius));

		// Shape-aware passage flip: zero-latency local ripple + whoosh (the
		// server's PASSAGE batch entry reconciles later).
		boolean inside = ShieldGeometry.isInside(shape, center, radius, playerFeet);
		if (entry.insideSeeded && inside != entry.lastInside) {
			ImpactTracker.addServerImpact(new GlobalPos(shield.dimension(), shield.pos()), outward, PASSAGE_STRENGTH,
					inside ? ShieldPayloads.ImpactEntry.KIND_PASSAGE_IN : ShieldPayloads.ImpactEntry.KIND_PASSAGE_OUT);
			playRateLimited(level, entry, tick, wallPoint, SoundKind.PASSAGE);
		}

		entry.lastInside = inside;
		entry.insideSeeded = true;

		// Open/close chimes on the hole radius crossing the audible threshold.
		if (previous < SOUND_CROSSING_RADIUS && entry.holeR >= SOUND_CROSSING_RADIUS && entry.open) {
			playRateLimited(level, entry, tick, wallPoint, SoundKind.OPEN);
		} else if (previous > SOUND_CROSSING_RADIUS && entry.holeR <= SOUND_CROSSING_RADIUS && !entry.open) {
			playRateLimited(level, entry, tick, wallPoint, SoundKind.CLOSE);
		}
	}

	private enum SoundKind {
		OPEN, CLOSE, PASSAGE
	}

	private static void playRateLimited(ClientLevel level, Entry entry, long tick, Vec3 at, SoundKind kind) {
		if (tick - entry.lastSoundTick < SOUND_RATE_TICKS) {
			return;
		}

		entry.lastSoundTick = tick;
		switch (kind) {
			case OPEN -> level.playLocalSound(at.x, at.y, at.z, SoundEvents.AMETHYST_CLUSTER_PLACE, SoundSource.BLOCKS, 0.6F, 0.8F, false);
			case CLOSE -> level.playLocalSound(at.x, at.y, at.z, SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.BLOCKS, 0.5F, 0.9F, false);
			case PASSAGE -> level.playLocalSound(at.x, at.y, at.z, SoundEvents.BREEZE_WHIRL, SoundSource.BLOCKS, 0.5F, 1.4F, false);
		}
	}

	/** Drops fully-closed entries (and the shield's map when it empties). */
	private static void pruneClosed(GlobalPos pos) {
		Map<UUID, Entry> entries = APERTURES.get(pos);
		if (entries == null) {
			return;
		}

		entries.values().removeIf(entry -> !entry.open && entry.holeR < REMOVE_BELOW_RADIUS);
		if (entries.isEmpty()) {
			APERTURES.remove(pos);
		}
	}
}

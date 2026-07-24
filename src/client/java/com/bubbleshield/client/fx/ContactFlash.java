package com.bubbleshield.client.fx;

import java.util.UUID;

import com.bubbleshield.client.ClientShieldManager;
import com.bubbleshield.client.ClientShieldManager.ClientShield;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.GlobalPos;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

/**
 * Client-side state machine for the barrier contact flash shown by
 * {@code ShieldFlashElement} when the LOCAL player presses a shield that blocks
 * them.
 *
 * <p><b>Prediction first:</b> the primary trigger is client-predicted so the flash
 * has zero perceived latency — {@link ClientShield#blocks} mirrors the server's
 * {@code ShieldLogic.shouldBlock} on the synced replica, and a press is either
 * (a) hugging the wall (spherical wall distance &lt; {@value #WALL_TRIGGER_DIST})
 * while moving inward faster than {@value #INWARD_SPEED_TRIGGER} blocks/tick, or
 * (b) a &gt; {@value #SNAP_TRIGGER_DIST}-block single-tick position snap while
 * blocked — the signature of the server barrier's expulsion teleport.
 * Server {@code CONTACT} batch entries then RECONCILE: a confirmed contact whose
 * prediction already fired within the last {@value #RECONCILE_SUPPRESS_MILLIS} ms
 * is suppressed (it IS that prediction); an unpredicted one (e.g. the player was
 * pushed in by a piston without inward input) triggers the flash late but
 * correctly.
 *
 * <p><b>Envelope:</b> a hard flash peaks at alpha {@value #HARD_PEAK_ALPHA} and
 * eases out cubically over {@value #EASE_SECONDS} s; a sustained press (trigger
 * conditions holding {@value #SUSTAIN_MIN_TICKS}+ consecutive ticks) holds a
 * steady {@value #SUSTAIN_ALPHA}; hard re-triggers are limited to one per
 * {@value #RETRIGGER_MILLIS} ms (&le; 2 Hz).
 *
 * <p>Wall distance uses the SPHERICAL approximation |dist(center) - r|: exact for
 * SPHERE/DOME and a safe underestimate of proximity for the sub-ball shapes
 * (every shape is a subset of the r-ball, see {@code ShieldGeometry}); a flash
 * for a shaped shield can only fire when genuinely near its bounding sphere and
 * moving inward, which reads correctly in play.
 */
public final class ContactFlash {
	private static final double WALL_TRIGGER_DIST = 0.6;
	private static final double INWARD_SPEED_TRIGGER = 0.05;
	private static final double SNAP_TRIGGER_DIST = 2.0;
	private static final long RETRIGGER_MILLIS = 500L;
	private static final long RECONCILE_SUPPRESS_MILLIS = 100L;
	private static final int SUSTAIN_MIN_TICKS = 4;
	private static final float HARD_PEAK_ALPHA = 0.35F;
	private static final float SUSTAIN_ALPHA = 0.12F;
	private static final float EASE_SECONDS = 0.6F;
	/** How far from the wall a server CONTACT can still plausibly be OUR press. */
	private static final double RECONCILE_MAX_WALL_DIST = 1.5;

	private static long flashStartMillis = Long.MIN_VALUE;
	private static long lastTriggerMillis = Long.MIN_VALUE;
	private static long lastPredictedMillis = Long.MIN_VALUE;
	private static int pressTicks;
	/** Outward unit normal (center -> contact point) of the most recent contact. */
	private static Vec3 normal = Vec3.ZERO;
	private static @Nullable GlobalPos shieldPos;
	private static @Nullable Vec3 prevPlayerPos;

	private ContactFlash() {
	}

	/** Per-tick prediction; registered on END_CLIENT_TICK by {@link ImpactFxManager}. */
	static void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (mc.level == null || player == null) {
			reset();
			return;
		}

		Vec3 pos = player.position();
		Vec3 moved = prevPlayerPos != null ? pos.subtract(prevPlayerPos) : Vec3.ZERO;
		prevPlayerPos = pos;

		UUID uuid = player.getUUID();
		String name = player.getGameProfile().name();
		ClientShield nearest = null;
		double nearestWallDist = Double.MAX_VALUE;
		Vec3 nearestOutward = Vec3.ZERO;
		for (ClientShield shield : ClientShieldManager.currentDimensionShields()) {
			if (!shield.active() || !shield.blocks(uuid, name)) {
				continue;
			}

			Vec3 center = Vec3.atCenterOf(shield.pos());
			Vec3 fromCenter = pos.subtract(center);
			double dist = fromCenter.length();
			double wallDist = Math.abs(dist - shield.currentRadius());
			if (wallDist < nearestWallDist) {
				nearest = shield;
				nearestWallDist = wallDist;
				nearestOutward = dist > 1.0E-4 ? fromCenter.scale(1.0 / dist) : new Vec3(1.0, 0.0, 0.0);
			}
		}

		if (nearest == null) {
			pressTicks = 0;
			return;
		}

		double inwardSpeed = -moved.dot(nearestOutward);
		boolean pressing = nearestWallDist < WALL_TRIGGER_DIST && inwardSpeed > INWARD_SPEED_TRIGGER;
		boolean snapped = moved.length() > SNAP_TRIGGER_DIST;
		pressTicks = pressing ? pressTicks + 1 : 0;
		if (pressing || snapped) {
			long now = Util.getMillis();
			lastPredictedMillis = now;
			trigger(now, nearestOutward, new GlobalPos(nearest.dimension(), nearest.pos()));
		}
	}

	/**
	 * Reconciles one server-confirmed CONTACT batch entry (client thread): ignored
	 * unless it is plausibly the LOCAL player's press (blocked by that shield, near
	 * its wall), and suppressed when a prediction already fired
	 * &lt; {@value #RECONCILE_SUPPRESS_MILLIS} ms ago (double-flash guard).
	 */
	static void onServerContact(GlobalPos pos, Vec3 dir) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (mc.level == null || player == null || !mc.level.dimension().equals(pos.dimension())) {
			return;
		}

		ClientShield shield = ClientShieldManager.shields().get(pos);
		if (shield == null || !shield.active() || !shield.blocks(player.getUUID(), player.getGameProfile().name())) {
			return;
		}

		Vec3 center = Vec3.atCenterOf(pos.pos());
		double wallDist = Math.abs(player.position().distanceTo(center) - shield.currentRadius());
		if (wallDist > RECONCILE_MAX_WALL_DIST) {
			// Another blocked player's press on the same shield; not ours to flash.
			return;
		}

		long now = Util.getMillis();
		if (now - lastPredictedMillis < RECONCILE_SUPPRESS_MILLIS) {
			return;
		}

		trigger(now, dir, pos);
	}

	/** Fires a hard flash, subject to the {@value #RETRIGGER_MILLIS} ms rate limit. */
	private static void trigger(long now, Vec3 outward, GlobalPos pos) {
		normal = outward;
		shieldPos = pos;
		if (now - lastTriggerMillis >= RETRIGGER_MILLIS) {
			lastTriggerMillis = now;
			flashStartMillis = now;
		}
	}

	/**
	 * The current overlay alpha in [0, {@value #HARD_PEAK_ALPHA}]: the cubic
	 * ease-out of the last hard flash, floored at {@value #SUSTAIN_ALPHA} during a
	 * sustained press. 0 when idle.
	 */
	public static float alpha() {
		float hard = 0.0F;
		if (flashStartMillis != Long.MIN_VALUE) {
			float t = (Util.getMillis() - flashStartMillis) / 1000.0F;
			if (t >= 0.0F && t < EASE_SECONDS) {
				float inv = 1.0F - t / EASE_SECONDS;
				hard = HARD_PEAK_ALPHA * inv * inv * inv;
			}
		}

		float sustained = pressTicks >= SUSTAIN_MIN_TICKS ? SUSTAIN_ALPHA : 0.0F;
		return Math.max(hard, sustained);
	}

	/** Outward unit normal of the most recent contact (zero before any contact). */
	public static Vec3 contactNormal() {
		return normal;
	}

	/** The shield involved in the most recent contact, or {@code null}. */
	public static @Nullable GlobalPos contactShield() {
		return shieldPos;
	}

	/** Full reset (disconnect / level change). */
	static void reset() {
		flashStartMillis = Long.MIN_VALUE;
		lastTriggerMillis = Long.MIN_VALUE;
		lastPredictedMillis = Long.MIN_VALUE;
		pressTicks = 0;
		normal = Vec3.ZERO;
		shieldPos = null;
		prevPlayerPos = null;
	}
}

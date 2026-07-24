package com.bubbleshield.client.fx;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.phys.Vec3;

/**
 * Tiny client-side store of recent shield surface events, fed by
 * {@link ImpactFxManager}'s {@code ImpactBatchS2C} receiver and consumed by future
 * surface-reaction work (WP-Dyn: mesh deformation reads {@link #impactsAt} per
 * rendered shield). Deliberately minimal API: add, read, prune, clear.
 *
 * <p>Per shield the deque keeps at most {@link #MAX_PER_SHIELD} newest impacts
 * (matching the wire batch cap), each expiring {@link #TTL_TICKS} client ticks
 * after ingestion. Pruning runs once per {@code END_CLIENT_TICK} (registered by
 * {@link ImpactFxManager}); the whole store is cleared on disconnect and level
 * change, and a single shield's history dies with its {@code ShieldRemoveS2C}
 * (called from {@code ClientShieldManager}'s receiver — Fabric allows only ONE
 * global receiver per payload type, so this store cannot register its own).
 */
public final class ImpactTracker {
	/** Mirror of the wire cap ({@code ImpactBatchS2C.MAX_ENTRIES}). */
	public static final int MAX_PER_SHIELD = 8;
	/** Impacts older than this many client ticks are pruned (2 seconds). */
	public static final int TTL_TICKS = 40;

	/**
	 * One remembered surface event: {@code dir} is the outward unit direction from
	 * the shield center to the event point (zero for BREAK), {@code strength01} the
	 * dequantized strength in [0, 1], {@code kind} one of the
	 * {@code ShieldPayloads.ImpactEntry.KIND_*} constants and {@code clientTick}
	 * the ingestion tick used for TTL/animation phase.
	 */
	public record Impact(Vec3 dir, float strength01, int kind, long clientTick) {
	}

	private static final Map<GlobalPos, ArrayDeque<Impact>> IMPACTS = new HashMap<>();
	private static long clientTicks;

	private ImpactTracker() {
	}

	/** The monotonically increasing client tick counter impacts are stamped with. */
	public static long currentTick() {
		return clientTicks;
	}

	/** Ingests one server-sent impact for the shield at {@code pos} (client thread). */
	public static void addServerImpact(GlobalPos pos, Vec3 dir, float strength01, int kind) {
		ArrayDeque<Impact> deque = IMPACTS.computeIfAbsent(pos, p -> new ArrayDeque<>(MAX_PER_SHIELD));
		while (deque.size() >= MAX_PER_SHIELD) {
			deque.pollFirst();
		}

		deque.addLast(new Impact(dir, strength01, kind, clientTicks));
	}

	/**
	 * The live impacts for the shield at {@code pos}, oldest first — read-only by
	 * convention (consumers iterate, never mutate). Empty deque when none.
	 */
	public static Iterable<Impact> impactsAt(GlobalPos pos) {
		ArrayDeque<Impact> deque = IMPACTS.get(pos);
		return deque != null ? deque : List.<Impact>of();
	}

	/** Drops one shield's history (its {@code ShieldRemoveS2C} arrived). */
	public static void remove(GlobalPos pos) {
		IMPACTS.remove(pos);
	}

	/** Drops everything (disconnect / level change). */
	public static void clear() {
		IMPACTS.clear();
	}

	/** Advances the tick counter and prunes expired impacts; runs on END_CLIENT_TICK. */
	static void endClientTick(Minecraft mc) {
		clientTicks++;
		if (IMPACTS.isEmpty()) {
			return;
		}

		Iterator<Map.Entry<GlobalPos, ArrayDeque<Impact>>> shields = IMPACTS.entrySet().iterator();
		while (shields.hasNext()) {
			ArrayDeque<Impact> deque = shields.next().getValue();
			// Deques are appended in tick order, so expired impacts are always a prefix.
			while (!deque.isEmpty() && clientTicks - deque.peekFirst().clientTick() > TTL_TICKS) {
				deque.pollFirst();
			}

			if (deque.isEmpty()) {
				shields.remove();
			}
		}
	}
}

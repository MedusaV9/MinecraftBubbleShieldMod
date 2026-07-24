package com.bubbleshield.registry;

import com.bubbleshield.BubbleShield;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.TicketType;

/**
 * Chunk ticket types owned by the mod (26.2 registers ticket types in
 * {@code BuiltInRegistries.TICKET_TYPE}, exactly like the vanilla ones).
 */
public final class ModTicketTypes {
	/**
	 * D5: keeps an ACTIVE projector's chunk loaded and ticking so the shield stays
	 * enforced when no player is near the projector (a diameter-200 bubble's far
	 * edge is ~100 blocks from its chunk). Added with radius 1 via
	 * {@code ServerChunkCache.addTicketWithRadius}, i.e. ticket level 32 =
	 * BLOCK_TICKING at the projector chunk — enough for the block-entity ticker
	 * (and thus all shield logic) to keep running.
	 *
	 * <p>Chosen approach: {@code FLAG_LOADING | FLAG_SIMULATION} (the same shape as
	 * the vanilla portal/ender-pearl tickets, minus persistence) with a finite
	 * timeout, RE-ARMED every active server tick — {@code TicketStorage.addTicket}
	 * dedups on (type, level) and resets the countdown, so re-arming is cheap.
	 * Fix 7: the projector deliberately NEVER releases the ticket explicitly (not
	 * on deactivate, break or removal) — the ticket identity is (type, chunk,
	 * level), so two projectors in one chunk SHARE one ticket and an explicit
	 * release from the one going down would strip the other's coverage. Expiry is
	 * timeout-only: once nothing re-arms it, the chunk stays loaded at most ~5 s
	 * longer. Deliberately NOT persistent: after a reload the first ticked
	 * activation re-arms it, and no stale saved ticket can pin chunks of a
	 * projector that is gone.
	 */
	public static final TicketType SHIELD_PROJECTOR = Registry.register(
		BuiltInRegistries.TICKET_TYPE,
		BubbleShield.id("shield_projector"),
		new TicketType(100L, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION)
	);

	private ModTicketTypes() {
	}

	public static void init() {
		// Forces the static registrations above to run during mod initialization.
	}
}

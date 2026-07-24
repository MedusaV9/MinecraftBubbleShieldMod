package com.bubbleshield.shield;

/**
 * The operating mode of a bubble shield. {@link #DEFENSE} is the classic behaviour;
 * {@link #PULSE} periodically zaps hostile mobs inside the bubble at an extra fuel
 * cost; {@link #ECO} halves the passive fuel drain in exchange for a smaller radius
 * and suppressed tier regeneration.
 */
public enum ShieldMode {
	DEFENSE,
	PULSE,
	ECO;

	private static final ShieldMode[] VALUES = values();

	/** @return the mode with the given ordinal, clamped to the valid range (default {@link #DEFENSE}). */
	public static ShieldMode byOrdinal(int ordinal) {
		return VALUES[ordinal >= 0 && ordinal < VALUES.length ? ordinal : 0];
	}
}

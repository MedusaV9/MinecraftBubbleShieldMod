package com.bubbleshield.shield;

/**
 * The volumetric shape of a bubble shield. {@link #SPHERE} is the classic full bubble;
 * {@link #DOME} is the upper hemisphere only (open below the projector's center plane).
 */
public enum ShieldShape {
	SPHERE,
	DOME;

	private static final ShieldShape[] VALUES = values();

	/** @return the shape with the given ordinal, clamped to the valid range (default {@link #SPHERE}). */
	public static ShieldShape byOrdinal(int ordinal) {
		return VALUES[ordinal >= 0 && ordinal < VALUES.length ? ordinal : 0];
	}
}

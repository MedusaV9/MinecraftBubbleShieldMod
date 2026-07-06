package com.bubbleshield.effect;

/**
 * Boundary-retaliation style applied when the barrier expels a blocked player
 * (and to blocked projectile impact FX).
 *
 * <p>Pure data for now: the server-side wiring lands in a later milestone;
 * until then the style only participates in the effect catalogue.
 */
public enum GuardStyle {
	/** No retaliation beyond the plain pushback. */
	NONE,
	/** Extra outward push plus gust particles. */
	GUST,
	/** Applies Slowness to the expelled intruder. */
	SLOW,
	/** Applies Blindness to the expelled intruder. */
	BLIND,
	/** Applies Darkness to the expelled intruder. */
	DARK,
	/** Applies Glowing, marking the intruder. */
	GLOW,
	/** Deals a small amount of magic damage. */
	STING
}

package com.bubbleshield.effect;

/**
 * Environment/state reactivity profile modulating an effect's inside-behavior tick.
 *
 * <p>Pure data for now: the modulation logic lands in a later milestone;
 * until then the profile only participates in the effect catalogue.
 */
public enum ContextProfile {
	/** No context reactivity. */
	NONE,
	/** Intensifies at night. */
	NIGHT_BLOOM,
	/** Extra sparks / louder ambient while raining. */
	STORM_CHARGED,
	/** Intensity scales with the number of players inside. */
	CROWD_SCALE,
	/** Faster behavior cadence while shield health is low. */
	LOW_HEALTH_FRENZY,
	/** Behavior switches to the secondary color while shield health is low. */
	HEALTH_HUE
}

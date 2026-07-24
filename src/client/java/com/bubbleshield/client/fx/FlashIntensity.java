package com.bubbleshield.client.fx;

/**
 * Static holder for the contact-flash intensity multiplier applied by
 * {@code ShieldFlashElement} (0 disables the overlay entirely, 1 is the authored
 * strength).
 *
 * <p>TODO(WP-Cfg): a {@code BubbleShieldClientConfig} does not exist yet; when the
 * config work package lands, this holder should be backed by (or replaced with) its
 * persisted {@code flashIntensity} option instead of the hardcoded default.
 */
public final class FlashIntensity {
	private static float value = 1.0F;

	private FlashIntensity() {
	}

	public static float get() {
		return value;
	}

	public static void set(float intensity) {
		value = Math.clamp(intensity, 0.0F, 1.0F);
	}
}

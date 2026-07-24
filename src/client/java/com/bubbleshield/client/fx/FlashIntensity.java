package com.bubbleshield.client.fx;

/**
 * Static holder for the contact-flash intensity multiplier applied by
 * {@code ShieldFlashElement} (0 disables the overlay entirely, 1 is the authored
 * strength). Also scales the interior blink envelope ({@code InteriorRenderer}),
 * so photosensitive players can damp every pulsing visual with one option.
 *
 * <p>Backed by the persisted {@code flashIntensity} option:
 * {@code BubbleShieldClientConfig.load()} pushes the configured value here during
 * client init (closing WP-Evt's wiring TODO).
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

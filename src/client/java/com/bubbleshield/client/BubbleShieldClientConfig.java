package com.bubbleshield.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.bubbleshield.BubbleShield;
import com.bubbleshield.client.fx.FlashIntensity;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Client-only visual options, persisted as JSON at
 * {@code <config dir>/bubbleshield-client.json}:
 * <ul>
 *   <li>{@code interiorDensity} (0..1, default 1) — global multiplier on the
 *       per-bubble interior element budget; 0 disables interiors entirely;</li>
 *   <li>{@code volumetricMode} ({@code OFF}/{@code LOW}/{@code FULL}, default
 *       {@code FULL}) — thins the smoke/fog interior layers (x0 / x0.5 / x1).
 *       The shader-side volumetrics are baked into the generated fx shaders, so
 *       this currently only gates the fog-flagged interior element counts;</li>
 *   <li>{@code flashIntensity} (0..1, default 1) — the contact-flash overlay
 *       multiplier (wired into the {@link FlashIntensity} static holder, closing
 *       WP-Evt's TODO) and the interior blink-envelope scale.</li>
 * </ul>
 *
 * <p>Robust load: a missing file writes the defaults; a malformed file or
 * out-of-range values log a warning, fall back per-field to the defaults, and the
 * normalized config is always written back (so hand edits are canonicalized and
 * new fields appear with their defaults).
 */
public final class BubbleShieldClientConfig {
	/** Volumetric interior thinning: fog-flagged layers keep x0 / x0.5 / x1 of their elements. */
	public enum VolumetricMode {
		OFF(0.0F),
		LOW(0.5F),
		FULL(1.0F);

		private final float fogCountMultiplier;

		VolumetricMode(float fogCountMultiplier) {
			this.fogCountMultiplier = fogCountMultiplier;
		}

		public float fogCountMultiplier() {
			return this.fogCountMultiplier;
		}
	}

	private static final String FILE_NAME = "bubbleshield-client.json";

	private static float interiorDensity = 1.0F;
	private static VolumetricMode volumetricMode = VolumetricMode.FULL;
	private static float flashIntensity = 1.0F;

	private BubbleShieldClientConfig() {
	}

	public static float interiorDensity() {
		return interiorDensity;
	}

	public static VolumetricMode volumetricMode() {
		return volumetricMode;
	}

	public static float flashIntensity() {
		return flashIntensity;
	}

	/**
	 * Loads (or creates) the config and pushes the flash intensity into the
	 * {@link FlashIntensity} holder. Called once from client init; any failure
	 * leaves the compiled defaults in place — the client never crashes over a
	 * config file.
	 */
	public static void load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		if (Files.exists(path)) {
			try {
				JsonObject json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
				interiorDensity = readClamped(json, "interiorDensity", interiorDensity);
				volumetricMode = readMode(json, "volumetricMode", volumetricMode);
				flashIntensity = readClamped(json, "flashIntensity", flashIntensity);
			} catch (IOException | RuntimeException e) {
				// JsonParser throws JsonSyntaxException; a non-object root throws
				// IllegalStateException — both mean "malformed", both fall back.
				BubbleShield.LOGGER.warn("Malformed {}; using defaults and rewriting it", FILE_NAME, e);
			}
		}

		FlashIntensity.set(flashIntensity);
		writeBack(path);
	}

	private static float readClamped(JsonObject json, String key, float fallback) {
		JsonElement element = json.get(key);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			if (element != null) {
				BubbleShield.LOGGER.warn("{}: '{}' is not a number; using {}", FILE_NAME, key, fallback);
			}

			return fallback;
		}

		float value = element.getAsFloat();
		if (Float.isNaN(value)) {
			BubbleShield.LOGGER.warn("{}: '{}' is NaN; using {}", FILE_NAME, key, fallback);
			return fallback;
		}

		return Math.clamp(value, 0.0F, 1.0F);
	}

	private static VolumetricMode readMode(JsonObject json, String key, VolumetricMode fallback) {
		JsonElement element = json.get(key);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			if (element != null) {
				BubbleShield.LOGGER.warn("{}: '{}' is not a string; using {}", FILE_NAME, key, fallback);
			}

			return fallback;
		}

		try {
			return VolumetricMode.valueOf(element.getAsString().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			BubbleShield.LOGGER.warn("{}: unknown volumetricMode '{}'; using {}", FILE_NAME, element.getAsString(), fallback);
			return fallback;
		}
	}

	private static void writeBack(Path path) {
		String json = String.format(Locale.ROOT, """
				{
					"interiorDensity": %s,
					"volumetricMode": "%s",
					"flashIntensity": %s
				}
				""", interiorDensity, volumetricMode.name(), flashIntensity);
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, json);
		} catch (IOException e) {
			BubbleShield.LOGGER.warn("Could not write {}", FILE_NAME, e);
		}
	}
}

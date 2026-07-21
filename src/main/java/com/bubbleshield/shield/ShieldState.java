package com.bubbleshield.shield;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.bubbleshield.effect.EffectRegistry;
import com.mojang.serialization.Codec;

import net.minecraft.core.UUIDUtil;
import net.minecraft.util.StringUtil;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import org.jspecify.annotations.Nullable;

/**
 * Plain mutable data holder for the state of a bubble shield projector.
 */
public class ShieldState {
	public static final float DEFAULT_TARGET_RADIUS = 16.0F;
	public static final float DEFAULT_MAX_HEALTH = 100.0F;
	/**
	 * Valid target radius range on NBT load: the GUI diameter range 8..200
	 * ({@code ServerNet.MIN_DIAMETER}/{@code MAX_DIAMETER}) halved. The bounds are
	 * duplicated here (compile-time constants, kept in lockstep by the NBT-tamper
	 * gametest) so the pure data holder does not depend on the network package.
	 */
	public static final float MIN_TARGET_RADIUS = 4.0F;
	public static final float MAX_TARGET_RADIUS = 100.0F;
	/**
	 * NBT-load cap for health/max_health: far above any tier's
	 * {@code 100 * (1 + tier)} (300 at tier 2) but finite, so /data can never
	 * smuggle Infinity-scale values into the radius shrink math, the boss-bar
	 * progress or the sync payload.
	 */
	public static final float MAX_LOADED_HEALTH = 1.0e6F;
	/** Sentinel for {@link #colorOverride}: no recolor, use the effect's authored palette. */
	public static final int NO_COLOR_OVERRIDE = -1;
	/**
	 * Hard cap on the custom shield name, matching the SetNameC2S/ShieldSyncS2C codecs
	 * ({@code stringUtf8(32)} throws an EncoderException on longer strings).
	 */
	public static final int MAX_NAME_LENGTH = 32;

	public boolean active;
	public int effectId;
	public ShieldShape shape = ShieldShape.SPHERE;
	public ShieldMode mode = ShieldMode.DEFENSE;
	/** When true, the active shield re-rolls its effect periodically (see ShieldLogic). */
	public boolean cycleEffect;
	/**
	 * Style of the central energy beam rising through the bubble (client-rendered).
	 * {@link BeamStyle#NONE} by default so pre-beam saves stay beam-free.
	 */
	public BeamStyle beamStyle = BeamStyle.NONE;
	public float targetRadius = DEFAULT_TARGET_RADIUS;
	public float health = DEFAULT_MAX_HEALTH;
	public float maxHealth = DEFAULT_MAX_HEALTH;
	public @Nullable UUID ownerUuid;
	/** Owner-set display name for the shield's boss bar; empty means "use the effect name". */
	public String customName = "";
	/**
	 * Owner-picked dye recolor for the shield's visuals (bubble surface, HUD bar,
	 * particle colors, boss bar bucket). {@link #NO_COLOR_OVERRIDE} (-1) means "use the
	 * effect's authored palette"; any other value is an OPAQUE ARGB color (alpha 0xFF,
	 * so real overrides are negative ints — always compare against the -1 sentinel,
	 * never with {@code >= 0}). The in-bubble screen post-effect deliberately keeps the
	 * authored palette (its colors are baked into the static post_effect JSON uniforms).
	 */
	public int colorOverride = NO_COLOR_OVERRIDE;
	public final Set<String> whitelistNames = new HashSet<>();
	public final Set<UUID> whitelistUuids = new HashSet<>();
	/**
	 * Name-to-UUID associations learned locally (add-time online lookup, join backfill,
	 * owner assignment), keyed by lowercase name. Persisted so whitelist removal can
	 * revoke the matching UUID without ever consulting the server's name-to-id cache
	 * (whose misses trigger a blocking remote lookup + usercache write).
	 */
	public final Map<String, UUID> whitelistNameToUuid = new HashMap<>();
	public int fuelSeconds;
	public long cooldownUntil;

	private static final Codec<Map<String, UUID>> NAME_TO_UUID_CODEC = Codec.unboundedMap(Codec.STRING, UUIDUtil.CODEC);

	/**
	 * Sanitizes a custom shield name: control/formatting characters are stripped
	 * ({@link StringUtil#filterText}), surrounding whitespace is trimmed and the
	 * result is capped at {@link #MAX_NAME_LENGTH} characters. May return an empty
	 * string, which means "no custom name". Applied to every write path (C2S
	 * requests via {@code ServerNet}) AND on NBT load, so a name smuggled in via
	 * /data or an NBT editor can never break the sync payload's bounded codec.
	 */
	public static String sanitizeName(String raw) {
		String name = StringUtil.filterText(raw).trim();
		if (name.length() > MAX_NAME_LENGTH) {
			name = name.substring(0, MAX_NAME_LENGTH).trim();
		}

		return name;
	}

	/**
	 * Pure validation for a shield color override: {@link #NO_COLOR_OVERRIDE} (-1)
	 * means "use the effect's authored palette", every other accepted value must be a
	 * fully opaque ARGB color (alpha byte 0xFF). Translucent or alpha-less colors are
	 * rejected so neither a hostile client nor edited NBT can make the bubble
	 * surface/HUD invisible. Shared by {@code ServerNet} (C2S requests) and
	 * {@link #load} (NBT).
	 */
	public static boolean isValidColorOverride(int argb) {
		return argb == NO_COLOR_OVERRIDE || (argb & 0xFF000000) == 0xFF000000;
	}

	/**
	 * NBT-load hardening for numeric float fields (same spirit as the
	 * effect_id/shape/custom_name/color_override handling in {@link #load}): NaN
	 * (which would poison every comparison and clamp downstream) falls back to
	 * {@code fallback}, everything else — including the infinities — clamps into
	 * {@code [min, max]}.
	 */
	private static float sanitizeLoadedFloat(float value, float min, float max, float fallback) {
		if (Float.isNaN(value)) {
			return fallback;
		}

		return Math.clamp(value, min, max);
	}

	/** Records a locally learned name-to-UUID association (lowercase key). */
	public void rememberWhitelistUuid(String name, UUID uuid) {
		this.whitelistNameToUuid.put(name.toLowerCase(Locale.ROOT), uuid);
	}

	/**
	 * Drops the stored association for {@code name} (case-insensitive).
	 *
	 * @return the UUID that was associated with the name, or null if none was stored.
	 */
	public @Nullable UUID forgetWhitelistUuid(String name) {
		return this.whitelistNameToUuid.remove(name.toLowerCase(Locale.ROOT));
	}

	public void save(ValueOutput output) {
		output.putBoolean("active", this.active);
		output.putInt("effect_id", this.effectId);
		output.putInt("shape", this.shape.ordinal());
		output.putInt("mode", this.mode.ordinal());
		output.putBoolean("cycle_effect", this.cycleEffect);
		output.putInt("beam_style", this.beamStyle.ordinal());
		output.putFloat("target_radius", this.targetRadius);
		output.putFloat("health", this.health);
		output.putFloat("max_health", this.maxHealth);
		output.storeNullable("owner_uuid", UUIDUtil.CODEC, this.ownerUuid);
		output.putString("custom_name", this.customName);
		output.putInt("color_override", this.colorOverride);

		ValueOutput.TypedOutputList<String> names = output.list("whitelist_names", Codec.STRING);
		for (String name : this.whitelistNames) {
			names.add(name);
		}

		ValueOutput.TypedOutputList<UUID> uuids = output.list("whitelist_uuids", UUIDUtil.CODEC);
		for (UUID uuid : this.whitelistUuids) {
			uuids.add(uuid);
		}

		output.store("whitelist_name_uuids", NAME_TO_UUID_CODEC, Map.copyOf(this.whitelistNameToUuid));
		output.putInt("fuel_seconds", this.fuelSeconds);
		output.putLong("cooldown_until", this.cooldownUntil);
	}

	public void load(ValueInput input) {
		this.active = input.getBooleanOr("active", false);
		// Clamp out-of-range effect ids edited into the NBT (same hardening spirit
		// as custom_name/color_override below): EffectRegistry.get() clamps on
		// read, but a raw out-of-range id would bias ShieldLogic.cycleEffect's
		// re-roll and feed unclamped values into the advancement criteria.
		this.effectId = Math.clamp(input.getIntOr("effect_id", 0), 0, EffectRegistry.COUNT - 1);
		this.shape = ShieldShape.byOrdinal(input.getIntOr("shape", 0));
		this.mode = ShieldMode.byOrdinal(input.getIntOr("mode", 0));
		this.cycleEffect = input.getBooleanOr("cycle_effect", false);
		// Legacy saves (no key) default to ordinal 0 = NONE; tampered ordinals clamp
		// back to NONE via byOrdinal — the same hardening as shape/mode above.
		this.beamStyle = BeamStyle.byOrdinal(input.getIntOr("beam_style", 0));
		// Numeric hardening (same spirit as effect_id/shape above): a NaN or
		// out-of-range float edited into the NBT would otherwise flow straight
		// into the radius math (ShieldLogic.currentRadius divides by maxHealth
		// and scales by targetRadius) and the boss-bar/sync payloads. NaN falls
		// back to the default; everything else clamps into the valid range.
		this.targetRadius = sanitizeLoadedFloat(input.getFloatOr("target_radius", DEFAULT_TARGET_RADIUS),
				MIN_TARGET_RADIUS, MAX_TARGET_RADIUS, DEFAULT_TARGET_RADIUS);
		// maxHealth first (health clamps against it); at least 1 so the
		// health/maxHealth radius fraction can never divide by zero.
		this.maxHealth = sanitizeLoadedFloat(input.getFloatOr("max_health", DEFAULT_MAX_HEALTH),
				1.0F, MAX_LOADED_HEALTH, DEFAULT_MAX_HEALTH);
		this.health = sanitizeLoadedFloat(input.getFloatOr("health", DEFAULT_MAX_HEALTH),
				0.0F, this.maxHealth, this.maxHealth);
		this.ownerUuid = input.read("owner_uuid", UUIDUtil.CODEC).orElse(null);
		// Re-sanitize on load: a >32-char (or control-char) name edited into the NBT
		// would throw an EncoderException in ShieldSyncS2C's stringUtf8(32) codec on
		// every broadcast, breaking shield sync for the whole level.
		this.customName = sanitizeName(input.getStringOr("custom_name", ""));
		int loadedColorOverride = input.getIntOr("color_override", NO_COLOR_OVERRIDE);
		// Reject non-opaque overrides edited into the NBT: a translucent/zero-alpha
		// value would render an invisible HUD bar. Same rule as the C2S validation.
		this.colorOverride = isValidColorOverride(loadedColorOverride) ? loadedColorOverride : NO_COLOR_OVERRIDE;

		this.whitelistNames.clear();
		for (String name : input.listOrEmpty("whitelist_names", Codec.STRING)) {
			this.whitelistNames.add(name);
		}

		this.whitelistUuids.clear();
		for (UUID uuid : input.listOrEmpty("whitelist_uuids", UUIDUtil.CODEC)) {
			this.whitelistUuids.add(uuid);
		}

		this.whitelistNameToUuid.clear();
		this.whitelistNameToUuid.putAll(input.read("whitelist_name_uuids", NAME_TO_UUID_CODEC).orElse(Map.of()));
		this.fuelSeconds = input.getIntOr("fuel_seconds", 0);
		this.cooldownUntil = input.getLongOr("cooldown_until", 0L);
	}
}

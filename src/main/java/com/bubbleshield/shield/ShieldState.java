package com.bubbleshield.shield;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.bubbleshield.effect.EffectRegistry;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

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
	/**
	 * Hard cap on whitelist entries, shared by the C2S add path ({@code ServerNet})
	 * and {@link #load}, so neither a request flood nor edited NBT can grow the
	 * whitelist beyond what the sync payloads and NBT are sized for.
	 */
	public static final int MAX_WHITELIST_SIZE = 64;
	/**
	 * NBT-load cap for fuel_seconds: far above anything the fuel map can grant in
	 * one sitting but finite, so edited NBT cannot park a near-Integer.MAX_VALUE
	 * value that later arithmetic (top-ups, comparator math) could overflow.
	 */
	public static final int MAX_LOADED_FUEL_SECONDS = 100000;
	/** B6 threat log: at most this many entries are kept (ring buffer, oldest dropped). */
	public static final int THREAT_LOG_MAX = 8;
	/** B6 threat log: attacker names are hard-capped at vanilla's 16-char player-name limit. */
	public static final int MAX_ATTACKER_NAME_LENGTH = 16;

	/**
	 * One B6 threat-log entry: the sanitized name of a projectile shooter whose shot
	 * this shield intercepted, the POST-DR damage the shield actually took from that
	 * hit (the linked-split share when resonance-linked), and the game time of the
	 * interception. Persisted via {@link #CODEC}; a later WP exposes the log through
	 * a command — for now it is only stored and readable via {@link #threatLog()}.
	 */
	public record ThreatLogEntry(String attackerName, float damage, long gameTime) {
		public static final Codec<ThreatLogEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.STRING.fieldOf("name").forGetter(ThreatLogEntry::attackerName),
				Codec.FLOAT.fieldOf("damage").forGetter(ThreatLogEntry::damage),
				Codec.LONG.fieldOf("game_time").forGetter(ThreatLogEntry::gameTime)
		).apply(instance, ThreatLogEntry::new));
	}

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
	/**
	 * Ticks of ACTIVE runtime accumulated toward the next passive fuel drain. Unlike
	 * the old {@code gameTime % interval} check, this only advances while the shield
	 * is active and fires (then resets) when it reaches the effective drain interval,
	 * so toggling the shield off across payment ticks can never dodge the drain: the
	 * cost is always exactly one fuel-second per interval of ACTIVE ticks.
	 */
	public int drainAccum;
	/** Ticks of active runtime accumulated toward the next regen pulse; see {@link #drainAccum}. */
	public int regenAccum;
	/** Game time of the most recent shield damage application; 0 until first hit. */
	public long lastHitGameTime;
	/** Total damage ever absorbed by this shield (accumulated in applyDamage). */
	public float absorbedTotal;
	/**
	 * False while a break cooldown that has not yet been announced is pending: set
	 * false when a break cooldown starts, so a later "shield ready again" ping can
	 * fire exactly once. Defaults to true (nothing to announce).
	 */
	public boolean readyAnnounced = true;
	/**
	 * B6 siege alarm: game time until which the shield counts as "alarmed" — set to
	 * {@code gameTime + }{@link ShieldLogic#ALARM_WINDOW_TICKS} by
	 * {@link ShieldLogic#triggerAlarm}. While alarmed the comparator output is
	 * overridden to 15 and the boss bar name carries the UNDER ATTACK suffix.
	 * 0 means "never alarmed". Persisted (clamped &ge; 0 on load; the block entity
	 * additionally caps a tampered far-future value against the level clock on the
	 * first tick after load, same pattern as cooldown_until).
	 */
	public long alarmUntilGameTime;
	/** B6 threat log ring buffer (newest last); see {@link ThreatLogEntry}. */
	private final ArrayDeque<ThreatLogEntry> threatLog = new ArrayDeque<>();

	private static final Codec<Map<String, UUID>> NAME_TO_UUID_CODEC = Codec.unboundedMap(Codec.STRING, UUIDUtil.CODEC);

	/** @return true while the B6 siege alarm window is open at the given game time. */
	public boolean isAlarmed(long gameTime) {
		return gameTime < this.alarmUntilGameTime;
	}

	/**
	 * Appends one B6 threat-log entry (sanitizing every field), dropping the oldest
	 * entry beyond {@link #THREAT_LOG_MAX}. Applied identically on the live append
	 * path (projectile interception) and on NBT load, so edited NBT can never park
	 * an oversized/poisoned entry that a later command exposure would render.
	 */
	public void recordThreat(String attackerName, float damage, long gameTime) {
		String name = sanitizeAttackerName(attackerName);
		if (name.isEmpty()) {
			return;
		}

		this.threatLog.addLast(new ThreatLogEntry(
				name,
				sanitizeLoadedFloat(damage, 0.0F, Float.MAX_VALUE, 0.0F),
				Math.max(0L, gameTime)));
		while (this.threatLog.size() > THREAT_LOG_MAX) {
			this.threatLog.removeFirst();
		}
	}

	/** An immutable snapshot of the B6 threat log, oldest entry first (at most {@link #THREAT_LOG_MAX}). */
	public List<ThreatLogEntry> threatLog() {
		return List.copyOf(this.threatLog);
	}

	/**
	 * Sanitizes a threat-log attacker name: control/formatting characters stripped,
	 * trimmed, capped at {@link #MAX_ATTACKER_NAME_LENGTH} (the vanilla player-name
	 * limit). Same spirit as {@link #sanitizeName}; may return an empty string,
	 * which {@link #recordThreat} treats as "no resolvable attacker" and drops.
	 */
	public static String sanitizeAttackerName(String raw) {
		String name = StringUtil.filterText(raw).trim();
		if (name.length() > MAX_ATTACKER_NAME_LENGTH) {
			name = name.substring(0, MAX_ATTACKER_NAME_LENGTH).trim();
		}

		return name;
	}

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
		output.putInt("drain_accum", this.drainAccum);
		output.putInt("regen_accum", this.regenAccum);
		output.putLong("last_hit_game_time", this.lastHitGameTime);
		output.putFloat("absorbed_total", this.absorbedTotal);
		output.putBoolean("ready_announced", this.readyAnnounced);
		output.putLong("alarm_until", this.alarmUntilGameTime);

		ValueOutput.TypedOutputList<ThreatLogEntry> threats = output.list("threat_log", ThreatLogEntry.CODEC);
		for (ThreatLogEntry entry : this.threatLog) {
			threats.add(entry);
		}
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

		// Whitelist hardening: the C2S add path enforces MAX_WHITELIST_SIZE and
		// StringUtil.isValidPlayerName, but /data or an NBT editor bypasses both. An
		// oversized list would bloat every sync payload, and a >16-char (or
		// control-char) name would blow up the bounded stringUtf8(16) name codec in
		// ShieldSyncS2C on every broadcast. Apply the exact same rules on load:
		// trim, drop invalid names, cap at MAX_WHITELIST_SIZE entries.
		this.whitelistNames.clear();
		for (String name : input.listOrEmpty("whitelist_names", Codec.STRING)) {
			if (this.whitelistNames.size() >= MAX_WHITELIST_SIZE) {
				break;
			}

			String trimmed = name.trim();
			if (!trimmed.isEmpty() && StringUtil.isValidPlayerName(trimmed)) {
				this.whitelistNames.add(trimmed);
			}
		}

		this.whitelistUuids.clear();
		for (UUID uuid : input.listOrEmpty("whitelist_uuids", UUIDUtil.CODEC)) {
			if (this.whitelistUuids.size() >= MAX_WHITELIST_SIZE) {
				break;
			}

			this.whitelistUuids.add(uuid);
		}

		this.whitelistNameToUuid.clear();
		this.whitelistNameToUuid.putAll(input.read("whitelist_name_uuids", NAME_TO_UUID_CODEC).orElse(Map.of()));
		// fuel_seconds/cooldown_until load clamps (D4): a negative or absurd value
		// edited into the NBT must not leak into the drain/cooldown math. The
		// remaining cooldown is additionally capped against the maximum possible
		// break cooldown on the first server tick after load (the state holder has
		// no game time here); see BubbleShieldBlockEntity.
		this.fuelSeconds = Math.clamp(input.getIntOr("fuel_seconds", 0), 0, MAX_LOADED_FUEL_SECONDS);
		this.cooldownUntil = Math.max(0L, input.getLongOr("cooldown_until", 0L));
		// Accumulators clamp into [0, their firing threshold]: a tampered value can
		// at worst fire one drain/regen pulse immediately, never skip payments.
		this.drainAccum = Math.clamp(input.getIntOr("drain_accum", 0), 0, ShieldLogic.MAX_DRAIN_INTERVAL_TICKS);
		this.regenAccum = Math.clamp(input.getIntOr("regen_accum", 0), 0, ShieldLogic.REGEN_PERIOD_TICKS);
		this.lastHitGameTime = Math.max(0L, input.getLongOr("last_hit_game_time", 0L));
		this.absorbedTotal = sanitizeLoadedFloat(input.getFloatOr("absorbed_total", 0.0F), 0.0F, Float.MAX_VALUE, 0.0F);
		this.readyAnnounced = input.getBooleanOr("ready_announced", true);
		// Clamp >= 0 here; the block entity caps a tampered far-future value against
		// the level clock on the first tick after load (like cooldown_until above).
		this.alarmUntilGameTime = Math.max(0L, input.getLongOr("alarm_until", 0L));

		// Threat log hardening: recordThreat re-sanitizes every field (name filter +
		// 16-char cap, damage NaN/negative clamp, game time >= 0) and the ring buffer
		// keeps only the LAST (most recent) THREAT_LOG_MAX entries, so an oversized
		// or poisoned list edited into the NBT can never survive the load.
		this.threatLog.clear();
		for (ThreatLogEntry entry : input.listOrEmpty("threat_log", ThreatLogEntry.CODEC)) {
			this.recordThreat(entry.attackerName(), entry.damage(), entry.gameTime());
		}
	}
}

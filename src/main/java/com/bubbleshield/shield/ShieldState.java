package com.bubbleshield.shield;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mojang.serialization.Codec;

import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import org.jspecify.annotations.Nullable;

/**
 * Plain mutable data holder for the state of a bubble shield projector.
 */
public class ShieldState {
	public static final float DEFAULT_TARGET_RADIUS = 16.0F;
	public static final float DEFAULT_MAX_HEALTH = 100.0F;

	public boolean active;
	public int effectId;
	public ShieldShape shape = ShieldShape.SPHERE;
	public float targetRadius = DEFAULT_TARGET_RADIUS;
	public float health = DEFAULT_MAX_HEALTH;
	public float maxHealth = DEFAULT_MAX_HEALTH;
	public @Nullable UUID ownerUuid;
	/** Owner-set display name for the shield's boss bar; empty means "use the effect name". */
	public String customName = "";
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
		output.putFloat("target_radius", this.targetRadius);
		output.putFloat("health", this.health);
		output.putFloat("max_health", this.maxHealth);
		output.storeNullable("owner_uuid", UUIDUtil.CODEC, this.ownerUuid);
		output.putString("custom_name", this.customName);

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
		this.effectId = input.getIntOr("effect_id", 0);
		this.shape = ShieldShape.byOrdinal(input.getIntOr("shape", 0));
		this.targetRadius = input.getFloatOr("target_radius", DEFAULT_TARGET_RADIUS);
		this.health = input.getFloatOr("health", DEFAULT_MAX_HEALTH);
		this.maxHealth = input.getFloatOr("max_health", DEFAULT_MAX_HEALTH);
		this.ownerUuid = input.read("owner_uuid", UUIDUtil.CODEC).orElse(null);
		this.customName = input.getStringOr("custom_name", "");

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

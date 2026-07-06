package com.bubbleshield.advancements;

import java.util.UUID;

import com.bubbleshield.BubbleShield;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import org.jspecify.annotations.Nullable;

/**
 * The mod's advancement criterion triggers. {@link #init()} must run during mod
 * initialization, before datapack advancements referencing these triggers are loaded.
 */
public final class ModCriteria {
	public static final ShieldActivatedTrigger SHIELD_ACTIVATED = Registry.register(
		BuiltInRegistries.TRIGGER_TYPES, BubbleShield.id("shield_activated"), new ShieldActivatedTrigger());
	public static final ShieldBrokenTrigger SHIELD_BROKEN = Registry.register(
		BuiltInRegistries.TRIGGER_TYPES, BubbleShield.id("shield_broken"), new ShieldBrokenTrigger());
	public static final PlayerWhitelistedTrigger PLAYER_WHITELISTED = Registry.register(
		BuiltInRegistries.TRIGGER_TYPES, BubbleShield.id("player_whitelisted"), new PlayerWhitelistedTrigger());

	private ModCriteria() {
	}

	public static void init() {
		// Static initializers above perform the registrations.
	}

	/** Fires {@link #SHIELD_BROKEN} for the shield's owner if they are online. */
	public static void fireShieldBroken(ServerLevel level, @Nullable UUID ownerUuid) {
		if (ownerUuid == null) {
			return;
		}

		ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerUuid);
		if (owner != null) {
			SHIELD_BROKEN.trigger(owner);
		}
	}
}

package com.bubbleshield.net;

import java.util.List;
import java.util.UUID;

import com.bubbleshield.BubbleShield;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Custom payloads for shield settings (C2S) and client-side shield replication (S2C).
 */
public final class ShieldPayloads {
	private ShieldPayloads() {
	}

	/** Client asks to change diameter/effect of the projector at {@code pos}. */
	public record SetSettingsC2S(BlockPos pos, int diameter, int effectId) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<SetSettingsC2S> TYPE = new CustomPacketPayload.Type<>(BubbleShield.id("set_settings"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SetSettingsC2S> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, SetSettingsC2S::pos,
			ByteBufCodecs.VAR_INT, SetSettingsC2S::diameter,
			ByteBufCodecs.VAR_INT, SetSettingsC2S::effectId,
			SetSettingsC2S::new
		);

		@Override
		public CustomPacketPayload.Type<SetSettingsC2S> type() {
			return TYPE;
		}
	}

	/** Client asks to add/remove a player name on the whitelist of the projector at {@code pos}. */
	public record WhitelistModifyC2S(BlockPos pos, String name, boolean add) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<WhitelistModifyC2S> TYPE = new CustomPacketPayload.Type<>(BubbleShield.id("whitelist_modify"));
		public static final StreamCodec<RegistryFriendlyByteBuf, WhitelistModifyC2S> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, WhitelistModifyC2S::pos,
			ByteBufCodecs.STRING_UTF8, WhitelistModifyC2S::name,
			ByteBufCodecs.BOOL, WhitelistModifyC2S::add,
			WhitelistModifyC2S::new
		);

		@Override
		public CustomPacketPayload.Type<WhitelistModifyC2S> type() {
			return TYPE;
		}
	}

	/** Client asks to (de)activate the projector at {@code pos}. */
	public record SetActiveC2S(BlockPos pos, boolean active) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<SetActiveC2S> TYPE = new CustomPacketPayload.Type<>(BubbleShield.id("set_active"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SetActiveC2S> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, SetActiveC2S::pos,
			ByteBufCodecs.BOOL, SetActiveC2S::active,
			SetActiveC2S::new
		);

		@Override
		public CustomPacketPayload.Type<SetActiveC2S> type() {
			return TYPE;
		}
	}

	/**
	 * Full shield snapshot for the client-side replica.
	 *
	 * <p>{@link StreamCodec#composite} supports up to 12 fields in this Minecraft version,
	 * so the 9 fields fit in a single composite.
	 */
	public record ShieldSyncS2C(
		BlockPos pos,
		boolean active,
		int effectId,
		float targetRadius,
		float currentRadius,
		float healthFrac,
		List<UUID> whitelist,
		List<String> whitelistNames,
		int cooldownSeconds
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<ShieldSyncS2C> TYPE = new CustomPacketPayload.Type<>(BubbleShield.id("shield_sync"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ShieldSyncS2C> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, ShieldSyncS2C::pos,
			ByteBufCodecs.BOOL, ShieldSyncS2C::active,
			ByteBufCodecs.VAR_INT, ShieldSyncS2C::effectId,
			ByteBufCodecs.FLOAT, ShieldSyncS2C::targetRadius,
			ByteBufCodecs.FLOAT, ShieldSyncS2C::currentRadius,
			ByteBufCodecs.FLOAT, ShieldSyncS2C::healthFrac,
			UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list()), ShieldSyncS2C::whitelist,
			ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ShieldSyncS2C::whitelistNames,
			ByteBufCodecs.VAR_INT, ShieldSyncS2C::cooldownSeconds,
			ShieldSyncS2C::new
		);

		@Override
		public CustomPacketPayload.Type<ShieldSyncS2C> type() {
			return TYPE;
		}
	}

	/** Tells clients that the shield at {@code pos} no longer exists. */
	public record ShieldRemoveS2C(BlockPos pos) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<ShieldRemoveS2C> TYPE = new CustomPacketPayload.Type<>(BubbleShield.id("shield_remove"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ShieldRemoveS2C> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, ShieldRemoveS2C::pos,
			ShieldRemoveS2C::new
		);

		@Override
		public CustomPacketPayload.Type<ShieldRemoveS2C> type() {
			return TYPE;
		}
	}

	public static void registerTypes() {
		PayloadTypeRegistry.serverboundPlay().register(SetSettingsC2S.TYPE, SetSettingsC2S.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(WhitelistModifyC2S.TYPE, WhitelistModifyC2S.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SetActiveC2S.TYPE, SetActiveC2S.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ShieldSyncS2C.TYPE, ShieldSyncS2C.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ShieldRemoveS2C.TYPE, ShieldRemoveS2C.CODEC);
	}
}

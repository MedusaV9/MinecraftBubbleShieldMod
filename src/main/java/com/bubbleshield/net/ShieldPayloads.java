package com.bubbleshield.net;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.bubbleshield.BubbleShield;

import io.netty.buffer.ByteBuf;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Custom payloads for shield settings (C2S) and client-side shield replication (S2C).
 */
public final class ShieldPayloads {
	private ShieldPayloads() {
	}

	/** Client asks to change diameter/effect/shape (ordinal) of the projector at {@code pos}. */
	public record SetSettingsC2S(BlockPos pos, int diameter, int effectId, int shapeOrdinal) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<SetSettingsC2S> TYPE = new CustomPacketPayload.Type<>(BubbleShield.id("set_settings"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SetSettingsC2S> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, SetSettingsC2S::pos,
			ByteBufCodecs.VAR_INT, SetSettingsC2S::diameter,
			ByteBufCodecs.VAR_INT, SetSettingsC2S::effectId,
			ByteBufCodecs.VAR_INT, SetSettingsC2S::shapeOrdinal,
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
			// Player names are at most 16 characters; a bounded codec rejects oversized payloads at decode time.
			ByteBufCodecs.stringUtf8(16), WhitelistModifyC2S::name,
			ByteBufCodecs.BOOL, WhitelistModifyC2S::add,
			WhitelistModifyC2S::new
		);

		@Override
		public CustomPacketPayload.Type<WhitelistModifyC2S> type() {
			return TYPE;
		}
	}

	/**
	 * Client asks to set the custom shield name of the projector at {@code pos}.
	 * An empty name clears the custom name. Server-side sanitization (trim, control
	 * character strip, 32-char cap) happens in {@code ServerNet}; the bounded codec
	 * rejects grossly oversized payloads at decode time.
	 */
	public record SetNameC2S(BlockPos pos, String name) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<SetNameC2S> TYPE = new CustomPacketPayload.Type<>(BubbleShield.id("set_name"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SetNameC2S> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, SetNameC2S::pos,
			ByteBufCodecs.stringUtf8(32), SetNameC2S::name,
			SetNameC2S::new
		);

		@Override
		public CustomPacketPayload.Type<SetNameC2S> type() {
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
	 * The renderable core of a shield snapshot, nested inside {@link ShieldSyncS2C} with
	 * its own codec so the outer {@link StreamCodec#composite} (capped at 12 fields in
	 * this Minecraft version) keeps free slots for future fields (e.g. tier/shape).
	 */
	public record ShieldVisual(
		boolean active,
		int effectId,
		float targetRadius,
		float currentRadius,
		float healthFrac,
		int tier,
		int shape
	) {
		public static final StreamCodec<ByteBuf, ShieldVisual> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, ShieldVisual::active,
			ByteBufCodecs.VAR_INT, ShieldVisual::effectId,
			ByteBufCodecs.FLOAT, ShieldVisual::targetRadius,
			ByteBufCodecs.FLOAT, ShieldVisual::currentRadius,
			ByteBufCodecs.FLOAT, ShieldVisual::healthFrac,
			ByteBufCodecs.VAR_INT, ShieldVisual::tier,
			ByteBufCodecs.VAR_INT, ShieldVisual::shape,
			ShieldVisual::new
		);
	}

	/**
	 * Full shield snapshot for the client-side replica. The dimension travels with the
	 * snapshot so clients never render "ghost" shields synced from another dimension.
	 */
	public record ShieldSyncS2C(
		BlockPos pos,
		ResourceKey<Level> dimension,
		ShieldVisual visual,
		List<UUID> whitelist,
		List<String> whitelistNames,
		int cooldownSeconds,
		Optional<UUID> ownerUuid,
		String customName
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<ShieldSyncS2C> TYPE = new CustomPacketPayload.Type<>(BubbleShield.id("shield_sync"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ShieldSyncS2C> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, ShieldSyncS2C::pos,
			ResourceKey.streamCodec(Registries.DIMENSION), ShieldSyncS2C::dimension,
			ShieldVisual.STREAM_CODEC, ShieldSyncS2C::visual,
			UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list()), ShieldSyncS2C::whitelist,
			ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ShieldSyncS2C::whitelistNames,
			ByteBufCodecs.VAR_INT, ShieldSyncS2C::cooldownSeconds,
			ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), ShieldSyncS2C::ownerUuid,
			ByteBufCodecs.stringUtf8(32), ShieldSyncS2C::customName,
			ShieldSyncS2C::new
		);

		@Override
		public CustomPacketPayload.Type<ShieldSyncS2C> type() {
			return TYPE;
		}
	}

	/** Tells clients that the shield at {@code pos} in {@code dimension} no longer exists. */
	public record ShieldRemoveS2C(BlockPos pos, ResourceKey<Level> dimension) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<ShieldRemoveS2C> TYPE = new CustomPacketPayload.Type<>(BubbleShield.id("shield_remove"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ShieldRemoveS2C> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, ShieldRemoveS2C::pos,
			ResourceKey.streamCodec(Registries.DIMENSION), ShieldRemoveS2C::dimension,
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
		PayloadTypeRegistry.serverboundPlay().register(SetNameC2S.TYPE, SetNameC2S.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SetActiveC2S.TYPE, SetActiveC2S.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ShieldSyncS2C.TYPE, ShieldSyncS2C.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ShieldRemoveS2C.TYPE, ShieldRemoveS2C.CODEC);
	}
}

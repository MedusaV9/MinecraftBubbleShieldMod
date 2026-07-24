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
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Custom payloads for shield settings (C2S) and client-side shield replication (S2C).
 */
public final class ShieldPayloads {
	private ShieldPayloads() {
	}

	/**
	 * Client asks to change diameter/effect/shape/mode/beam (ordinals)/effect-cycle of
	 * the projector at {@code pos}. This composite sits at 7 of the 12-field
	 * {@link StreamCodec#composite} cap; if a future change would push past 12, nest a
	 * settings sub-record with its own codec the way {@link ShieldVisual} nests inside
	 * {@link ShieldSyncS2C}.
	 */
	public record SetSettingsC2S(BlockPos pos, int diameter, int effectId, int shapeOrdinal, int modeOrdinal, boolean cycleEnabled, int beamStyleOrdinal) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<SetSettingsC2S> TYPE = new CustomPacketPayload.Type<>(BubbleShield.id("set_settings"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SetSettingsC2S> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, SetSettingsC2S::pos,
			ByteBufCodecs.VAR_INT, SetSettingsC2S::diameter,
			ByteBufCodecs.VAR_INT, SetSettingsC2S::effectId,
			ByteBufCodecs.VAR_INT, SetSettingsC2S::shapeOrdinal,
			ByteBufCodecs.VAR_INT, SetSettingsC2S::modeOrdinal,
			ByteBufCodecs.BOOL, SetSettingsC2S::cycleEnabled,
			ByteBufCodecs.VAR_INT, SetSettingsC2S::beamStyleOrdinal,
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

	/**
	 * Client asks to recolor the shield of the projector at {@code pos}: {@code argb}
	 * is an opaque (0xFFrrggbb) ARGB color, or -1 to reset to the effect's authored
	 * palette. VAR_INT is fine here: -1 and opaque colors cost 5 bytes, which is
	 * irrelevant for a rare owner-initiated request. Validated server-side by
	 * {@code ServerNet.isValidColorOverride}.
	 */
	public record SetColorC2S(BlockPos pos, int argb) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<SetColorC2S> TYPE = new CustomPacketPayload.Type<>(BubbleShield.id("set_color"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SetColorC2S> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, SetColorC2S::pos,
			ByteBufCodecs.VAR_INT, SetColorC2S::argb,
			SetColorC2S::new
		);

		@Override
		public CustomPacketPayload.Type<SetColorC2S> type() {
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
	 * This nested composite itself sits at 10 of those 12 fields.
	 *
	 * <p>{@code healthFrac} drives the renderer; {@code maxHealth} (0 = unknown) lets
	 * the in-bubble HUD render absolute "HP cur/max" without a second payload.
	 * {@code colorOverride} is the owner-picked recolor: -1 = authored palette,
	 * otherwise an opaque ARGB (negative as a signed int; compare against -1, not 0).
	 * {@code beamStyle} is the synced {@link com.bubbleshield.shield.BeamStyle} ordinal.
	 */
	public record ShieldVisual(
		boolean active,
		int effectId,
		float targetRadius,
		float currentRadius,
		float healthFrac,
		float maxHealth,
		int tier,
		int shape,
		int colorOverride,
		int beamStyle
	) {
		public static final StreamCodec<ByteBuf, ShieldVisual> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, ShieldVisual::active,
			ByteBufCodecs.VAR_INT, ShieldVisual::effectId,
			ByteBufCodecs.FLOAT, ShieldVisual::targetRadius,
			ByteBufCodecs.FLOAT, ShieldVisual::currentRadius,
			ByteBufCodecs.FLOAT, ShieldVisual::healthFrac,
			ByteBufCodecs.FLOAT, ShieldVisual::maxHealth,
			ByteBufCodecs.VAR_INT, ShieldVisual::tier,
			ByteBufCodecs.VAR_INT, ShieldVisual::shape,
			ByteBufCodecs.VAR_INT, ShieldVisual::colorOverride,
			ByteBufCodecs.VAR_INT, ShieldVisual::beamStyle,
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
			// Whitelist entries are player names (at most 16 characters, enforced by
			// the C2S add path and re-applied on NBT load); the bounded per-name codec
			// rejects oversized strings at decode time like WhitelistModifyC2S does.
			ByteBufCodecs.stringUtf8(16).apply(ByteBufCodecs.list()), ShieldSyncS2C::whitelistNames,
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

	/**
	 * One shield-surface visual event, 5 bytes on the wire (kind, quantized
	 * outward unit direction, strength). {@code dx/dy/dz} are the outward unit
	 * direction from the shield center to the event point, quantized as
	 * {@code round(n * 127)}; {@code strength} is an UNSIGNED byte encoding
	 * {@code round(min(damage, 25.5) * 10)} — read it back through
	 * {@link #strengthUnsigned()} (a raw {@code byte} read would sign-flip
	 * anything above 12.7, most notably the BREAK sentinel 255). Unknown kinds
	 * (&ge; {@link #KIND_COUNT}) are skipped by the client so a newer server can
	 * add kinds without breaking older clients.
	 */
	public record ImpactEntry(byte kind, byte dx, byte dy, byte dz, byte strength) {
		public static final int KIND_IMPACT = 0;
		public static final int KIND_BREAK = 1;
		public static final int KIND_HEAL = 2;
		public static final int KIND_CONTACT = 3;
		public static final int KIND_PASSAGE_IN = 4;
		public static final int KIND_PASSAGE_OUT = 5;
		/** Exclusive upper bound of the known kinds; the client skips anything at or above it. */
		public static final int KIND_COUNT = 6;
		/** Strength saturates at 25.5 damage: the x10 byte encoding tops out at 255. */
		public static final float MAX_STRENGTH = 25.5F;

		public static final StreamCodec<ByteBuf, ImpactEntry> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.BYTE, ImpactEntry::kind,
			ByteBufCodecs.BYTE, ImpactEntry::dx,
			ByteBufCodecs.BYTE, ImpactEntry::dy,
			ByteBufCodecs.BYTE, ImpactEntry::dz,
			ByteBufCodecs.BYTE, ImpactEntry::strength,
			ImpactEntry::new
		);

		/**
		 * Quantizes an event into its wire form: {@code dirUnit} should be a unit
		 * vector (or {@link net.minecraft.world.phys.Vec3#ZERO} for directionless
		 * events like BREAK), {@code strength} is the damage-scale value clamped
		 * into [0, {@link #MAX_STRENGTH}] before the x10 byte encoding.
		 */
		public static ImpactEntry of(int kind, Vec3 dirUnit, float strength) {
			return new ImpactEntry(
					(byte) kind,
					(byte) Math.round(dirUnit.x * 127.0),
					(byte) Math.round(dirUnit.y * 127.0),
					(byte) Math.round(dirUnit.z * 127.0),
					(byte) Math.round(Mth.clamp(strength, 0.0F, MAX_STRENGTH) * 10.0F));
		}

		/** The strength byte read UNSIGNED (0..255); see the record javadoc. */
		public int strengthUnsigned() {
			return this.strength & 0xFF;
		}

		/** The dequantized outward unit direction (approximately unit length, or zero). */
		public Vec3 dir() {
			return new Vec3(this.dx / 127.0, this.dy / 127.0, this.dz / 127.0);
		}
	}

	/**
	 * A per-tick batch of visual events for the shield at {@code pos}: at most
	 * {@link #MAX_ENTRIES} {@link ImpactEntry} rows, coalesced by
	 * {@code BubbleShieldBlockEntity.flushImpacts} (one batch per shield per tick,
	 * sent OUTSIDE the sync diff gate so pure events are never swallowed by an
	 * unchanged snapshot). The dimension travels with the batch like
	 * {@link ShieldSyncS2C} so cross-dimension events are never applied locally.
	 */
	public record ImpactBatchS2C(BlockPos pos, ResourceKey<Level> dimension, List<ImpactEntry> entries) implements CustomPacketPayload {
		/** Hard cap on entries per batch; the bounded list codec rejects oversized payloads at decode time. */
		public static final int MAX_ENTRIES = 8;
		public static final CustomPacketPayload.Type<ImpactBatchS2C> TYPE = new CustomPacketPayload.Type<>(BubbleShield.id("impact_batch"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ImpactBatchS2C> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, ImpactBatchS2C::pos,
			ResourceKey.streamCodec(Registries.DIMENSION), ImpactBatchS2C::dimension,
			// Bounded like WhitelistModifyC2S's stringUtf8(16): decode-time rejection.
			ImpactEntry.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ENTRIES)), ImpactBatchS2C::entries,
			ImpactBatchS2C::new
		);

		@Override
		public CustomPacketPayload.Type<ImpactBatchS2C> type() {
			return TYPE;
		}
	}

	public static void registerTypes() {
		PayloadTypeRegistry.serverboundPlay().register(SetSettingsC2S.TYPE, SetSettingsC2S.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(WhitelistModifyC2S.TYPE, WhitelistModifyC2S.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SetNameC2S.TYPE, SetNameC2S.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SetColorC2S.TYPE, SetColorC2S.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SetActiveC2S.TYPE, SetActiveC2S.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ShieldSyncS2C.TYPE, ShieldSyncS2C.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ShieldRemoveS2C.TYPE, ShieldRemoveS2C.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ImpactBatchS2C.TYPE, ImpactBatchS2C.CODEC);
	}
}

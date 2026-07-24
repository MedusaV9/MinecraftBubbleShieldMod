package com.bubbleshield.command;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.EffectRegistry;
import com.bubbleshield.menu.BubbleShieldMenu;
import com.bubbleshield.net.ServerNet;
import com.bubbleshield.shield.ShieldLogic;
import com.bubbleshield.shield.ShieldState;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

/**
 * The {@code /bubbleshield} command: browse the effect catalogue ({@code list [page]},
 * {@code info <id>}), retune the nearest owned projector ({@code set <id>}), and read
 * its telemetry ({@code log} — the B6 threat log; {@code status} — the full stat
 * sheet). {@code log}/{@code status} are strictly READ-ONLY: unlike {@code set} they
 * never claim an ownerless projector.
 *
 * <p>Server-authoritative like every other mutation path: {@code set} applies the same
 * owner/claim rule as the C2S payloads ({@link ServerNet#isOwner}) and clamps the
 * effect id into the catalogue range before touching the shield. All feedback goes
 * through translatable keys so EN/DE stay in lockstep with the rest of the mod.
 */
public final class BubbleShieldCommand {
	/** {@code set}/{@code log}/{@code status} only address a projector within this distance of the sender. */
	public static final double MAX_TARGET_DISTANCE = 16.0;
	/** {@code list} prints this many "id: Name" lines per page. */
	public static final int LIST_PAGE_SIZE = 10;
	/** Number of {@code list} pages for the {@link EffectRegistry#COUNT}-effect catalogue. */
	public static final int LIST_PAGE_COUNT = (EffectRegistry.COUNT + LIST_PAGE_SIZE - 1) / LIST_PAGE_SIZE;

	private BubbleShieldCommand() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> dispatcher.register(
			Commands.literal("bubbleshield")
				.then(Commands.literal("list")
					.executes(ctx -> list(ctx.getSource(), 1))
					.then(Commands.argument("page", IntegerArgumentType.integer(1))
						.executes(ctx -> list(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "page")))))
				.then(Commands.literal("info")
					.then(Commands.argument("id", IntegerArgumentType.integer(0))
						.executes(ctx -> info(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id")))))
				.then(Commands.literal("set")
					.then(Commands.argument("id", IntegerArgumentType.integer())
						.executes(ctx -> set(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id")))))
				.then(Commands.literal("log")
					.executes(ctx -> log(ctx.getSource())))
				.then(Commands.literal("status")
					.executes(ctx -> status(ctx.getSource())))
		));
	}

	/**
	 * E4 {@code /bubbleshield log}: prints the B6 threat log of the nearest owned
	 * (or ownerless) projector within {@link #MAX_TARGET_DISTANCE} blocks, oldest
	 * entry first, as localized "Ns ago: name (-D.D)" lines. Read-only: never
	 * claims an ownerless projector.
	 *
	 * @return the number of log lines printed (1 for the localized empty notice).
	 */
	private static int log(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		BubbleShieldBlockEntity nearest = nearestRetunableProjector(player);
		if (nearest == null) {
			source.sendFailure(Component.translatable("command.bubbleshield.set.no_projector", (int) MAX_TARGET_DISTANCE));
			return 0;
		}

		List<ShieldState.ThreatLogEntry> entries = nearest.getShieldState().threatLog();
		if (entries.isEmpty()) {
			source.sendSuccess(() -> Component.translatable("command.bubbleshield.log.empty"), false);
			return Command.SINGLE_SUCCESS;
		}

		long gameTime = player.level().getGameTime();
		for (ShieldState.ThreatLogEntry entry : entries) {
			long secondsAgo = Math.max(0L, (gameTime - entry.gameTime()) / 20L);
			String damage = String.format(Locale.ROOT, "%.1f", entry.damage());
			source.sendSuccess(
				() -> Component.translatable("command.bubbleshield.log.entry", secondsAgo, entry.attackerName(), damage),
				false);
		}

		return entries.size();
	}

	/**
	 * E4 {@code /bubbleshield status}: the nearest owned (or ownerless) projector's
	 * full stat sheet — HP, tier + combined DR (the same
	 * {@link ShieldLogic#combinedDr} the damage pipeline applies), regen/min,
	 * drain/min with the time-to-empty projection, cooldown (or ready), strength
	 * gamerule and live threat count. The rate/cooldown/threat values are read
	 * through the projector's OWN menu {@code ContainerData} snapshot, so the
	 * command can never drift from what the GUI shows. Read-only: never claims an
	 * ownerless projector.
	 */
	private static int status(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		BubbleShieldBlockEntity nearest = nearestRetunableProjector(player);
		if (nearest == null) {
			source.sendFailure(Component.translatable("command.bubbleshield.set.no_projector", (int) MAX_TARGET_DISTANCE));
			return 0;
		}

		ShieldState state = nearest.getShieldState();
		int tier = nearest.tier();
		int drPercent = Math.round(ShieldLogic.combinedDr(tier, nearest.platingDr()) * 100.0F);
		var data = nearest.getMenuData();
		int regenX10 = data.get(BubbleShieldMenu.DATA_REGEN_PER_MIN_X10);
		int drainX10 = data.get(BubbleShieldMenu.DATA_DRAIN_PER_MIN_X10);
		int cooldownSeconds = data.get(BubbleShieldMenu.DATA_COOLDOWN_SECONDS);
		int threats = data.get(BubbleShieldMenu.DATA_THREAT_COUNT);

		source.sendSuccess(() -> Component.translatable("command.bubbleshield.status.hp",
				Math.round(state.health), Math.round(state.maxHealth)), false);
		source.sendSuccess(() -> Component.translatable("command.bubbleshield.status.tier", tier, drPercent), false);
		source.sendSuccess(() -> Component.translatable("command.bubbleshield.status.regen", perMinute(regenX10)), false);
		if (drainX10 > 0) {
			// Time-to-empty at the steady baseline drain: fuelSeconds / (drainX10/10 per minute).
			long emptySeconds = state.fuelSeconds * 600L / drainX10;
			source.sendSuccess(() -> Component.translatable("command.bubbleshield.status.drain",
					perMinute(drainX10), ShieldLogic.formatMinutesSeconds(emptySeconds)), false);
		} else {
			source.sendSuccess(() -> Component.translatable("command.bubbleshield.status.drain_idle", perMinute(drainX10)), false);
		}

		source.sendSuccess(() -> cooldownSeconds > 0
				? Component.translatable("command.bubbleshield.status.cooldown", ShieldLogic.formatMinutesSeconds(cooldownSeconds))
				: Component.translatable("command.bubbleshield.status.cooldown_ready"), false);
		source.sendSuccess(() -> Component.translatable("command.bubbleshield.status.strength", nearest.strengthPercent()), false);
		source.sendSuccess(() -> Component.translatable("command.bubbleshield.status.threats", threats), false);
		return Command.SINGLE_SUCCESS;
	}

	/** Formats a per-minute-x10 data-slot value as "X.X" (locale-stable). */
	private static String perMinute(int x10) {
		return String.format(Locale.ROOT, "%.1f", x10 / 10.0F);
	}

	/**
	 * Prints one page (10 entries) of the effect catalogue as "id: Name" lines,
	 * with the name resolved through the effect's translatable {@code nameKey}.
	 *
	 * @return the number of entries printed.
	 */
	private static int list(CommandSourceStack source, int page) {
		int clampedPage = Mth.clamp(page, 1, LIST_PAGE_COUNT);
		int first = (clampedPage - 1) * LIST_PAGE_SIZE;
		int last = Math.min(first + LIST_PAGE_SIZE, EffectRegistry.COUNT);
		source.sendSuccess(() -> Component.translatable("command.bubbleshield.list.header", clampedPage, LIST_PAGE_COUNT), false);
		for (int id = first; id < last; id++) {
			EffectDefinition def = EffectRegistry.get(id);
			int effectId = id;
			source.sendSuccess(
				() -> Component.translatable("command.bubbleshield.list.entry", effectId, Component.translatable(def.nameKey())),
				false);
		}

		return last - first;
	}

	/**
	 * Prints the effect's four-axis breakdown, reusing exactly the translatable axis
	 * labels the (client-only) EffectPickerScreen tooltip composes: surface template,
	 * inside behavior, guard style and context profile.
	 */
	private static int info(CommandSourceStack source, int rawId) {
		int id = Mth.clamp(rawId, 0, EffectRegistry.COUNT - 1);
		EffectDefinition def = EffectRegistry.get(id);
		source.sendSuccess(() -> Component.translatable("command.bubbleshield.info.header", id, Component.translatable(def.nameKey())), false);
		source.sendSuccess(() -> Component.translatable("surface.bubbleshield." + def.surface().name().toLowerCase(Locale.ROOT)), false);
		source.sendSuccess(() -> Component.translatable("behavior.bubbleshield." + def.insideBehaviorId()), false);
		source.sendSuccess(() -> Component.translatable("guard.bubbleshield." + def.guard().name().toLowerCase(Locale.ROOT)), false);
		source.sendSuccess(() -> Component.translatable("context.bubbleshield." + def.context().name().toLowerCase(Locale.ROOT)), false);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * Applies the (clamped) effect id to the nearest RETUNABLE projector (owned by
	 * the sender, or still ownerless) within {@link #MAX_TARGET_DISTANCE} blocks of
	 * the sender, gated by the same owner/claim rule as the GUI payloads, keeping
	 * diameter/shape/mode/cycle. Filtering during the search means a neighbor's
	 * closer projector never shadows your own.
	 */
	private static int set(CommandSourceStack source, int rawId) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		BubbleShieldBlockEntity nearest = nearestRetunableProjector(player);
		if (nearest == null) {
			source.sendFailure(Component.translatable("command.bubbleshield.set.no_projector", (int) MAX_TARGET_DISTANCE));
			return 0;
		}

		// The claim side-effect (an ownerless shield adopts the sender) fires exactly
		// once, on the selected shield only; the search itself is side-effect free.
		if (!ServerNet.isOwner(player, nearest)) {
			source.sendFailure(Component.translatable("command.bubbleshield.set.not_owner"));
			return 0;
		}

		int effectId = Mth.clamp(rawId, 0, EffectRegistry.COUNT - 1);
		ShieldState state = nearest.getShieldState();
		nearest.setSettings(
			Math.round(state.targetRadius * 2.0F),
			effectId,
			state.shape.ordinal(),
			state.mode.ordinal(),
			state.cycleEffect,
			state.beamStyle.ordinal());
		source.sendSuccess(
			() -> Component.translatable("command.bubbleshield.set.success", effectId, Component.translatable(EffectRegistry.get(effectId).nameKey())),
			false);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * @return the loaded retunable (owned by the player, or ownerless) projector
	 * nearest to the player, or null when none sits within
	 * {@link #MAX_TARGET_DISTANCE} blocks. The player's level and position are used
	 * as ONE consistent frame. The ownership filter is a PURE predicate — the
	 * claiming side-effect of {@link ServerNet#isOwner} must only fire on the single
	 * selected shield, never on every candidate scanned. Only consults
	 * already-loaded shields ({@link ServerNet#loadedShields}), so the command can
	 * never force a chunk load.
	 */
	private static @Nullable BubbleShieldBlockEntity nearestRetunableProjector(ServerPlayer player) {
		BubbleShieldBlockEntity nearest = null;
		double best = MAX_TARGET_DISTANCE;
		for (BubbleShieldBlockEntity shield : ServerNet.loadedShields(player.level())) {
			UUID owner = shield.getShieldState().ownerUuid;
			if (owner != null && !owner.equals(player.getUUID())) {
				continue;
			}

			double distance = player.position().distanceTo(Vec3.atCenterOf(shield.getBlockPos()));
			if (distance <= best) {
				best = distance;
				nearest = shield;
			}
		}

		return nearest;
	}
}

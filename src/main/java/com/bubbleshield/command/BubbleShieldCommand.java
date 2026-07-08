package com.bubbleshield.command;

import java.util.Locale;

import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.EffectRegistry;
import com.bubbleshield.net.ServerNet;
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
 * {@code info <id>}) and retune the nearest owned projector ({@code set <id>}).
 *
 * <p>Server-authoritative like every other mutation path: {@code set} applies the same
 * owner/claim rule as the C2S payloads ({@link ServerNet#isOwner}) and clamps the
 * effect id into the catalogue range before touching the shield. All feedback goes
 * through translatable keys so EN/DE stay in lockstep with the rest of the mod.
 */
public final class BubbleShieldCommand {
	/** {@code set} only touches a projector within this distance of the sender. */
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
		));
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
	 * Applies the (clamped) effect id to the nearest projector within
	 * {@link #MAX_TARGET_DISTANCE} blocks of the sender, gated by the same
	 * owner/claim rule as the GUI payloads, keeping diameter/shape/mode/cycle.
	 */
	private static int set(CommandSourceStack source, int rawId) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		BubbleShieldBlockEntity nearest = nearestProjector(player, source.getPosition());
		if (nearest == null) {
			source.sendFailure(Component.translatable("command.bubbleshield.set.no_projector", (int) MAX_TARGET_DISTANCE));
			return 0;
		}

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
			state.cycleEffect);
		source.sendSuccess(
			() -> Component.translatable("command.bubbleshield.set.success", effectId, Component.translatable(EffectRegistry.get(effectId).nameKey())),
			false);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * @return the loaded projector nearest to {@code position} in the player's level,
	 * or null when none sits within {@link #MAX_TARGET_DISTANCE} blocks. Only consults
	 * already-loaded shields ({@link ServerNet#loadedShields}), so the command can
	 * never force a chunk load.
	 */
	private static @Nullable BubbleShieldBlockEntity nearestProjector(ServerPlayer player, Vec3 position) {
		BubbleShieldBlockEntity nearest = null;
		double best = MAX_TARGET_DISTANCE;
		for (BubbleShieldBlockEntity shield : ServerNet.loadedShields(player.level())) {
			double distance = position.distanceTo(Vec3.atCenterOf(shield.getBlockPos()));
			if (distance <= best) {
				best = distance;
				nearest = shield;
			}
		}

		return nearest;
	}
}

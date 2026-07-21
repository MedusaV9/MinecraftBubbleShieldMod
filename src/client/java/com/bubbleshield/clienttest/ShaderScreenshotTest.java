package com.bubbleshield.clienttest;

import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.EffectRegistry;
import com.bubbleshield.effect.SurfaceTemplate;
import com.bubbleshield.registry.ModBlocks;
import com.bubbleshield.shield.BeamStyle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldBuilder;

import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Automated visual capture harness for the bubble shield surface shaders and in-bubble
 * screen post-effects. Dev-only: runs via {@code ./gradlew runClientGameTest} (Loom's
 * {@code enableClientGameTests}); on this repo's headless VMs it needs
 * {@code DISPLAY=:1 LIBGL_ALWAYS_SOFTWARE=1 MESA_GL_VERSION_OVERRIDE=4.5} (llvmpipe).
 *
 * <p>Creates a flat creative world, raises one shield projector and, for a
 * representative effect set (the lowest catalogue id of each of the 24
 * {@link SurfaceTemplate} families), retunes the live shield server-side and captures a
 * framed screenshot of the rendered bubble. Also captures a handful of inside shots
 * (screen post-effect + shield boss bar), one dome-shape shot, and — ALWAYS, as part of
 * the default set — one shot per rendered projector-beam style
 * ({@code beam_<style>.png} for every {@code BeamStyle.RENDERED} entry, effect {@value #BEAM_EFFECT_ID} from a
 * pulled-back camera framing the whole column), so the beam is visually reviewable on
 * every harness run.
 *
 * <p>Screenshots are written to {@code /tmp/bubble_captures} (NOT the run dir, which
 * Loom clears on every run). Override with env vars:
 * <ul>
 *   <li>{@code BUBBLESHIELD_CAPTURE_DIR} — destination directory;</li>
 *   <li>{@code BUBBLESHIELD_CAPTURE_IDS} — comma-separated effect ids replacing the
 *       default one-per-surface-family outside set;</li>
 *   <li>{@code BUBBLESHIELD_CAPTURE_BEAM} — {@link com.bubbleshield.shield.BeamStyle}
 *       ordinal applied to every NON-beam capture retune (default 0 = NONE; 2..9 = the
 *       rendered STORM/PULSE/HELIX/PRISM/VOID/EMBER/RUNIC/FROST styles), so any
 *       effect/beam combination can be screenshotted without code edits. The dedicated
 *       per-style beam shots always run regardless.</li>
 * </ul>
 */
public class ShaderScreenshotTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("bubbleshield-capture");

	/** Absolute projector position on the flat world's surface (ground level y = -60). */
	private static final BlockPos PROJECTOR_POS = new BlockPos(8, -60, 0);
	/** Captured shield diameter; radius 6 frames fully in an 854x480 window from the outside camera. */
	private static final int DIAMETER = 12;
	/** Outside camera: ~13.5 blocks west of the bubble center, looking east, slightly down. */
	private static final String OUTSIDE_CAMERA = "tp @a -5 -58 0 -90 12";
	/** Inside camera: ~2 east of the projector, looking east through the bubble's far wall. */
	private static final String INSIDE_CAMERA = "tp @a 10.5 -60 0.5 -90 5";
	/**
	 * Beam camera: pulled back (~20.5 blocks) and pitched up 10 degrees so the whole
	 * energy column — base flare, membrane crossing and top fade (12 blocks above the
	 * center at radius 6) — fits the frame.
	 */
	private static final String BEAM_CAMERA = "tp @a -12 -58 0 -90 -10";
	/** Ticks to wait after a server-side effect retune for S2C sync + a few rendered frames. */
	private static final int SETTLE_TICKS = 20;
	/**
	 * Effect used for the dedicated per-beam-style shots: fx_006 (pink/purple "arcs"
	 * palette 0xFF66E0/0x5500BB) — strongly hued, so any beam whiteout/clipping
	 * regression is immediately visible against it.
	 */
	private static final int BEAM_EFFECT_ID = 6;

	/** Screen-fx families captured from inside the bubble (post-effect + boss bar shots). */
	private static final List<String> INSIDE_SCREEN_FAMILIES =
			List.of("chroma", "pixelate", "frostlens", "edgeglow", "glitch");

	/** Effect id used for the dome-shape variant shot. */
	private static final int DOME_EFFECT_ID = 12;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		Path captureDir = Path.of(System.getenv().getOrDefault("BUBBLESHIELD_CAPTURE_DIR", "/tmp/bubble_captures"));
		try {
			Files.createDirectories(captureDir);
		} catch (Exception e) {
			throw new RuntimeException("Cannot create capture dir " + captureDir, e);
		}

		TestWorldBuilder worldBuilder = ctx.worldBuilder();
		worldBuilder.adjustSettings(settings -> {
			settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			settings.setAllowCommands(true);
		});

		int captured = 0;
		int failed = 0;
		try (TestSingleplayerContext singleplayer = worldBuilder.create()) {
			singleplayer.getClientLevel().waitForChunksRender();
			TestServerContext server = singleplayer.getServer();

			// Deterministic, clean stage: fixed light, no weather/mobs, no chat spam.
			// 26.2 gamerule ids are snake_case (advance_time, spawn_mobs, ...).
			server.runCommand("gamerule send_command_feedback false");
			server.runCommand("gamerule log_admin_commands false");
			server.runCommand("gamerule advance_time false");
			server.runCommand("gamerule advance_weather false");
			server.runCommand("gamerule spawn_mobs false");
			server.runCommand("gamerule spawn_wandering_traders false");
			server.runCommand("time set noon");
			server.runCommand("weather clear");
			server.runCommand("kill @e[type=!player]");
			server.runCommand(OUTSIDE_CAMERA);
			ctx.waitTicks(10);

			// Raise the shield (owner = the test player, so the barrier never expels the camera).
			server.runOnServer(mc -> {
				ServerLevel level = mc.overworld();
				ServerPlayer player = mc.getPlayerList().getPlayers().get(0);
				level.setBlock(PROJECTOR_POS, ModBlocks.BUBBLE_SHIELD_PROJECTOR.defaultBlockState(), 3);
				BubbleShieldBlockEntity shield = (BubbleShieldBlockEntity) level.getBlockEntity(PROJECTOR_POS);
				shield.setOwner(player);
				shield.addFuelSeconds(36000);
				shield.setSettings(DIAMETER, 0, 0, 0, false, beamOrdinal());
				if (!shield.tryActivate()) {
					throw new AssertionError("Shield failed to activate");
				}
			});
			ctx.waitTicks(40);

			// --- Outside shots: one per surface technique family, HUD hidden. ---
			// 26.2: F1's HUD hiding lives on Gui/Hud (toggle/isHidden), not Options.
			ctx.runOnClient(mc -> {
				if (!mc.gui.hud.isHidden()) {
					mc.gui.hud.toggle();
				}
			});
			for (EffectDefinition def : outsideSet()) {
				String name = String.format(Locale.ROOT, "fx_%03d_%s",
						def.id(), def.surface().name().toLowerCase(Locale.ROOT));
				if (capture(ctx, server, def.id(), 0, beamOrdinal(), name, captureDir)) {
					captured++;
				} else {
					failed++;
				}
			}

			// --- Dome-shape variant, still from outside. ---
			String domeName = String.format(Locale.ROOT, "dome_fx_%03d", DOME_EFFECT_ID);
			if (capture(ctx, server, DOME_EFFECT_ID, 1, beamOrdinal(), domeName, captureDir)) {
				captured++;
			} else {
				failed++;
			}

			// --- Every rendered beam style, ALWAYS captured (default set): ---
			// fx_006 with each BeamStyle.RENDERED entry from the pulled-back
			// camera, so every harness run leaves reviewable beam PNGs.
			server.runCommand(BEAM_CAMERA);
			ctx.waitTicks(10);
			for (BeamStyle style : BeamStyle.RENDERED) {
				String name = "beam_" + style.name().toLowerCase(Locale.ROOT);
				if (capture(ctx, server, BEAM_EFFECT_ID, 0, style.ordinal(), name, captureDir)) {
					captured++;
				} else {
					failed++;
				}
			}

			// --- Inside shots: screen post-effect + boss bar, HUD visible. ---
			ctx.runOnClient(mc -> {
				if (mc.gui.hud.isHidden()) {
					mc.gui.hud.toggle();
				}
			});
			server.runCommand(INSIDE_CAMERA);
			ctx.waitTicks(10);
			for (EffectDefinition def : insideSet()) {
				String name = String.format(Locale.ROOT, "inside_fx_%03d_%s", def.id(), def.screenTemplate());
				if (capture(ctx, server, def.id(), 0, beamOrdinal(), name, captureDir)) {
					captured++;
				} else {
					failed++;
				}
			}

			// Leave the world with the shield lowered so the close-out save is quiet.
			server.runOnServer(mc -> {
				BubbleShieldBlockEntity shield = (BubbleShieldBlockEntity) mc.overworld().getBlockEntity(PROJECTOR_POS);
				if (shield != null) {
					shield.setActive(false);
				}
			});
			ctx.waitTicks(10);
		}

		LOGGER.info("Bubble capture batch done: {} screenshots written to {}, {} failed", captured, captureDir, failed);
		if (failed > 0) {
			throw new AssertionError(failed + " captures failed (see log); " + captured + " succeeded");
		}
	}

	/**
	 * Retunes the live shield to the given effect/shape/beam, waits for sync + render,
	 * then takes a screenshot. Failures are logged and reported, never abort the batch.
	 */
	private boolean capture(ClientGameTestContext ctx, TestServerContext server,
			int effectId, int shapeOrdinal, int beamStyleOrdinal, String name, Path captureDir) {
		try {
			server.runOnServer(mc -> {
				BubbleShieldBlockEntity shield = (BubbleShieldBlockEntity) mc.overworld().getBlockEntity(PROJECTOR_POS);
				shield.setSettings(DIAMETER, effectId, shapeOrdinal, 0, false, beamStyleOrdinal);
			});
			ctx.waitTicks(SETTLE_TICKS);
			Path png = ctx.takeScreenshot(TestScreenshotOptions.of(name)
					.withDestinationDir(captureDir)
					.disableCounterPrefix());
			LOGGER.info("Captured {} -> {}", name, png);
			return true;
		} catch (Exception e) {
			LOGGER.error("Capture {} failed", name, e);
			return false;
		}
	}

	/**
	 * The {@code BUBBLESHIELD_CAPTURE_BEAM} beam-style ordinal (default 0 = NONE),
	 * applied to the non-beam captures; the dedicated beam shots pass explicit styles.
	 */
	private static int beamOrdinal() {
		String override = System.getenv("BUBBLESHIELD_CAPTURE_BEAM");
		return override == null || override.isBlank() ? 0 : Integer.parseInt(override.trim());
	}

	/**
	 * The outside capture set: from {@code BUBBLESHIELD_CAPTURE_IDS} when set, otherwise
	 * the lowest catalogue id of each {@link SurfaceTemplate} family (24 ids), derived
	 * from {@link EffectRegistry#ALL} so the set never rots as the catalogue grows.
	 */
	private static List<EffectDefinition> outsideSet() {
		String override = System.getenv("BUBBLESHIELD_CAPTURE_IDS");
		if (override != null && !override.isBlank()) {
			List<EffectDefinition> picked = new ArrayList<>();
			for (String token : override.split(",")) {
				int id = Integer.parseInt(token.trim());
				if (id >= 0 && id < EffectRegistry.COUNT) {
					picked.add(EffectRegistry.ALL.get(id));
				} else {
					LOGGER.warn("Ignoring out-of-range capture id {}", id);
				}
			}

			return picked;
		}

		Map<SurfaceTemplate, EffectDefinition> byFamily = new EnumMap<>(SurfaceTemplate.class);
		for (EffectDefinition def : EffectRegistry.ALL) {
			byFamily.putIfAbsent(def.surface(), def);
		}

		return List.copyOf(byFamily.values());
	}

	/** The inside capture set: the lowest catalogue id of each dramatic screen-fx family. */
	private static List<EffectDefinition> insideSet() {
		Map<String, EffectDefinition> byScreenFamily = new LinkedHashMap<>();
		for (EffectDefinition def : EffectRegistry.ALL) {
			if (INSIDE_SCREEN_FAMILIES.contains(def.screenTemplate())) {
				byScreenFamily.putIfAbsent(def.screenTemplate(), def);
			}
		}

		return List.copyOf(byScreenFamily.values());
	}
}

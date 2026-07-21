package com.bubbleshield.clienttest;

import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.EffectRegistry;
import com.bubbleshield.effect.SurfaceTemplate;
import com.bubbleshield.registry.ModBlocks;
import com.bubbleshield.shield.BeamStyle;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
import net.minecraft.world.level.block.Blocks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Automated VIDEO capture harness for the animated bubble shield surfaces: captures
 * per-tick frame sequences (the {@code GameTime} shader uniform advances 1 tick =
 * 0.05 s even under the client-gametest runner, so one screenshot per tick encoded at
 * 20 fps replays real-time animation — see {@code tools/encode_captures.py}).
 *
 * <p>DISABLED BY DEFAULT: no-ops instantly unless the env var
 * {@code BUBBLESHIELD_VIDEO=1} is set, so the ordinary
 * {@code ./gradlew runClientGameTest} screenshot run ({@link ShaderScreenshotTest})
 * stays fast. When enabled, frame sequences are written one subdir per clip
 * ({@code <clip>/f_%04d.png}, starting at {@code f_0000.png}) under
 * {@code BUBBLESHIELD_VIDEO_DIR} (default {@code /tmp/bubble_video}), plus a
 * {@code manifest.json} describing every clip (effect id/family, camera mode, frame
 * count). Clips:
 * <ul>
 *   <li>6 "hero" outside clips ({@value #HERO_FRAMES} frames, fixed camera) of
 *       contrasting surface families — plasma, lightning, crystalrefract (glassy),
 *       stainedglass, portalvoid (dark) and nebula — framed against the striped
 *       red/white backdrop so refraction motion is visible;</li>
 *   <li>2 orbit clips ({@value #ORBIT_FRAMES} frames, camera revolving
 *       {@value #ORBIT_STEP_DEGREES}°/frame around the shield center) of the
 *       holoparallax and galaxyswirl families — the only way to judge parallax
 *       depth vs a flat decal;</li>
 *   <li>1 inside clip ({@value #INSIDE_FRAMES} frames, camera inside the bubble, HUD
 *       visible) showing the inside-behavior particles + screen post-effect;</li>
 *   <li>1 beam clip ({@value #BEAM_FRAMES} frames, pulled-back camera) of the STORM
 *       rendered beam style.</li>
 * </ul>
 */
public class VideoCaptureTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("bubbleshield-video");

	/** Same stage geometry as {@link ShaderScreenshotTest}: flat-world surface, y = -60. */
	private static final BlockPos PROJECTOR_POS = new BlockPos(8, -60, 0);
	private static final int DIAMETER = 12;
	private static final String OUTSIDE_CAMERA = "tp @a -5 -58 0 -90 12";
	private static final String INSIDE_CAMERA = "tp @a 10.5 -60 0.5 -90 5";
	private static final String BEAM_CAMERA = "tp @a -12 -58 0 -90 -10";
	/** Striped red/white backdrop wall plane (behind the bubble from the outside camera). */
	private static final int BACKDROP_X = 18;

	/** Bubble center (x/z) the orbit camera revolves around. */
	private static final double CENTER_X = PROJECTOR_POS.getX() + 0.5;
	private static final double CENTER_Z = PROJECTOR_POS.getZ() + 0.5;
	/** Orbit camera distance from the bubble center — matches the fixed outside camera. */
	private static final double ORBIT_RADIUS = 13.5;
	/** Orbit camera height/pitch — same eye line as the fixed outside camera. */
	private static final double ORBIT_Y = -58;
	private static final double ORBIT_PITCH = 12;
	/** Orbit start angle 180° = due west of the center, i.e. the fixed camera's side. */
	private static final double ORBIT_START_DEGREES = 180;
	private static final double ORBIT_STEP_DEGREES = 3;

	private static final int HERO_FRAMES = 40;
	private static final int ORBIT_FRAMES = 40;
	private static final int INSIDE_FRAMES = 48;
	private static final int BEAM_FRAMES = 40;

	/** Ticks waited after a server-side retune/camera move before a clip starts. */
	private static final int SETTLE_TICKS = 40;

	/** Hero clip families: contrasting looks incl. glassy, lightning, stainedglass, dark, plasma/nebula. */
	private static final List<SurfaceTemplate> HERO_FAMILIES = List.of(
			SurfaceTemplate.PLASMA,
			SurfaceTemplate.LIGHTNING,
			SurfaceTemplate.CRYSTALREFRACT,
			SurfaceTemplate.STAINEDGLASS,
			SurfaceTemplate.PORTALVOID,
			SurfaceTemplate.NEBULA);

	/** Orbit clip families: visually rich/deep looks whose parallax is worth judging. */
	private static final List<SurfaceTemplate> ORBIT_FAMILIES = List.of(
			SurfaceTemplate.HOLOPARALLAX,
			SurfaceTemplate.GALAXYSWIRL);

	/** Screen-fx family of the inside clip (particles + dramatic post-effect). */
	private static final String INSIDE_SCREEN_FAMILY = "glitch";

	/** Effect for the beam clip: fx_006, the strongly-hued pink/purple arcs palette. */
	private static final int BEAM_EFFECT_ID = 6;
	private static final BeamStyle BEAM_CLIP_STYLE = BeamStyle.STORM;

	/** One captured clip, as recorded into manifest.json. */
	private record ClipRecord(String name, int effectId, String family, String screenTemplate,
			String camera, int frames, String beamStyle) {
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (!"1".equals(System.getenv("BUBBLESHIELD_VIDEO"))) {
			LOGGER.info("BUBBLESHIELD_VIDEO != 1 -> video capture skipped");
			return;
		}

		Path videoDir = Path.of(System.getenv().getOrDefault("BUBBLESHIELD_VIDEO_DIR", "/tmp/bubble_video"));
		try {
			Files.createDirectories(videoDir);
		} catch (Exception e) {
			throw new RuntimeException("Cannot create video dir " + videoDir, e);
		}

		TestWorldBuilder worldBuilder = ctx.worldBuilder();
		worldBuilder.adjustSettings(settings -> {
			settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			settings.setAllowCommands(true);
		});

		List<ClipRecord> clips = new ArrayList<>();
		int failed = 0;
		try (TestSingleplayerContext singleplayer = worldBuilder.create()) {
			singleplayer.getClientLevel().waitForChunksRender();
			TestServerContext server = singleplayer.getServer();

			// Deterministic stage: fixed light, no weather/mobs, no chat spam.
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

			// Striped backdrop FIRST (unlike the screenshot harness, every outside
			// clip wants it): refraction motion against straight stripes is the point.
			server.runOnServer(mc -> {
				ServerLevel level = mc.overworld();
				for (int z = -14; z <= 14; z++) {
					boolean red = Math.floorMod(Math.floorDiv(z, 2), 2) == 0;
					for (int y = -60; y <= -46; y++) {
						level.setBlock(new BlockPos(BACKDROP_X, y, z),
								(red ? Blocks.CONCRETE.red() : Blocks.CONCRETE.white()).defaultBlockState(), 3);
					}
				}
			});

			// Raise the shield (owner = the test player, so the barrier never expels the camera).
			server.runOnServer(mc -> {
				ServerLevel level = mc.overworld();
				ServerPlayer player = mc.getPlayerList().getPlayers().get(0);
				level.setBlock(PROJECTOR_POS, ModBlocks.BUBBLE_SHIELD_PROJECTOR.defaultBlockState(), 3);
				BubbleShieldBlockEntity shield = (BubbleShieldBlockEntity) level.getBlockEntity(PROJECTOR_POS);
				shield.setOwner(player);
				shield.addFuelSeconds(36000);
				shield.setSettings(DIAMETER, 0, 0, 0, false, 0);
				if (!shield.tryActivate()) {
					throw new AssertionError("Shield failed to activate");
				}
			});
			ctx.waitTicks(40);

			// --- Hero clips: fixed outside camera, HUD hidden, backdrop behind. ---
			setHudHidden(ctx, true);
			for (SurfaceTemplate family : HERO_FAMILIES) {
				EffectDefinition def = firstOfFamily(family);
				String name = String.format(Locale.ROOT, "hero_fx%03d_%s",
						def.id(), family.name().toLowerCase(Locale.ROOT));
				server.runCommand(OUTSIDE_CAMERA);
				retune(server, def.id(), BeamStyle.NONE);
				ctx.waitTicks(SETTLE_TICKS);
				if (captureFixedClip(ctx, videoDir, name, HERO_FRAMES)) {
					clips.add(new ClipRecord(name, def.id(), family.name().toLowerCase(Locale.ROOT),
							def.screenTemplate(), "fixed_outside", HERO_FRAMES, "none"));
				} else {
					failed++;
				}
			}

			// --- Orbit clips: camera revolves around the shield center (parallax test). ---
			for (SurfaceTemplate family : ORBIT_FAMILIES) {
				EffectDefinition def = firstOfFamily(family);
				String name = String.format(Locale.ROOT, "orbit_fx%03d_%s",
						def.id(), family.name().toLowerCase(Locale.ROOT));
				retune(server, def.id(), BeamStyle.NONE);
				orbitCamera(server, ORBIT_START_DEGREES);
				ctx.waitTicks(SETTLE_TICKS);
				if (captureOrbitClip(ctx, server, videoDir, name, ORBIT_FRAMES)) {
					clips.add(new ClipRecord(name, def.id(), family.name().toLowerCase(Locale.ROOT),
							def.screenTemplate(), "orbit", ORBIT_FRAMES, "none"));
				} else {
					failed++;
				}
			}

			// --- Inside clip: HUD visible so the boss bar + post-fx read together. ---
			EffectDefinition insideDef = firstWithScreenTemplate(INSIDE_SCREEN_FAMILY);
			String insideName = String.format(Locale.ROOT, "inside_fx%03d_%s",
					insideDef.id(), insideDef.screenTemplate());
			setHudHidden(ctx, false);
			retune(server, insideDef.id(), BeamStyle.NONE);
			server.runCommand(INSIDE_CAMERA);
			ctx.waitTicks(SETTLE_TICKS);
			if (captureFixedClip(ctx, videoDir, insideName, INSIDE_FRAMES)) {
				clips.add(new ClipRecord(insideName, insideDef.id(),
						insideDef.surface().name().toLowerCase(Locale.ROOT),
						insideDef.screenTemplate(), "inside", INSIDE_FRAMES, "none"));
			} else {
				failed++;
			}

			// --- Beam clip: pulled-back camera framing the whole energy column. ---
			String beamName = "beam_" + BEAM_CLIP_STYLE.name().toLowerCase(Locale.ROOT)
					+ String.format(Locale.ROOT, "_fx%03d", BEAM_EFFECT_ID);
			setHudHidden(ctx, true);
			retune(server, BEAM_EFFECT_ID, BEAM_CLIP_STYLE);
			server.runCommand(BEAM_CAMERA);
			ctx.waitTicks(SETTLE_TICKS);
			if (captureFixedClip(ctx, videoDir, beamName, BEAM_FRAMES)) {
				EffectDefinition beamDef = EffectRegistry.ALL.get(BEAM_EFFECT_ID);
				clips.add(new ClipRecord(beamName, BEAM_EFFECT_ID,
						beamDef.surface().name().toLowerCase(Locale.ROOT),
						beamDef.screenTemplate(), "beam", BEAM_FRAMES,
						BEAM_CLIP_STYLE.name().toLowerCase(Locale.ROOT)));
			} else {
				failed++;
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

		writeManifest(videoDir, clips);
		int totalFrames = clips.stream().mapToInt(ClipRecord::frames).sum();
		LOGGER.info("Bubble video batch done: {} clips / {} frames written to {}, {} clips failed",
				clips.size(), totalFrames, videoDir, failed);
		if (failed > 0) {
			throw new AssertionError(failed + " clips failed (see log); " + clips.size() + " succeeded");
		}
	}

	/**
	 * Captures {@code frames} consecutive per-tick frames from the current (fixed)
	 * camera into {@code videoDir/<clipName>/f_%04d.png}. A frame failure aborts the
	 * clip (a numbering gap would break the encoder) and is reported as a clip failure.
	 */
	private boolean captureFixedClip(ClientGameTestContext ctx, Path videoDir, String clipName, int frames) {
		Path clipDir = videoDir.resolve(clipName);
		try {
			Files.createDirectories(clipDir);
			for (int f = 0; f < frames; f++) {
				ctx.takeScreenshot(TestScreenshotOptions.of(String.format(Locale.ROOT, "f_%04d", f))
						.withDestinationDir(clipDir)
						.disableCounterPrefix());
				ctx.waitTicks(1);
			}
			LOGGER.info("Captured clip {} ({} frames) -> {}", clipName, frames, clipDir);
			return true;
		} catch (Exception e) {
			LOGGER.error("Clip {} failed", clipName, e);
			return false;
		}
	}

	/**
	 * Captures an orbit clip: each frame the camera is re-teleported
	 * {@value #ORBIT_STEP_DEGREES}° further around the bubble center (always facing
	 * it), one tick apart, so encoding at 20 fps yields a smooth revolve.
	 */
	private boolean captureOrbitClip(ClientGameTestContext ctx, TestServerContext server,
			Path videoDir, String clipName, int frames) {
		Path clipDir = videoDir.resolve(clipName);
		try {
			Files.createDirectories(clipDir);
			for (int f = 0; f < frames; f++) {
				orbitCamera(server, ORBIT_START_DEGREES + f * ORBIT_STEP_DEGREES);
				ctx.waitTicks(1);
				ctx.takeScreenshot(TestScreenshotOptions.of(String.format(Locale.ROOT, "f_%04d", f))
						.withDestinationDir(clipDir)
						.disableCounterPrefix());
			}
			LOGGER.info("Captured orbit clip {} ({} frames) -> {}", clipName, frames, clipDir);
			return true;
		} catch (Exception e) {
			LOGGER.error("Orbit clip {} failed", clipName, e);
			return false;
		}
	}

	/**
	 * Teleports the camera onto the orbit circle at {@code angleDegrees} (180° = due
	 * west of the bubble center, the fixed camera's side), yawed to face the center.
	 */
	private static void orbitCamera(TestServerContext server, double angleDegrees) {
		double a = Math.toRadians(angleDegrees);
		double px = CENTER_X + ORBIT_RADIUS * Math.cos(a);
		double pz = CENTER_Z + ORBIT_RADIUS * Math.sin(a);
		// MC yaw convention: direction = (-sin(yaw), cos(yaw)); face the center.
		double yaw = Math.toDegrees(Math.atan2(-(CENTER_X - px), CENTER_Z - pz));
		server.runCommand(String.format(Locale.ROOT, "tp @a %.3f %.3f %.3f %.2f %.2f",
				px, ORBIT_Y, pz, yaw, ORBIT_PITCH));
	}

	/** Retunes the live shield to the given effect + beam style (sphere, defense mode). */
	private static void retune(TestServerContext server, int effectId, BeamStyle beam) {
		server.runOnServer(mc -> {
			BubbleShieldBlockEntity shield = (BubbleShieldBlockEntity) mc.overworld().getBlockEntity(PROJECTOR_POS);
			shield.setSettings(DIAMETER, effectId, 0, 0, false, beam.ordinal());
		});
	}

	/** Shows/hides the HUD (26.2: F1 hiding lives on Gui/Hud, not Options). */
	private static void setHudHidden(ClientGameTestContext ctx, boolean hidden) {
		ctx.runOnClient(mc -> {
			if (mc.gui.hud.isHidden() != hidden) {
				mc.gui.hud.toggle();
			}
		});
	}

	/** The lowest-id catalogue effect of the given surface family. */
	private static EffectDefinition firstOfFamily(SurfaceTemplate family) {
		for (EffectDefinition def : EffectRegistry.ALL) {
			if (def.surface() == family) {
				return def;
			}
		}

		throw new AssertionError("No catalogue effect uses surface family " + family);
	}

	/** The lowest-id catalogue effect using the given screen-fx template family. */
	private static EffectDefinition firstWithScreenTemplate(String screenTemplate) {
		for (EffectDefinition def : EffectRegistry.ALL) {
			if (screenTemplate.equals(def.screenTemplate())) {
				return def;
			}
		}

		throw new AssertionError("No catalogue effect uses screen template " + screenTemplate);
	}

	/** Writes {@code manifest.json}: one entry per captured clip, consumed by tools/encode_captures.py. */
	private static void writeManifest(Path videoDir, List<ClipRecord> clips) {
		JsonObject root = new JsonObject();
		root.addProperty("framerate", 20);
		root.addProperty("frame_pattern", "f_%04d.png");
		JsonArray arr = new JsonArray();
		for (ClipRecord clip : clips) {
			JsonObject entry = new JsonObject();
			entry.addProperty("name", clip.name());
			entry.addProperty("effect_id", clip.effectId());
			entry.addProperty("family", clip.family());
			entry.addProperty("screen_template", clip.screenTemplate());
			entry.addProperty("camera", clip.camera());
			entry.addProperty("frames", clip.frames());
			entry.addProperty("beam_style", clip.beamStyle());
			arr.add(entry);
		}
		root.add("clips", arr);
		try {
			Files.writeString(videoDir.resolve("manifest.json"),
					new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n");
		} catch (Exception e) {
			throw new RuntimeException("Cannot write manifest.json to " + videoDir, e);
		}
	}
}

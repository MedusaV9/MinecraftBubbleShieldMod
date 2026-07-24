package com.bubbleshield.clienttest;

import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.client.fx.ContactFlash;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.EffectRegistry;
import com.bubbleshield.effect.SurfaceTemplate;
import com.bubbleshield.net.ShieldPayloads;
import com.bubbleshield.registry.ModBlocks;
import com.bubbleshield.shield.ShieldState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

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
import net.minecraft.world.phys.Vec3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SCENARIO capture harness for the dynamic membrane systems (WP-Evt/Dyn/Vol/Int):
 * staged screenshots of the traveling impact waves, the whitelisted-player
 * aperture opening/closing, the blocked-player contact flash, the per-effect
 * interior specials and the volumetric limb thickening — the states the static
 * {@link ShaderScreenshotTest} batch can never show.
 *
 * <p>DISABLED BY DEFAULT (mirroring {@link VideoCaptureTest}'s gate): no-ops
 * instantly unless {@code BUBBLESHIELD_SCENARIOS} is set to a comma-separated
 * subset of the group names below, so the ordinary
 * {@code ./gradlew runClientGameTest} run is unchanged. Groups always execute
 * in the fixed order listed here regardless of the env var's ordering:
 * <ul>
 *   <li>{@code dynamics} — {@code impact_wave_t0/t1/t2.png} (a real
 *       {@code queueImpact} + {@code applyShieldDamage} volley captured +1/+5/+10
 *       ticks after batch delivery), {@code aperture_open.png} (owner camera
 *       near the wall, parted-mass lip/rim) and {@code passage_close.png}
 *       (owner beyond close-hysteresis, wall easing shut);</li>
 *   <li>{@code interiors} — {@code interior_taco_inside/outside.png} (fx_633),
 *       {@code interior_void_inside.png} (fx_839) and
 *       {@code interior_disco_inside.png} (fx_809); the outside shot shows the
 *       floating interiors THROUGH the membrane;</li>
 *   <li>{@code volumetric} — {@code volumetric_grazing.png}: near-tangent view
 *       along the surface showing the limb thickening (ownership is temporarily
 *       foreign so no aperture punches through the grazed wall), plus
 *       {@code volumetric_inside.png}: the far wall framed from INSIDE on a
 *       celestial effect, showing the back-face inner-material recipe;</li>
 *   <li>{@code contact} — {@code contact_flash.png}: the shield is re-owned to
 *       a foreign UUID (whitelist cleared + {@code markUpdated} so the synced
 *       replica blocks the camera), then a &gt; 2-block snap-teleport to the
 *       wall starts a fresh hard flash on the capture tick while a HELD
 *       walk-backward key keeps the predicted press (and its sustain floor)
 *       alive through the capture's internal ticks.</li>
 * </ul>
 *
 * <p>Screenshots land in {@code BUBBLESHIELD_CAPTURE_DIR} (default
 * {@code /tmp/bubble_captures}), same as {@link ShaderScreenshotTest}. Every
 * scenario uses fixed positions/tick counts, so reruns are deterministic.
 */
public class ScenarioCaptureTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("bubbleshield-scenarios");

	/** Same stage geometry as {@link ShaderScreenshotTest}: flat-world surface, y = -60. */
	private static final BlockPos PROJECTOR_POS = new BlockPos(8, -60, 0);
	private static final int DIAMETER = 12;
	private static final String OUTSIDE_CAMERA = "tp @a -5 -58 0 -90 12";
	private static final String INSIDE_CAMERA = "tp @a 10.5 -60 0.5 -90 5";
	/**
	 * Interior camera: just inside the EAST wall looking WEST across the whole
	 * bubble. The scattered interior budget at radius 6 is only ~11 elements and
	 * the renderer skips anything nearer than 2.5 blocks, so the default
	 * east-facing inside camera (4 blocks of visible depth) frequently frames
	 * zero sprites — this one sees a 2.5..10.5-block band covering most of the
	 * volume.
	 */
	private static final String INTERIOR_CAMERA = "tp @a 13.0 -60 0.5 90 -5";

	/**
	 * Aperture camera: ~4.5 blocks outside the west wall (x = 2.5), facing it. The
	 * spec's 1.5-block distance puts the fully-open 2.8-block rim OUTSIDE the
	 * 854x480 frustum (the whole frame falls inside the transparent core), so the
	 * camera is pulled back until the parted-mass lip/rim ring fits the frame —
	 * still well inside the open-hysteresis band (wall distance 4.5 &lt; 5.5).
	 */
	private static final String APERTURE_CAMERA = "tp @a -2.0 -60 0.5 -90 10";
	/**
	 * Close camera: 13 blocks from the center — past the close-hysteresis edge
	 * (radius 6 + 6.5), so the aperture eases shut. The spec's "3 blocks further
	 * out" (7.5 from the wall) would still be INSIDE the 5.5-block open band.
	 */
	private static final String CLOSE_CAMERA = "tp @a -4.5 -60 0.5 -90 8";
	/**
	 * Grazing camera: 0.5 blocks outside the west wall looking almost due north
	 * (yaw -170 = tangent, nudged 10 degrees toward the bubble) so the membrane
	 * fills the frame edge-on and the volumetric limb thickening reads.
	 */
	private static final String GRAZING_CAMERA = "tp @a 2.0 -60 0.5 -170 3";

	/** Ticks to wait after a retune/camera move for S2C sync + rendered frames. */
	private static final int SETTLE_TICKS = 20;
	/** Aperture hysteresis settle (spec): open tau 0.15 s / close tau 0.5 s. */
	private static final int APERTURE_SETTLE_TICKS = 15;

	/** Vivid pink/purple arcs palette — waves/aperture geometry reads against it. */
	private static final int DYNAMICS_EFFECT_ID = 6;
	/** The novelty-retheme interior ids (see {@code InteriorThemes.OVERRIDES}). */
	private static final int TACO_EFFECT_ID = 633;
	private static final int VOID_EFFECT_ID = 839;
	private static final int DISCO_EFFECT_ID = 809;
	/** Contact flash: fx_633's huedrift screen family maps to the GRADE overlay. */
	private static final int CONTACT_EFFECT_ID = 633;

	private static final List<String> GROUP_ORDER = List.of("dynamics", "interiors", "volumetric", "contact");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		Set<String> groups = enabledGroups();
		if (groups.isEmpty()) {
			LOGGER.info("BUBBLESHIELD_SCENARIOS unset -> scenario captures skipped");
			return;
		}

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

		List<String> failures = new ArrayList<>();
		int captured = 0;
		try (TestSingleplayerContext singleplayer = worldBuilder.create()) {
			singleplayer.getClientLevel().waitForChunksRender();
			TestServerContext server = singleplayer.getServer();

			// Deterministic stage, identical to ShaderScreenshotTest.
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

			// Raise the shield, owned by the test player (the barrier never expels the camera).
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

			for (String group : GROUP_ORDER) {
				if (!groups.contains(group)) {
					continue;
				}

				int before = failures.size();
				switch (group) {
					case "dynamics" -> captured += runDynamics(ctx, server, captureDir, failures);
					case "interiors" -> captured += runInteriors(ctx, server, captureDir, failures);
					case "volumetric" -> captured += runVolumetric(ctx, server, captureDir, failures);
					case "contact" -> captured += runContact(ctx, server, captureDir, failures);
					default -> throw new AssertionError(group);
				}

				LOGGER.info("Scenario group '{}' done ({} new failures)", group, failures.size() - before);
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

		LOGGER.info("Scenario capture batch done: {} screenshots written to {}, {} failed",
				captured, captureDir, failures.size());
		if (!failures.isEmpty()) {
			throw new AssertionError("Scenario captures failed: " + failures);
		}
	}

	/**
	 * {@code dynamics}: a real IMPACT event (batch flushes next serverTick, the
	 * client tracker animates it for 2 s) captured at three ages, then the aperture
	 * open/close pair. The 30-damage {@code applyShieldDamage} makes the weakness
	 * amplitude boost and the boss-bar/health state genuine.
	 */
	private int runDynamics(ClientGameTestContext ctx, TestServerContext server, Path captureDir, List<String> failures) {
		int captured = 0;
		retune(server, DYNAMICS_EFFECT_ID);
		setHudHidden(ctx, true);
		server.runCommand(OUTSIDE_CAMERA);
		ctx.waitTicks(SETTLE_TICKS);

		// Hit the west side (facing the outside camera): wave rings travel out of
		// the hit point at 12 blocks/s, so +1/+5/+10 ticks show launch, mid-surface
		// ring and wrap-around. 2 wait ticks bridge flush (next serverTick) + S2C.
		server.runOnServer(mc -> {
			BubbleShieldBlockEntity shield = (BubbleShieldBlockEntity) mc.overworld().getBlockEntity(PROJECTOR_POS);
			shield.applyShieldDamage(30.0F);
			shield.queueImpact(ShieldPayloads.ImpactEntry.KIND_IMPACT, new Vec3(-1.0, 0.0, 0.0), 8.0F);
		});
		ctx.waitTicks(2);
		captured += capture(ctx, "impact_wave_t0", captureDir, failures);
		ctx.waitTicks(4);
		captured += capture(ctx, "impact_wave_t1", captureDir, failures);
		ctx.waitTicks(5);
		captured += capture(ctx, "impact_wave_t2", captureDir, failures);
		// Let the wave fully expire (client TTL 40 ticks) before the aperture shots.
		ctx.waitTicks(50);

		// Aperture: the owner camera walks up to the wall; hysteresis opens within
		// wall distance 5.5 (tau 0.15 s), closes beyond 6.5 (tau 0.5 s).
		server.runCommand(APERTURE_CAMERA);
		ctx.waitTicks(APERTURE_SETTLE_TICKS);
		captured += capture(ctx, "aperture_open", captureDir, failures);

		server.runCommand(CLOSE_CAMERA);
		ctx.waitTicks(APERTURE_SETTLE_TICKS);
		captured += capture(ctx, "passage_close", captureDir, failures);
		return captured;
	}

	/** {@code interiors}: the taco/void/disco novelty interiors, inside and (taco) through the membrane. */
	private int runInteriors(ClientGameTestContext ctx, TestServerContext server, Path captureDir, List<String> failures) {
		int captured = 0;
		setHudHidden(ctx, true);

		retune(server, TACO_EFFECT_ID);
		server.runCommand(INTERIOR_CAMERA);
		ctx.waitTicks(SETTLE_TICKS);
		captured += capture(ctx, "interior_taco_inside", captureDir, failures);

		// Outside: 13.5 blocks from the center is past close-hysteresis, so the
		// aperture opened by the inside shot eases shut; the long settle lets the
		// membrane seal before the through-the-membrane refraction proof.
		server.runCommand(OUTSIDE_CAMERA);
		ctx.waitTicks(40);
		captured += capture(ctx, "interior_taco_outside", captureDir, failures);

		retune(server, VOID_EFFECT_ID);
		server.runCommand(INTERIOR_CAMERA);
		ctx.waitTicks(SETTLE_TICKS);
		captured += capture(ctx, "interior_void_inside", captureDir, failures);

		// The disco ball is pinned top-center, so the east-facing default inside
		// camera (which keeps it in the upper frame) frames it better than the
		// across-the-bubble interior camera.
		retune(server, DISCO_EFFECT_ID);
		server.runCommand(INSIDE_CAMERA);
		ctx.waitTicks(SETTLE_TICKS);
		captured += capture(ctx, "interior_disco_inside", captureDir, failures);
		return captured;
	}

	/**
	 * {@code volumetric}: a near-tangent membrane view. Ownership is temporarily
	 * foreign (whitelist cleared) so the camera's proximity opens NO aperture in
	 * the grazed wall; the camera never moves inward, so no contact fires either.
	 *
	 * <p>Also captures {@code volumetric_inside}: the interior camera (just
	 * inside the east wall, looking west across the whole bubble) framing the
	 * FAR wall from INSIDE on a celestial-group effect — the view that must show
	 * the {@code [layer:inner:stars]} inner-material recipe on the far shell
	 * (the aperture opened by the camera's own proximity sits on the east wall
	 * BEHIND it, so the framed far wall stays sealed).
	 */
	private int runVolumetric(ClientGameTestContext ctx, TestServerContext server, Path captureDir, List<String> failures) {
		int captured = 0;
		setHudHidden(ctx, true);
		retune(server, firstOfFamily(SurfaceTemplate.VOLUMECLOUD).id());
		disownShield(server);
		// Long settle: the sync must reach the client AND any previously-open
		// aperture must ease shut (close tau 0.5 s -> ~0.02 blocks after 3 s).
		server.runCommand(GRAZING_CAMERA);
		ctx.waitTicks(60);
		captured += capture(ctx, "volumetric_grazing", captureDir, failures);
		reownShield(server);
		ctx.waitTicks(10);

		// Inside view of the far wall, on the deepest-shell group (celestial,
		// rho 0.18, inner recipe "stars") so the inner material reads clearly.
		retune(server, firstOfFamily(SurfaceTemplate.STARFIELD).id());
		server.runCommand(INTERIOR_CAMERA);
		ctx.waitTicks(SETTLE_TICKS);
		captured += capture(ctx, "volumetric_inside", captureDir, failures);
		return captured;
	}

	/**
	 * {@code contact}: the camera becomes a blocked stranger pressed against the
	 * wall. Two triggers stack so the flash is provably live in the captured
	 * frame regardless of llvmpipe latency:
	 * <ol>
	 *   <li>the final staging teleport (8 blocks, far park spot &rarr; 0.5 outside
	 *       the wall) is itself a &gt; 2-block single-tick "snap" — the expulsion
	 *       signature — so the HARD flash (alpha 0.35, 0.6 s cubic ease-out)
	 *       starts ON the capture tick; the 20-tick park pause beforehand lets
	 *       the park-teleport's own stray envelope and the 500 ms re-trigger
	 *       limit expire so this snap is a fresh full-brightness flash;</li>
	 *   <li>the walk-backward key is HELD through the capture ({@code holdKey}
	 *       survives {@code takeScreenshot}'s internal tick loop, unlike
	 *       tp-stepping which stops when the staging code blocks), so the camera
	 *       keeps genuinely moving inward: the predicted press (wall distance
	 *       &lt; 0.6, inward speed &gt; 0.05/tick) accrues 4+ consecutive ticks,
	 *       holding the 0.12 sustain floor and re-triggering the hard flash
	 *       every 500 ms as the barrier expels and the walk re-presses.</li>
	 * </ol>
	 * The camera FACES AWAY from the bubble while walking backward into it: the
	 * flash is a screen-space HUD overlay, and against open grass/sky the tinted
	 * edge vignette is unmistakable (facing the wall, the membrane fills the
	 * frame in the same palette and hides it).
	 */
	private int runContact(ClientGameTestContext ctx, TestServerContext server, Path captureDir, List<String> failures) {
		int captured = 0;
		retune(server, CONTACT_EFFECT_ID);
		setHudHidden(ctx, false);
		disownShield(server);
		ctx.waitTicks(10);

		// Park far west of the wall, already facing away (west). This teleport
		// fires a stray snap flash of its own; the pause clearing it must be
		// WALL-CLOCK (the envelope and the re-trigger limit are millis-based and
		// gametest client ticks run far faster than 50 ms), so a fixed tick
		// count would leave the 500 ms limit armed and eat the next snap.
		server.runCommand("tp @a -6.0 -60 0.5 90 0");
		long parkStart = System.currentTimeMillis();
		while (System.currentTimeMillis() - parkStart < 700) {
			ctx.waitTicks(1);
		}

		// Hold walk-backward, then snap-tp to 0.5 outside the west wall (x = 2.5):
		// the snap starts a fresh hard flash and the held key keeps pressing
		// through the capture's internal ticks (expel -> walk back in -> re-press).
		ctx.getInput().holdKey(options -> options.keyDown);
		try {
			server.runCommand("tp @a 2.0 -60 0.5 90 0");
			// Gate on the flash actually burning: the hard envelope only stays
			// above 0.2 alpha for ~100 ms, so a fixed tick count would race it.
			// Worst case the gate passes on a later press-cycle re-trigger
			// (every 500 ms while the held key keeps pressing), within ~10 s.
			ctx.waitFor(mc -> ContactFlash.alpha() > 0.2F, 400);
			captured += capture(ctx, "contact_flash", captureDir, failures);
		} finally {
			ctx.getInput().releaseKey(options -> options.keyDown);
		}

		reownShield(server);
		ctx.waitTicks(10);
		return captured;
	}

	/**
	 * Re-owns the shield to a random foreign UUID and clears BOTH whitelist sets
	 * (names match case-insensitively, so clearing only UUIDs would not block the
	 * camera); {@code markUpdated} queues the ShieldSyncS2C broadcast.
	 */
	private static void disownShield(TestServerContext server) {
		server.runOnServer(mc -> {
			BubbleShieldBlockEntity shield = (BubbleShieldBlockEntity) mc.overworld().getBlockEntity(PROJECTOR_POS);
			ShieldState state = shield.getShieldState();
			state.ownerUuid = UUID.randomUUID();
			state.whitelistUuids.clear();
			state.whitelistNames.clear();
			shield.markUpdated();
		});
	}

	/** Restores the test player as owner + whitelisted (setOwner marks updated itself). */
	private static void reownShield(TestServerContext server) {
		server.runOnServer(mc -> {
			BubbleShieldBlockEntity shield = (BubbleShieldBlockEntity) mc.overworld().getBlockEntity(PROJECTOR_POS);
			shield.setOwner(mc.getPlayerList().getPlayers().get(0));
		});
	}

	/** Retunes the live shield to the given effect (sphere, defense mode, no beam). */
	private static void retune(TestServerContext server, int effectId) {
		server.runOnServer(mc -> {
			BubbleShieldBlockEntity shield = (BubbleShieldBlockEntity) mc.overworld().getBlockEntity(PROJECTOR_POS);
			shield.setSettings(DIAMETER, effectId, 0, 0, false, 0);
		});
	}

	/** Takes one screenshot; a failure is recorded but never aborts the batch. */
	private int capture(ClientGameTestContext ctx, String name, Path captureDir, List<String> failures) {
		try {
			Path png = ctx.takeScreenshot(TestScreenshotOptions.of(name)
					.withDestinationDir(captureDir)
					.disableCounterPrefix());
			LOGGER.info("Captured {} -> {}", name, png);
			return 1;
		} catch (Exception e) {
			LOGGER.error("Capture {} failed", name, e);
			failures.add(name);
			return 0;
		}
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

	/** Parses {@code BUBBLESHIELD_SCENARIOS}; unknown group names fail fast. */
	private static Set<String> enabledGroups() {
		String raw = System.getenv("BUBBLESHIELD_SCENARIOS");
		if (raw == null || raw.isBlank()) {
			return Set.of();
		}

		Set<String> groups = new LinkedHashSet<>();
		for (String token : raw.split(",")) {
			String name = token.trim().toLowerCase(Locale.ROOT);
			if (name.isEmpty()) {
				continue;
			}

			if (!GROUP_ORDER.contains(name)) {
				throw new IllegalArgumentException("Unknown BUBBLESHIELD_SCENARIOS group '" + name
						+ "' (known: " + GROUP_ORDER + ")");
			}

			groups.add(name);
		}

		return groups;
	}
}

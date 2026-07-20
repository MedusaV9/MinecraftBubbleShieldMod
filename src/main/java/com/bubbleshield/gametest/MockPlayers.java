package com.bubbleshield.gametest;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.bubbleshield.mixin.GameTestHelperAccessor;
import com.mojang.authlib.GameProfile;

import io.netty.channel.embedded.EmbeddedChannel;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * In-level mock ServerPlayers with UNIQUE names for game tests.
 *
 * <p>Vanilla's {@code GameTestHelper.makeMockServerPlayerInLevel()} (deprecated for
 * removal) registers every mock in the PlayerList under the shared name
 * "test-mock-player". The default batch ticks up to 50 tests in parallel, and
 * {@code PlayerList.getPlayerByName} returns the FIRST case-insensitive match, so any
 * name-resolving logic (e.g. whitelist add/remove) could cross-resolve ANOTHER
 * concurrent test's mock and fail intermittently. This helper reproduces the exact
 * vanilla recipe (verified against the 26.2 sources: {@code CommonListenerCookie
 * .createInitial(profile, false)}, a SERVERBOUND {@code Connection} wrapped in an
 * {@code EmbeddedChannel}, then {@code PlayerList.placeNewPlayer}) but under a unique
 * per-call name, which eliminates the collision regardless of batch composition.
 *
 * <p>Created players are also tracked per test: a {@link GameTestListener} registered
 * on the running {@link GameTestInfo} sweeps any still-online mock out of the
 * PlayerList when the test ends — passed, failed OR timed out — so no phantom player
 * outlives its test even when an assertion throws before an explicit removal.
 * {@link #removeMockPlayer} stays the preferred in-test cleanup (and is what the
 * sweep falls back to); both paths are idempotent.
 */
public final class MockPlayers {
	/** Unique-name source: "bsmock" + hex counter is at most 14 chars (limit 16). */
	private static final AtomicInteger NAME_COUNTER = new AtomicInteger();

	/**
	 * Mock players still to be swept, per running test. Only touched from the server
	 * thread (test functions, runAtTickTime callbacks and GameTestListener callbacks
	 * all run there).
	 */
	private static final Map<GameTestInfo, Set<ServerPlayer>> TRACKED = new IdentityHashMap<>();

	private static final GameTestListener SWEEP_LISTENER = new GameTestListener() {
		@Override
		public void testStructureLoaded(GameTestInfo testInfo) {
		}

		@Override
		public void testPassed(GameTestInfo testInfo, GameTestRunner runner) {
			sweep(testInfo);
		}

		@Override
		public void testFailed(GameTestInfo testInfo, GameTestRunner runner) {
			sweep(testInfo);
		}

		@Override
		public void testAddedForRerun(GameTestInfo original, GameTestInfo copy, GameTestRunner runner) {
		}
	};

	private MockPlayers() {
	}

	/**
	 * A mock player paired with the {@link EmbeddedChannel} backing its connection.
	 * Every clientbound packet the server sends this player is recorded in the
	 * channel's outbound queue, so tests can assert on what was ACTUALLY sent --
	 * e.g. particle emission positions ({@link #drainParticlePackets()}).
	 */
	public record CapturingMockPlayer(ServerPlayer player, EmbeddedChannel channel) {
		/**
		 * Drains every packet captured since the previous drain and returns the
		 * particle packets among them (other packet types -- sounds, entity data,
		 * chunk data... -- are discarded). Call once to flush setup noise, then
		 * again after the action under test.
		 */
		public List<ClientboundLevelParticlesPacket> drainParticlePackets() {
			List<ClientboundLevelParticlesPacket> packets = new ArrayList<>();
			Object message;
			while ((message = this.channel.readOutbound()) != null) {
				if (message instanceof ClientboundLevelParticlesPacket particles) {
					packets.add(particles);
				}
			}

			return packets;
		}
	}

	/**
	 * Creates an in-level (PlayerList-registered) creative-mode mock ServerPlayer under
	 * a unique per-call name ("bsmock0", "bsmock1", ...), placed at the helper's
	 * structure center, and tracks it for end-of-test sweeping. Drop-in replacement
	 * for the deprecated {@code helper.makeMockServerPlayerInLevel()}.
	 */
	public static ServerPlayer createUniqueMockPlayer(GameTestHelper helper) {
		return createCapturingMockPlayer(helper, GameType.CREATIVE).player();
	}

	/**
	 * Creates an in-level mock ServerPlayer of the given game mode (unique name,
	 * parked at the structure center, swept at end of test -- exactly like
	 * {@link #createUniqueMockPlayer}) and returns it together with its packet
	 * capture channel. A SURVIVAL mock is the right pick when the test needs
	 * vanilla-player semantics (aura targeting, damage) rather than a creative
	 * ghost.
	 */
	public static CapturingMockPlayer createCapturingMockPlayer(GameTestHelper helper, GameType gameType) {
		String uniqueName = "bsmock" + Integer.toHexString(NAME_COUNTER.getAndIncrement());
		// The exact vanilla makeMockServerPlayerInLevel recipe, minus the shared name.
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), uniqueName), false);
		ServerPlayer player = new ServerPlayer(
				helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation()) {
			@Override
			public GameType gameMode() {
				return gameType;
			}
		};
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		EmbeddedChannel channel = new EmbeddedChannel(connection);
		helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);

		// placeNewPlayer spawns at the shared world spawn; park the mock inside its own
		// test structure instead so concurrent tests' mocks never sit in each other's
		// bubbles. Tests still snapTo their exact spot afterwards.
		Vec3 structureCenter = helper.getBounds().getCenter();
		player.snapTo(structureCenter.x, structureCenter.y, structureCenter.z);

		GameTestInfo testInfo = ((GameTestHelperAccessor) helper).bubbleshield$testInfo();
		TRACKED.computeIfAbsent(testInfo, info -> {
			info.addListener(SWEEP_LISTENER);
			return new LinkedHashSet<>();
		}).add(player);

		return new CapturingMockPlayer(player, channel);
	}

	/**
	 * Removes a mock player created by {@link #createUniqueMockPlayer} from the
	 * PlayerList and untracks it. Idempotent: a player already removed (by an earlier
	 * call or by the end-of-test sweep) is skipped.
	 */
	public static void removeMockPlayer(GameTestHelper helper, ServerPlayer player) {
		Set<ServerPlayer> players = TRACKED.get(((GameTestHelperAccessor) helper).bubbleshield$testInfo());
		if (players != null) {
			players.remove(player);
		}

		removeIfOnline(helper.getLevel().getServer().getPlayerList(), player);
	}

	private static void sweep(GameTestInfo testInfo) {
		Set<ServerPlayer> players = TRACKED.remove(testInfo);
		if (players == null) {
			return;
		}

		for (ServerPlayer player : players) {
			removeIfOnline(player.level().getServer().getPlayerList(), player);
		}
	}

	/** PlayerList.remove is not idempotent (stats/adv saves, packets), so guard it. */
	private static void removeIfOnline(PlayerList playerList, ServerPlayer player) {
		if (playerList.getPlayer(player.getUUID()) == player) {
			playerList.remove(player);
		}
	}
}

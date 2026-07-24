package com.bubbleshield.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.command.BubbleShieldCommand;
import com.bubbleshield.effect.EffectRegistry;
import com.bubbleshield.net.ServerNet;
import com.bubbleshield.net.ShieldPayloads;
import com.bubbleshield.registry.ModBlocks;
import com.bubbleshield.registry.ModItems;
import com.bubbleshield.registry.ModTicketTypes;
import com.bubbleshield.shield.ShieldMode;
import com.bubbleshield.shield.ShieldShape;
import com.bubbleshield.shield.ShieldState;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.state.properties.SculkSensorPhase;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

/**
 * Coverage for milestone V8, the world-integration features: the /bubbleshield
 * command (list/info/set with owner gating and id clamping), the sculk-audible
 * BLOCK_ACTIVATE/BLOCK_DEACTIVATE game events on real shield transitions, and the
 * Resonant Core loot injection into the End City / Ancient City chest tables.
 */
public class WorldIntegrationGameTests {
	/**
	 * A dedicated (but otherwise vanilla-default) test environment,
	 * {@code data/bubbleshield/test_environment/world_integration.json}. Its own batch
	 * avoids reshuffling the shared default one past the runner's 50-test cap (see
	 * ColorGameTests.ISOLATED_ENVIRONMENT for the full story; mocks are now uniquely
	 * named via {@link MockPlayers}).
	 */
	private static final String ISOLATED_ENVIRONMENT = "bubbleshield:world_integration";
	private static final BlockPos PROJECTOR_POS = new BlockPos(4, 2, 4);
	/** 3 blocks north of the projector: well inside the sensor's 8-block vibration radius. */
	private static final BlockPos SENSOR_POS = new BlockPos(4, 2, 1);
	private static final int PLENTY_OF_FUEL = 600;
	/**
	 * Ticks to wait after placing the sculk sensor before emitting the vibration
	 * under test, covering its post-placement/cooldown settling
	 * ({@code SculkSensorBlock.COOLDOWN_TICKS} = 10).
	 */
	private static final int SENSOR_SETTLE_TICKS = 10;

	private static BubbleShieldBlockEntity placeProjector(GameTestHelper helper) {
		helper.setBlock(PROJECTOR_POS, ModBlocks.BUBBLE_SHIELD_PROJECTOR);
		BubbleShieldBlockEntity be = helper.getBlockEntity(PROJECTOR_POS, BubbleShieldBlockEntity.class);
		be.getShieldState().targetRadius = 4.0F;
		return be;
	}

	/** An online (PlayerList-registered) mock player standing right next to the projector. */
	private static ServerPlayer mockPlayerNearProjector(GameTestHelper helper) {
		ServerPlayer player = MockPlayers.createUniqueMockPlayer(helper);
		Vec3 center = Vec3.atCenterOf(helper.absolutePos(PROJECTOR_POS));
		player.snapTo(center.x + 1.5, center.y - 0.5, center.z);
		return player;
	}

	private static void runCommand(GameTestHelper helper, ServerPlayer player, String command) {
		helper.getLevel().getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
	}

	/**
	 * (a) "/bubbleshield set 42" from the owner retunes the nearest projector to
	 * effect 42 while keeping diameter, shape, mode and the cycle toggle untouched.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void commandSetAppliesEffectForOwner(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper);
		ShieldState state = be.getShieldState();
		state.shape = ShieldShape.DOME;
		state.mode = ShieldMode.ECO;
		state.cycleEffect = true;

		ServerPlayer player = mockPlayerNearProjector(helper);
		state.ownerUuid = player.getUUID();

		runCommand(helper, player, "bubbleshield set 42");

		helper.runAfterDelay(2, () -> {
			try {
				helper.assertTrue(state.effectId == 42, "the owner's set command should apply effect 42, got " + state.effectId);
				helper.assertTrue(state.targetRadius == 4.0F, "set must keep the diameter, target radius is " + state.targetRadius);
				helper.assertTrue(state.shape == ShieldShape.DOME, "set must keep the shape, got " + state.shape);
				helper.assertTrue(state.mode == ShieldMode.ECO, "set must keep the mode, got " + state.mode);
				helper.assertTrue(state.cycleEffect, "set must keep the cycle toggle");
			} finally {
				MockPlayers.removeMockPlayer(helper, player);
			}

			helper.succeed();
		});
	}

	/**
	 * (a') The set command is gated by the same owner/claim rule as the C2S payloads:
	 * another player's projector is untouched, an ownerless projector is claimed by
	 * the sender, out-of-range ids clamp into [0, COUNT), and a projector farther
	 * than 16 blocks is out of reach.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void commandSetGatesOwnerClaimsAndClamps(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper);
		ShieldState state = be.getShieldState();
		state.ownerUuid = UUID.randomUUID();

		ServerPlayer player = mockPlayerNearProjector(helper);
		runCommand(helper, player, "bubbleshield set 42");

		helper.runAfterDelay(2, () -> {
			helper.assertTrue(state.effectId == 0, "a non-owner must not change the effect, got " + state.effectId);
			helper.assertTrue(!player.getUUID().equals(state.ownerUuid), "a non-owner must never take over an owned projector");

			// Ownerless -> the first commanding player claims it (ServerNet.isOwner rule),
			// and a wildly out-of-range id clamps to the last catalogue entry.
			state.ownerUuid = null;
			runCommand(helper, player, "bubbleshield set 9999");
			helper.runAfterDelay(2, () -> {
				helper.assertTrue(player.getUUID().equals(state.ownerUuid), "an ownerless projector should be claimed by the command sender");
				helper.assertTrue(state.effectId == EffectRegistry.COUNT - 1,
						"id 9999 should clamp to " + (EffectRegistry.COUNT - 1) + ", got " + state.effectId);

				// More than 16 blocks away: the projector is out of the command's reach.
				Vec3 center = Vec3.atCenterOf(helper.absolutePos(PROJECTOR_POS));
				player.snapTo(center.x, center.y + BubbleShieldCommand.MAX_TARGET_DISTANCE + 4.0, center.z);
				runCommand(helper, player, "bubbleshield set 7");
				helper.runAfterDelay(2, () -> {
					try {
						helper.assertTrue(state.effectId == EffectRegistry.COUNT - 1,
								"a projector farther than 16 blocks must be out of reach, got effect " + state.effectId);
					} finally {
						MockPlayers.removeMockPlayer(helper, player);
					}

					helper.succeed();
				});
			});
		});
	}

	/** (a'') list/info execute successfully and print the expected number of entries. */
	@GameTest(environment = ISOLATED_ENVIRONMENT, padding = 16)
	public void commandListAndInfoExecute(GameTestHelper helper) {
		ServerPlayer player = MockPlayers.createUniqueMockPlayer(helper);
		try {
			CommandDispatcher<CommandSourceStack> dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
			int firstPage = dispatcher.execute("bubbleshield list", player.createCommandSourceStack());
			helper.assertTrue(firstPage == BubbleShieldCommand.LIST_PAGE_SIZE,
					"list (page 1) should print 10 effects, got " + firstPage);
			// COUNT-derived: the trailing page holds whatever the first LIST_PAGE_COUNT-1
			// full pages leave over (a full LIST_PAGE_SIZE when COUNT divides evenly,
			// e.g. 10 at the 420-effect catalogue -- NOT "COUNT % LIST_PAGE_SIZE" = 0).
			int expectedLastPageSize = EffectRegistry.COUNT - (BubbleShieldCommand.LIST_PAGE_COUNT - 1) * BubbleShieldCommand.LIST_PAGE_SIZE;
			int lastPage = dispatcher.execute("bubbleshield list " + BubbleShieldCommand.LIST_PAGE_COUNT, player.createCommandSourceStack());
			helper.assertTrue(lastPage == expectedLastPageSize,
					"the last page should print the trailing " + expectedLastPageSize + " effects, got " + lastPage);
			int overflowPage = dispatcher.execute("bubbleshield list 9999", player.createCommandSourceStack());
			helper.assertTrue(overflowPage == lastPage, "an out-of-range page should clamp to the last page, got " + overflowPage);
			int info = dispatcher.execute("bubbleshield info 42", player.createCommandSourceStack());
			helper.assertTrue(info == 1, "info should succeed, got " + info);
		} catch (CommandSyntaxException e) {
			helper.assertTrue(false, "list/info should parse and execute: " + e.getMessage());
		} finally {
			MockPlayers.removeMockPlayer(helper, player);
		}

		helper.succeed();
	}

	/**
	 * (b) A vanilla sculk sensor 3 blocks from the projector hears BOTH real shield
	 * transitions: activation (BLOCK_ACTIVATE) and, after the sensor's own
	 * active/cooldown window has passed, deactivation (BLOCK_DEACTIVATE).
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void sculkSensorHearsShieldTransitions(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper);
		be.addFuelSeconds(PLENTY_OF_FUEL);

		helper.setBlock(SENSOR_POS, Blocks.SCULK_SENSOR);
		helper.assertTrue(SculkSensorBlock.getPhase(helper.getBlockState(SENSOR_POS)) == SculkSensorPhase.INACTIVE,
				"a freshly placed sensor should start inactive");

		helper.startSequence()
				// Let the sensor settle past its post-placement cooldown before the vibration.
				.thenExecuteAfter(SENSOR_SETTLE_TICKS, () -> helper.assertTrue(be.tryActivate(), "the fueled shield should activate"))
				.thenWaitUntil(() -> helper.assertTrue(
						SculkSensorBlock.getPhase(helper.getBlockState(SENSOR_POS)) == SculkSensorPhase.ACTIVE,
						"the sensor should hear the shield's BLOCK_ACTIVATE vibration"))
				// Wait out the sensor's 30-tick active phase + 10-tick cooldown.
				.thenWaitUntil(() -> helper.assertTrue(
						SculkSensorBlock.getPhase(helper.getBlockState(SENSOR_POS)) == SculkSensorPhase.INACTIVE,
						"the sensor should return to inactive before the deactivation check"))
				.thenExecuteAfter(SENSOR_SETTLE_TICKS, () -> be.setActive(false))
				.thenWaitUntil(() -> helper.assertTrue(
						SculkSensorBlock.getPhase(helper.getBlockState(SENSOR_POS)) == SculkSensorPhase.ACTIVE,
						"the sensor should hear the shield's BLOCK_DEACTIVATE vibration"))
				.thenSucceed();
	}

	/**
	 * (b') Break-by-damage is sculk-audible: {@code applyShieldDamage} depletes the
	 * active shield's health directly (the path linked partners take when another
	 * shield's tick splits damage to them), which flips {@code state.active} without
	 * going through {@code setActive(false)}. The sensor is placed AFTER activation
	 * so the BLOCK_ACTIVATE vibration is never heard and the deactivation is the
	 * only candidate vibration.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void sculkSensorHearsBreakByDamage(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "the fueled shield should activate");

		helper.setBlock(SENSOR_POS, Blocks.SCULK_SENSOR);
		helper.startSequence()
				.thenExecuteAfter(SENSOR_SETTLE_TICKS, () -> {
					helper.assertTrue(
							SculkSensorBlock.getPhase(helper.getBlockState(SENSOR_POS)) == SculkSensorPhase.INACTIVE,
							"the settled sensor should be inactive before the break");
					be.applyShieldDamage(1000.0F);
					helper.assertTrue(!be.getShieldState().active, "1000 damage should break the active shield");
				})
				.thenWaitUntil(() -> helper.assertTrue(
						SculkSensorBlock.getPhase(helper.getBlockState(SENSOR_POS)) == SculkSensorPhase.ACTIVE,
						"the sensor should hear the BLOCK_DEACTIVATE vibration of a shield broken by damage"))
				.thenSucceed();
	}

	/**
	 * (b'') Fuel-out is sculk-audible: the passive drain inside ShieldLogic.serverTick
	 * flips {@code state.active} directly when the last fuel-second burns, which the
	 * block entity's wasActive snapshot turns into a BLOCK_DEACTIVATE. Same
	 * sensor-after-activation trick as the break test.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void sculkSensorHearsFuelOutDeactivation(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper);
		// Enough fuel that the shield outlives the sensor's 10-tick settling window
		// (the drain accumulator aligns to the activation tick: first drain 20 ticks
		// after activation, one more every 20 after), but runs dry well within
		// maxTicks: deactivation exactly 100 ticks after activation.
		be.addFuelSeconds(5);
		helper.assertTrue(be.tryActivate(), "the fueled shield should activate");

		helper.setBlock(SENSOR_POS, Blocks.SCULK_SENSOR);
		helper.startSequence()
				.thenExecuteAfter(SENSOR_SETTLE_TICKS, () -> helper.assertTrue(
						SculkSensorBlock.getPhase(helper.getBlockState(SENSOR_POS)) == SculkSensorPhase.INACTIVE,
						"the settled sensor should be inactive while the shield burns its fuel"))
				.thenWaitUntil(() -> helper.assertTrue(
						!be.getShieldState().active,
						"the shield should deactivate when the fuel runs out"))
				.thenWaitUntil(() -> helper.assertTrue(
						SculkSensorBlock.getPhase(helper.getBlockState(SENSOR_POS)) == SculkSensorPhase.ACTIVE,
						"the sensor should hear the fuel-out BLOCK_DEACTIVATE vibration"))
				.thenSucceed();
	}

	/**
	 * (a''') The set command targets the nearest RETUNABLE projector: a neighbor's
	 * (differently owned) projector standing closer to the sender must not shadow
	 * the sender's own — the ownership filter applies during the nearest search,
	 * not after it.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void commandSetPrefersOwnedProjectorOverCloserForeign(GameTestHelper helper) {
		BubbleShieldBlockEntity foreign = placeProjectorAt(helper, new BlockPos(1, 2, 4));
		UUID neighborUuid = UUID.randomUUID();
		foreign.getShieldState().ownerUuid = neighborUuid;

		BubbleShieldBlockEntity own = placeProjectorAt(helper, new BlockPos(7, 2, 4));

		// The player stands right next to the NEIGHBOR's projector, ~6 blocks from
		// their own; both are within the command's 16-block reach.
		ServerPlayer player = MockPlayers.createUniqueMockPlayer(helper);
		Vec3 foreignCenter = Vec3.atCenterOf(helper.absolutePos(new BlockPos(1, 2, 4)));
		player.snapTo(foreignCenter.x + 1.5, foreignCenter.y - 0.5, foreignCenter.z);
		own.getShieldState().ownerUuid = player.getUUID();

		runCommand(helper, player, "bubbleshield set 42");

		helper.runAfterDelay(2, () -> {
			try {
				helper.assertTrue(
						own.getShieldState().effectId == 42,
						"the sender's own projector should be retuned even though a foreign one is closer, got effect "
								+ own.getShieldState().effectId);
				helper.assertTrue(
						foreign.getShieldState().effectId == 0,
						"the closer foreign projector must stay untouched, got effect " + foreign.getShieldState().effectId);
				helper.assertTrue(
						neighborUuid.equals(foreign.getShieldState().ownerUuid),
						"the foreign projector's owner must never change during the search");
			} finally {
				MockPlayers.removeMockPlayer(helper, player);
			}

			helper.succeed();
		});
	}

	private static BubbleShieldBlockEntity placeProjectorAt(GameTestHelper helper, BlockPos pos) {
		helper.setBlock(pos, ModBlocks.BUBBLE_SHIELD_PROJECTOR);
		BubbleShieldBlockEntity be = helper.getBlockEntity(pos, BubbleShieldBlockEntity.class);
		be.getShieldState().targetRadius = 4.0F;
		return be;
	}

	/**
	 * (c) The loot injection: fresh-context draws of the Ancient City and End City
	 * treasure tables each surface at least one Resonant Core, while an untouched
	 * table (simple dungeon) yields none in 50 draws.
	 *
	 * <p>Fix 9j determinism bound: the draws use the level's live
	 * {@code RandomSource} (the chest {@code LootParams} context offers no seed
	 * hook), so the draw count is sized for a false-failure probability below
	 * 1e-12 per assertion: the injected pool is 1-in-10 per chest
	 * ({@code CoreLootInjector}), so 270 all-miss draws are a 0.9^270 &asymp;
	 * 4.4e-13 fluke. The untouched-table check asserts an exact 0 and carries no
	 * probabilistic risk.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void lootInjectionSeedsCores(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		Vec3 origin = Vec3.atCenterOf(helper.absolutePos(PROJECTOR_POS));

		LootTable ancientCity = level.getServer().reloadableRegistries().getLootTable(BuiltInLootTables.ANCIENT_CITY);
		helper.assertTrue(countCoreDraws(level, ancientCity, origin, 270) >= 1,
				"ancient city chests should yield at least one resonant core in 270 draws");

		LootTable endCity = level.getServer().reloadableRegistries().getLootTable(BuiltInLootTables.END_CITY_TREASURE);
		helper.assertTrue(countCoreDraws(level, endCity, origin, 270) >= 1,
				"end city treasure should yield at least one resonant core in 270 draws");

		LootTable untouched = level.getServer().reloadableRegistries().getLootTable(BuiltInLootTables.SIMPLE_DUNGEON);
		helper.assertTrue(countCoreDraws(level, untouched, origin, 50) == 0,
				"the untouched simple dungeon table must never yield a resonant core");
		helper.succeed();
	}

	/**
	 * (d) D5/fix 7: an ACTIVE projector holds a {@code bubbleshield:shield_projector}
	 * chunk ticket on its own chunk (so a far-flung shield edge stays enforced when
	 * no player keeps the chunk ticking), re-armed every active tick. There is
	 * deliberately NO explicit release anywhere — the ticket identity is
	 * (type, chunk, level), so two projectors in one chunk SHARE one ticket and an
	 * explicit release by the one deactivating would strip the other's coverage.
	 * The ticket therefore SURVIVES deactivation and block removal, expiring only
	 * through the SHIELD_PROJECTOR type's 100-tick timeout once nothing re-arms
	 * it. Asserted directly against the level's TicketStorage (the same store
	 * {@code ServerChunkCache.addTicketWithRadius} writes to): the gametest
	 * framework force-loads the structure's chunks with its own tickets, so
	 * "chunk is ticking" alone would prove nothing here — ticket presence is the
	 * headless-robust signal.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void activeShieldHoldsChunkTicket(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		BlockPos absPos = helper.absolutePos(PROJECTOR_POS);

		helper.assertTrue(!hasProjectorTicket(helper, absPos), "an inactive projector must hold no chunk ticket");
		helper.assertTrue(be.tryActivate(), "the fueled shield should activate");
		helper.assertTrue(hasProjectorTicket(helper, absPos), "activation should register the projector chunk ticket");

		helper.startSequence()
				// A few ticks of runtime: the per-tick re-arm must keep it present
				// (every re-add resets the 100-tick countdown).
				.thenExecuteAfter(10, () -> {
					helper.assertTrue(hasProjectorTicket(helper, absPos), "the ticket should stay armed while active");
					be.setActive(false);
					helper.assertTrue(hasProjectorTicket(helper, absPos),
							"deactivation must NOT release the ticket (fix 7: a co-located projector may share it)");
				})
				// Well past the 100-tick timeout since the last active-tick re-arm:
				// the orphaned ticket must have expired on its own.
				.thenExecuteAfter(120, () -> {
					helper.assertTrue(!hasProjectorTicket(helper, absPos),
							"the un-re-armed ticket should expire via the SHIELD_PROJECTOR 100-tick timeout");
					helper.assertTrue(be.tryActivate(), "the shield should re-activate");
					helper.assertTrue(hasProjectorTicket(helper, absPos), "re-activation should re-register the ticket");
					// Break the projector: removal must not strip the ticket either.
					helper.setBlock(PROJECTOR_POS, Blocks.AIR);
				})
				.thenExecuteAfter(2, () -> helper.assertTrue(
						hasProjectorTicket(helper, absPos),
						"block removal must not release the shared ticket; it expires by timeout like any orphan"))
				.thenSucceed();
	}

	/** @return true when the chunk holding {@code absPos} has a shield-projector ticket. */
	private static boolean hasProjectorTicket(GameTestHelper helper, BlockPos absPos) {
		TicketStorage tickets = helper.getLevel().getChunkSource().getDataStorage().computeIfAbsent(TicketStorage.TYPE);
		for (Ticket ticket : tickets.getTickets(ChunkPos.containing(absPos).pack())) {
			if (ticket.getType() == ModTicketTypes.SHIELD_PROJECTOR) {
				return true;
			}
		}

		return false;
	}

	/**
	 * (e) D7c broadcast coalescing, asserted end-to-end on the wire: several
	 * replicable mutations landing on the SAME tick must flush as exactly ONE
	 * ShieldSyncS2C broadcast (carrying the final combined state), not one per
	 * mutation. The capturing mock player's embedded channel records every packet
	 * the server actually sent it; payloads are filtered by projector position
	 * because concurrent tests' shields broadcast to every player in the level.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void sameTickMutationsCoalesceIntoOneSync(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper);
		MockPlayers.CapturingMockPlayer capture = MockPlayers.createCapturingMockPlayer(helper, GameType.CREATIVE);
		BlockPos absPos = helper.absolutePos(PROJECTOR_POS);

		helper.startSequence()
				// Let the placement/join syncs flush, then drop them as setup noise.
				.thenExecuteAfter(5, () -> drainSyncPayloads(capture, absPos))
				.thenExecute(() -> {
					// Four distinct replicable mutations in ONE tick.
					be.setCustomName("Coalesced");
					be.setColorOverride(0xFFC81414);
					be.whitelistAdd(helper.getLevel().getServer(), "SomeFriend");
					be.setSettings(12, 7, 0, 0, false, 0);
				})
				.thenExecuteAfter(5, () -> {
					List<ShieldPayloads.ShieldSyncS2C> syncs = drainSyncPayloads(capture, absPos);
					helper.assertTrue(
							syncs.size() == 1,
							"4 same-tick mutations should coalesce into exactly 1 ShieldSyncS2C, got " + syncs.size());
					helper.assertTrue(
							"Coalesced".equals(syncs.get(0).customName()) && syncs.get(0).visual().effectId() == 7,
							"the one coalesced sync should carry the final combined state");
					MockPlayers.removeMockPlayer(helper, capture.player());
				})
				.thenSucceed();
	}

	/** Drains the capture channel, returning the ShieldSyncS2C payloads for the given projector. */
	private static List<ShieldPayloads.ShieldSyncS2C> drainSyncPayloads(MockPlayers.CapturingMockPlayer capture, BlockPos absPos) {
		List<ShieldPayloads.ShieldSyncS2C> syncs = new ArrayList<>();
		for (Object message : capture.drainPackets()) {
			if (message instanceof ClientboundCustomPayloadPacket packet
					&& packet.payload() instanceof ShieldPayloads.ShieldSyncS2C sync
					&& sync.pos().equals(absPos)) {
				syncs.add(sync);
			}
		}

		return syncs;
	}

	/**
	 * (e') The per-player C2S token bucket gating the custom shield payloads:
	 * a same-tick flood gets exactly the burst capacity through, refills at
	 * 1 token per tick, and keeps dropping whatever exceeds the refill.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void c2sRateLimitDropsFlood(GameTestHelper helper) {
		ServerPlayer player = MockPlayers.createUniqueMockPlayer(helper);
		// A fresh bucket passes exactly the burst capacity in one tick, then drops.
		for (int i = 0; i < ServerNet.C2S_TOKENS_PER_SECOND; i++) {
			helper.assertTrue(ServerNet.tryConsumeC2S(player, "gametest-flood"),
					"burst packet " + i + " should pass the rate limit");
		}

		helper.assertTrue(!ServerNet.tryConsumeC2S(player, "gametest-flood"),
				"the packet beyond the burst capacity must be dropped");
		long drainedAtTick = helper.getLevel().getServer().getTickCount();

		helper.runAfterDelay(5, () -> {
			try {
				// The refill is exactly 1 token per elapsed tick (capped at capacity).
				int refilled = (int) Math.min(
						ServerNet.C2S_TOKENS_PER_SECOND,
						helper.getLevel().getServer().getTickCount() - drainedAtTick);
				helper.assertTrue(refilled > 0, "the delayed check should run on a later tick");
				for (int i = 0; i < refilled; i++) {
					helper.assertTrue(ServerNet.tryConsumeC2S(player, "gametest-flood"),
							"the bucket should refill 1 token per tick (token " + i + " of " + refilled + ")");
				}

				helper.assertTrue(!ServerNet.tryConsumeC2S(player, "gametest-flood"),
						"packets beyond the per-tick refill must keep dropping");
			} finally {
				MockPlayers.removeMockPlayer(helper, player);
			}

			helper.succeed();
		});
	}

	/** Draws the table {@code draws} times with fresh chest-context params, counting resonant cores. */
	private static int countCoreDraws(ServerLevel level, LootTable table, Vec3 origin, int draws) {
		int found = 0;
		for (int i = 0; i < draws; i++) {
			LootParams params = new LootParams.Builder(level)
					.withParameter(LootContextParams.ORIGIN, origin)
					.create(LootContextParamSets.CHEST);
			for (ItemStack stack : table.getRandomItems(params)) {
				if (stack.is(ModItems.RESONANT_CORE)) {
					found++;
				}
			}
		}

		return found;
	}
}

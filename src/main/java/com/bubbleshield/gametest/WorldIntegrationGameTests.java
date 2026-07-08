package com.bubbleshield.gametest;

import java.util.UUID;

import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.command.BubbleShieldCommand;
import com.bubbleshield.effect.EffectRegistry;
import com.bubbleshield.registry.ModBlocks;
import com.bubbleshield.registry.ModItems;
import com.bubbleshield.shield.ShieldMode;
import com.bubbleshield.shield.ShieldShape;
import com.bubbleshield.shield.ShieldState;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
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
	 * {@code data/bubbleshield/test_environment/world_integration.json}. This class
	 * spawns identically-named "test-mock-player" mocks, so it gets its own batch
	 * instead of reshuffling the shared default one (see
	 * ColorGameTests.ISOLATED_ENVIRONMENT for the full story).
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
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
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
				helper.getLevel().getServer().getPlayerList().remove(player);
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
						helper.getLevel().getServer().getPlayerList().remove(player);
					}

					helper.succeed();
				});
			});
		});
	}

	/** (a'') list/info execute successfully and print the expected number of entries. */
	@GameTest(environment = ISOLATED_ENVIRONMENT, padding = 16)
	public void commandListAndInfoExecute(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		try {
			CommandDispatcher<CommandSourceStack> dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
			int firstPage = dispatcher.execute("bubbleshield list", player.createCommandSourceStack());
			helper.assertTrue(firstPage == BubbleShieldCommand.LIST_PAGE_SIZE,
					"list (page 1) should print 10 effects, got " + firstPage);
			int lastPage = dispatcher.execute("bubbleshield list " + BubbleShieldCommand.LIST_PAGE_COUNT, player.createCommandSourceStack());
			helper.assertTrue(lastPage == EffectRegistry.COUNT % BubbleShieldCommand.LIST_PAGE_SIZE,
					"the last page should print the trailing " + EffectRegistry.COUNT % BubbleShieldCommand.LIST_PAGE_SIZE + " effects, got " + lastPage);
			int overflowPage = dispatcher.execute("bubbleshield list 9999", player.createCommandSourceStack());
			helper.assertTrue(overflowPage == lastPage, "an out-of-range page should clamp to the last page, got " + overflowPage);
			int info = dispatcher.execute("bubbleshield info 42", player.createCommandSourceStack());
			helper.assertTrue(info == 1, "info should succeed, got " + info);
		} catch (CommandSyntaxException e) {
			helper.assertTrue(false, "list/info should parse and execute: " + e.getMessage());
		} finally {
			helper.getLevel().getServer().getPlayerList().remove(player);
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
	 * (c) The loot injection: 200 fresh-context draws of the Ancient City and End City
	 * treasure tables each surface at least one Resonant Core (the injected pool is a
	 * 1-in-10 per chest; 200 misses would be a ~7e-10 fluke), while an untouched table
	 * (simple dungeon) yields none in 50 draws.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void lootInjectionSeedsCores(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		Vec3 origin = Vec3.atCenterOf(helper.absolutePos(PROJECTOR_POS));

		LootTable ancientCity = level.getServer().reloadableRegistries().getLootTable(BuiltInLootTables.ANCIENT_CITY);
		helper.assertTrue(countCoreDraws(level, ancientCity, origin, 200) >= 1,
				"ancient city chests should yield at least one resonant core in 200 draws");

		LootTable endCity = level.getServer().reloadableRegistries().getLootTable(BuiltInLootTables.END_CITY_TREASURE);
		helper.assertTrue(countCoreDraws(level, endCity, origin, 200) >= 1,
				"end city treasure should yield at least one resonant core in 200 draws");

		LootTable untouched = level.getServer().reloadableRegistries().getLootTable(BuiltInLootTables.SIMPLE_DUNGEON);
		helper.assertTrue(countCoreDraws(level, untouched, origin, 50) == 0,
				"the untouched simple dungeon table must never yield a resonant core");
		helper.succeed();
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

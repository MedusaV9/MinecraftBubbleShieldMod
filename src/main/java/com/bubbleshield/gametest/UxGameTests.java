package com.bubbleshield.gametest;

import java.util.ArrayList;
import java.util.List;

import com.bubbleshield.BubbleShield;
import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.menu.BubbleShieldMenu;
import com.bubbleshield.net.ShieldPayloads;
import com.bubbleshield.registry.ModBlocks;
import com.bubbleshield.registry.ModItems;
import com.bubbleshield.shield.ShieldLogic;
import com.bubbleshield.shield.ShieldState;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

/**
 * Coverage for WP7 "UX, telemetry &amp; progression": the E1 power-readout data
 * slots (regen/drain/whitelist/threats) against live shield state, the E7 boss
 * bar percent suffix + E3b overlay switch, the E3d one-shot ready ping, the E4
 * {@code /bubbleshield log}/{@code status} subcommands, the C7 Bulwark
 * advancement chain (tier-gated) + unbroken (500 absorbed), and the C8 Aegis
 * Core / Patch Kit loot injections.
 */
public class UxGameTests {
	/**
	 * A dedicated (but otherwise vanilla-default) test environment,
	 * {@code data/bubbleshield/test_environment/ux.json}: the vanilla runner
	 * batches tests by environment (50 per batch, ticked in parallel), and adding
	 * this class to the shared default batch would push the pre-existing suite
	 * past 50 and reshuffle which tests overlap in time (see ColorGameTests for
	 * the full rationale).
	 */
	private static final String ISOLATED_ENVIRONMENT = "bubbleshield:ux";
	private static final BlockPos PROJECTOR_POS = new BlockPos(4, 2, 4);
	private static final int PLENTY_OF_FUEL = 600;

	private static BubbleShieldBlockEntity placeProjector(GameTestHelper helper, float targetRadius) {
		helper.setBlock(PROJECTOR_POS, ModBlocks.BUBBLE_SHIELD_PROJECTOR);
		BubbleShieldBlockEntity be = helper.getBlockEntity(PROJECTOR_POS, BubbleShieldBlockEntity.class);
		be.getShieldState().targetRadius = targetRadius;
		return be;
	}

	private static AdvancementHolder advancement(GameTestHelper helper, String path) {
		AdvancementNode node = helper.getLevel().getServer().getAdvancements().tree().get(BubbleShield.id(path));
		helper.assertTrue(node != null, "advancement bubbleshield:" + path + " should be loaded");
		return node.holder();
	}

	private static boolean isDone(ServerPlayer player, AdvancementHolder advancement) {
		return player.getAdvancements().getOrStartProgress(advancement).isDone();
	}

	private static CommandDispatcher<CommandSourceStack> dispatcher(GameTestHelper helper) {
		return helper.getLevel().getServer().getCommands().getDispatcher();
	}

	/**
	 * (E1) The power-readout data slots mirror live state: regen (13) and drain
	 * (14) read 0 while inactive; a tier-1 active shield reports the exact
	 * out-of-combat and in-combat regen rates and the diameter-scaled baseline
	 * drain; the whitelist slot (15) tracks adds.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, padding = 16)
	public void menuDataPowerReadoutSlots(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		var data = be.getMenuData();

		helper.assertTrue(data.get(BubbleShieldMenu.DATA_REGEN_PER_MIN_X10) == 0,
				"an inactive shield's regen slot must read 0");
		helper.assertTrue(data.get(BubbleShieldMenu.DATA_DRAIN_PER_MIN_X10) == 0,
				"an inactive shield's drain slot must read 0");

		be.getCoreContainer().setItem(0, new ItemStack(ModItems.RESONANT_CORE));
		helper.assertTrue(be.tryActivate(), "the fueled shield should activate");

		// Force out-of-combat regardless of the level clock (gameTime - lastHit
		// >= COMBAT_GATE_TICKS always holds against a negative sentinel): tier 1
		// pulses 3 HP x3 OOC every 40 ticks -> 9 * 30 pulses/min * 10 = 2700.
		be.getShieldState().lastHitGameTime = -ShieldLogic.COMBAT_GATE_TICKS;
		helper.assertTrue(data.get(BubbleShieldMenu.DATA_REGEN_PER_MIN_X10) == 2700,
				"tier-1 OOC regen should read 2700 (9.0 HP/min x10 x30 pulses), got "
						+ data.get(BubbleShieldMenu.DATA_REGEN_PER_MIN_X10));
		// Baseline drain at diameter 8, no ECO/capacitor: 1 unit per 20 ticks ->
		// 60 units/min -> 600 in the x10 encoding.
		helper.assertTrue(data.get(BubbleShieldMenu.DATA_DRAIN_PER_MIN_X10) == 600,
				"the active drain slot should read 600, got " + data.get(BubbleShieldMenu.DATA_DRAIN_PER_MIN_X10));

		// A hit right now opens the combat gate: tier 1 falls back to its base rate.
		be.getShieldState().lastHitGameTime = helper.getLevel().getGameTime();
		helper.assertTrue(data.get(BubbleShieldMenu.DATA_REGEN_PER_MIN_X10) == 900,
				"tier-1 in-combat regen should read 900, got " + data.get(BubbleShieldMenu.DATA_REGEN_PER_MIN_X10));

		helper.assertTrue(data.get(BubbleShieldMenu.DATA_WHITELIST_COUNT) == 0, "the whitelist starts empty");
		be.whitelistAdd(helper.getLevel().getServer(), "Alice");
		be.whitelistAdd(helper.getLevel().getServer(), "Bob");
		helper.assertTrue(data.get(BubbleShieldMenu.DATA_WHITELIST_COUNT) == 2,
				"two adds should read 2 in the whitelist slot, got " + data.get(BubbleShieldMenu.DATA_WHITELIST_COUNT));
		helper.succeed();
	}

	/**
	 * (Fix 8) DATA_COOLDOWN_SECONDS uses CEILING division: 1..19 remaining ticks
	 * must display 1 s (the old floor showed 0 s while the server still refused
	 * activation), exact boundaries round up correctly, and the slot is gated to
	 * 0 while the shield is ACTIVE (a post-revive background window is not a
	 * user-facing "time until ready").
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, padding = 16)
	public void menuDataCooldownSecondsCeil(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		ShieldState state = be.getShieldState();
		var data = be.getMenuData();
		long gameTime = helper.getLevel().getGameTime();

		helper.assertTrue(data.get(BubbleShieldMenu.DATA_COOLDOWN_SECONDS) == 0,
				"no cooldown should read 0 s");
		state.cooldownUntil = gameTime + 1L;
		helper.assertTrue(data.get(BubbleShieldMenu.DATA_COOLDOWN_SECONDS) == 1,
				"1 remaining tick must display 1 s (ceil), got " + data.get(BubbleShieldMenu.DATA_COOLDOWN_SECONDS));
		state.cooldownUntil = gameTime + 19L;
		helper.assertTrue(data.get(BubbleShieldMenu.DATA_COOLDOWN_SECONDS) == 1,
				"19 remaining ticks must display 1 s (ceil), got " + data.get(BubbleShieldMenu.DATA_COOLDOWN_SECONDS));
		state.cooldownUntil = gameTime + 20L;
		helper.assertTrue(data.get(BubbleShieldMenu.DATA_COOLDOWN_SECONDS) == 1,
				"a full 20 remaining ticks is exactly 1 s, got " + data.get(BubbleShieldMenu.DATA_COOLDOWN_SECONDS));
		state.cooldownUntil = gameTime + 21L;
		helper.assertTrue(data.get(BubbleShieldMenu.DATA_COOLDOWN_SECONDS) == 2,
				"21 remaining ticks must round up to 2 s, got " + data.get(BubbleShieldMenu.DATA_COOLDOWN_SECONDS));

		// Active gating: with a background (post-revive style) window still
		// running, an ACTIVE shield's slot reads 0.
		state.cooldownUntil = gameTime;
		helper.assertTrue(be.tryActivate(), "the fueled shield should activate");
		state.cooldownUntil = gameTime + 100L;
		helper.assertTrue(data.get(BubbleShieldMenu.DATA_COOLDOWN_SECONDS) == 0,
				"while ACTIVE the cooldown slot must read 0, got " + data.get(BubbleShieldMenu.DATA_COOLDOWN_SECONDS));
		helper.succeed();
	}

	/**
	 * (Fix 13) Inactive-projector sync: the block-entity ticker runs while
	 * INACTIVE (fuel insert, cooldown countdown and the comparator depend on it)
	 * and the WP2 coalescing flush lives in the always-running part of serverTick
	 * — so a GUI mutation arriving through the REAL C2S handler path on an
	 * inactive projector reaches clients within a tick. Exercised end-to-end: a
	 * SetSettingsC2S packet is pushed through the mock owner's live connection
	 * (Fabric's global receiver validates, clamps and applies it), then the
	 * capture channel must show exactly ONE ShieldSyncS2C carrying the updated
	 * replicated state (the settings change and its same-tick max-health
	 * recompute coalesce into the one broadcast).
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void inactiveProjectorSyncsC2SMutationWithinATick(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		BlockPos absPos = helper.absolutePos(PROJECTOR_POS);

		MockPlayers.CapturingMockPlayer capture = MockPlayers.createCapturingMockPlayer(helper, GameType.CREATIVE);
		ServerPlayer player = capture.player();
		// Owner + in interact range: the handler's validatedShield/isOwner gates.
		state.ownerUuid = player.getUUID();
		Vec3 center = Vec3.atCenterOf(absPos);
		player.snapTo(center.x + 1.5, center.y - 0.5, center.z);

		helper.startSequence()
				// Let the placement/join syncs flush, then drop them as setup noise.
				.thenExecuteAfter(5, () -> {
					helper.assertTrue(!state.active, "the projector under test must be INACTIVE");
					drainShieldSyncs(capture, absPos);
					player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(
							new ShieldPayloads.SetSettingsC2S(absPos, 12, 7, 0, 0, false, 0)));
				})
				.thenExecuteAfter(2, () -> {
					helper.assertTrue(state.targetRadius == 6.0F && state.effectId == 7,
							"the C2S handler should have applied the settings to the inactive projector, radius "
									+ state.targetRadius + " effect " + state.effectId);
					List<ShieldPayloads.ShieldSyncS2C> syncs = drainShieldSyncs(capture, absPos);
					helper.assertTrue(syncs.size() == 1,
							"the inactive projector's tick must flush exactly ONE sync, got " + syncs.size());
					ShieldPayloads.ShieldSyncS2C sync = syncs.get(0);
					helper.assertTrue(sync.visual().effectId() == 7 && sync.visual().targetRadius() == 6.0F,
							"the sync must carry the updated replicated state, radius "
									+ sync.visual().targetRadius() + " effect " + sync.visual().effectId());
					MockPlayers.removeMockPlayer(helper, player);
				})
				.thenSucceed();
	}

	/** Drains the capture channel, returning the ShieldSyncS2C payloads for the given projector. */
	private static List<ShieldPayloads.ShieldSyncS2C> drainShieldSyncs(MockPlayers.CapturingMockPlayer capture, BlockPos absPos) {
		List<ShieldPayloads.ShieldSyncS2C> syncs = new ArrayList<>();
		Object message;
		while ((message = capture.channel().readOutbound()) != null) {
			if (message instanceof ClientboundCustomPayloadPacket packet
					&& packet.payload() instanceof ShieldPayloads.ShieldSyncS2C sync
					&& sync.pos().equals(absPos)) {
				syncs.add(sync);
			}
		}

		return syncs;
	}

	/**
	 * (E1) The threat slot (17) counts a hostile monster in the census ring of an
	 * ACTIVE shield and clears when the monster is gone.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void menuDataThreatSlotCountsZombie(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "the fueled shield should activate");
		helper.assertTrue(be.getMenuData().get(BubbleShieldMenu.DATA_THREAT_COUNT) == 0,
				"no threats yet: the slot must read 0");

		// 6 blocks from the center: outside the radius-4 bubble (no barrier
		// interaction) but inside the census ring (radius + 8 = 12). Frozen +
		// helmeted so it neither wanders nor burns away mid-census.
		Zombie zombie = helper.spawn(EntityTypes.ZOMBIE, new Vec3(10.5, 2.0, 4.5));
		zombie.setNoAi(true);
		zombie.setPersistenceRequired();
		zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(
						be.getMenuData().get(BubbleShieldMenu.DATA_THREAT_COUNT) == 1,
						"the once-per-second census should surface the zombie in slot 17"))
				.thenExecute(zombie::discard)
				.thenWaitUntil(() -> helper.assertTrue(
						be.getMenuData().get(BubbleShieldMenu.DATA_THREAT_COUNT) == 0,
						"the slot should clear after the zombie is gone"))
				.thenSucceed();
	}

	/**
	 * (E7 + E3b) The boss bar name carries the health percent quantized to 5%
	 * steps ("Home Base · NN%"), and below the 25% last-stand threshold the bar's
	 * overlay switches PROGRESS -&gt; NOTCHED_10 as a shape cue.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void bossBarPercentSuffixAndOverlaySwitch(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		be.setCustomName("Home Base");
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.startSequence()
				.thenExecuteAfter(2, () -> {
					ServerBossEvent event = be.getBossEvent();
					helper.assertTrue(event != null, "an active shield should have created its boss event");
					helper.assertTrue("Home Base \u00b7 100%".equals(event.getName().getString()),
							"a full shield should read 100%, got '" + event.getName().getString() + "'");
					helper.assertTrue(event.getOverlay() == BossEvent.BossBarOverlay.PROGRESS,
							"a full shield uses the PROGRESS overlay");

					// 75/125 = 60%: an exact 5% step, still above last stand.
					be.applyShieldDamage(50.0F);
				})
				.thenExecuteAfter(2, () -> {
					ServerBossEvent event = be.getBossEvent();
					helper.assertTrue("Home Base \u00b7 60%".equals(event.getName().getString()),
							"75/125 should quantize to 60%, got '" + event.getName().getString() + "'");
					helper.assertTrue(event.getOverlay() == BossEvent.BossBarOverlay.PROGRESS,
							"60% health keeps the PROGRESS overlay");

					// 25/125 = 20%: below the 25% last-stand threshold.
					be.applyShieldDamage(50.0F);
				})
				.thenExecuteAfter(2, () -> {
					ServerBossEvent event = be.getBossEvent();
					helper.assertTrue("Home Base \u00b7 20%".equals(event.getName().getString()),
							"25/125 should quantize to 20%, got '" + event.getName().getString() + "'");
					helper.assertTrue(event.getOverlay() == BossEvent.BossBarOverlay.NOTCHED_10,
							"below 25% health the overlay must switch to NOTCHED_10");
				})
				.thenSucceed();
	}

	/**
	 * (E3d) The ready ping: an UNREVIVED break cooldown expiring naturally while
	 * the projector is loaded flips {@code readyAnnounced} exactly once — false
	 * for the whole cooldown, true at expiry, and (structurally) never re-fired:
	 * the tick condition requires {@code !readyAnnounced}, so once true it can
	 * never trigger again.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void readyPingFlipsOnceAtNaturalExpiry(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		ShieldState state = be.getShieldState();
		helper.assertTrue(state.readyAnnounced, "the flag defaults to true (nothing to announce)");
		helper.assertTrue(be.tryActivate(), "shield should activate");

		be.applyShieldDamage(1000.0F);
		helper.assertTrue(!state.active, "the shield should have broken");
		helper.assertTrue(!state.readyAnnounced, "a break must arm the ready ping");

		// Shorten the tier-0 15-minute cooldown to something tickable.
		state.cooldownUntil = helper.getLevel().getGameTime() + 30L;

		helper.startSequence()
				.thenExecuteAfter(10, () -> helper.assertTrue(!state.readyAnnounced,
						"the flag must stay false while the cooldown is still running"))
				.thenExecuteAfter(40, () -> helper.assertTrue(state.readyAnnounced,
						"natural expiry should fire the ready ping and set the flag"))
				.thenExecuteAfter(20, () -> helper.assertTrue(state.readyAnnounced && !state.active,
						"extra ticks after the ping change nothing (no double-fire, no self-activation)"))
				.thenSucceed();
	}

	/**
	 * (E4) {@code /bubbleshield log}: the localized empty notice on a fresh
	 * projector (result 1), one line per threat-log entry afterwards (result 2),
	 * and the read-only guarantee — the ownerless projector is never claimed.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, padding = 16)
	public void commandLogPrintsThreatLog(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		ServerPlayer player = MockPlayers.createUniqueMockPlayer(helper);
		try {
			int empty = dispatcher(helper).execute("bubbleshield log", player.createCommandSourceStack());
			helper.assertTrue(empty == 1, "an empty log should print the localized notice (result 1), got " + empty);

			long gameTime = helper.getLevel().getGameTime();
			state.recordThreat("Griefer", 4.5F, gameTime - 100L);
			state.recordThreat("Raider", 2.25F, gameTime - 40L);
			int lines = dispatcher(helper).execute("bubbleshield log", player.createCommandSourceStack());
			helper.assertTrue(lines == 2, "two recorded threats should print two lines, got " + lines);

			helper.assertTrue(state.ownerUuid == null,
					"log is read-only: the ownerless projector must never be claimed");
		} catch (CommandSyntaxException e) {
			helper.assertTrue(false, "log should parse and execute: " + e.getMessage());
		} finally {
			MockPlayers.removeMockPlayer(helper, player);
		}

		helper.succeed();
	}

	/**
	 * (E4) {@code /bubbleshield status} executes successfully (result 1) and its
	 * feedback actually mentions the HP readout — asserted against the system-chat
	 * packets REALLY sent to the invoking player's connection.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, padding = 16)
	public void commandStatusExecutesAndMentionsHp(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		be.getCoreContainer().setItem(0, new ItemStack(ModItems.RESONANT_CORE));
		helper.assertTrue(be.tryActivate(), "shield should activate");

		MockPlayers.CapturingMockPlayer mock = MockPlayers.createCapturingMockPlayer(helper, GameType.CREATIVE);
		ServerPlayer player = mock.player();
		try {
			// Flush the join/setup packet noise before the command under test.
			while (mock.channel().readOutbound() != null) {
				// drain
			}

			int result = dispatcher(helper).execute("bubbleshield status", player.createCommandSourceStack());
			helper.assertTrue(result == 1, "status should succeed (result 1), got " + result);

			boolean mentionsHp = false;
			Object message;
			while ((message = mock.channel().readOutbound()) != null) {
				if (message instanceof ClientboundSystemChatPacket chat
						&& chat.content().getString().contains("HP")) {
					mentionsHp = true;
				}
			}

			helper.assertTrue(mentionsHp, "the status feedback should include the HP line");
			helper.assertTrue(be.getShieldState().ownerUuid == null,
					"status is read-only: the ownerless projector must never be claimed");
		} catch (CommandSyntaxException e) {
			helper.assertTrue(false, "status should parse and execute: " + e.getMessage());
		} finally {
			MockPlayers.removeMockPlayer(helper, player);
		}

		helper.succeed();
	}

	/**
	 * (C7) The Bulwark chain is tier-gated through the shield_activated trigger's
	 * new tier bounds: a tier-0 activation awards none of it, a tier-1 activation
	 * awards reinforced (not bastion), and a tier-3 activation completes bastion
	 * and aegis_bearer in one go (min bounds, not exact matches).
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, padding = 16)
	public void advancementBulwarkChainTierGates(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);

		AdvancementHolder reinforced = advancement(helper, "reinforced");
		AdvancementHolder bastion = advancement(helper, "bastion");
		AdvancementHolder aegisBearer = advancement(helper, "aegis_bearer");
		ServerPlayer player = MockPlayers.createUniqueMockPlayer(helper);
		try {
			helper.assertTrue(be.tryActivate(player), "the tier-0 activation should succeed");
			helper.assertTrue(!isDone(player, reinforced), "a tier-0 activation must NOT award reinforced");

			be.setActive(false);
			be.getCoreContainer().setItem(0, new ItemStack(ModItems.RESONANT_CORE));
			helper.assertTrue(be.tryActivate(player), "the tier-1 activation should succeed");
			helper.assertTrue(isDone(player, reinforced), "a tier-1 activation should award reinforced");
			helper.assertTrue(!isDone(player, bastion), "a tier-1 activation must NOT award bastion");

			be.setActive(false);
			be.getCoreContainer().setItem(0, new ItemStack(ModItems.AEGIS_CORE));
			helper.assertTrue(be.tryActivate(player), "the tier-3 activation should succeed");
			helper.assertTrue(isDone(player, bastion), "a tier-3 activation should award bastion (min bound)");
			helper.assertTrue(isDone(player, aegisBearer), "a tier-3 activation should award aegis_bearer");
		} finally {
			MockPlayers.removeMockPlayer(helper, player);
		}

		helper.succeed();
	}

	/**
	 * (C7) "unbroken" awards when ONE projector's lifetime absorbed total reaches
	 * 500, fed from the damage path (the direct applyShieldDamage road here; the
	 * projectile interception path fires the identical ModCriteria helper).
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, padding = 16)
	public void advancementUnbrokenAt500Absorbed(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		ShieldState state = be.getShieldState();

		AdvancementHolder unbroken = advancement(helper, "unbroken");
		ServerPlayer owner = MockPlayers.createUniqueMockPlayer(helper);
		try {
			state.ownerUuid = owner.getUUID();
			helper.assertTrue(be.tryActivate(owner), "shield should activate");

			be.applyShieldDamage(50.0F);
			helper.assertTrue(!isDone(owner, unbroken),
					"50 absorbed is far below the 500 threshold: unbroken must not award yet");

			// Pre-seed the lifetime total just under the threshold; the next hit
			// (health 75/125 = 60%, not last stand, tier 0 -> applied = raw) tips
			// the running total to 503 >= 500.
			state.absorbedTotal = 499.0F;
			be.applyShieldDamage(4.0F);
			helper.assertTrue(isDone(owner, unbroken),
					"crossing 500 lifetime absorbed should award unbroken, got total " + state.absorbedTotal);
		} finally {
			MockPlayers.removeMockPlayer(helper, owner);
		}

		helper.succeed();
	}

	/**
	 * (C8) The new loot injections: End City treasure surfaces an Aegis Core
	 * (1-in-20; 400 misses would be a ~1e-9 fluke) and Ancient City chests a
	 * 1-2 stack of Patch Kits (1-in-8; 200 misses ~3e-12), while an untouched
	 * table (simple dungeon) yields neither.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void lootInjectionSeedsAegisCoresAndPatchKits(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		Vec3 origin = Vec3.atCenterOf(helper.absolutePos(PROJECTOR_POS));

		LootTable endCity = level.getServer().reloadableRegistries().getLootTable(BuiltInLootTables.END_CITY_TREASURE);
		helper.assertTrue(countItemDraws(helper, level, endCity, origin, 400, ModItems.AEGIS_CORE) >= 1,
				"end city treasure should yield at least one aegis core in 400 draws");

		LootTable ancientCity = level.getServer().reloadableRegistries().getLootTable(BuiltInLootTables.ANCIENT_CITY);
		helper.assertTrue(countItemDraws(helper, level, ancientCity, origin, 200, ModItems.PATCH_KIT) >= 1,
				"ancient city chests should yield at least one patch kit stack in 200 draws");

		LootTable untouched = level.getServer().reloadableRegistries().getLootTable(BuiltInLootTables.SIMPLE_DUNGEON);
		helper.assertTrue(countItemDraws(helper, level, untouched, origin, 50, ModItems.AEGIS_CORE) == 0,
				"the untouched simple dungeon table must never yield an aegis core");
		helper.assertTrue(countItemDraws(helper, level, untouched, origin, 50, ModItems.PATCH_KIT) == 0,
				"the untouched simple dungeon table must never yield a patch kit");
		helper.succeed();
	}

	/**
	 * Draws the table {@code draws} times with fresh chest-context params, counting
	 * stacks of {@code item} — and asserting every patch-kit stack is the injected
	 * 1-2 size while at it.
	 */
	private static int countItemDraws(GameTestHelper helper, ServerLevel level, LootTable table, Vec3 origin, int draws, Item item) {
		int found = 0;
		for (int i = 0; i < draws; i++) {
			LootParams params = new LootParams.Builder(level)
					.withParameter(LootContextParams.ORIGIN, origin)
					.create(LootContextParamSets.CHEST);
			for (ItemStack stack : table.getRandomItems(params)) {
				if (stack.is(item)) {
					found++;
					if (item == ModItems.PATCH_KIT) {
						helper.assertTrue(stack.getCount() >= 1 && stack.getCount() <= 2,
								"the injected patch kit stack should be 1-2, got " + stack.getCount());
					}
				}
			}
		}

		return found;
	}
}

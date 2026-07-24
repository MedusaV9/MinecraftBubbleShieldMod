package com.bubbleshield.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
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
	 * Drains the capture channel, counting the sound packets carrying exactly
	 * {@code sound}. NOTE the drain is destructive across ALL packet types: to
	 * count several sounds out of one window, drain once and use
	 * {@link #countSoundPackets} per sound instead of calling this repeatedly.
	 */
	private static int drainSoundPackets(MockPlayers.CapturingMockPlayer capture, SoundEvent sound) {
		return countSoundPackets(capture.drainPackets(), sound);
	}

	/** Counts the sound packets among {@code packets} carrying exactly {@code sound}. */
	private static int countSoundPackets(List<Object> packets, SoundEvent sound) {
		int count = 0;
		for (Object message : packets) {
			if (message instanceof ClientboundSoundPacket packet && packet.getSound().value() == sound) {
				count++;
			}
		}

		return count;
	}

	/** Drains the capture channel, returning the system-chat lines in send order. */
	private static List<String> drainChatLines(MockPlayers.CapturingMockPlayer capture) {
		List<String> lines = new ArrayList<>();
		for (Object message : capture.drainPackets()) {
			if (message instanceof ClientboundSystemChatPacket chat) {
				lines.add(chat.content().getString());
			}
		}

		return lines;
	}

	/** Asserts that one of the captured chat lines is exactly {@code expected}. */
	private static void assertChatLine(GameTestHelper helper, List<String> chat, String expected) {
		helper.assertTrue(chat.contains(expected), "the feedback should contain '" + expected + "', got " + chat);
	}

	/**
	 * (E3d) The ready ping: an UNREVIVED break cooldown expiring naturally while
	 * the projector is loaded flips {@code readyAnnounced} exactly once — false
	 * for the whole cooldown, true EXACTLY on the {@code cooldownUntil} tick
	 * (the block entity ticks before the gametest callbacks of the same server
	 * tick, so the wait-until below catches the transition tick itself), with
	 * exactly ONE bell sound packet on the wire that tick and none afterward:
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

		// A capture player inside the ping's ~16-block audible radius (the ping
		// is a volume-0.8 playSound at the projector), parked outside the bubble
		// footprint so the broken-then-idle projector never interacts with it.
		MockPlayers.CapturingMockPlayer capture = MockPlayers.createCapturingMockPlayer(helper, GameType.CREATIVE);
		Vec3 center = Vec3.atCenterOf(helper.absolutePos(PROJECTOR_POS));
		capture.player().snapTo(center.x + 6.0, center.y, center.z);

		be.applyShieldDamage(1000.0F);
		helper.assertTrue(!state.active, "the shield should have broken");
		helper.assertTrue(!state.readyAnnounced, "a break must arm the ready ping");

		// Shorten the tier-0 15-minute cooldown to something tickable.
		long expiresAt = helper.getLevel().getGameTime() + 30L;
		state.cooldownUntil = expiresAt;

		helper.startSequence()
				.thenExecuteAfter(10, () -> {
					helper.assertTrue(!state.readyAnnounced,
							"the flag must stay false while the cooldown is still running");
					// Drop the break/nova packet noise; the projector is inactive
					// from here on (no census, no alarm), so every BELL_RESONATE
					// arriving after this drain must be the one-shot ready ping.
					drainSoundPackets(capture, SoundEvents.BELL_RESONATE);
				})
				.thenWaitUntil(() -> helper.assertTrue(state.readyAnnounced,
						"waiting for the natural cooldown expiry"))
				.thenExecute(() -> {
					// The wait-until above triggers on the first tick the flag reads
					// true — which must be exactly the cooldownUntil tick.
					long flippedAt = helper.getLevel().getGameTime();
					helper.assertTrue(flippedAt == expiresAt,
							"the ready ping must fire exactly on the expiry tick " + expiresAt + ", fired at " + flippedAt);
					int bells = drainSoundPackets(capture, SoundEvents.BELL_RESONATE);
					helper.assertTrue(bells == 1,
							"exactly ONE bell sound packet must be sent on the expiry tick, got " + bells);
				})
				.thenExecuteAfter(20, () -> {
					helper.assertTrue(state.readyAnnounced && !state.active,
							"extra ticks after the ping change nothing (no double-fire, no self-activation)");
					int bells = drainSoundPackets(capture, SoundEvents.BELL_RESONATE);
					helper.assertTrue(bells == 0, "no further bell may be sent after the one-shot ping, got " + bells);
				})
				.thenSucceed();
	}

	/**
	 * (E3c / fix 9h) The audible transition cues fire on REAL transitions only,
	 * asserted on the wire: exactly ONE {@code BEACON_ACTIVATE} sound packet on
	 * the inactive-to-active flip, silence for a no-op re-activation of an
	 * already-active shield, exactly ONE {@code BEACON_DEACTIVATE} on the
	 * deliberate power-down, and silence again for a no-op deactivation. All
	 * transitions run synchronously, so each phase drains the wire ONCE and
	 * counts both cue sounds out of that single window (a drain is destructive
	 * across all packet types).
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 100, padding = 16)
	public void beaconTransitionSounds(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);

		MockPlayers.CapturingMockPlayer capture = MockPlayers.createCapturingMockPlayer(helper, GameType.CREATIVE);
		Vec3 center = Vec3.atCenterOf(helper.absolutePos(PROJECTOR_POS));
		// Inside the volume-1.0 16-block audible radius, outside the bubble.
		capture.player().snapTo(center.x + 6.0, center.y, center.z);
		capture.drainPackets();

		helper.assertTrue(be.tryActivate(), "shield should activate");
		List<Object> onActivate = capture.drainPackets();
		helper.assertTrue(countSoundPackets(onActivate, SoundEvents.BEACON_DEACTIVATE) == 0,
				"an activation must not send a deactivate cue");
		int activates = countSoundPackets(onActivate, SoundEvents.BEACON_ACTIVATE);
		helper.assertTrue(activates == 1,
				"exactly ONE activate cue must be sent on the real transition, got " + activates);

		// No-op re-activations of an already-active shield: still "successful",
		// but never audible (and setActive(true) routes through tryActivate).
		helper.assertTrue(be.tryActivate(), "re-activating an active shield reports success");
		be.setActive(true);
		List<Object> onNoopActivate = capture.drainPackets();
		helper.assertTrue(countSoundPackets(onNoopActivate, SoundEvents.BEACON_ACTIVATE) == 0
						&& countSoundPackets(onNoopActivate, SoundEvents.BEACON_DEACTIVATE) == 0,
				"a no-op activate must stay silent");

		be.setActive(false);
		List<Object> onDeactivate = capture.drainPackets();
		helper.assertTrue(countSoundPackets(onDeactivate, SoundEvents.BEACON_ACTIVATE) == 0,
				"a deactivation must not send an activate cue");
		int deactivates = countSoundPackets(onDeactivate, SoundEvents.BEACON_DEACTIVATE);
		helper.assertTrue(deactivates == 1,
				"exactly ONE deactivate cue must be sent on the real transition, got " + deactivates);

		be.setActive(false);
		List<Object> onNoopDeactivate = capture.drainPackets();
		helper.assertTrue(countSoundPackets(onNoopDeactivate, SoundEvents.BEACON_DEACTIVATE) == 0
						&& countSoundPackets(onNoopDeactivate, SoundEvents.BEACON_ACTIVATE) == 0,
				"a no-op deactivate must stay silent");
		helper.succeed();
	}

	/**
	 * (E4) {@code /bubbleshield log}: the localized empty notice on a fresh
	 * projector (result 1), one line per threat-log entry afterwards (result 2) —
	 * with the REAL chat packets asserted verbatim (fix 9b): each line carries the
	 * exact "Ns ago" age, the attacker name and the %.1f damage figure, oldest
	 * entry first — and the read-only guarantee: the ownerless projector is never
	 * claimed.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, padding = 16)
	public void commandLogPrintsThreatLog(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		MockPlayers.CapturingMockPlayer mock = MockPlayers.createCapturingMockPlayer(helper, GameType.CREATIVE);
		ServerPlayer player = mock.player();
		try {
			drainChatLines(mock);
			int empty = dispatcher(helper).execute("bubbleshield log", player.createCommandSourceStack());
			helper.assertTrue(empty == 1, "an empty log should print the localized notice (result 1), got " + empty);
			assertChatLine(helper, drainChatLines(mock), "No recorded attacks");

			long gameTime = helper.getLevel().getGameTime();
			state.recordThreat("Griefer", 4.5F, gameTime - 100L);
			state.recordThreat("Raider", 2.25F, gameTime - 40L);
			int lines = dispatcher(helper).execute("bubbleshield log", player.createCommandSourceStack());
			helper.assertTrue(lines == 2, "two recorded threats should print two lines, got " + lines);

			// The execution ran synchronously on the tick that recorded the
			// entries, so the ages and the Locale.ROOT %.1f damage figures are
			// exact, oldest entry first. The ages are recomputed from the
			// entries actually stored (recordThreat clamps game times at 0, so
			// on a young level clock "-100 ticks" may land closer than 5 s).
			List<String> chat = drainChatLines(mock);
			helper.assertTrue(chat.size() == 2, "exactly two log lines should reach the player, got " + chat);
			List<ShieldState.ThreatLogEntry> entries = state.threatLog();
			String oldest = Math.max(0L, (gameTime - entries.get(0).gameTime()) / 20L)
					+ "s ago: Griefer (-" + String.format(Locale.ROOT, "%.1f", 4.5F) + ")";
			String newest = Math.max(0L, (gameTime - entries.get(1).gameTime()) / 20L)
					+ "s ago: Raider (-" + String.format(Locale.ROOT, "%.1f", 2.25F) + ")";
			helper.assertTrue(oldest.equals(chat.get(0)),
					"the first line must be the oldest entry '" + oldest + "', got '" + chat.get(0) + "'");
			helper.assertTrue(newest.equals(chat.get(1)),
					"the second line must be the newest entry '" + newest + "', got '" + chat.get(1) + "'");

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
	 * feedback carries the full stat sheet — asserted as EXACT lines (fix 9b),
	 * built from the same live state/{@code ContainerData} sources the command
	 * reads on the same tick: HP cur/max, tier + combined DR, regen, drain with
	 * the time-to-empty projection, cooldown-or-ready, strength% and threats.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 100, padding = 16)
	public void commandStatusExecutesAndMentionsHp(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		be.getCoreContainer().setItem(0, new ItemStack(ModItems.RESONANT_CORE));
		helper.assertTrue(be.tryActivate(), "shield should activate");

		MockPlayers.CapturingMockPlayer mock = MockPlayers.createCapturingMockPlayer(helper, GameType.CREATIVE);
		ServerPlayer player = mock.player();
		// Let the first-tick recompute land so the asserted figures are the
		// settled tier-1 values (250/250 HP), not the placement defaults.
		helper.runAfterDelay(2, () -> {
			ShieldState state = be.getShieldState();
			var data = be.getMenuData();
			try {
				drainChatLines(mock);

				// Snapshot the live figures on THIS tick; the command reads the
				// same sources synchronously below, so nothing can drift between.
				String expectHp = "HP: " + Math.round(state.health) + "/" + Math.round(state.maxHealth);
				String expectTier = "Tier: " + be.tier() + " (DR "
						+ Math.round(ShieldLogic.combinedDr(be.tier(), be.platingDr()) * 100.0F) + "%)";
				String expectRegen = "Regen: "
						+ String.format(Locale.ROOT, "%.1f", data.get(BubbleShieldMenu.DATA_REGEN_PER_MIN_X10) / 10.0F) + "/min";
				int drainX10 = data.get(BubbleShieldMenu.DATA_DRAIN_PER_MIN_X10);
				String expectDrain = "Drain: " + String.format(Locale.ROOT, "%.1f", drainX10 / 10.0F) + "/min (empty in "
						+ ShieldLogic.formatMinutesSeconds(state.fuelSeconds * 600L / drainX10) + ")";
				String expectStrength = "Strength: " + be.strengthPercent() + "%";
				String expectThreats = "Threats: " + data.get(BubbleShieldMenu.DATA_THREAT_COUNT);
				// Pin the concrete tier-1 diameter-8 figures too, so the live
				// snapshot cannot silently degrade into asserting garbage.
				helper.assertTrue(expectHp.equals("HP: 250/250"), "the settled tier-1 HP line should be 250/250, got '" + expectHp + "'");
				helper.assertTrue(expectTier.equals("Tier: 1 (DR 25%)"), "the tier line should read tier 1 / DR 25%, got '" + expectTier + "'");
				helper.assertTrue(drainX10 == 600, "the diameter-8 baseline drain slot should read 600, got " + drainX10);

				int result = dispatcher(helper).execute("bubbleshield status", player.createCommandSourceStack());
				helper.assertTrue(result == 1, "status should succeed (result 1), got " + result);

				List<String> chat = drainChatLines(mock);
				assertChatLine(helper, chat, expectHp);
				assertChatLine(helper, chat, expectTier);
				assertChatLine(helper, chat, expectRegen);
				assertChatLine(helper, chat, expectDrain);
				assertChatLine(helper, chat, "Cooldown: ready");
				assertChatLine(helper, chat, expectStrength);
				assertChatLine(helper, chat, expectThreats);

				helper.assertTrue(state.ownerUuid == null,
						"status is read-only: the ownerless projector must never be claimed");
			} catch (CommandSyntaxException e) {
				helper.assertTrue(false, "status should parse and execute: " + e.getMessage());
			} finally {
				MockPlayers.removeMockPlayer(helper, player);
			}

			helper.succeed();
		});
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
	 * (C8) The new loot injections: End City treasure surfaces an Aegis Core and
	 * Ancient City chests a 1-2 stack of Patch Kits, while an untouched table
	 * (simple dungeon) yields neither.
	 *
	 * <p>Fix 9j determinism bound: the loot draws use the level's live
	 * {@code RandomSource} (the {@code LootParams} chest context offers no seed
	 * hook), so instead of seeding, the draw counts are sized for a false-failure
	 * probability below 1e-12 per assertion: the Aegis pool is 1-in-20 per chest
	 * ({@code CoreLootInjector}), so 560 all-miss draws are a 0.95^560 &asymp;
	 * 3.3e-13 fluke; the Patch Kit pool is 1-in-8, so 250 misses are 0.875^250
	 * &asymp; 3.2e-15. The untouched-table checks assert an exact 0 and carry no
	 * probabilistic risk.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void lootInjectionSeedsAegisCoresAndPatchKits(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		Vec3 origin = Vec3.atCenterOf(helper.absolutePos(PROJECTOR_POS));

		LootTable endCity = level.getServer().reloadableRegistries().getLootTable(BuiltInLootTables.END_CITY_TREASURE);
		helper.assertTrue(countItemDraws(helper, level, endCity, origin, 560, ModItems.AEGIS_CORE) >= 1,
				"end city treasure should yield at least one aegis core in 560 draws");

		LootTable ancientCity = level.getServer().reloadableRegistries().getLootTable(BuiltInLootTables.ANCIENT_CITY);
		helper.assertTrue(countItemDraws(helper, level, ancientCity, origin, 250, ModItems.PATCH_KIT) >= 1,
				"ancient city chests should yield at least one patch kit stack in 250 draws");

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

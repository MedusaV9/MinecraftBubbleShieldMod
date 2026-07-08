package com.bubbleshield.gametest;

import java.util.UUID;

import com.bubbleshield.BubbleShield;
import com.bubbleshield.advancements.ModCriteria;
import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.net.ServerNet;
import com.bubbleshield.registry.ModBlocks;
import com.bubbleshield.shield.ShieldLinking;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/**
 * Coverage for the mod's advancements: the datapack JSONs load, and each custom
 * criterion trigger (shield_activated with diameter bounds, shield_broken,
 * player_whitelisted, shield_named, shield_recolored, shields_linked) awards its
 * advancement through the real call sites (or the exact trigger/fire method those
 * call sites use where the call site is a network receiver).
 */
public class AdvancementGameTests {
	/**
	 * Dedicated (but otherwise vanilla-default) environment for the V9 tests below,
	 * {@code data/bubbleshield/test_environment/advancement.json}. The shared default
	 * batch runs at the runner's 50-tests-per-batch limit, so these tests get their
	 * own batch instead of splitting/reshuffling the pre-existing suite (see
	 * ColorGameTests.ISOLATED_ENVIRONMENT for the full story; mocks are now uniquely
	 * named via {@link MockPlayers}). advancementMaximalist also lives here — see its
	 * own javadoc for why its diameter-200 shield must not share a batch with tests
	 * that park mock players across ticks.
	 */
	private static final String ISOLATED_ENVIRONMENT = "bubbleshield:advancement";
	private static final BlockPos PROJECTOR_POS = new BlockPos(4, 2, 4);
	/** 2 blocks east of {@link #PROJECTOR_POS}: overlaps it at radius 8 (2 &lt; 8 + 8). */
	private static final BlockPos LINKED_PARTNER_POS = new BlockPos(6, 2, 4);
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

	@GameTest(padding = 16)
	public void advancementJsonsLoad(GameTestHelper helper) {
		// tree().get(...) is only non-null when the datapack JSON parsed and linked.
		for (String path : new String[] {
				"root", "shield_raised", "maximalist", "bubble_burst", "friend_zone",
				"christened", "full_spectrum", "linked_up"}) {
			advancement(helper, path);
		}

		helper.succeed();
	}

	@GameTest(padding = 16)
	public void advancementShieldRaised(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);

		AdvancementHolder shieldRaised = advancement(helper, "shield_raised");
		ServerPlayer player = MockPlayers.createUniqueMockPlayer(helper);
		try {
			helper.assertTrue(!isDone(player, shieldRaised), "a fresh player should not have shield_raised yet");
			helper.assertTrue(be.tryActivate(player), "activation with fuel should succeed");
			helper.assertTrue(isDone(player, shieldRaised), "activating a shield should award shield_raised");
		} finally {
			MockPlayers.removeMockPlayer(helper, player);
		}

		// A no-op re-activation of the already-active shield is not an activation edge
		// and must not award the criterion to a second player.
		ServerPlayer second = MockPlayers.createUniqueMockPlayer(helper);
		try {
			helper.assertTrue(be.getShieldState().active, "the shield should still be active");
			helper.assertTrue(be.tryActivate(second), "re-activating an active shield should report success");
			helper.assertTrue(!isDone(second, shieldRaised), "a no-op re-activation should NOT award shield_raised");
		} finally {
			MockPlayers.removeMockPlayer(helper, second);
		}

		helper.succeed();
	}

	/**
	 * Runs in the isolated advancement batch, NOT the shared default one: the
	 * diameter-200 activation's {@code expelBlockedPlayers} sweep reaches ~100 blocks
	 * — several structures over in the shared test grid (structures sit ~45 blocks
	 * apart) — and would teleport away mock players that concurrent tests (boss bar
	 * membership, behavior auras) park inside their own bubbles across ticks. Every
	 * test in the advancement batch creates and removes its mocks within a single
	 * synchronous tick, so the sweep can never hit a foreign player there.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, padding = 16)
	public void advancementMaximalist(GameTestHelper helper) {
		// A diameter-16 activation must NOT complete maximalist (fresh player)...
		BubbleShieldBlockEntity be = placeProjector(helper, 8.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);

		AdvancementHolder maximalist = advancement(helper, "maximalist");
		AdvancementHolder shieldRaised = advancement(helper, "shield_raised");
		ServerPlayer smallPlayer = MockPlayers.createUniqueMockPlayer(helper);
		try {
			helper.assertTrue(be.tryActivate(smallPlayer), "diameter-16 activation should succeed");
			helper.assertTrue(isDone(smallPlayer, shieldRaised), "a diameter-16 activation should still award shield_raised");
			helper.assertTrue(!isDone(smallPlayer, maximalist), "a diameter-16 activation should NOT award maximalist");
		} finally {
			MockPlayers.removeMockPlayer(helper, smallPlayer);
		}

		// ...while a diameter-200 activation must complete it.
		be.setActive(false);
		be.getShieldState().targetRadius = 100.0F;
		ServerPlayer maxPlayer = MockPlayers.createUniqueMockPlayer(helper);
		try {
			helper.assertTrue(be.tryActivate(maxPlayer), "diameter-200 activation should succeed");
			helper.assertTrue(isDone(maxPlayer, maximalist), "a diameter-200 activation should award maximalist");
		} finally {
			MockPlayers.removeMockPlayer(helper, maxPlayer);
			// Don't leave a 100-block expel sweep ticking after the test: batch chunks
			// stay loaded briefly past the batch end and could reach later structures.
			be.setActive(false);
		}

		helper.succeed();
	}

	@GameTest(padding = 16)
	public void advancementFriendZone(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);

		AdvancementHolder friendZone = advancement(helper, "friend_zone");
		ServerPlayer player = MockPlayers.createUniqueMockPlayer(helper);
		try {
			helper.assertTrue(!isDone(player, friendZone), "a fresh player should not have friend_zone yet");

			// Whitelisting yourself is not befriending anyone.
			be.whitelistAdd(helper.getLevel().getServer(), player.getGameProfile().name(), player);
			helper.assertTrue(!isDone(player, friendZone), "whitelisting yourself should NOT award friend_zone");

			// Same actor-crediting overload the WhitelistModifyC2S add-branch uses.
			be.whitelistAdd(helper.getLevel().getServer(), "SomeFriend", player);
			helper.assertTrue(isDone(player, friendZone), "whitelisting a player should award friend_zone");
		} finally {
			MockPlayers.removeMockPlayer(helper, player);
		}

		// A duplicate add (different casing) is a no-op and must not award the criterion.
		ServerPlayer second = MockPlayers.createUniqueMockPlayer(helper);
		try {
			be.whitelistAdd(helper.getLevel().getServer(), "SOMEFRIEND", second);
			helper.assertTrue(!isDone(second, friendZone), "re-adding an existing name should NOT award friend_zone");
		} finally {
			MockPlayers.removeMockPlayer(helper, second);
		}

		helper.succeed();
	}

	@GameTest(padding = 16)
	public void advancementBubbleBurst(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);

		AdvancementHolder bubbleBurst = advancement(helper, "bubble_burst");
		ServerPlayer owner = MockPlayers.createUniqueMockPlayer(helper);
		try {
			be.getShieldState().ownerUuid = owner.getUUID();
			helper.assertTrue(be.tryActivate(owner), "shield should activate");
			helper.assertTrue(!isDone(owner, bubbleBurst), "the owner should not have bubble_burst before the break");

			be.applyShieldDamage(1000.0F);
			helper.assertTrue(!be.getShieldState().active, "the shield should have broken");
			helper.assertTrue(isDone(owner, bubbleBurst), "breaking the shield should award bubble_burst to the online owner");
		} finally {
			MockPlayers.removeMockPlayer(helper, owner);
		}

		helper.succeed();
	}

	@GameTest(environment = ISOLATED_ENVIRONMENT, padding = 16)
	public void advancementChristened(GameTestHelper helper) {
		AdvancementHolder christened = advancement(helper, "christened");
		ServerPlayer player = MockPlayers.createUniqueMockPlayer(helper);
		try {
			helper.assertTrue(!isDone(player, christened), "a fresh player should not have christened yet");

			// The exact trigger call the SetNameC2S receiver makes after sanitizing
			// a NON-empty custom name (clearing the name never fires it).
			ModCriteria.SHIELD_NAMED.trigger(player);
			helper.assertTrue(isDone(player, christened), "naming a shield should award christened");
		} finally {
			MockPlayers.removeMockPlayer(helper, player);
		}

		helper.succeed();
	}

	@GameTest(environment = ISOLATED_ENVIRONMENT, padding = 16)
	public void advancementFullSpectrum(GameTestHelper helper) {
		AdvancementHolder fullSpectrum = advancement(helper, "full_spectrum");
		ServerPlayer player = MockPlayers.createUniqueMockPlayer(helper);
		try {
			helper.assertTrue(!isDone(player, fullSpectrum), "a fresh player should not have full_spectrum yet");

			// The exact trigger call the SetColorC2S receiver makes for a real recolor
			// (argb != -1; a reset to the authored palette never fires it).
			ModCriteria.SHIELD_RECOLORED.trigger(player);
			helper.assertTrue(isDone(player, fullSpectrum), "recoloring a shield should award full_spectrum");
		} finally {
			MockPlayers.removeMockPlayer(helper, player);
		}

		helper.succeed();
	}

	@GameTest(environment = ISOLATED_ENVIRONMENT, padding = 16)
	public void advancementLinkedUp(GameTestHelper helper) {
		AdvancementHolder linkedUp = advancement(helper, "linked_up");
		ServerPlayer owner = MockPlayers.createUniqueMockPlayer(helper);
		try {
			// Two overlapping same-owner active shields: exactly the findLinked-size>1
			// condition that gates the damage-split fire site in interceptProjectiles.
			BubbleShieldBlockEntity shieldA = placeProjector(helper, 8.0F);
			shieldA.addFuelSeconds(PLENTY_OF_FUEL);
			helper.setBlock(LINKED_PARTNER_POS, ModBlocks.BUBBLE_SHIELD_PROJECTOR);
			BubbleShieldBlockEntity shieldB = helper.getBlockEntity(LINKED_PARTNER_POS, BubbleShieldBlockEntity.class);
			shieldB.getShieldState().targetRadius = 8.0F;
			shieldB.addFuelSeconds(PLENTY_OF_FUEL);
			shieldA.getShieldState().ownerUuid = owner.getUUID();
			shieldB.getShieldState().ownerUuid = owner.getUUID();
			helper.assertTrue(shieldA.tryActivate(), "shield A should activate");
			helper.assertTrue(shieldB.tryActivate(), "shield B should activate");
			helper.assertTrue(
					ShieldLinking.findLinked(shieldA, ServerNet.loadedShields(helper.getLevel())).size() > 1,
					"the overlapping same-owner shields should be resonance-linked");

			// Same owner-resolution rule as fireShieldBroken: no owner or an offline
			// owner resolves to nobody and must award nothing (and not throw).
			ModCriteria.fireShieldsLinked(helper.getLevel(), null);
			ModCriteria.fireShieldsLinked(helper.getLevel(), UUID.randomUUID());
			helper.assertTrue(!isDone(owner, linkedUp), "an unresolved owner must not award linked_up");

			// The exact fire-site call the damage split makes when findLinked returns > 1.
			ModCriteria.fireShieldsLinked(helper.getLevel(), shieldA.getShieldState().ownerUuid);
			helper.assertTrue(isDone(owner, linkedUp), "a damage split across linked shields should award linked_up to the online owner");
		} finally {
			MockPlayers.removeMockPlayer(helper, owner);
		}

		helper.succeed();
	}
}

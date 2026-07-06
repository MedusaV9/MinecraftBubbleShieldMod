package com.bubbleshield.gametest;

import com.bubbleshield.BubbleShield;
import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.registry.ModBlocks;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/**
 * Coverage for the mod's advancements: the datapack JSONs load, and each custom
 * criterion trigger (shield_activated with diameter bounds, shield_broken,
 * player_whitelisted) awards its advancement through the real call sites.
 */
public class AdvancementGameTests {
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

	@GameTest(padding = 16)
	public void advancementJsonsLoad(GameTestHelper helper) {
		// tree().get(...) is only non-null when the datapack JSON parsed and linked.
		for (String path : new String[] {"root", "shield_raised", "maximalist", "bubble_burst", "friend_zone"}) {
			advancement(helper, path);
		}

		helper.succeed();
	}

	@GameTest(padding = 16)
	public void advancementShieldRaised(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);

		AdvancementHolder shieldRaised = advancement(helper, "shield_raised");
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		try {
			helper.assertTrue(!isDone(player, shieldRaised), "a fresh player should not have shield_raised yet");
			helper.assertTrue(be.tryActivate(player), "activation with fuel should succeed");
			helper.assertTrue(isDone(player, shieldRaised), "activating a shield should award shield_raised");
		} finally {
			helper.getLevel().getServer().getPlayerList().remove(player);
		}

		helper.succeed();
	}

	@GameTest(padding = 16)
	public void advancementMaximalist(GameTestHelper helper) {
		// A diameter-16 activation must NOT complete maximalist (fresh player)...
		BubbleShieldBlockEntity be = placeProjector(helper, 8.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);

		AdvancementHolder maximalist = advancement(helper, "maximalist");
		AdvancementHolder shieldRaised = advancement(helper, "shield_raised");
		ServerPlayer smallPlayer = helper.makeMockServerPlayerInLevel();
		try {
			helper.assertTrue(be.tryActivate(smallPlayer), "diameter-16 activation should succeed");
			helper.assertTrue(isDone(smallPlayer, shieldRaised), "a diameter-16 activation should still award shield_raised");
			helper.assertTrue(!isDone(smallPlayer, maximalist), "a diameter-16 activation should NOT award maximalist");
		} finally {
			helper.getLevel().getServer().getPlayerList().remove(smallPlayer);
		}

		// ...while a diameter-200 activation must complete it.
		be.setActive(false);
		be.getShieldState().targetRadius = 100.0F;
		ServerPlayer maxPlayer = helper.makeMockServerPlayerInLevel();
		try {
			helper.assertTrue(be.tryActivate(maxPlayer), "diameter-200 activation should succeed");
			helper.assertTrue(isDone(maxPlayer, maximalist), "a diameter-200 activation should award maximalist");
		} finally {
			helper.getLevel().getServer().getPlayerList().remove(maxPlayer);
		}

		helper.succeed();
	}

	@GameTest(padding = 16)
	public void advancementFriendZone(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);

		AdvancementHolder friendZone = advancement(helper, "friend_zone");
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		try {
			helper.assertTrue(!isDone(player, friendZone), "a fresh player should not have friend_zone yet");
			// Same actor-crediting overload the WhitelistModifyC2S add-branch uses.
			be.whitelistAdd(helper.getLevel().getServer(), "SomeFriend", player);
			helper.assertTrue(isDone(player, friendZone), "whitelisting a player should award friend_zone");
		} finally {
			helper.getLevel().getServer().getPlayerList().remove(player);
		}

		helper.succeed();
	}

	@GameTest(padding = 16)
	public void advancementBubbleBurst(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);

		AdvancementHolder bubbleBurst = advancement(helper, "bubble_burst");
		ServerPlayer owner = helper.makeMockServerPlayerInLevel();
		try {
			be.getShieldState().ownerUuid = owner.getUUID();
			helper.assertTrue(be.tryActivate(owner), "shield should activate");
			helper.assertTrue(!isDone(owner, bubbleBurst), "the owner should not have bubble_burst before the break");

			be.applyShieldDamage(1000.0F);
			helper.assertTrue(!be.getShieldState().active, "the shield should have broken");
			helper.assertTrue(isDone(owner, bubbleBurst), "breaking the shield should award bubble_burst to the online owner");
		} finally {
			helper.getLevel().getServer().getPlayerList().remove(owner);
		}

		helper.succeed();
	}
}

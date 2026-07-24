package com.bubbleshield.gametest;

import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.net.ServerNet;
import com.bubbleshield.registry.ModBlocks;
import com.bubbleshield.shield.ShieldState;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;

/**
 * Coverage for milestone V3: the in-shield boss bar (membership, progress) and the
 * owner-set custom shield name (persistence, sanitization).
 */
public class BossBarNameGameTests {
	private static final BlockPos PROJECTOR_POS = new BlockPos(4, 2, 4);
	private static final int PLENTY_OF_FUEL = 600;

	private static BubbleShieldBlockEntity placeProjector(GameTestHelper helper, float targetRadius) {
		helper.setBlock(PROJECTOR_POS, ModBlocks.BUBBLE_SHIELD_PROJECTOR);
		BubbleShieldBlockEntity be = helper.getBlockEntity(PROJECTOR_POS, BubbleShieldBlockEntity.class);
		be.getShieldState().targetRadius = targetRadius;
		return be;
	}

	@GameTest(maxTicks = 200, padding = 16)
	public void bossBarTracksPlayersInside(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);

		// An in-level ServerPlayer: boss bar membership requires a real connection
		// (addPlayer sends packets) and the level's player list. Whitelist it (by its
		// unique per-call mock name) so the barrier does not expel it, then park it
		// inside the radius-4 bubble. The player is removed from the PlayerList on
		// every exit path so later crowd-scale tests see no phantoms.
		ServerPlayer player = MockPlayers.createUniqueMockPlayer(helper);
		Runnable cleanup = () -> MockPlayers.removeMockPlayer(helper, player);
		be.whitelistAdd(helper.getLevel().getServer(), player.getGameProfile().name());
		helper.assertTrue(be.tryActivate(), "shield should activate");

		Vec3 center = Vec3.atCenterOf(helper.absolutePos(PROJECTOR_POS));
		player.snapTo(center.x + 1.5, center.y, center.z);

		helper.runAfterDelay(5, () -> {
			ServerBossEvent event = be.getBossEvent();
			boolean tracked = event != null && event.getPlayers().contains(player);
			if (!tracked) {
				cleanup.run();
			}
			helper.assertTrue(tracked, "a player inside the active shield should be on the boss bar");

			be.setActive(false);
			helper.runAfterDelay(5, () -> {
				boolean stillTracked = event.getPlayers().contains(player);
				cleanup.run();
				helper.assertTrue(!stillTracked, "deactivating the shield should empty the boss bar");
				helper.succeed();
			});
		});
	}

	@GameTest(maxTicks = 200, padding = 16)
	public void bossBarProgressTracksDamage(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.runAfterDelay(2, () -> {
			ServerBossEvent event = be.getBossEvent();
			helper.assertTrue(event != null, "an active shield should lazily create its boss event");
			helper.assertTrue(
					Math.abs(event.getProgress() - 1.0F) < 0.01F,
					"a full-health shield's boss bar should be full, got " + event.getProgress());

			// The hit opens the 200-tick combat gate, and tier 0 shields do not
			// regenerate in combat, so 95/125 (30 raw, no tier-0 DR) stays put
			// between ticks.
			be.applyShieldDamage(30.0F);
			be.setCustomName("Home Base");
			helper.runAfterDelay(2, () -> {
				helper.assertTrue(
						Math.abs(event.getProgress() - 0.76F) < 0.02F,
						"the boss bar should track applyShieldDamage (95/125), got " + event.getProgress());
				// E7: the name carries the health readout quantized to 5% steps —
				// 95/125 = 76% quantizes to 75%.
				helper.assertTrue(
						"Home Base \u00b7 75%".equals(event.getName().getString()),
						"the boss bar should show the custom name + quantized percent, got " + event.getName().getString());
				helper.succeed();
			});
		});
	}

	@GameTest(padding = 16)
	public void nameNbtRoundTrip(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.getShieldState().customName = "Home Base";

		var registries = helper.getLevel().registryAccess();
		CompoundTag tag = be.saveCustomOnly(registries);
		helper.assertTrue(tag.contains("custom_name"), "saved block entity NBT should include custom_name");

		BubbleShieldBlockEntity loaded = new BubbleShieldBlockEntity(helper.absolutePos(PROJECTOR_POS), be.getBlockState());
		loaded.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
		helper.assertTrue(
				"Home Base".equals(loaded.getShieldState().customName),
				"the custom name should round-trip through NBT, got '" + loaded.getShieldState().customName + "'");

		// Legacy NBT without the key must load as "no custom name".
		tag.remove("custom_name");
		BubbleShieldBlockEntity legacy = new BubbleShieldBlockEntity(helper.absolutePos(PROJECTOR_POS), be.getBlockState());
		legacy.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
		helper.assertTrue(
				legacy.getShieldState().customName.isEmpty(),
				"NBT without custom_name should load an empty custom name");
		helper.succeed();
	}

	@GameTest(padding = 16)
	public void nameSanitization(GameTestHelper helper) {
		// A 33-char request is capped at the 32-char limit, never stored verbatim.
		String tooLong = "A".repeat(33);
		String capped = ServerNet.sanitizeShieldName(tooLong);
		helper.assertTrue(
				capped.length() == ServerNet.MAX_SHIELD_NAME_LENGTH,
				"a 33-char name should be capped at 32, got length " + capped.length());

		helper.assertTrue(
				"Home Base".equals(ServerNet.sanitizeShieldName("  Home Base  ")),
				"surrounding whitespace should be trimmed");
		helper.assertTrue(
				"BadkName".equals(ServerNet.sanitizeShieldName("Bad\u00A7k\nNa\tme\u007F")),
				"control characters and the section sign should be stripped");
		helper.assertTrue(
				ServerNet.sanitizeShieldName("   ").isEmpty(),
				"a whitespace-only name should sanitize to empty (clear)");

		// End to end through the block entity: set, then clear with an empty string.
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		be.setCustomName(ServerNet.sanitizeShieldName(tooLong));
		helper.assertTrue(
				state.customName.length() == ServerNet.MAX_SHIELD_NAME_LENGTH,
				"the stored custom name should honour the 32-char cap");

		be.setCustomName(ServerNet.sanitizeShieldName(""));
		helper.assertTrue(state.customName.isEmpty(), "an empty request should clear the custom name");
		helper.succeed();
	}
}

package com.bubbleshield.gametest;

import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.net.ServerNet;
import com.bubbleshield.registry.ModBlocks;
import com.bubbleshield.registry.ModItems;
import com.bubbleshield.shield.ShieldState;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.BossEvent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;

/**
 * Coverage for milestone V3: the in-shield boss bar (membership, progress,
 * last-stand overlay round trip) and the owner-set custom shield name
 * (persistence, sanitization).
 */
public class BossBarNameGameTests {
	/**
	 * A dedicated (but otherwise vanilla-default) test environment,
	 * {@code data/bubbleshield/test_environment/bossbar.json}, following the
	 * {@link ColorGameTests} pattern. The vanilla runner batches tests by
	 * environment (50 per batch, ticked in parallel); without this, the default
	 * environment would hold 51 tests and spill into a second implicit batch,
	 * reshuffling which tests overlap in time. Moving this class out keeps the
	 * default batch at 47.
	 */
	private static final String ISOLATED_ENVIRONMENT = "bubbleshield:bossbar";
	private static final BlockPos PROJECTOR_POS = new BlockPos(4, 2, 4);
	private static final int PLENTY_OF_FUEL = 600;

	private static BubbleShieldBlockEntity placeProjector(GameTestHelper helper, float targetRadius) {
		helper.setBlock(PROJECTOR_POS, ModBlocks.BUBBLE_SHIELD_PROJECTOR);
		BubbleShieldBlockEntity be = helper.getBlockEntity(PROJECTOR_POS, BubbleShieldBlockEntity.class);
		be.getShieldState().targetRadius = targetRadius;
		return be;
	}

	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
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

	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
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

	/**
	 * (E3b / fix 9g) The overlay is a live SHAPE cue, not a latch: dropping below
	 * the 25% last-stand threshold switches PROGRESS to NOTCHED_10, and a REAL
	 * patch-kit repair (through {@code applyPatchKit}) back above the threshold
	 * reverts it to PROGRESS on the next tick.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void bossBarOverlayRevertsAfterRepair(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		be.addFuelSeconds(PLENTY_OF_FUEL);
		ServerPlayer owner = MockPlayers.createUniqueMockPlayer(helper);
		be.setOwner(owner);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.startSequence()
				.thenExecuteAfter(2, () -> {
					helper.assertTrue(state.maxHealth == 125.0F, "tier 0 at diameter 8 should have maxHealth 125, got " + state.maxHealth);
					ServerBossEvent event = be.getBossEvent();
					helper.assertTrue(event != null, "an active shield should have created its boss event");
					helper.assertTrue(event.getOverlay() == BossEvent.BossBarOverlay.PROGRESS,
							"a full shield uses the PROGRESS overlay");

					// 20/125 = 16%: into last stand (tier 0, no DR on the way down).
					be.applyShieldDamage(105.0F);
					helper.assertTrue(state.health == 20.0F, "damaged health should be 20, got " + state.health);
				})
				.thenExecuteAfter(2, () -> {
					helper.assertTrue(be.getBossEvent().getOverlay() == BossEvent.BossBarOverlay.NOTCHED_10,
							"below the 25% threshold the overlay must switch to NOTCHED_10");

					// The REAL repair path: one kit heals min(150, missing 105) -> 125/125.
					helper.assertTrue(be.applyPatchKit(owner, new ItemStack(ModItems.PATCH_KIT)),
							"the owner's patch kit should apply to the active shield");
					helper.assertTrue(state.health == 125.0F,
							"the kit should heal back to full (capped at max), got " + state.health);
				})
				.thenExecuteAfter(2, () -> helper.assertTrue(
						be.getBossEvent().getOverlay() == BossEvent.BossBarOverlay.PROGRESS,
						"back above 25% the overlay must revert to PROGRESS"))
				.thenSucceed();
	}

	@GameTest(environment = ISOLATED_ENVIRONMENT, padding = 16)
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

	@GameTest(environment = ISOLATED_ENVIRONMENT, padding = 16)
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

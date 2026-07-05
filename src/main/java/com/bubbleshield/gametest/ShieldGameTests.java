package com.bubbleshield.gametest;

import java.util.UUID;

import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.effect.EffectRegistry;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.menu.BubbleShieldMenu;
import com.bubbleshield.registry.ModBlocks;
import com.bubbleshield.shield.ShieldLogic;
import com.bubbleshield.shield.ShieldState;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

public class ShieldGameTests {
	private static final BlockPos PROJECTOR_POS = new BlockPos(4, 2, 4);
	private static final int PLENTY_OF_FUEL = 600;

	private static BubbleShieldBlockEntity placeProjector(GameTestHelper helper, float targetRadius) {
		helper.setBlock(PROJECTOR_POS, ModBlocks.BUBBLE_SHIELD_PROJECTOR);
		BubbleShieldBlockEntity be = helper.getBlockEntity(PROJECTOR_POS, BubbleShieldBlockEntity.class);
		be.getShieldState().targetRadius = targetRadius;
		return be;
	}

	@GameTest(padding = 16)
	public void shieldActivates(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);

		helper.assertTrue(!be.tryActivate(), "activation without fuel should fail");
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "activation with fuel and no cooldown should succeed");
		helper.assertTrue(be.getShieldState().active, "shield should be active");
		helper.assertTrue(be.currentRadius() > 0.0F, "active shield should have a positive radius");
		helper.succeed();
	}

	@GameTest(maxTicks = 200, padding = 16)
	public void arrowShrinksShield(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 8.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");
		helper.assertTrue(be.currentRadius() == 8.0F, "shield radius should start at 8");

		// Block center (relative) is at (4.5, 2.5, 4.5). Spawn the arrow 12 blocks above it, shooting straight
		// down at the center. Staying in the structure's chunk column matters: only the structure's own chunks
		// are force-loaded and entity-ticking during a game test, so an arrow spawned 12 blocks to the side
		// would sit in a frozen chunk and never move.
		Arrow arrow = helper.spawn(EntityTypes.ARROW, new Vec3(4.5, 14.5, 4.5));
		arrow.setDeltaMovement(0.0, -1.5, 0.0);

		ShieldState state = be.getShieldState();
		helper.succeedWhen(() -> helper.assertTrue(state.health < state.maxHealth, "shield should take damage from the intercepted arrow"));
	}

	@GameTest(padding = 16)
	public void shieldBreaksAndCoolsDown(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		be.applyShieldDamage(1000.0F);

		ShieldState state = be.getShieldState();
		long gameTime = helper.getLevel().getGameTime();
		helper.assertTrue(!state.active, "shield should deactivate when broken");
		helper.assertTrue(state.health == state.maxHealth, "health should be restored for the next activation");
		helper.assertTrue(state.cooldownUntil > gameTime, "breaking should start a cooldown");
		helper.assertTrue(!be.tryActivate(), "re-activation should fail while cooling down");
		helper.succeed();
	}

	@GameTest(padding = 16)
	public void whitelistBlocksAndAdmits(GameTestHelper helper) {
		// Pure decision helper.
		ShieldState decision = new ShieldState();
		UUID ownerUuid = UUID.randomUUID();
		UUID friendUuid = UUID.randomUUID();
		UUID strangerUuid = UUID.randomUUID();
		decision.ownerUuid = ownerUuid;
		decision.whitelistNames.add("Friendly");
		decision.whitelistUuids.add(friendUuid);

		helper.assertTrue(ShieldLogic.shouldBlock(decision, "Stranger", strangerUuid, false), "strangers should be blocked");
		helper.assertTrue(!ShieldLogic.shouldBlock(decision, "fRiEnDlY", strangerUuid, false), "whitelisted names should match case-insensitively");
		helper.assertTrue(!ShieldLogic.shouldBlock(decision, "Unknown", friendUuid, false), "whitelisted UUIDs should be admitted");
		helper.assertTrue(!ShieldLogic.shouldBlock(decision, "Unknown", ownerUuid, false), "the owner UUID should be admitted");
		helper.assertTrue(!ShieldLogic.shouldBlock(decision, "Stranger", strangerUuid, true), "the owner flag should always admit");

		// Integration placement: a real projector in the world plus mock players crossing the boundary.
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		ShieldState shield = be.getShieldState();
		Vec3 center = Vec3.atCenterOf(helper.absolutePos(PROJECTOR_POS));
		double radius = be.currentRadius();
		Vec3 outside = center.add(radius + 1.5, 0.0, 0.0);
		Vec3 inside = center.add(radius - 0.5, 0.0, 0.0);

		Player stranger = helper.makeMockPlayer(GameType.SURVIVAL);
		stranger.snapTo(inside.x, inside.y, inside.z);
		stranger.xo = outside.x;
		stranger.yo = outside.y;
		stranger.zo = outside.z;

		helper.assertTrue(ShieldLogic.applyPlayerBarrier(center, radius, shield, stranger), "a stranger entering should be pushed back");
		helper.assertTrue(stranger.position().distanceTo(center) > radius, "the stranger should end up outside the shield");
		helper.assertTrue(stranger.getDeltaMovement().lengthSqr() == 0.0, "the pushed player's momentum should be cleared");

		// Mock players are named "test-mock-player"; whitelist with different casing on purpose.
		be.whitelistAdd(helper.getLevel().getServer(), "TEST-MOCK-PLAYER");

		Player friend = helper.makeMockPlayer(GameType.SURVIVAL);
		friend.snapTo(inside.x, inside.y, inside.z);
		friend.xo = outside.x;
		friend.yo = outside.y;
		friend.zo = outside.z;

		helper.assertTrue(!ShieldLogic.applyPlayerBarrier(center, radius, shield, friend), "a whitelisted player should not be pushed");
		helper.assertTrue(friend.position().distanceTo(center) <= radius, "the whitelisted player should stay inside");
		helper.succeed();
	}

	@GameTest(padding = 16)
	public void menuOpens(GameTestHelper helper) {
		placeProjector(helper, 4.0F);

		// makeMockPlayer(GameType) returns a bare Player whose openMenu() is a no-op, so a
		// ServerPlayer placed in the level (with a working connection) is required to observe
		// containerMenu changing when the block is used.
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		try {
			Vec3 center = Vec3.atCenterOf(helper.absolutePos(PROJECTOR_POS));
			player.snapTo(center.x + 1.5, center.y - 0.5, center.z);

			helper.useBlock(PROJECTOR_POS, player);

			helper.assertTrue(player.containerMenu instanceof BubbleShieldMenu, "using the projector should open a BubbleShieldMenu");
			BubbleShieldMenu menu = (BubbleShieldMenu) player.containerMenu;
			helper.assertTrue(menu.pos().equals(helper.absolutePos(PROJECTOR_POS)), "the menu should know the projector position");
			helper.assertTrue(menu.stillValid(player), "the menu should be valid for a player next to the projector");
		} finally {
			helper.getLevel().getServer().getPlayerList().remove(player);
		}

		helper.succeed();
	}

	@GameTest(padding = 16)
	public void allFiftyEffectsValid(GameTestHelper helper) {
		EffectRegistry.validate();
		helper.assertTrue(EffectRegistry.ALL.size() == EffectRegistry.COUNT, "registry should expose exactly 50 effect definitions");
		helper.assertTrue(InsideEffectBehavior.REGISTRY.size() == 10, "exactly 10 inside behaviors should be registered");
		helper.succeed();
	}

	@GameTest(maxTicks = 100, padding = 16)
	public void insideBehaviorRuns(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		be.getShieldState().effectId = 0;
		helper.assertTrue(be.tryActivate(), "shield should activate");

		// The block ticker drives the effect behavior every 10 game ticks; 30 ticks
		// exercise it several times. Reaching the delayed check without an exception
		// (and with the shield still up) proves the behavior ran cleanly.
		helper.runAfterDelay(30, () -> {
			helper.assertTrue(be.getShieldState().active, "shield should still be active after the effect behavior ran");
			helper.succeed();
		});
	}

	@GameTest
	public void persistenceRoundTrip(GameTestHelper helper) {
		ShieldState original = new ShieldState();
		original.active = true;
		original.effectId = 3;
		original.targetRadius = 12.5F;
		original.health = 42.0F;
		original.maxHealth = 120.0F;
		original.ownerUuid = UUID.randomUUID();
		original.whitelistNames.add("Alice");
		original.whitelistNames.add("Bob");
		original.whitelistUuids.add(UUID.randomUUID());
		original.whitelistUuids.add(UUID.randomUUID());
		original.fuelSeconds = 77;
		original.cooldownUntil = 123456L;

		var registries = helper.getLevel().registryAccess();
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
		original.save(output);
		CompoundTag tag = output.buildResult();

		ShieldState loaded = new ShieldState();
		loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));

		helper.assertTrue(loaded.active == original.active, "active should round-trip");
		helper.assertTrue(loaded.effectId == original.effectId, "effectId should round-trip");
		helper.assertTrue(loaded.targetRadius == original.targetRadius, "targetRadius should round-trip");
		helper.assertTrue(loaded.health == original.health, "health should round-trip");
		helper.assertTrue(loaded.maxHealth == original.maxHealth, "maxHealth should round-trip");
		helper.assertTrue(original.ownerUuid.equals(loaded.ownerUuid), "ownerUuid should round-trip");
		helper.assertTrue(loaded.whitelistNames.equals(original.whitelistNames), "whitelistNames should round-trip");
		helper.assertTrue(loaded.whitelistUuids.equals(original.whitelistUuids), "whitelistUuids should round-trip");
		helper.assertTrue(loaded.fuelSeconds == original.fuelSeconds, "fuelSeconds should round-trip");
		helper.assertTrue(loaded.cooldownUntil == original.cooldownUntil, "cooldownUntil should round-trip");
		helper.succeed();
	}
}

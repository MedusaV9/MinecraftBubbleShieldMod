package com.bubbleshield.gametest;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.effect.EffectRegistry;
import com.bubbleshield.menu.BubbleShieldMenu;
import com.bubbleshield.registry.ModBlocks;
import com.bubbleshield.shield.ShieldLogic;
import com.bubbleshield.shield.ShieldState;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.bubbleshield.registry.ModItems;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
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
	public void ownerSetOnPlace(GameTestHelper helper) {
		// Place the projector through a real BlockItem interaction (BlockItem.place ->
		// Block.setPlacedBy) so the owner assignment path is exercised end to end.
		// Note: no snapTo here; the mock ServerPlayer has no connection and BlockItem.place
		// performs no reach check, so the player's position is irrelevant.
		Player player = helper.makeMockServerPlayer(GameType.SURVIVAL);
		ItemStack stack = new ItemStack(ModItems.BUBBLE_SHIELD_PROJECTOR);
		player.setItemInHand(InteractionHand.MAIN_HAND, stack);

		// placeAt clicks the face-adjacent position, so click UP on the block below the target.
		helper.placeAt(player, stack, PROJECTOR_POS.below(), Direction.UP);

		BubbleShieldBlockEntity be = helper.getBlockEntity(PROJECTOR_POS, BubbleShieldBlockEntity.class);
		ShieldState state = be.getShieldState();
		helper.assertTrue(player.getUUID().equals(state.ownerUuid), "placing the projector should record the placer as owner");
		helper.assertTrue(state.whitelistUuids.contains(player.getUUID()), "the owner's UUID should be whitelisted on placement");
		helper.assertTrue(
			state.whitelistNames.stream().anyMatch(name -> name.equalsIgnoreCase(player.getGameProfile().name())),
			"the owner's name should be whitelisted on placement");
		helper.succeed();
	}

	@GameTest(padding = 16)
	public void whitelistRemoveRevokesUuid(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();

		// An online (PlayerList-registered) player so whitelistAdd/Remove can resolve name -> UUID.
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		try {
			UUID uuid = player.getUUID();
			String name = player.getGameProfile().name();

			// Add with different casing on purpose; resolution must be case-insensitive.
			be.whitelistAdd(helper.getLevel().getServer(), name.toUpperCase(java.util.Locale.ROOT));
			helper.assertTrue(state.whitelistUuids.contains(uuid), "adding an online player should record their UUID");
			helper.assertTrue(!ShieldLogic.shouldBlock(state, null, uuid, false), "the whitelisted player should be admitted by UUID");

			be.whitelistRemove(helper.getLevel().getServer(), name);
			helper.assertTrue(!state.whitelistUuids.contains(uuid), "removing the name should also revoke the recorded UUID");
			helper.assertTrue(ShieldLogic.shouldBlock(state, null, uuid, false), "the removed player's UUID should no longer be admitted");
			helper.assertTrue(ShieldLogic.shouldBlock(state, name, uuid, false), "the removed player should be blocked by name and UUID");
		} finally {
			helper.getLevel().getServer().getPlayerList().remove(player);
		}

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

	@GameTest
	public void postEffectAssetsExist(GameTestHelper helper) {
		for (int i = 0; i < EffectRegistry.COUNT; i++) {
			String jsonPath = String.format(java.util.Locale.ROOT, "/assets/bubbleshield/post_effect/effect_%02d.json", i);
			JsonObject config;
			try (InputStream in = ShieldGameTests.class.getResourceAsStream(jsonPath)) {
				helper.assertTrue(in != null, "missing post effect asset: " + jsonPath);
				config = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			} catch (Exception e) {
				throw helper.assertionException("failed to read/parse " + jsonPath + ": " + e);
			}

			JsonArray passes = config.getAsJsonArray("passes");
			helper.assertTrue(passes != null && !passes.isEmpty(), jsonPath + " should declare at least one pass");

			for (var passElement : passes) {
				String fragmentShader = passElement.getAsJsonObject().get("fragment_shader").getAsString();
				if (!fragmentShader.startsWith("bubbleshield:")) {
					continue; // vanilla shader (e.g. minecraft:post/blit)
				}

				String shaderPath = "/assets/bubbleshield/shaders/" + fragmentShader.substring("bubbleshield:".length()) + ".fsh";
				helper.assertTrue(
						shaderPath.startsWith("/assets/bubbleshield/shaders/screenfx/"),
						jsonPath + " should only reference screenfx shaders, got " + fragmentShader);
				try (InputStream shader = ShieldGameTests.class.getResourceAsStream(shaderPath)) {
					helper.assertTrue(shader != null, jsonPath + " references missing shader: " + shaderPath);
				} catch (Exception e) {
					throw helper.assertionException("failed to read " + shaderPath + ": " + e);
				}
			}
		}

		helper.succeed();
	}

	@GameTest
	public void dataPackAssetsExist(GameTestHelper helper) {
		JsonObject lootTable = readJsonResource(helper, "/data/bubbleshield/loot_table/blocks/bubble_shield_projector.json");
		helper.assertTrue("minecraft:block".equals(lootTable.get("type").getAsString()), "loot table should be of type minecraft:block");
		helper.assertTrue(
				lootTable.getAsJsonArray("pools") != null && !lootTable.getAsJsonArray("pools").isEmpty(),
				"loot table should declare at least one pool");

		JsonObject recipe = readJsonResource(helper, "/data/bubbleshield/recipe/bubble_shield_projector.json");
		helper.assertTrue("minecraft:crafting_shaped".equals(recipe.get("type").getAsString()), "recipe should be minecraft:crafting_shaped");
		helper.assertTrue(
				"bubbleshield:bubble_shield_projector".equals(recipe.getAsJsonObject("result").get("id").getAsString()),
				"recipe should produce the projector item");

		// The vanilla jar ships the same tag paths, so check every classpath copy:
		// the mod's copy must exist and list the projector (tag JSONs merge at runtime).
		for (String tagPath : new String[] {"data/minecraft/tags/block/mineable/pickaxe.json", "data/minecraft/tags/block/needs_iron_tool.json"}) {
			boolean containsProjector = false;
			try {
				var resources = ShieldGameTests.class.getClassLoader().getResources(tagPath);
				while (resources.hasMoreElements() && !containsProjector) {
					try (InputStream in = resources.nextElement().openStream()) {
						JsonObject tag = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
						JsonArray values = tag.getAsJsonArray("values");
						containsProjector = values != null && values.asList().stream()
								.anyMatch(value -> value.isJsonPrimitive() && "bubbleshield:bubble_shield_projector".equals(value.getAsString()));
					}
				}
			} catch (Exception e) {
				throw helper.assertionException("failed to read/parse " + tagPath + ": " + e);
			}

			helper.assertTrue(containsProjector, tagPath + " should list bubbleshield:bubble_shield_projector");
		}

		helper.succeed();
	}

	private static JsonObject readJsonResource(GameTestHelper helper, String path) {
		try (InputStream in = ShieldGameTests.class.getResourceAsStream(path)) {
			helper.assertTrue(in != null, "missing data pack resource: " + path);
			return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (Exception e) {
			throw helper.assertionException("failed to read/parse " + path + ": " + e);
		}
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

	@GameTest(maxTicks = 200, padding = 16)
	public void behaviorEffectsApply(GameTestHelper helper) {
		helper.assertTrue("regen_aura".equals(EffectRegistry.get(11).insideBehaviorId()), "effect 11 should be regen_aura");
		helper.assertTrue("haste_aura".equals(EffectRegistry.get(9).insideBehaviorId()), "effect 9 should be haste_aura");

		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		be.getShieldState().effectId = 11; // regen_aura@0

		// An in-level ServerPlayer so the aura's level.getEntitiesOfClass query finds it.
		// Whitelist it (mock players are named "test-mock-player") so the barrier does
		// not expel it, then park it inside the radius-4 bubble.
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		be.whitelistAdd(helper.getLevel().getServer(), player.getGameProfile().name());
		helper.assertTrue(be.tryActivate(), "shield should activate");

		Vec3 center = Vec3.atCenterOf(helper.absolutePos(PROJECTOR_POS));
		player.snapTo(center.x + 1.5, center.y, center.z);

		helper.runAfterDelay(30, () -> {
			boolean hasRegen = player.hasEffect(MobEffects.REGENERATION);
			if (!hasRegen) {
				helper.getLevel().getServer().getPlayerList().remove(player);
			}
			helper.assertTrue(hasRegen, "a player inside a regen_aura shield should have Regeneration");

			be.getShieldState().effectId = 9; // haste_aura@0
			helper.runAfterDelay(30, () -> {
				boolean hasHaste = player.hasEffect(MobEffects.HASTE);
				helper.getLevel().getServer().getPlayerList().remove(player);
				helper.assertTrue(hasHaste, "a player inside a haste_aura shield should have Haste");
				helper.succeed();
			});
		});
	}

	@GameTest
	public void persistenceRoundTrip(GameTestHelper helper) {
		ShieldState original = new ShieldState();
		original.active = true;
		original.effectId = 3;
		original.shape = com.bubbleshield.shield.ShieldShape.DOME;
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
		helper.assertTrue(loaded.shape == original.shape, "shape should round-trip");
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

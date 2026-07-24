package com.bubbleshield.gametest;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.menu.BubbleShieldMenu;
import com.bubbleshield.registry.ModBlocks;
import com.bubbleshield.registry.ModItems;
import com.bubbleshield.shield.ShieldLogic;
import com.bubbleshield.shield.ShieldState;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Coverage for WP4 "Recovery &amp; repair": the A7 emergency revive (fuel-paid
 * break-cooldown skip) and the C3 patch kit (heal an active shield / shorten a
 * break cooldown), plus the patch kit's recipe and registration.
 */
public class RecoveryGameTests {
	/**
	 * A dedicated (but otherwise vanilla-default) test environment,
	 * {@code data/bubbleshield/test_environment/recovery.json}, keeping this class
	 * out of the shared default batch (see ColorGameTests.ISOLATED_ENVIRONMENT for
	 * the full batching rationale).
	 */
	private static final String ISOLATED_ENVIRONMENT = "bubbleshield:recovery";
	private static final BlockPos PROJECTOR_POS = new BlockPos(4, 2, 4);
	private static final int PLENTY_OF_FUEL = 1000;

	private static BubbleShieldBlockEntity placeProjector(GameTestHelper helper, float targetRadius) {
		helper.setBlock(PROJECTOR_POS, ModBlocks.BUBBLE_SHIELD_PROJECTOR);
		BubbleShieldBlockEntity be = helper.getBlockEntity(PROJECTOR_POS, BubbleShieldBlockEntity.class);
		be.getShieldState().targetRadius = targetRadius;
		return be;
	}

	/** Parks the mock next to the projector (menu stillValid range) and hands it {@code count} patch kits. */
	private static void armWithPatchKits(GameTestHelper helper, ServerPlayer player, int count) {
		Vec3 center = Vec3.atCenterOf(helper.absolutePos(PROJECTOR_POS));
		player.snapTo(center.x + 1.5, center.y - 0.5, center.z);
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.PATCH_KIT, count));
	}

	/**
	 * (a) The A7 emergency revive: with a running break cooldown and at least 400
	 * stored fuel-seconds, the OWNER's revive clears the cooldown, charges exactly
	 * 400 fuel-seconds and reactivates the shield at 50% of its max health.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 100, padding = 16)
	public void reviveSkipsCooldownForFuel(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		ServerPlayer owner = MockPlayers.createUniqueMockPlayer(helper);
		be.setOwner(owner);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.runAfterDelay(2, () -> {
			helper.assertTrue(state.maxHealth == 125.0F, "tier 0 at diameter 8 should have maxHealth 125, got " + state.maxHealth);
			be.applyShieldDamage(100000.0F);
			long gameTime = helper.getLevel().getGameTime();
			helper.assertTrue(!state.active && state.cooldownUntil > gameTime, "overkill damage should break the shield into a cooldown");
			helper.assertTrue(!be.tryActivate(), "the plain activation must still refuse during the cooldown");
			int fuelBefore = state.fuelSeconds;
			helper.assertTrue(fuelBefore >= ShieldLogic.REVIVE_FUEL_COST, "the test setup should leave at least the revive fee stored");

			helper.assertTrue(be.tryEmergencyRevive(owner), "the owner's revive should succeed with the fee affordable");
			helper.assertTrue(state.active, "the revived shield should be active");
			helper.assertTrue(
					state.fuelSeconds == fuelBefore - ShieldLogic.REVIVE_FUEL_COST,
					"the revive should charge exactly 400 fuel-seconds, fuel went " + fuelBefore + " -> " + state.fuelSeconds);
			helper.assertTrue(state.cooldownUntil <= gameTime, "the revive should clear the break cooldown");
			helper.assertTrue(
					state.health == 0.5F * state.maxHealth,
					"the revived shield should restart at 50% of max health (62.5), got " + state.health);
			helper.succeed();
		});
	}

	/**
	 * (b) The revive is refused when fewer than 400 fuel-seconds are stored: the
	 * shield stays broken, the cooldown keeps running and no fuel is charged.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 100, padding = 16)
	public void reviveRefusedWhenFuelBelowCost(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		ServerPlayer owner = MockPlayers.createUniqueMockPlayer(helper);
		be.setOwner(owner);
		be.addFuelSeconds(ShieldLogic.REVIVE_FUEL_COST - 1);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.runAfterDelay(2, () -> {
			be.applyShieldDamage(100000.0F);
			long gameTime = helper.getLevel().getGameTime();
			int fuelBefore = state.fuelSeconds;
			helper.assertTrue(fuelBefore == ShieldLogic.REVIVE_FUEL_COST - 1, "the setup should leave 399 fuel-seconds, got " + fuelBefore);

			helper.assertTrue(!be.tryEmergencyRevive(owner), "the revive must refuse below the 400 fuel-second fee");
			helper.assertTrue(!state.active, "the shield must stay inactive after the refused revive");
			helper.assertTrue(state.cooldownUntil > gameTime, "the cooldown must keep running after the refused revive");
			helper.assertTrue(state.fuelSeconds == fuelBefore, "a refused revive must not charge any fuel");
			helper.succeed();
		});
	}

	/**
	 * (c) The revive is owner-gated exactly like the other mutating requests: a
	 * non-owner is refused (no state change), then the owner succeeds.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 100, padding = 16)
	public void reviveOwnerGated(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		ServerPlayer owner = MockPlayers.createUniqueMockPlayer(helper);
		ServerPlayer stranger = MockPlayers.createUniqueMockPlayer(helper);
		be.setOwner(owner);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.runAfterDelay(2, () -> {
			be.applyShieldDamage(100000.0F);
			long gameTime = helper.getLevel().getGameTime();
			int fuelBefore = state.fuelSeconds;

			helper.assertTrue(!be.tryEmergencyRevive(stranger), "a non-owner's revive must be refused");
			helper.assertTrue(!state.active && state.cooldownUntil > gameTime && state.fuelSeconds == fuelBefore,
					"a refused non-owner revive must not change any state");

			helper.assertTrue(be.tryEmergencyRevive(owner), "the owner's revive should succeed after the stranger's refusal");
			helper.assertTrue(state.active, "the shield should be active after the owner's revive");
			helper.succeed();
		});
	}

	/**
	 * (d) The revive only bypasses the COOLDOWN failure: without a running
	 * cooldown (inactive-with-fuel, or already active) it refuses and charges
	 * nothing — plain activation stays the only path there.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, padding = 16)
	public void reviveRequiresRunningCooldown(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		ServerPlayer owner = MockPlayers.createUniqueMockPlayer(helper);
		be.setOwner(owner);
		be.addFuelSeconds(PLENTY_OF_FUEL);

		helper.assertTrue(!be.tryEmergencyRevive(owner), "an inactive shield without a cooldown has nothing to revive");
		helper.assertTrue(state.fuelSeconds == PLENTY_OF_FUEL && !state.active, "the refused revive must not touch fuel or activate");

		helper.assertTrue(be.tryActivate(), "the plain activation should succeed instead");
		helper.assertTrue(!be.tryEmergencyRevive(owner), "an already-active shield must refuse the revive");
		helper.assertTrue(state.fuelSeconds == PLENTY_OF_FUEL, "the refused revive on an active shield must not charge fuel");
		helper.succeed();
	}

	/**
	 * (e) The patch kit on an ACTIVE shield: the first use restores exactly 150 HP
	 * and consumes one kit; the second use caps at max health (healing the last 10)
	 * and is still consumed because it healed at least 1 HP.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 100, padding = 16)
	public void patchKitHealsAndCapsAtMax(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 16.0F);
		ShieldState state = be.getShieldState();
		ServerPlayer owner = MockPlayers.createUniqueMockPlayer(helper);
		be.setOwner(owner);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.runAfterDelay(2, () -> {
			helper.assertTrue(state.maxHealth == 200.0F, "tier 0 at diameter 32 should have maxHealth 200, got " + state.maxHealth);
			// 160 raw at tier 0 (no DR) leaves 40 HP; the hit also opens the combat
			// gate, so tier 0 cannot regenerate between the checks below.
			be.applyShieldDamage(160.0F);
			helper.assertTrue(state.health == 40.0F, "damaged health should be 40, got " + state.health);

			armWithPatchKits(helper, owner, 2);
			helper.useBlock(PROJECTOR_POS, owner);
			helper.assertTrue(state.health == 190.0F, "the first kit should heal exactly 150 (40 -> 190), got " + state.health);
			helper.assertTrue(owner.getMainHandItem().getCount() == 1, "the first (effective) kit use should consume one kit");

			helper.useBlock(PROJECTOR_POS, owner);
			helper.assertTrue(state.health == 200.0F, "the second kit should cap at max health (190 -> 200), got " + state.health);
			helper.assertTrue(owner.getMainHandItem().getCount() == 0, "healing the last 10 HP still consumes the kit (>= 1 HP healed)");
			helper.succeed();
		});
	}

	/**
	 * (f) The patch kit at full health is a no-op: nothing to heal means no kit is
	 * consumed — and the interaction falls through to the regular menu open, so a
	 * kit-holding owner is never locked out of the GUI.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 100, padding = 16)
	public void patchKitFullHealthNoOpOpensMenu(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		ServerPlayer owner = MockPlayers.createUniqueMockPlayer(helper);
		be.setOwner(owner);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.runAfterDelay(2, () -> {
			helper.assertTrue(state.health == state.maxHealth, "the shield should be at full health");
			armWithPatchKits(helper, owner, 1);
			helper.useBlock(PROJECTOR_POS, owner);
			helper.assertTrue(state.health == state.maxHealth, "a full-health shield must not change");
			helper.assertTrue(owner.getMainHandItem().getCount() == 1, "a no-op kit use must not be consumed");
			helper.assertTrue(owner.containerMenu instanceof BubbleShieldMenu,
					"the no-op kit interaction should fall through to the menu open");
			helper.succeed();
		});
	}

	/**
	 * (g) The patch kit on a broken shield cuts the REMAINING cooldown by 20% of
	 * the tier's FULL cooldown (tier 0: 3600 of 18000 ticks), stacks across uses,
	 * floors at 1 remaining tick, and is only consumed when it reduced something.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 100, padding = 16)
	public void patchKitCutsBreakCooldown(GameTestHelper helper) {
		// The 20%-of-full reduction table is pure and exact (all values divide by 5).
		helper.assertTrue(ShieldLogic.patchKitCooldownReduction(0) == 3600L, "tier 0 reduction should be 3600 ticks");
		helper.assertTrue(ShieldLogic.patchKitCooldownReduction(1) == 2400L, "tier 1 reduction should be 2400 ticks");
		helper.assertTrue(ShieldLogic.patchKitCooldownReduction(2) == 1440L, "tier 2 reduction should be 1440 ticks");
		helper.assertTrue(ShieldLogic.patchKitCooldownReduction(3) == 720L, "tier 3 reduction should be 720 ticks");

		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		ServerPlayer owner = MockPlayers.createUniqueMockPlayer(helper);
		be.setOwner(owner);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.runAfterDelay(2, () -> {
			be.applyShieldDamage(100000.0F);
			long gameTime = helper.getLevel().getGameTime();
			helper.assertTrue(state.cooldownUntil == gameTime + 18000L, "a tier-0 break should start the full 18000-tick cooldown");

			armWithPatchKits(helper, owner, 3);
			helper.useBlock(PROJECTOR_POS, owner);
			helper.assertTrue(state.cooldownUntil == gameTime + 14400L,
					"the first kit should cut the remaining cooldown by exactly 3600, got " + (state.cooldownUntil - gameTime));
			helper.assertTrue(owner.getMainHandItem().getCount() == 2, "the first cooldown cut should consume one kit");

			helper.useBlock(PROJECTOR_POS, owner);
			helper.assertTrue(state.cooldownUntil == gameTime + 10800L,
					"the second kit should stack another 3600 cut, got " + (state.cooldownUntil - gameTime));
			helper.assertTrue(owner.getMainHandItem().getCount() == 1, "the second cooldown cut should consume one kit");
			helper.assertTrue(!state.active, "cooldown cuts must not activate the shield");

			// The floor: a remaining cooldown shorter than the reduction drops to
			// exactly 1 tick (consumed: it reduced something)...
			state.cooldownUntil = gameTime + 100L;
			helper.useBlock(PROJECTOR_POS, owner);
			helper.assertTrue(state.cooldownUntil == gameTime + 1L,
					"a 100-tick remainder should floor at 1 remaining tick, got " + (state.cooldownUntil - gameTime));
			helper.assertTrue(owner.getMainHandItem().getCount() == 0, "the floored cut still consumed a kit (it reduced 99 ticks)");

			// ... and a further use has nothing left to reduce, so it must not consume.
			armWithPatchKits(helper, owner, 1);
			helper.useBlock(PROJECTOR_POS, owner);
			helper.assertTrue(state.cooldownUntil == gameTime + 1L, "at the 1-tick floor nothing may be reduced");
			helper.assertTrue(owner.getMainHandItem().getCount() == 1, "a kit that reduced nothing must not be consumed");
			helper.succeed();
		});
	}

	/**
	 * (h) The patch kit mirrors the barrier's subject rule: a non-whitelisted
	 * stranger gets no effect and keeps the kit, a whitelisted (non-owner) friend
	 * may patch.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 100, padding = 16)
	public void patchKitStrangerNoOpWhitelistedAllowed(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		ServerPlayer owner = MockPlayers.createUniqueMockPlayer(helper);
		ServerPlayer stranger = MockPlayers.createUniqueMockPlayer(helper);
		be.setOwner(owner);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.runAfterDelay(2, () -> {
			be.applyShieldDamage(100000.0F);
			long gameTime = helper.getLevel().getGameTime();
			long cooldownBefore = state.cooldownUntil;
			helper.assertTrue(cooldownBefore > gameTime, "the break should start a cooldown");

			armWithPatchKits(helper, stranger, 1);
			helper.useBlock(PROJECTOR_POS, stranger);
			helper.assertTrue(state.cooldownUntil == cooldownBefore, "a stranger's kit must not touch the cooldown");
			helper.assertTrue(stranger.getMainHandItem().getCount() == 1, "a stranger's kit must not be consumed");

			// Whitelisting the same player flips the decision — no ownership needed.
			state.whitelistUuids.add(stranger.getUUID());
			helper.useBlock(PROJECTOR_POS, stranger);
			helper.assertTrue(state.cooldownUntil == cooldownBefore - ShieldLogic.patchKitCooldownReduction(0),
					"a whitelisted friend's kit should cut the cooldown");
			helper.assertTrue(stranger.getMainHandItem().getCount() == 0, "the friend's effective kit use should be consumed");
			helper.succeed();
		});
	}

	/**
	 * (i) The patch kit ships as authored: registered under
	 * {@code bubbleshield:patch_kit} with a max stack of 16, its shapeless recipe
	 * (2x amethyst shard + slime ball + copper ingot -&gt; 2 kits) parses, and the
	 * item-definition/model assets exist on the classpath.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void patchKitRecipeAndRegistration(GameTestHelper helper) {
		helper.assertTrue(
				BuiltInRegistries.ITEM.getValue(Identifier.parse("bubbleshield:patch_kit")) == ModItems.PATCH_KIT,
				"bubbleshield:patch_kit should resolve to the registered item");
		helper.assertTrue(new ItemStack(ModItems.PATCH_KIT).getMaxStackSize() == 16, "the patch kit should stack to 16");

		JsonObject recipe = readJsonResource(helper, "/data/bubbleshield/recipe/patch_kit.json");
		helper.assertTrue(
				"minecraft:crafting_shapeless".equals(recipe.get("type").getAsString()),
				"the patch kit recipe should be minecraft:crafting_shapeless");
		JsonObject result = recipe.getAsJsonObject("result");
		helper.assertTrue("bubbleshield:patch_kit".equals(result.get("id").getAsString()), "the recipe should produce patch kits");
		helper.assertTrue(result.get("count").getAsInt() == 2, "the recipe should produce 2 kits");

		JsonArray ingredients = recipe.getAsJsonArray("ingredients");
		helper.assertTrue(ingredients != null && ingredients.size() == 4, "the recipe should list exactly 4 ingredients");
		int shards = 0;
		int slime = 0;
		int copper = 0;
		for (int i = 0; i < ingredients.size(); i++) {
			switch (ingredients.get(i).getAsString()) {
				case "minecraft:amethyst_shard" -> shards++;
				case "minecraft:slime_ball" -> slime++;
				case "minecraft:copper_ingot" -> copper++;
				default -> throw helper.assertionException("unexpected ingredient: " + ingredients.get(i).getAsString());
			}
		}

		helper.assertTrue(shards == 2 && slime == 1 && copper == 1,
				"the recipe should take 2 amethyst shards, 1 slime ball and 1 copper ingot");

		// The item definition points at the mod model, which reuses the vanilla
		// phantom-membrane sprite (same pattern as the cores' nether-star reuse).
		JsonObject itemDefinition = readJsonResource(helper, "/assets/bubbleshield/items/patch_kit.json");
		helper.assertTrue(
				"bubbleshield:item/patch_kit".equals(itemDefinition.getAsJsonObject("model").get("model").getAsString()),
				"the item definition should reference bubbleshield:item/patch_kit");
		JsonObject model = readJsonResource(helper, "/assets/bubbleshield/models/item/patch_kit.json");
		helper.assertTrue(
				"minecraft:item/phantom_membrane".equals(model.getAsJsonObject("textures").get("layer0").getAsString()),
				"the model should reuse the vanilla phantom membrane sprite");
		helper.succeed();
	}

	private static JsonObject readJsonResource(GameTestHelper helper, String path) {
		try (InputStream in = RecoveryGameTests.class.getResourceAsStream(path)) {
			helper.assertTrue(in != null, "missing data pack resource: " + path);
			return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (Exception e) {
			throw helper.assertionException("failed to read/parse " + path + ": " + e);
		}
	}
}

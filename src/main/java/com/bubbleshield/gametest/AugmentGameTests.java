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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;

/**
 * Coverage for WP5 "Augment slot &amp; defense modules": the 4th (single) device
 * slot on the projector menu and the two mutually exclusive modules it accepts —
 * reinforced plating ({@link ShieldLogic#PLATING_DR}: +30% DR on every hit, stacked
 * multiplicatively with the tier DR under the 70% combined cap) and the blast ward
 * ({@link ShieldLogic#BLAST_WARD_MULTIPLIER}: explosive projectile damage x0.4
 * BEFORE the DR pipeline). Also pins the slot-index shift (augment = 3, player
 * inventory now starts at 4), persistence, drop-on-break, quickMove routing,
 * mayPlace gating and the recipes/registration.
 */
public class AugmentGameTests {
	/**
	 * A dedicated (but otherwise vanilla-default) test environment,
	 * {@code data/bubbleshield/test_environment/augment.json}, keeping this class
	 * out of the shared default batch (see ColorGameTests.ISOLATED_ENVIRONMENT for
	 * the full batching rationale).
	 */
	private static final String ISOLATED_ENVIRONMENT = "bubbleshield:augment";
	private static final BlockPos PROJECTOR_POS = new BlockPos(4, 2, 4);
	private static final int PLENTY_OF_FUEL = 600;
	private static final float TOLERANCE = 0.001F;

	private static BubbleShieldBlockEntity placeProjector(GameTestHelper helper, float targetRadius) {
		helper.setBlock(PROJECTOR_POS, ModBlocks.BUBBLE_SHIELD_PROJECTOR);
		BubbleShieldBlockEntity be = helper.getBlockEntity(PROJECTOR_POS, BubbleShieldBlockEntity.class);
		be.getShieldState().targetRadius = targetRadius;
		return be;
	}

	private static void installAugment(BubbleShieldBlockEntity be, ItemStack stack) {
		be.getAugmentContainer().setItem(0, stack);
	}

	/**
	 * (a) The plating DR pure math through {@link ShieldLogic#appliedDamage}: an
	 * arrow (3 raw) applies 2.1 at tier 0 + plating (30% DR), 1.26 at tier 2 +
	 * plating (combined 1 - (1-0.40)(1-0.30) = 0.58) and 1.05 at tier 3 + plating
	 * (combined 1 - (1-0.50)(1-0.30) = 0.65 — the multiplicative stack stays BELOW
	 * the 70% cap, which only engages for hypothetical plating values above 0.40
	 * at tier 3, pinned by the last check).
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void platingDrPureMath(GameTestHelper helper) {
		helper.assertTrue(ShieldLogic.PLATING_DR == 0.30F, "the plating DR should be exactly 30%");

		float t0 = ShieldLogic.appliedDamage(ShieldLogic.PROJECTILE_DAMAGE, 0, ShieldLogic.PLATING_DR, false);
		helper.assertTrue(Math.abs(t0 - 2.1F) < TOLERANCE,
				"an arrow into a tier-0 plated shield should apply 2.1, got " + t0);

		float t2 = ShieldLogic.appliedDamage(ShieldLogic.PROJECTILE_DAMAGE, 2, ShieldLogic.PLATING_DR, false);
		helper.assertTrue(Math.abs(t2 - 1.26F) < TOLERANCE,
				"an arrow into a tier-2 plated shield should apply 3 x (1 - 0.58) = 1.26, got " + t2);

		float t3 = ShieldLogic.appliedDamage(ShieldLogic.PROJECTILE_DAMAGE, 3, ShieldLogic.PLATING_DR, false);
		helper.assertTrue(Math.abs(t3 - 1.05F) < TOLERANCE,
				"an arrow into a tier-3 plated shield should apply 3 x (1 - 0.65) = 1.05 (below the cap), got " + t3);

		// The 70% combined cap is still armed above the reachable 0.65: a hypothetical
		// 50% plating at tier 3 (0.75 uncapped) clamps to 0.70.
		helper.assertTrue(
				Math.abs(ShieldLogic.appliedDamage(10.0F, 3, 0.5F, false) - 3.0F) < TOLERANCE,
				"combined DR above 70% must clamp to the cap");
		helper.succeed();
	}

	/**
	 * (b) Plating live: an intercepted arrow costs a tier-0 shield with reinforced
	 * plating socketed exactly 2.1 health (3 raw x 0.70) through the real
	 * interception path — proving the augment container feeds
	 * {@code platingDr = 0.30} into the pipeline. Geometry mirrors
	 * ShieldGameTests.arrowDamagesShield (radius 8 keeps the boundary crossing
	 * above the framework's barrier ceiling).
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void platingReducesArrowDamage(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 8.0F);
		ShieldState state = be.getShieldState();
		installAugment(be, new ItemStack(ModItems.REINFORCED_PLATING));
		helper.assertTrue(be.hasPlating(), "the installed plating should be detected");
		helper.assertTrue(be.platingDr() == ShieldLogic.PLATING_DR, "the installed plating should report 30% DR");
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		Arrow arrow = helper.spawn(EntityTypes.ARROW, new Vec3(4.5, 14.5, 4.5));
		arrow.setDeltaMovement(0.0, -1.5, 0.0);

		// Tier 0 in combat cannot regenerate, so the damaged value stays put.
		helper.succeedWhen(() -> {
			helper.assertTrue(state.health < state.maxHealth, "the plated shield should take damage from the intercepted arrow");
			helper.assertTrue(state.maxHealth == 150.0F, "tier 0 at diameter 16 should have maxHealth 150, got " + state.maxHealth);
			helper.assertTrue(
					Math.abs(state.health - (150.0F - 2.1F)) <= TOLERANCE,
					"plating should cut the 3-damage arrow to exactly 2.1, health is " + state.health);
		});
	}

	/**
	 * (c) The blast ward pure math and its canonical order: explosive raw damage is
	 * warded x0.4 FIRST ({@link ShieldLogic#blastWardedDamage}), THEN the DR
	 * pipeline runs — tier 0 + ward: 10 -&gt; 4.0; the ward+plating composition
	 * (pure pipeline-order check; in game the single augment slot makes the two
	 * modules mutually exclusive): 10 -&gt; 4.0 -&gt; x0.70 = 2.8.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void blastWardMathPure(GameTestHelper helper) {
		helper.assertTrue(ShieldLogic.BLAST_WARD_MULTIPLIER == 0.4F, "the blast ward multiplier should be exactly x0.4");

		float warded = ShieldLogic.blastWardedDamage(ShieldLogic.HURTING_PROJECTILE_DAMAGE, true);
		helper.assertTrue(Math.abs(warded - 4.0F) < TOLERANCE, "10 raw explosive should ward to 4.0, got " + warded);
		helper.assertTrue(
				ShieldLogic.blastWardedDamage(ShieldLogic.HURTING_PROJECTILE_DAMAGE, false) == ShieldLogic.HURTING_PROJECTILE_DAMAGE,
				"without a ward the raw damage must pass through unchanged");

		float t0Ward = ShieldLogic.appliedDamage(warded, 0, 0.0F, false);
		helper.assertTrue(Math.abs(t0Ward - 4.0F) < TOLERANCE,
				"a fireball into a tier-0 warded shield should apply 4.0, got " + t0Ward);

		// Canonical order: raw x0.4 BEFORE appliedDamage, so plating discounts the
		// already-warded value (10 -> 4.0 -> 2.8), never the other way around.
		float wardPlusPlating = ShieldLogic.appliedDamage(warded, 0, ShieldLogic.PLATING_DR, false);
		helper.assertTrue(Math.abs(wardPlusPlating - 2.8F) < TOLERANCE,
				"ward-then-plating at tier 0 should compose 10 -> 4.0 -> 2.8, got " + wardPlusPlating);
		helper.succeed();
	}

	/**
	 * (d) The blast ward live: an intercepted small fireball costs a tier-0 shield
	 * with a blast ward socketed exactly 4.0 health (10 raw x 0.4) through the real
	 * interception path. The interception itself is unchanged by the ward: the
	 * fireball is REVERSE-deflected (kept alive, sent back out), so nothing
	 * explodes inside the bubble — same as ControlShapeGameTests.fireballDeflected.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void blastWardReducesFireballDamage(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 8.0F);
		ShieldState state = be.getShieldState();
		installAugment(be, new ItemStack(ModItems.BLAST_WARD));
		helper.assertTrue(be.hasBlastWard(), "the installed blast ward should be detected");
		helper.assertTrue(be.platingDr() == 0.0F, "a blast ward must not grant plating DR");
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		SmallFireball fireball = helper.spawn(EntityTypes.SMALL_FIREBALL, new Vec3(4.5, 14.5, 4.5));
		fireball.setDeltaMovement(0.0, -1.5, 0.0);

		Vec3 center = Vec3.atCenterOf(helper.absolutePos(PROJECTOR_POS));
		helper.succeedWhen(() -> {
			helper.assertTrue(state.health < state.maxHealth, "the warded shield should take damage from the intercepted fireball");
			helper.assertTrue(state.maxHealth == 150.0F, "tier 0 at diameter 16 should have maxHealth 150, got " + state.maxHealth);
			helper.assertTrue(
					Math.abs(state.health - (150.0F - 4.0F)) <= TOLERANCE,
					"the ward should cut the 10-damage fireball to exactly 4.0, health is " + state.health);
			helper.assertTrue(fireball.isAlive(), "the deflected fireball must stay alive (not explode inside)");
			helper.assertTrue(
					fireball.getDeltaMovement().dot(fireball.position().subtract(center)) > 0.0,
					"the deflected fireball should move away from the shield center");
		});
	}

	/**
	 * (e) The blast ward does NOT reduce non-explosive damage: an intercepted arrow
	 * still costs the full 3.0 on a tier-0 shield with the ward socketed.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void blastWardDoesNotReduceArrows(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 8.0F);
		ShieldState state = be.getShieldState();
		installAugment(be, new ItemStack(ModItems.BLAST_WARD));
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		Arrow arrow = helper.spawn(EntityTypes.ARROW, new Vec3(4.5, 14.5, 4.5));
		arrow.setDeltaMovement(0.0, -1.5, 0.0);

		helper.succeedWhen(() -> {
			helper.assertTrue(state.health < state.maxHealth, "the warded shield should take damage from the intercepted arrow");
			helper.assertTrue(
					state.health == state.maxHealth - ShieldLogic.PROJECTILE_DAMAGE,
					"a blast ward must not discount arrows (expected the full 3.0), health is " + state.health);
		});
	}

	/**
	 * (f) The augment slot round-trips through block entity NBT as
	 * {@code augment_items} (same save/load probe as
	 * CapacitorGameTests.capacitorPersistence), for both modules.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, padding = 16)
	public void augmentPersistence(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		installAugment(be, new ItemStack(ModItems.REINFORCED_PLATING));

		var registries = helper.getLevel().registryAccess();
		CompoundTag tag = be.saveCustomOnly(registries);
		helper.assertTrue(tag.contains("augment_items"), "saved block entity NBT should include augment_items");

		BubbleShieldBlockEntity loaded = new BubbleShieldBlockEntity(helper.absolutePos(PROJECTOR_POS), be.getBlockState());
		loaded.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
		helper.assertTrue(
				loaded.getAugmentContainer().getItem(0).is(ModItems.REINFORCED_PLATING),
				"the reinforced plating should round-trip through NBT");
		helper.assertTrue(loaded.hasPlating(), "the loaded block entity should report installed plating");
		helper.assertTrue(loaded.platingDr() == ShieldLogic.PLATING_DR, "the loaded plating should grant 30% DR");

		// The other module takes the same path.
		installAugment(be, new ItemStack(ModItems.BLAST_WARD));
		BubbleShieldBlockEntity reloaded = new BubbleShieldBlockEntity(helper.absolutePos(PROJECTOR_POS), be.getBlockState());
		reloaded.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, registries, be.saveCustomOnly(registries)));
		helper.assertTrue(reloaded.hasBlastWard(), "the blast ward should round-trip through NBT");
		helper.succeed();
	}

	/**
	 * (g) A socketed augment drops when the projector block is broken, through the
	 * same {@code preRemoveSideEffects} hook that already drops the fuel, core and
	 * capacitor slots.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 100, padding = 16)
	public void augmentDropsOnBlockBreak(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		installAugment(be, new ItemStack(ModItems.BLAST_WARD));

		helper.destroyBlock(PROJECTOR_POS);
		helper.succeedWhen(() -> helper.assertItemEntityPresent(ModItems.BLAST_WARD, PROJECTOR_POS, 3.0));
	}

	/**
	 * (h) quickMove routes both modules from the hotbar into slot 3 (the augment
	 * slot), refuses a second module while the single slot is occupied, keeps
	 * routing fuel/core/capacitor to slots 0/1/2 after the +1 inventory shift, and
	 * moves a socketed augment back out to the player inventory. Follows
	 * CapacitorGameTests.quickMoveRoutesCapacitor (real block use opens the menu).
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, padding = 16)
	public void quickMoveRoutesAugment(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);

		ServerPlayer player = MockPlayers.createUniqueMockPlayer(helper);
		try {
			Vec3 center = Vec3.atCenterOf(helper.absolutePos(PROJECTOR_POS));
			player.snapTo(center.x + 1.5, center.y - 0.5, center.z);
			helper.useBlock(PROJECTOR_POS, player);
			helper.assertTrue(player.containerMenu instanceof BubbleShieldMenu, "using the projector should open a BubbleShieldMenu");
			BubbleShieldMenu menu = (BubbleShieldMenu) player.containerMenu;

			// Hotbar slots 0..4 map to menu slots 31..35 (4 projector slots + 27 inventory).
			int hotbarStart = 4 + 27;
			player.getInventory().setItem(0, new ItemStack(ModItems.REINFORCED_PLATING));
			player.getInventory().setItem(1, new ItemStack(ModItems.BLAST_WARD));
			player.getInventory().setItem(2, new ItemStack(Items.COAL));
			player.getInventory().setItem(3, new ItemStack(ModItems.RESONANT_CORE));
			player.getInventory().setItem(4, new ItemStack(ModItems.FLUX_CAPACITOR));

			menu.quickMoveStack(player, hotbarStart);
			helper.assertTrue(
					menu.getSlot(BubbleShieldMenu.AUGMENT_SLOT).getItem().is(ModItems.REINFORCED_PLATING),
					"quickMove should route the reinforced plating into slot 3");
			helper.assertTrue(
					be.getAugmentContainer().getItem(0).is(ModItems.REINFORCED_PLATING),
					"the routed plating should land in the block entity's augment container");
			helper.assertTrue(
					menu.getSlot(hotbarStart).getItem().isEmpty(),
					"the hotbar slot should be empty after the plating was moved");

			// The single augment slot is occupied: the second module must stay put.
			menu.quickMoveStack(player, hotbarStart + 1);
			helper.assertTrue(
					menu.getSlot(hotbarStart + 1).getItem().is(ModItems.BLAST_WARD),
					"a second module must be refused while the augment slot is occupied");
			helper.assertTrue(
					menu.getSlot(BubbleShieldMenu.AUGMENT_SLOT).getItem().is(ModItems.REINFORCED_PLATING),
					"the socketed plating must not be displaced by the refused ward");

			// The +1 shift must not break the classic routes.
			menu.quickMoveStack(player, hotbarStart + 2);
			helper.assertTrue(
					menu.getSlot(BubbleShieldMenu.FUEL_SLOT).getItem().is(Items.COAL),
					"quickMove should still route fuels into slot 0");
			menu.quickMoveStack(player, hotbarStart + 3);
			helper.assertTrue(
					menu.getSlot(BubbleShieldMenu.CORE_SLOT).getItem().is(ModItems.RESONANT_CORE),
					"quickMove should still route cores into slot 1");
			menu.quickMoveStack(player, hotbarStart + 4);
			helper.assertTrue(
					menu.getSlot(BubbleShieldMenu.CAPACITOR_SLOT).getItem().is(ModItems.FLUX_CAPACITOR),
					"quickMove should still route capacitors into slot 2");

			// And back out: quickMove FROM the augment slot returns the module to the player.
			menu.quickMoveStack(player, BubbleShieldMenu.AUGMENT_SLOT);
			helper.assertTrue(
					menu.getSlot(BubbleShieldMenu.AUGMENT_SLOT).getItem().isEmpty(),
					"quickMove out of the augment slot should empty it");
			helper.assertTrue(
					player.getInventory().countItem(ModItems.REINFORCED_PLATING) == 1,
					"the extracted plating should land in the player inventory");
		} finally {
			MockPlayers.removeMockPlayer(helper, player);
		}

		helper.succeed();
	}

	/**
	 * (i) The augment slot's mayPlace accepts exactly the two defense modules and
	 * nothing else; the other device slots keep refusing the modules.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, padding = 16)
	public void augmentSlotMayPlaceGate(GameTestHelper helper) {
		placeProjector(helper, 4.0F);

		ServerPlayer player = MockPlayers.createUniqueMockPlayer(helper);
		try {
			Vec3 center = Vec3.atCenterOf(helper.absolutePos(PROJECTOR_POS));
			player.snapTo(center.x + 1.5, center.y - 0.5, center.z);
			helper.useBlock(PROJECTOR_POS, player);
			BubbleShieldMenu menu = (BubbleShieldMenu) player.containerMenu;

			var augmentSlot = menu.getSlot(BubbleShieldMenu.AUGMENT_SLOT);
			helper.assertTrue(augmentSlot.mayPlace(new ItemStack(ModItems.REINFORCED_PLATING)), "the augment slot should accept reinforced plating");
			helper.assertTrue(augmentSlot.mayPlace(new ItemStack(ModItems.BLAST_WARD)), "the augment slot should accept the blast ward");
			helper.assertTrue(!augmentSlot.mayPlace(new ItemStack(Items.COAL)), "the augment slot must refuse fuel items");
			helper.assertTrue(!augmentSlot.mayPlace(new ItemStack(Items.STONE)), "the augment slot must refuse arbitrary items");
			helper.assertTrue(!augmentSlot.mayPlace(new ItemStack(ModItems.FLUX_CAPACITOR)), "the augment slot must refuse the flux capacitor");
			helper.assertTrue(!augmentSlot.mayPlace(new ItemStack(ModItems.AEGIS_CORE)), "the augment slot must refuse upgrade cores");

			helper.assertTrue(!menu.getSlot(BubbleShieldMenu.FUEL_SLOT).mayPlace(new ItemStack(ModItems.REINFORCED_PLATING)),
					"the fuel slot must refuse augment modules");
			helper.assertTrue(!menu.getSlot(BubbleShieldMenu.CORE_SLOT).mayPlace(new ItemStack(ModItems.BLAST_WARD)),
					"the core slot must refuse augment modules");
			helper.assertTrue(!menu.getSlot(BubbleShieldMenu.CAPACITOR_SLOT).mayPlace(new ItemStack(ModItems.REINFORCED_PLATING)),
					"the capacitor slot must refuse augment modules");
		} finally {
			MockPlayers.removeMockPlayer(helper, player);
		}

		helper.succeed();
	}

	/**
	 * (j) Both modules ship as authored: registered (stack size 1), shaped recipes
	 * parse with the exact patterns (plating III/IOI/III with iron/obsidian; ward
	 * OBO/BNB/OBO with obsidian/brick/netherite scrap), the item-definition/model
	 * assets reuse the vanilla sprites, and the EN+DE tooltip keys exist (full
	 * key-set parity is enforced by EffectCatalogGameTests.langKeysComplete).
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void augmentRecipesAndRegistration(GameTestHelper helper) {
		helper.assertTrue(
				BuiltInRegistries.ITEM.getValue(Identifier.parse("bubbleshield:reinforced_plating")) == ModItems.REINFORCED_PLATING,
				"bubbleshield:reinforced_plating should resolve to the registered item");
		helper.assertTrue(
				BuiltInRegistries.ITEM.getValue(Identifier.parse("bubbleshield:blast_ward")) == ModItems.BLAST_WARD,
				"bubbleshield:blast_ward should resolve to the registered item");
		helper.assertTrue(new ItemStack(ModItems.REINFORCED_PLATING).getMaxStackSize() == 1, "the plating should stack to 1");
		helper.assertTrue(new ItemStack(ModItems.BLAST_WARD).getMaxStackSize() == 1, "the ward should stack to 1");

		JsonObject plating = readJsonResource(helper, "/data/bubbleshield/recipe/reinforced_plating.json");
		helper.assertTrue("minecraft:crafting_shaped".equals(plating.get("type").getAsString()),
				"the plating recipe should be minecraft:crafting_shaped");
		JsonObject platingKey = plating.getAsJsonObject("key");
		helper.assertTrue("minecraft:iron_ingot".equals(platingKey.get("I").getAsString()), "plating key I should be iron ingots");
		helper.assertTrue("minecraft:obsidian".equals(platingKey.get("O").getAsString()), "plating key O should be obsidian");
		assertPattern(helper, plating, "III", "IOI", "III");
		JsonObject platingResult = plating.getAsJsonObject("result");
		helper.assertTrue("bubbleshield:reinforced_plating".equals(platingResult.get("id").getAsString())
						&& platingResult.get("count").getAsInt() == 1,
				"the plating recipe should produce exactly 1 reinforced plating");

		JsonObject ward = readJsonResource(helper, "/data/bubbleshield/recipe/blast_ward.json");
		helper.assertTrue("minecraft:crafting_shaped".equals(ward.get("type").getAsString()),
				"the ward recipe should be minecraft:crafting_shaped");
		JsonObject wardKey = ward.getAsJsonObject("key");
		helper.assertTrue("minecraft:obsidian".equals(wardKey.get("O").getAsString()), "ward key O should be obsidian");
		helper.assertTrue("minecraft:brick".equals(wardKey.get("B").getAsString()), "ward key B should be bricks");
		helper.assertTrue("minecraft:netherite_scrap".equals(wardKey.get("N").getAsString()), "ward key N should be netherite scrap");
		assertPattern(helper, ward, "OBO", "BNB", "OBO");
		JsonObject wardResult = ward.getAsJsonObject("result");
		helper.assertTrue("bubbleshield:blast_ward".equals(wardResult.get("id").getAsString())
						&& wardResult.get("count").getAsInt() == 1,
				"the ward recipe should produce exactly 1 blast ward");

		// The item definitions point at mod models reusing vanilla sprites
		// (netherite scrap / nether brick), same pattern as the patch kit.
		JsonObject platingModel = readJsonResource(helper, "/assets/bubbleshield/models/item/reinforced_plating.json");
		helper.assertTrue(
				"minecraft:item/netherite_scrap".equals(platingModel.getAsJsonObject("textures").get("layer0").getAsString()),
				"the plating model should reuse the vanilla netherite scrap sprite");
		JsonObject wardModel = readJsonResource(helper, "/assets/bubbleshield/models/item/blast_ward.json");
		helper.assertTrue(
				"minecraft:item/nether_brick".equals(wardModel.getAsJsonObject("textures").get("layer0").getAsString()),
				"the ward model should reuse the vanilla nether brick sprite");

		JsonObject en = readJsonResource(helper, "/assets/bubbleshield/lang/en_us.json");
		JsonObject de = readJsonResource(helper, "/assets/bubbleshield/lang/de_de.json");
		for (String key : new String[] {
				"item.bubbleshield.reinforced_plating", "item.bubbleshield.reinforced_plating.tooltip",
				"item.bubbleshield.blast_ward", "item.bubbleshield.blast_ward.tooltip"}) {
			helper.assertTrue(en.has(key), "missing en_us lang key: " + key);
			helper.assertTrue(de.has(key), "missing de_de lang key: " + key);
		}

		helper.succeed();
	}

	private static void assertPattern(GameTestHelper helper, JsonObject recipe, String row0, String row1, String row2) {
		JsonArray pattern = recipe.getAsJsonArray("pattern");
		helper.assertTrue(
				pattern != null && pattern.size() == 3
						&& row0.equals(pattern.get(0).getAsString())
						&& row1.equals(pattern.get(1).getAsString())
						&& row2.equals(pattern.get(2).getAsString()),
				"the recipe pattern should be " + row0 + "/" + row1 + "/" + row2);
	}

	private static JsonObject readJsonResource(GameTestHelper helper, String path) {
		try (InputStream in = AugmentGameTests.class.getResourceAsStream(path)) {
			helper.assertTrue(in != null, "missing data pack resource: " + path);
			return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (Exception e) {
			throw helper.assertionException("failed to read/parse " + path + ": " + e);
		}
	}
}

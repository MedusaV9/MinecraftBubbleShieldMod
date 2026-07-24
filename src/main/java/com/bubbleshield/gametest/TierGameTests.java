package com.bubbleshield.gametest;

import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.menu.BubbleShieldMenu;
import com.bubbleshield.registry.ModBlocks;
import com.bubbleshield.registry.ModItems;
import com.bubbleshield.shield.ShieldState;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueInput;

/**
 * Coverage for milestone M4: upgrade cores driving the shield tier (max health,
 * fueled regeneration, persistence of the core slot).
 */
public class TierGameTests {
	private static final BlockPos PROJECTOR_POS = new BlockPos(4, 2, 4);
	private static final int PLENTY_OF_FUEL = 600;

	private static BubbleShieldBlockEntity placeProjector(GameTestHelper helper, float targetRadius) {
		helper.setBlock(PROJECTOR_POS, ModBlocks.BUBBLE_SHIELD_PROJECTOR);
		BubbleShieldBlockEntity be = helper.getBlockEntity(PROJECTOR_POS, BubbleShieldBlockEntity.class);
		be.getShieldState().targetRadius = targetRadius;
		return be;
	}

	/**
	 * The max-health table at diameter 8 (ShieldLogic.maxHealthFor:
	 * BASE_HP[tier] x (0.5 + 8/64)): tier 2 = 438, tier 3 (aegis) = 750, tier 0 = 125.
	 * Removing a core keeps the health FRACTION, not the absolute value.
	 */
	@GameTest(maxTicks = 100, padding = 16)
	public void tierRaisesMaxHealth(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		helper.assertTrue(be.tier() == 0, "an empty core slot should be tier 0");

		be.getCoreContainer().setItem(0, new ItemStack(ModItems.PRISMATIC_CORE));
		helper.assertTrue(be.tier() == 2, "a prismatic core should derive tier 2");

		// The block ticker applies the tier to maxHealth on the next serverTick.
		helper.runAfterDelay(2, () -> {
			helper.assertTrue(state.maxHealth == 438.0F, "tier 2 at diameter 8 should raise maxHealth to 438, got " + state.maxHealth);
			helper.assertTrue(
					be.getMenuData().get(BubbleShieldMenu.DATA_TIER) == 2,
					"the menu data slot should reflect tier 2");

			// Tier 3: the aegis core tops the table.
			be.getCoreContainer().setItem(0, new ItemStack(ModItems.AEGIS_CORE));
			helper.assertTrue(be.tier() == 3, "an aegis core should derive tier 3");
			helper.runAfterDelay(2, () -> {
				helper.assertTrue(state.maxHealth == 750.0F, "tier 3 at diameter 8 should raise maxHealth to 750, got " + state.maxHealth);
				helper.assertTrue(be.getMenuData().get(BubbleShieldMenu.DATA_TIER) == 3, "the menu data slot should reflect tier 3");

				// Damage to 1/3, then pull the core: maxHealth drops back to the
				// tier-0 125 and health maps by FRACTION (250/750 -> 125/3).
				state.health = 250.0F;
				be.getCoreContainer().setItem(0, ItemStack.EMPTY);
				helper.runAfterDelay(2, () -> {
					helper.assertTrue(state.maxHealth == 125.0F, "removing the core should restore maxHealth to 125, got " + state.maxHealth);
					float expectedHealth = 250.0F / 750.0F * 125.0F;
					helper.assertTrue(
							Math.abs(state.health - expectedHealth) < 0.01F,
							"health should map by fraction to " + expectedHealth + ", got " + state.health);
					helper.assertTrue(be.getMenuData().get(BubbleShieldMenu.DATA_TIER) == 0, "the menu data slot should reflect tier 0");
					helper.succeed();
				});
			});
		});
	}

	/**
	 * Inserting a core into a live shield raises maxHealth but must PRESERVE the health
	 * fraction (and therefore the current radius); only removing a core clamps health.
	 */
	@GameTest(maxTicks = 100, padding = 16)
	public void tierUpPreservesHealthFraction(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 8.0F);
		ShieldState state = be.getShieldState();
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");
		helper.assertTrue(be.currentRadius() == 8.0F, "a full-health shield should start at radius 8");

		// Full health, tier 0 -> 1: the fraction (1.0) is preserved exactly, so the
		// radius must not budge (pre-fix, health stayed low and the radius halved).
		// Tier 1 at diameter 16: 400 x (0.5 + 16/64) = 300.
		be.getCoreContainer().setItem(0, new ItemStack(ModItems.RESONANT_CORE));
		helper.runAfterDelay(2, () -> {
			helper.assertTrue(state.maxHealth == 300.0F, "tier 1 at diameter 16 should raise maxHealth to 300, got " + state.maxHealth);
			helper.assertTrue(state.health == 300.0F, "tier-up at full health should scale health to 300, got " + state.health);
			helper.assertTrue(be.currentRadius() == 8.0F, "tier-up must not shrink the live shield, radius is " + be.currentRadius());

			// Partial health, tier 1 -> 2: 200 raw is 150 after tier 1's 25% DR, so
			// health lands at 150/300 = 50% — below the 60% shrink plateau, so the
			// radius shrinks to 8 x (0.5/0.6) and must SURVIVE the tier-up unchanged.
			// Small tolerances absorb at most one regen pulse between the two ticks.
			be.applyShieldDamage(200.0F);
			float fracBefore = state.health / state.maxHealth;
			float radiusBefore = be.currentRadius();
			helper.assertTrue(radiusBefore < 8.0F, "a 50%-health shield should be below full radius, got " + radiusBefore);
			be.getCoreContainer().setItem(0, new ItemStack(ModItems.PRISMATIC_CORE));
			helper.runAfterDelay(2, () -> {
				helper.assertTrue(state.maxHealth == 525.0F, "tier 2 at diameter 16 should raise maxHealth to 525, got " + state.maxHealth);
				float fracAfter = state.health / state.maxHealth;
				helper.assertTrue(
						Math.abs(fracAfter - fracBefore) < 0.02F,
						"tier-up should preserve the health fraction: " + fracBefore + " -> " + fracAfter);
				helper.assertTrue(
						Math.abs(be.currentRadius() - radiusBefore) < 0.1F,
						"tier-up must not shrink the live shield: radius " + radiusBefore + " -> " + be.currentRadius());
				helper.succeed();
			});
		});
	}

	@GameTest(maxTicks = 200, padding = 16)
	public void shieldRegenerates(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();

		be.getCoreContainer().setItem(0, new ItemStack(ModItems.RESONANT_CORE));
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		// Let the first tick's max-health recompute land (tier 1 at diameter 8:
		// 400 x 0.625 = 250) BEFORE damaging, so the damaged value is exact.
		helper.runAfterDelay(2, () -> {
			helper.assertTrue(state.maxHealth == 250.0F, "tier 1 at diameter 8 should have maxHealth 250, got " + state.maxHealth);
			be.applyShieldDamage(30.0F);

			float damagedHealth = state.health;
			int fuelBefore = state.fuelSeconds;
			long startTime = helper.getLevel().getGameTime();
			// 30 raw is 22.5 after tier 1's 25% DR.
			helper.assertTrue(damagedHealth == 227.5F, "damaged health should be 227.5, got " + damagedHealth);

			// 90 ticks cover at least two 40-tick regen pulses; each pulse heals 3.0
			// (tier 1, in combat: the damage above opened the 200-tick combat gate)
			// and burns one fuel-second on top of the 1-per-20-ticks runtime drain.
			helper.runAfterDelay(90, () -> {
				helper.assertTrue(state.active, "shield should still be active");
				helper.assertTrue(
						state.health > damagedHealth,
						"a tier-1 fueled shield should regenerate above " + damagedHealth + ", got " + state.health);

				long elapsed = helper.getLevel().getGameTime() - startTime;
				int fuelUsed = fuelBefore - state.fuelSeconds;
				long runtimeBaseline = elapsed / 20 + 1;
				helper.assertTrue(
						fuelUsed > runtimeBaseline,
						"regen pulses should drain extra fuel: used " + fuelUsed + " over " + elapsed
								+ " ticks, runtime baseline " + runtimeBaseline);
				helper.succeed();
			});
		});
	}

	@GameTest(padding = 16)
	public void corePersistence(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.getCoreContainer().setItem(0, new ItemStack(ModItems.RESONANT_CORE));

		var registries = helper.getLevel().registryAccess();
		CompoundTag tag = be.saveCustomOnly(registries);
		helper.assertTrue(tag.contains("core_items"), "saved block entity NBT should include core_items");

		BubbleShieldBlockEntity loaded = new BubbleShieldBlockEntity(helper.absolutePos(PROJECTOR_POS), be.getBlockState());
		loaded.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
		helper.assertTrue(
				loaded.getCoreContainer().getItem(0).is(ModItems.RESONANT_CORE),
				"the core item should round-trip through NBT");
		helper.assertTrue(loaded.tier() == 1, "the loaded core should derive tier 1");
		helper.succeed();
	}
}

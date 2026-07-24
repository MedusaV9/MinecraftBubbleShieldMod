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
	 * Fix 1: every recompute keeps the ABSOLUTE health, clamped into [0, newMax] —
	 * raising the max grants no HP and removing a core clamps down what no longer
	 * fits. Only a fresh placement's very first recompute snaps to full.
	 */
	@GameTest(maxTicks = 100, padding = 16)
	public void tierRaisesMaxHealth(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		ShieldState state = be.getShieldState();
		helper.assertTrue(be.tier() == 0, "an empty core slot should be tier 0");

		be.getCoreContainer().setItem(0, new ItemStack(ModItems.PRISMATIC_CORE));
		helper.assertTrue(be.tier() == 2, "a prismatic core should derive tier 2");

		// The block ticker applies the tier to maxHealth on the next serverTick.
		// The core insert above landed BEFORE the first tick, so the fresh
		// placement's first recompute snaps health to the tier-2 full max.
		helper.runAfterDelay(2, () -> {
			helper.assertTrue(state.maxHealth == 438.0F, "tier 2 at diameter 8 should raise maxHealth to 438, got " + state.maxHealth);
			helper.assertTrue(state.health == 438.0F, "a fresh placement's first recompute snaps health to full, got " + state.health);
			helper.assertTrue(
					be.getMenuData().get(BubbleShieldMenu.DATA_TIER) == 2,
					"the menu data slot should reflect tier 2");

			// Tier 3: the aegis core tops the table — but the ABSOLUTE health
			// stays at 438 (fix 1: a bigger tank grants no free HP).
			be.getCoreContainer().setItem(0, new ItemStack(ModItems.AEGIS_CORE));
			helper.assertTrue(be.tier() == 3, "an aegis core should derive tier 3");
			helper.runAfterDelay(2, () -> {
				helper.assertTrue(state.maxHealth == 750.0F, "tier 3 at diameter 8 should raise maxHealth to 750, got " + state.maxHealth);
				helper.assertTrue(state.health == 438.0F, "tier-up keeps the absolute health (438), got " + state.health);
				helper.assertTrue(be.getMenuData().get(BubbleShieldMenu.DATA_TIER) == 3, "the menu data slot should reflect tier 3");

				// Set health to 250, then pull the core: maxHealth drops back to
				// the tier-0 125 and health CLAMPS to the new max (fix 1) —
				// the old fraction semantics would have mapped 250/750 -> 41.7.
				state.health = 250.0F;
				be.getCoreContainer().setItem(0, ItemStack.EMPTY);
				helper.runAfterDelay(2, () -> {
					helper.assertTrue(state.maxHealth == 125.0F, "removing the core should restore maxHealth to 125, got " + state.maxHealth);
					helper.assertTrue(state.health == 125.0F,
							"health should clamp to the new max (125), got " + state.health);
					helper.assertTrue(be.getMenuData().get(BubbleShieldMenu.DATA_TIER) == 0, "the menu data slot should reflect tier 0");
					helper.succeed();
				});
			});
		});
	}

	/**
	 * Fix 1 (exploit kill): inserting a core into a live shield raises maxHealth
	 * but keeps the ABSOLUTE current health — the old fraction preservation let a
	 * cheaply topped-up small shield resize into thousands of free HP. The health
	 * fraction (and with it the radius, once below the 60% plateau) drops instead.
	 */
	@GameTest(maxTicks = 100, padding = 16)
	public void tierUpKeepsAbsoluteHealth(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 8.0F);
		ShieldState state = be.getShieldState();
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");
		helper.assertTrue(be.currentRadius() == 8.0F, "a full-health shield should start at radius 8");

		// Let the first-tick recompute land (T0 D16: 200 x (0.5 + 16/64) = 150,
		// snapped to full — the one fresh-placement exception).
		helper.runAfterDelay(2, () -> {
			helper.assertTrue(state.maxHealth == 150.0F, "tier 0 at diameter 16 should compute maxHealth 150, got " + state.maxHealth);
			helper.assertTrue(state.health == 150.0F, "the fresh placement should start at full health, got " + state.health);

			// Full health, tier 0 -> 1 (diameter 16: 400 x 0.75 = 300): the
			// ABSOLUTE 150 HP is kept, so the fraction drops to 50% — below the
			// 60% shrink plateau — and the radius contracts to 8 x (0.5/0.6)
			// until the shield regenerates or is repaired.
			be.getCoreContainer().setItem(0, new ItemStack(ModItems.RESONANT_CORE));
			helper.runAfterDelay(2, () -> {
				helper.assertTrue(state.maxHealth == 300.0F, "tier 1 at diameter 16 should raise maxHealth to 300, got " + state.maxHealth);
				helper.assertTrue(state.health == 150.0F, "tier-up keeps the absolute health (150), got " + state.health);
				float expectedRadius = 8.0F * (0.5F / 0.6F);
				helper.assertTrue(
						Math.abs(be.currentRadius() - expectedRadius) < 0.05F,
						"the 50%-health shield should shrink to " + expectedRadius + ", got " + be.currentRadius());
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

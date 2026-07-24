package com.bubbleshield.gametest;

import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.menu.BubbleShieldMenu;
import com.bubbleshield.registry.ModBlocks;
import com.bubbleshield.registry.ModItems;
import com.bubbleshield.shield.ShieldLogic;
import com.bubbleshield.shield.ShieldMode;
import com.bubbleshield.shield.ShieldState;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;

/**
 * Coverage for milestone V7: the flux capacitor upgrade. While installed, the active
 * shield's passive drain halves and tier regeneration pulses no longer burn the extra
 * fuel-second. The combined passive-drain rule (with the V4 ECO mode) is: effective
 * drain interval in ticks = TICKS_PER_FUEL_SECOND * (eco ? 2 : 1) * (capacitor ? 2 : 1),
 * capped at 80 (see {@link ShieldLogic#drainIntervalTicks}).
 */
public class CapacitorGameTests {
	/**
	 * A dedicated (but otherwise vanilla-default) test environment,
	 * {@code data/bubbleshield/test_environment/capacitor.json}. Same rationale as
	 * {@code ColorGameTests.ISOLATED_ENVIRONMENT}: its own batch avoids reshuffling
	 * the shared default batch past the runner's 50-test cap. (Mock players are now
	 * uniquely named via {@link MockPlayers}, so PlayerList name collisions are gone.)
	 */
	private static final String ISOLATED_ENVIRONMENT = "bubbleshield:capacitor";
	private static final BlockPos PROJECTOR_POS = new BlockPos(4, 2, 4);
	private static final int PLENTY_OF_FUEL = 600;

	private static BubbleShieldBlockEntity placeProjector(GameTestHelper helper, BlockPos pos, float targetRadius) {
		helper.setBlock(pos, ModBlocks.BUBBLE_SHIELD_PROJECTOR);
		BubbleShieldBlockEntity be = helper.getBlockEntity(pos, BubbleShieldBlockEntity.class);
		be.getShieldState().targetRadius = targetRadius;
		return be;
	}

	private static void installCapacitor(BubbleShieldBlockEntity be) {
		be.getCapacitorContainer().setItem(0, new ItemStack(ModItems.FLUX_CAPACITOR));
	}

	/**
	 * (a) With a capacitor the passive drain halves: 1 fuel-second per 40 ticks instead
	 * of 1 per 20. Two projectors run side by side over the same 120-tick window,
	 * started right after an observed simultaneous drain (a 40-tick boundary, the only
	 * tick where both fuels drop at once), so the expected counts are exact.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 300, padding = 16)
	public void capacitorHalvesPassiveDrain(GameTestHelper helper) {
		BubbleShieldBlockEntity bare = placeProjector(helper, new BlockPos(2, 2, 2), 4.0F);
		bare.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(bare.tryActivate(), "the bare shield should activate");

		BubbleShieldBlockEntity upgraded = placeProjector(helper, new BlockPos(6, 2, 6), 4.0F);
		upgraded.addFuelSeconds(PLENTY_OF_FUEL);
		installCapacitor(upgraded);
		helper.assertTrue(upgraded.hasCapacitor(), "the installed capacitor should be detected");
		helper.assertTrue(upgraded.tryActivate(), "the upgraded shield should activate");

		ShieldState bareState = bare.getShieldState();
		ShieldState upgradedState = upgraded.getShieldState();

		// Tier 0, full health, DEFENSE: the ONLY fuel sink is the passive drain. Both
		// shields activated on the same tick, so their drain accumulators are aligned
		// and a tick where both fuels dropped is exactly a shared 40-tick boundary
		// (like ModeGameTests).
		int[] prevFuel = {-1, -1};
		int[] baseline = new int[2];
		long[] deadline = {-1L};
		helper.onEachTick(() -> {
			long now = helper.getLevel().getGameTime();
			if (deadline[0] < 0L) {
				boolean bothDropped = prevFuel[0] >= 0
						&& bareState.fuelSeconds < prevFuel[0]
						&& upgradedState.fuelSeconds < prevFuel[1];
				if (bothDropped) {
					deadline[0] = now + 120L;
					baseline[0] = bareState.fuelSeconds;
					baseline[1] = upgradedState.fuelSeconds;
				} else {
					prevFuel[0] = bareState.fuelSeconds;
					prevFuel[1] = upgradedState.fuelSeconds;
				}

				return;
			}

			if (now >= deadline[0]) {
				int bareUsed = baseline[0] - bareState.fuelSeconds;
				int upgradedUsed = baseline[1] - upgradedState.fuelSeconds;
				helper.assertTrue(
						bareUsed == 6,
						"a bare shield should drain 6 fuel-seconds over 120 ticks, used " + bareUsed);
				helper.assertTrue(
						upgradedUsed == 3,
						"a capacitor shield should drain 3 fuel-seconds over 120 ticks, used " + upgradedUsed);
				helper.assertTrue(
						upgradedUsed * 2 == bareUsed,
						"a capacitor must halve the passive drain: " + upgradedUsed + " vs " + bareUsed);
				helper.succeed();
			}
		});
	}

	/**
	 * (b) Capacitor + resonant core: regeneration still runs, but the pulses no longer
	 * burn the extra fuel-second, so the fuel use over an aligned 120-tick window equals
	 * the passive-drain baseline exactly (3 drains at the capacitor's 40-tick interval;
	 * without the capacitor the same window would cost 6 passive + 3 regen surcharges).
	 * Accounting style follows TierGameTests.shieldRegenerates, with the window aligned
	 * to an observed drain boundary so the baseline comparison is exact, not a bound.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 300, padding = 16)
	public void capacitorSkipsRegenSurcharge(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, PROJECTOR_POS, 4.0F);
		ShieldState state = be.getShieldState();
		be.getCoreContainer().setItem(0, new ItemStack(ModItems.RESONANT_CORE));
		installCapacitor(be);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");
		be.applyShieldDamage(30.0F);

		// With a capacitor the only fuel sink is the 40-tick passive drain (regen
		// pulses are fuel-free), so any observed fuel drop marks a 40-tick boundary.
		int[] prevFuel = {-1};
		int[] fuelBaseline = new int[1];
		float[] healthBaseline = new float[1];
		long[] deadline = {-1L};
		helper.onEachTick(() -> {
			long now = helper.getLevel().getGameTime();
			if (deadline[0] < 0L) {
				if (prevFuel[0] >= 0 && state.fuelSeconds < prevFuel[0]) {
					deadline[0] = now + 120L;
					fuelBaseline[0] = state.fuelSeconds;
					healthBaseline[0] = state.health;
				} else {
					prevFuel[0] = state.fuelSeconds;
				}

				return;
			}

			if (now >= deadline[0]) {
				helper.assertTrue(state.active, "shield should still be active");
				helper.assertTrue(
						state.health > healthBaseline[0],
						"a tier-1 fueled shield should regenerate above " + healthBaseline[0] + ", got " + state.health);

				int fuelUsed = fuelBaseline[0] - state.fuelSeconds;
				helper.assertTrue(
						fuelUsed == 3,
						"with a capacitor, regen pulses must not burn extra fuel: used " + fuelUsed
								+ " over 120 ticks, passive-drain baseline is 3");
				helper.succeed();
			}
		});
	}

	/**
	 * (c') Every device-slot change (capacitor, core, fuel — insert AND remove) marks
	 * the projector's chunk unsaved. SimpleContainer.setChanged() is a no-op in 26.2,
	 * so without the block entity hook an inactive projector's menu edits would never
	 * dirty the chunk: a capacitor could vanish on reload (inserted but never saved)
	 * or be duplicated (removed item kept in hand + resurrected from the stale save).
	 * The synchronous tryMarkSaved -> mutate -> isUnsaved probe is race-free: gametest
	 * bodies run on the server thread, so nothing else touches the chunk in between.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, padding = 16)
	public void deviceSlotChangesMarkChunkDirty(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, PROJECTOR_POS, 4.0F);
		LevelChunk chunk = helper.getLevel().getChunkAt(helper.absolutePos(PROJECTOR_POS));

		// Insert: the capacitor landing in the slot must dirty the chunk immediately.
		chunk.tryMarkSaved();
		be.getCapacitorContainer().setItem(0, new ItemStack(ModItems.FLUX_CAPACITOR));
		helper.assertTrue(chunk.isUnsaved(), "inserting a capacitor must mark the projector's chunk unsaved");

		// Simulated save while the capacitor sits in the slot: it must round-trip
		// without vanishing (the "lost on reload" half of the defect).
		var registries = helper.getLevel().registryAccess();
		CompoundTag tag = be.saveCustomOnly(registries);
		BubbleShieldBlockEntity reloaded = new BubbleShieldBlockEntity(helper.absolutePos(PROJECTOR_POS), be.getBlockState());
		reloaded.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
		helper.assertTrue(
				reloaded.getCapacitorContainer().getItem(0).is(ModItems.FLUX_CAPACITOR),
				"the capacitor must survive a save/load round-trip after a slot insert");

		// Remove: taking the capacitor out must dirty the chunk too, or a stale save
		// would resurrect it next load (the "duplicated" half of the defect).
		chunk.tryMarkSaved();
		be.getCapacitorContainer().removeItem(0, 1);
		helper.assertTrue(chunk.isUnsaved(), "removing the capacitor must mark the projector's chunk unsaved");
		BubbleShieldBlockEntity reloadedAfterRemove = new BubbleShieldBlockEntity(helper.absolutePos(PROJECTOR_POS), be.getBlockState());
		reloadedAfterRemove.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, registries, be.saveCustomOnly(registries)));
		helper.assertTrue(
				reloadedAfterRemove.getCapacitorContainer().getItem(0).isEmpty(),
				"a save taken after the removal must no longer resurrect the capacitor");

		// The fuel and core device slots share the same container hook.
		chunk.tryMarkSaved();
		be.getFuelContainer().setItem(0, new ItemStack(Items.COAL));
		helper.assertTrue(chunk.isUnsaved(), "inserting fuel must mark the projector's chunk unsaved");

		chunk.tryMarkSaved();
		be.getCoreContainer().setItem(0, new ItemStack(ModItems.RESONANT_CORE));
		helper.assertTrue(chunk.isUnsaved(), "inserting an upgrade core must mark the projector's chunk unsaved");
		helper.succeed();
	}

	/** (c) The capacitor slot round-trips through block entity NBT as capacitor_items. */
	@GameTest(environment = ISOLATED_ENVIRONMENT, padding = 16)
	public void capacitorPersistence(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, PROJECTOR_POS, 4.0F);
		installCapacitor(be);

		var registries = helper.getLevel().registryAccess();
		CompoundTag tag = be.saveCustomOnly(registries);
		helper.assertTrue(tag.contains("capacitor_items"), "saved block entity NBT should include capacitor_items");

		BubbleShieldBlockEntity loaded = new BubbleShieldBlockEntity(helper.absolutePos(PROJECTOR_POS), be.getBlockState());
		loaded.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
		helper.assertTrue(
				loaded.getCapacitorContainer().getItem(0).is(ModItems.FLUX_CAPACITOR),
				"the flux capacitor should round-trip through NBT");
		helper.assertTrue(loaded.hasCapacitor(), "the loaded block entity should report an installed capacitor");
		helper.succeed();
	}

	/**
	 * (d) quickMove routes a FLUX_CAPACITOR stack from the hotbar into slot 2, while
	 * cores still land in slot 1 and fuels in slot 0 (the inventory/hotbar index shift).
	 * The server-side menu is opened through a real block use, like ShieldGameTests.menuOpens.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, padding = 16)
	public void quickMoveRoutesCapacitor(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, PROJECTOR_POS, 4.0F);

		ServerPlayer player = MockPlayers.createUniqueMockPlayer(helper);
		try {
			Vec3 center = Vec3.atCenterOf(helper.absolutePos(PROJECTOR_POS));
			player.snapTo(center.x + 1.5, center.y - 0.5, center.z);
			helper.useBlock(PROJECTOR_POS, player);
			helper.assertTrue(player.containerMenu instanceof BubbleShieldMenu, "using the projector should open a BubbleShieldMenu");
			BubbleShieldMenu menu = (BubbleShieldMenu) player.containerMenu;

			// Hotbar slots 0..2 map to menu slots 31..33 (4 projector slots + 27 inventory).
			int hotbarStart = 4 + 27;
			player.getInventory().setItem(0, new ItemStack(ModItems.FLUX_CAPACITOR));
			player.getInventory().setItem(1, new ItemStack(ModItems.RESONANT_CORE));
			player.getInventory().setItem(2, new ItemStack(Items.COAL));

			menu.quickMoveStack(player, hotbarStart);
			helper.assertTrue(
					menu.getSlot(BubbleShieldMenu.CAPACITOR_SLOT).getItem().is(ModItems.FLUX_CAPACITOR),
					"quickMove should route the flux capacitor into slot 2");
			helper.assertTrue(
					be.getCapacitorContainer().getItem(0).is(ModItems.FLUX_CAPACITOR),
					"the routed capacitor should land in the block entity's capacitor container");
			helper.assertTrue(
					menu.getSlot(hotbarStart).getItem().isEmpty(),
					"the hotbar slot should be empty after the capacitor was moved");

			menu.quickMoveStack(player, hotbarStart + 1);
			helper.assertTrue(
					menu.getSlot(BubbleShieldMenu.CORE_SLOT).getItem().is(ModItems.RESONANT_CORE),
					"quickMove should still route cores into slot 1");

			menu.quickMoveStack(player, hotbarStart + 2);
			helper.assertTrue(
					menu.getSlot(BubbleShieldMenu.FUEL_SLOT).getItem().is(Items.COAL),
					"quickMove should still route fuels into slot 0");
		} finally {
			MockPlayers.removeMockPlayer(helper, player);
		}

		helper.succeed();
	}

	/**
	 * (e) The combined ECO+capacitor drain interval hits the 80-tick cap. Checked both
	 * through the pure helper (the full rule table) and live: an ECO shield with a
	 * capacitor drains exactly 2 fuel-seconds over an aligned 160-tick window.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 400, padding = 16)
	public void ecoCapacitorDrainIntervalCapped(GameTestHelper helper) {
		// Pure rule table: 20 plain, 40 with either modifier, 80 (the cap) with both.
		helper.assertTrue(ShieldLogic.drainIntervalTicks(false, false) == 20, "plain drain interval should be 20 ticks");
		helper.assertTrue(ShieldLogic.drainIntervalTicks(true, false) == 40, "ECO drain interval should be 40 ticks");
		helper.assertTrue(ShieldLogic.drainIntervalTicks(false, true) == 40, "capacitor drain interval should be 40 ticks");
		helper.assertTrue(
				ShieldLogic.drainIntervalTicks(true, true) == ShieldLogic.MAX_DRAIN_INTERVAL_TICKS
						&& ShieldLogic.drainIntervalTicks(true, true) == 80,
				"ECO+capacitor drain interval should hit the 80-tick cap");

		BubbleShieldBlockEntity be = placeProjector(helper, PROJECTOR_POS, 4.0F);
		ShieldState state = be.getShieldState();
		state.mode = ShieldMode.ECO;
		installCapacitor(be);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		// Tier 0 ECO: no regen, no pulse, so the only fuel sink is the capped 80-tick
		// passive drain; any observed drop marks an 80-tick boundary.
		int[] prevFuel = {-1};
		int[] baseline = new int[1];
		long[] deadline = {-1L};
		helper.onEachTick(() -> {
			long now = helper.getLevel().getGameTime();
			if (deadline[0] < 0L) {
				if (prevFuel[0] >= 0 && state.fuelSeconds < prevFuel[0]) {
					deadline[0] = now + 160L;
					baseline[0] = state.fuelSeconds;
				} else {
					prevFuel[0] = state.fuelSeconds;
				}

				return;
			}

			if (now >= deadline[0]) {
				int used = baseline[0] - state.fuelSeconds;
				helper.assertTrue(
						used == 2,
						"an ECO+capacitor shield should drain exactly 2 fuel-seconds over 160 ticks (80-tick cap), used " + used);
				helper.succeed();
			}
		});
	}
}

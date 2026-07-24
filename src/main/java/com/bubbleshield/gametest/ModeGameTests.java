package com.bubbleshield.gametest;

import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.effect.EffectRegistry;
import com.bubbleshield.registry.ModBlocks;
import com.bubbleshield.shield.ShieldLogic;
import com.bubbleshield.shield.ShieldMode;
import com.bubbleshield.shield.ShieldState;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * Coverage for milestone V4: shield modes (DEFENSE/PULSE/ECO) and the effect-cycle
 * toggle. PULSE zaps monsters inside the bubble, ECO halves the passive drain and
 * caps the radius at 0.75x, and cycleEffect re-rolls the effect id.
 */
public class ModeGameTests {
	private static final BlockPos PROJECTOR_POS = new BlockPos(4, 2, 4);
	private static final int PLENTY_OF_FUEL = 600;

	private static BubbleShieldBlockEntity placeProjector(GameTestHelper helper, BlockPos pos, float targetRadius) {
		helper.setBlock(pos, ModBlocks.BUBBLE_SHIELD_PROJECTOR);
		BubbleShieldBlockEntity be = helper.getBlockEntity(pos, BubbleShieldBlockEntity.class);
		be.getShieldState().targetRadius = targetRadius;
		return be;
	}

	/**
	 * Spawns a frozen (no-AI) zombie: it stays exactly where it was placed so the
	 * inside/outside classification is deterministic. The iron helmet absorbs
	 * daylight sun-burn ticks (the helmet takes durability, the zombie stays
	 * unhurt), so PULSE damage is the only thing that can drop its health.
	 */
	private static Zombie spawnFrozenZombie(GameTestHelper helper, Vec3 pos) {
		Zombie zombie = helper.spawn(EntityTypes.ZOMBIE, pos);
		zombie.setNoAi(true);
		zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
		return zombie;
	}

	/**
	 * PULSE zaps the zombie inside the bubble (magic damage, so the helmet does not
	 * reduce it) within ~80 ticks, while a zombie outside the radius -- but still
	 * within the mode's search AABB -- stays unhurt.
	 */
	@GameTest(maxTicks = 150, padding = 16)
	public void pulseZapsMonstersInside(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, PROJECTOR_POS, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		be.getShieldState().mode = ShieldMode.PULSE;
		helper.assertTrue(be.tryActivate(), "shield should activate");
		helper.assertTrue(be.currentRadius() == 4.0F, "PULSE must not change the radius, got " + be.currentRadius());

		// Shield center (relative) is at (4.5, 2.5, 4.5), radius 4. Both zombies sit in
		// the structure's own chunk column (only those chunks entity-tick in a game test):
		// one 2 blocks above the center (inside), one 7 blocks above (outside the sphere
		// but inside the pulse's search AABB, which spans radius + 4 blocks vertically).
		Zombie inside = spawnFrozenZombie(helper, new Vec3(4.5, 4.5, 4.5));
		Zombie outside = spawnFrozenZombie(helper, new Vec3(4.5, 9.5, 4.5));
		float insideStart = inside.getHealth();
		float outsideStart = outside.getHealth();
		int fuelBefore = be.getShieldState().fuelSeconds;

		// 80 ticks always cover at least one 60-tick pulse boundary.
		helper.runAfterDelay(80, () -> {
			try {
				helper.assertTrue(
						inside.getHealth() < insideStart,
						"a zombie inside a PULSE shield should have been zapped, health still " + inside.getHealth());
				helper.assertTrue(
						outside.getHealth() == outsideStart,
						"a zombie outside the bubble must stay unhurt, health is " + outside.getHealth());

				// Each pulse that hit >= 1 mob burns one extra fuel-second on top of the
				// 1-per-20-ticks runtime drain (80 ticks -> at most 5 passive drains).
				int fuelUsed = fuelBefore - be.getShieldState().fuelSeconds;
				helper.assertTrue(
						fuelUsed > 0 && fuelUsed <= 5 + 2,
						"pulse fuel usage out of range: used " + fuelUsed + " fuel-seconds over ~80 ticks");
			} finally {
				inside.discard();
				outside.discard();
			}

			helper.succeed();
		});
	}

	/**
	 * ECO passive drain is 1 fuel-second per 40 ticks -- at most half of DEFENSE's
	 * 1 per 20 -- and the current radius is capped at 0.75x. Two projectors run
	 * side by side over the same 100-tick window (started right after an observed
	 * simultaneous drain, i.e. a 40-tick boundary, so the expected counts are exact).
	 */
	@GameTest(maxTicks = 300, padding = 16)
	public void ecoHalvesDrainAndCapsRadius(GameTestHelper helper) {
		// Pure radius check: ECO caps currentRadius at 0.75x the normal value.
		ShieldState pure = new ShieldState();
		pure.active = true;
		pure.targetRadius = 8.0F;
		helper.assertTrue(ShieldLogic.currentRadius(pure) == 8.0F, "DEFENSE full-health radius should be 8");
		pure.mode = ShieldMode.ECO;
		helper.assertTrue(
				ShieldLogic.currentRadius(pure) == 8.0F * ShieldLogic.ECO_RADIUS_FACTOR,
				"ECO should cap the radius at 0.75x, got " + ShieldLogic.currentRadius(pure));
		// Fix 10: the 4-block MIN_RADIUS floor applies AFTER the ECO multiplier —
		// the old order let ECO undercut the floor to an effective 3 at target 4.
		pure.targetRadius = 4.0F;
		helper.assertTrue(
				ShieldLogic.currentRadius(pure) == ShieldLogic.MIN_RADIUS,
				"ECO at the minimum target radius must floor at 4, got " + ShieldLogic.currentRadius(pure));

		BubbleShieldBlockEntity defense = placeProjector(helper, new BlockPos(2, 2, 2), 4.0F);
		defense.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(defense.tryActivate(), "DEFENSE shield should activate");

		BubbleShieldBlockEntity eco = placeProjector(helper, new BlockPos(6, 2, 6), 4.0F);
		eco.addFuelSeconds(PLENTY_OF_FUEL);
		eco.getShieldState().mode = ShieldMode.ECO;
		helper.assertTrue(eco.tryActivate(), "ECO shield should activate");
		helper.assertTrue(
				eco.currentRadius() == ShieldLogic.MIN_RADIUS,
				"the live ECO shield radius must floor at 4 (fix 10), got " + eco.currentRadius());

		ShieldState defenseState = defense.getShieldState();
		ShieldState ecoState = eco.getShieldState();

		// Tier 0, full health, DEFENSE/ECO: the ONLY fuel sink is the passive drain.
		// Both shields activated on the same tick, so their drain accumulators are
		// aligned and a tick where both fuels dropped is exactly a shared 40-tick
		// boundary. Starting the window there makes the expected drain counts exact
		// regardless of test start time.
		int[] prevFuel = {-1, -1};
		int[] baseline = new int[2];
		long[] deadline = {-1L};
		helper.onEachTick(() -> {
			long now = helper.getLevel().getGameTime();
			if (deadline[0] < 0L) {
				boolean bothDropped = prevFuel[0] >= 0
						&& defenseState.fuelSeconds < prevFuel[0]
						&& ecoState.fuelSeconds < prevFuel[1];
				if (bothDropped) {
					deadline[0] = now + 100L;
					baseline[0] = defenseState.fuelSeconds;
					baseline[1] = ecoState.fuelSeconds;
				} else {
					prevFuel[0] = defenseState.fuelSeconds;
					prevFuel[1] = ecoState.fuelSeconds;
				}

				return;
			}

			if (now >= deadline[0]) {
				int defenseUsed = baseline[0] - defenseState.fuelSeconds;
				int ecoUsed = baseline[1] - ecoState.fuelSeconds;
				helper.assertTrue(
						defenseUsed == 5,
						"DEFENSE should drain 5 fuel-seconds over 100 ticks, used " + defenseUsed);
				helper.assertTrue(
						ecoUsed == 2,
						"ECO should drain 2 fuel-seconds over 100 ticks, used " + ecoUsed);
				helper.assertTrue(
						ecoUsed * 2 <= defenseUsed,
						"ECO must drain at most half of DEFENSE: " + ecoUsed + " vs " + defenseUsed);
				helper.succeed();
			}
		});
	}

	/** cycleEffect is pure and always yields a valid id different from the current one. */
	@GameTest
	public void cycleEffectPure(GameTestHelper helper) {
		RandomSource random = RandomSource.create(42L);
		ShieldState state = new ShieldState();
		state.effectId = 7;

		int previous = state.effectId;
		for (int i = 0; i < 200; i++) {
			ShieldLogic.cycleEffect(state, random);
			helper.assertTrue(
					state.effectId >= 0 && state.effectId < EffectRegistry.COUNT,
					"cycled effect id out of range at iteration " + i + ": " + state.effectId);
			helper.assertTrue(
					state.effectId != previous,
					"cycled effect id must differ from the current one at iteration " + i + ": " + state.effectId);
			previous = state.effectId;
		}

		helper.succeed();
	}

	/** Mode and the effect-cycle toggle persist through NBT, with clamped defaults. */
	@GameTest
	public void modeCycleNbtRoundTrip(GameTestHelper helper) {
		var registries = helper.getLevel().registryAccess();

		for (ShieldMode mode : ShieldMode.values()) {
			ShieldState original = new ShieldState();
			original.mode = mode;
			original.cycleEffect = mode == ShieldMode.PULSE;

			TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
			original.save(output);
			CompoundTag tag = output.buildResult();

			ShieldState loaded = new ShieldState();
			loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
			helper.assertTrue(loaded.mode == mode, "mode " + mode + " should round-trip, got " + loaded.mode);
			helper.assertTrue(
					loaded.cycleEffect == original.cycleEffect,
					"cycleEffect should round-trip for mode " + mode);
		}

		// Loading a tag without the new fields (pre-V4 data) defaults to DEFENSE + off.
		ShieldState legacy = new ShieldState();
		legacy.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, new CompoundTag()));
		helper.assertTrue(legacy.mode == ShieldMode.DEFENSE, "a missing mode tag should default to DEFENSE");
		helper.assertTrue(!legacy.cycleEffect, "a missing cycle_effect tag should default to off");

		// byOrdinal clamps garbage ordinals back to DEFENSE (mirrors ShieldShape).
		helper.assertTrue(ShieldMode.byOrdinal(-1) == ShieldMode.DEFENSE, "byOrdinal(-1) should clamp to DEFENSE");
		helper.assertTrue(ShieldMode.byOrdinal(99) == ShieldMode.DEFENSE, "byOrdinal(99) should clamp to DEFENSE");
		helper.succeed();
	}
}

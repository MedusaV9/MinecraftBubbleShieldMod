package com.bubbleshield.gametest;

import java.util.List;
import java.util.Optional;

import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.net.ShieldPayloads;
import com.bubbleshield.registry.ModBlocks;
import com.bubbleshield.shield.ShieldState;

import io.netty.buffer.Unpooled;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/**
 * NBT-load hardening: persisted data is UNTRUSTED (players can edit it via /data or
 * external NBT editors), so {@link ShieldState#load} and the block entity's
 * {@code loadAdditional} must sanitize/validate on the way in. Covers the custom
 * name cap (a &gt;32-char name would throw an EncoderException in ShieldSyncS2C's
 * {@code stringUtf8(32)} codec on every broadcast, breaking sync for the whole
 * level) and the legacy pre-"powered" save re-seed (an absent key must not count
 * as an observed-false level, or a steady redstone source next to the projector
 * would be misread as a rising edge on the first neighbor update).
 */
public class NbtHardeningGameTests {
	/**
	 * A dedicated (but otherwise vanilla-default) test environment,
	 * {@code data/bubbleshield/test_environment/nbt_hardening.json}. The vanilla
	 * runner batches tests by environment (50 per batch, ticked in parallel);
	 * keeping this class out of the shared default batch avoids reshuffling the
	 * pre-existing suite (see ColorGameTests.ISOLATED_ENVIRONMENT for the full story).
	 */
	private static final String ISOLATED_ENVIRONMENT = "bubbleshield:nbt_hardening";
	private static final BlockPos PROJECTOR_POS = new BlockPos(4, 2, 4);
	private static final int PLENTY_OF_FUEL = 600;

	/**
	 * (a) A 40-char custom_name edited into the NBT is capped to 32 chars (and
	 * control/section characters are stripped) on load, and the loaded state encodes
	 * through the full ShieldSyncS2C stream codec without throwing. Also pins the
	 * defect mechanism: an UNSANITIZED 40-char name makes the codec throw.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void oversizedCustomNameSanitizedOnLoad(GameTestHelper helper) {
		var registries = helper.getLevel().registryAccess();

		ShieldState original = new ShieldState();
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
		original.save(output);
		CompoundTag tag = output.buildResult();
		// Simulate /data or NBT-editor tampering: 40 chars, above the 32-char cap.
		String oversized = "N".repeat(40);
		tag.putString("custom_name", oversized);

		ShieldState loaded = new ShieldState();
		loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
		helper.assertTrue(
				loaded.customName.length() <= ShieldState.MAX_NAME_LENGTH,
				"a 40-char custom_name must be capped on load, got length " + loaded.customName.length());
		helper.assertTrue(
				loaded.customName.equals("N".repeat(ShieldState.MAX_NAME_LENGTH)),
				"the capped name should be the first 32 chars, got '" + loaded.customName + "'");

		// Control/formatting characters smuggled into the NBT are stripped too.
		tag.putString("custom_name", "Home\u00A7cBase\n\u007F");
		ShieldState filtered = new ShieldState();
		filtered.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
		helper.assertTrue(
				"HomecBase".equals(filtered.customName),
				"section/control characters must be stripped on load, got '" + filtered.customName + "'");

		// The loaded (sanitized) state must survive the bounded sync codec round-trip.
		ShieldPayloads.ShieldSyncS2C payload = syncPayload(helper, loaded.customName);
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
		try {
			ShieldPayloads.ShieldSyncS2C.CODEC.encode(buffer, payload);
			ShieldPayloads.ShieldSyncS2C decoded = ShieldPayloads.ShieldSyncS2C.CODEC.decode(buffer);
			helper.assertTrue(
					decoded.customName().equals(loaded.customName),
					"the sanitized name should round-trip through ShieldSyncS2C, got '" + decoded.customName() + "'");
			helper.assertTrue(buffer.readableBytes() == 0, "the codec should consume the whole buffer");
		} finally {
			buffer.release();
		}

		// Defect mechanism pin: WITHOUT the load sanitization the oversized name
		// blows up stringUtf8(32) on encode — i.e. on every shield broadcast.
		RegistryFriendlyByteBuf poison = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
		boolean threw = false;
		try {
			ShieldPayloads.ShieldSyncS2C.CODEC.encode(poison, syncPayload(helper, oversized));
		} catch (Exception expected) {
			threw = true;
		} finally {
			poison.release();
		}

		helper.assertTrue(threw, "encoding an unsanitized 40-char name must throw; load sanitization exists to prevent exactly this");
		helper.succeed();
	}

	/** A minimal, otherwise-valid sync payload carrying {@code customName}. */
	private static ShieldPayloads.ShieldSyncS2C syncPayload(GameTestHelper helper, String customName) {
		return new ShieldPayloads.ShieldSyncS2C(
			new BlockPos(0, 64, 0),
			helper.getLevel().dimension(),
			new ShieldPayloads.ShieldVisual(false, 0, ShieldState.DEFAULT_TARGET_RADIUS, 0.0F, 1.0F, 0, 0, ShieldState.NO_COLOR_OVERRIDE),
			List.of(),
			List.of(),
			0,
			Optional.empty(),
			customName
		);
	}

	/**
	 * (b) A legacy (pre-"powered") save loaded next to a steady redstone source
	 * re-seeds from the live signal on the first tick instead of treating the absent
	 * key as observed-false: the next neighbor update reporting the same steady
	 * level must NOT be misread as a rising edge (no spurious activation).
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void legacyPoweredNbtReseedsFromLiveSignal(GameTestHelper helper) {
		// The steady source exists BEFORE the projector, so no edge ever happened.
		helper.setBlock(PROJECTOR_POS.east(), Blocks.REDSTONE_BLOCK);
		helper.setBlock(PROJECTOR_POS, ModBlocks.BUBBLE_SHIELD_PROJECTOR);
		BubbleShieldBlockEntity be = helper.getBlockEntity(PROJECTOR_POS, BubbleShieldBlockEntity.class);
		be.getShieldState().targetRadius = 4.0F;
		be.addFuelSeconds(PLENTY_OF_FUEL);

		// Simulate loading a pre-v3 save: strip the "powered" key and re-load the
		// live block entity from it (fuel included, so a false rising edge WOULD
		// activate and be caught below).
		var registries = helper.getLevel().registryAccess();
		CompoundTag legacyTag = be.saveCustomOnly(registries);
		legacyTag.remove("powered");
		be.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, registries, legacyTag));

		// Give serverTick a tick to re-seed from the live neighbor signal.
		helper.runAfterDelay(2, () -> {
			helper.assertTrue(
					be.isPowered(),
					"a legacy load next to a redstone block should re-seed powered=true from the live signal");
			helper.assertTrue(
					!be.getShieldState().active,
					"re-seeding must observe the signal WITHOUT acting on it");

			// The first neighbor update after the legacy load reports the same
			// steady level; with the pre-fix observed-false default this was a
			// spurious rising edge that activated the shield.
			be.setNeighborPowered(true);
			helper.assertTrue(
					!be.getShieldState().active,
					"a steady redstone level after a legacy load must not be misread as a rising edge");

			// A modern save DOES record the level; absent-key handling must not
			// regress the normal reload path (persisted level counts as observed).
			CompoundTag modernTag = be.saveCustomOnly(registries);
			helper.assertTrue(
					modernTag.getBooleanOr("powered", false),
					"a modern save should persist the observed powered=true level");
			helper.succeed();
		});
	}
}

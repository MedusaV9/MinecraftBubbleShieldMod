package com.bubbleshield.gametest;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.effect.EffectRegistry;
import com.bubbleshield.menu.BubbleShieldMenu;
import com.bubbleshield.net.ShieldPayloads;
import com.bubbleshield.registry.ModBlocks;
import com.bubbleshield.shield.ShieldLogic;
import com.bubbleshield.shield.ShieldState;

import io.netty.buffer.Unpooled;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * NBT-load hardening: persisted data is UNTRUSTED (players can edit it via /data or
 * external NBT editors), so {@link ShieldState#load} and the block entity's
 * {@code loadAdditional} must sanitize/validate on the way in. Covers the custom
 * name cap (a &gt;32-char name would throw an EncoderException in ShieldSyncS2C's
 * {@code stringUtf8(32)} codec on every broadcast, breaking sync for the whole
 * level), the effect_id range clamp, and the legacy pre-"powered" save re-seed
 * (an absent key must not count as an observed-false level, or a steady redstone
 * source next to the projector would be misread as a rising edge on the first
 * neighbor update). Also hosts the WP1 hardening coverage: whitelist load
 * cap/sanitization, fuel/cooldown load clamps (incl. the first-tick cooldown
 * cap), 16-bit-safe menu data slots under absurd loaded health, the same-tick
 * projectile volley radius recompute, and the collision-free expulsion probe.
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

	/**
	 * An out-of-range effect_id edited into the NBT is clamped to
	 * [0, EffectRegistry.COUNT - 1] on load (mirroring the custom_name/
	 * color_override hardening): EffectRegistry.get() clamps on read, but a raw
	 * out-of-range id would bias ShieldLogic.cycleEffect's re-roll and feed
	 * unclamped values into the advancement criteria.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void outOfRangeEffectIdClampedOnLoad(GameTestHelper helper) {
		var registries = helper.getLevel().registryAccess();

		ShieldState original = new ShieldState();
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
		original.save(output);
		CompoundTag tag = output.buildResult();

		tag.putInt("effect_id", EffectRegistry.COUNT + 9649);
		ShieldState tooHigh = new ShieldState();
		tooHigh.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
		helper.assertTrue(
				tooHigh.effectId == EffectRegistry.COUNT - 1,
				"an above-range effect_id must clamp to COUNT - 1, got " + tooHigh.effectId);

		tag.putInt("effect_id", -5);
		ShieldState negative = new ShieldState();
		negative.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
		helper.assertTrue(negative.effectId == 0, "a negative effect_id must clamp to 0, got " + negative.effectId);

		// In-range ids load unchanged (both range edges).
		tag.putInt("effect_id", EffectRegistry.COUNT - 1);
		ShieldState maxValid = new ShieldState();
		maxValid.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
		helper.assertTrue(
				maxValid.effectId == EffectRegistry.COUNT - 1,
				"the max valid effect_id must load unchanged, got " + maxValid.effectId);

		tag.putInt("effect_id", 42);
		ShieldState valid = new ShieldState();
		valid.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
		helper.assertTrue(valid.effectId == 42, "a valid effect_id must load unchanged, got " + valid.effectId);

		helper.succeed();
	}

	/** A minimal, otherwise-valid sync payload carrying {@code customName}. */
	private static ShieldPayloads.ShieldSyncS2C syncPayload(GameTestHelper helper, String customName) {
		return syncPayload(helper, customName, List.of());
	}

	/** A minimal, otherwise-valid sync payload carrying {@code customName} and {@code whitelistNames}. */
	private static ShieldPayloads.ShieldSyncS2C syncPayload(GameTestHelper helper, String customName, List<String> whitelistNames) {
		return new ShieldPayloads.ShieldSyncS2C(
			new BlockPos(0, 64, 0),
			helper.getLevel().dimension(),
			new ShieldPayloads.ShieldVisual(false, 0, ShieldState.DEFAULT_TARGET_RADIUS, 0.0F, 1.0F, 0.0F, 0, 0, ShieldState.NO_COLOR_OVERRIDE, 0),
			List.of(),
			whitelistNames,
			0,
			Optional.empty(),
			customName
		);
	}

	/**
	 * (c) 100 whitelist entries edited into the NBT — 70 valid plus 30 garbage
	 * (space-containing, oversized, control-character) names — load back capped at
	 * {@link ShieldState#MAX_WHITELIST_SIZE} with every survivor passing the same
	 * name-validity rule the C2S add path enforces. 100 whitelist UUIDs cap the
	 * same way, and the loaded name set survives ShieldSyncS2C's bounded
	 * stringUtf8(16) per-name codec — which an UNSANITIZED oversized name breaks.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void oversizedWhitelistCappedAndSanitizedOnLoad(GameTestHelper helper) {
		var registries = helper.getLevel().registryAccess();

		ShieldState original = new ShieldState();
		for (int i = 0; i < 70; i++) {
			original.whitelistNames.add("Player" + i);
		}
		for (int i = 0; i < 20; i++) {
			original.whitelistNames.add("Bad Name" + i); // spaces are invalid in player names
		}
		for (int i = 0; i < 5; i++) {
			original.whitelistNames.add("W".repeat(17 + i)); // above the 16-char limit
		}
		for (int i = 0; i < 5; i++) {
			original.whitelistNames.add("Ctrl\u00A7Name" + i); // section sign (>= 127) is invalid
		}
		helper.assertTrue(original.whitelistNames.size() == 100, "test setup: expected 100 raw names");
		for (int i = 0; i < 100; i++) {
			original.whitelistUuids.add(java.util.UUID.randomUUID());
		}

		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
		original.save(output);
		CompoundTag tag = output.buildResult();

		ShieldState loaded = new ShieldState();
		loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
		helper.assertTrue(
				loaded.whitelistNames.size() == ShieldState.MAX_WHITELIST_SIZE,
				"70 valid names must cap at " + ShieldState.MAX_WHITELIST_SIZE + ", got " + loaded.whitelistNames.size());
		for (String name : loaded.whitelistNames) {
			helper.assertTrue(
					net.minecraft.util.StringUtil.isValidPlayerName(name),
					"every loaded whitelist name must pass the C2S validity rule, got '" + name + "'");
			helper.assertTrue(name.startsWith("Player"), "garbage names must be dropped on load, got '" + name + "'");
		}

		helper.assertTrue(
				loaded.whitelistUuids.size() == ShieldState.MAX_WHITELIST_SIZE,
				"100 whitelist UUIDs must cap at " + ShieldState.MAX_WHITELIST_SIZE + ", got " + loaded.whitelistUuids.size());

		// The loaded (sanitized) name set must survive the bounded sync codec.
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
		try {
			ShieldPayloads.ShieldSyncS2C payload = syncPayload(helper, "", List.copyOf(loaded.whitelistNames));
			ShieldPayloads.ShieldSyncS2C.CODEC.encode(buffer, payload);
			ShieldPayloads.ShieldSyncS2C decoded = ShieldPayloads.ShieldSyncS2C.CODEC.decode(buffer);
			helper.assertTrue(
					Set.copyOf(decoded.whitelistNames()).equals(loaded.whitelistNames),
					"the sanitized whitelist should round-trip through ShieldSyncS2C");
		} finally {
			buffer.release();
		}

		// Defect mechanism pin: WITHOUT the load sanitization an oversized name
		// blows up the bounded stringUtf8(16) name codec on every shield broadcast.
		RegistryFriendlyByteBuf poison = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
		boolean threw = false;
		try {
			ShieldPayloads.ShieldSyncS2C.CODEC.encode(poison, syncPayload(helper, "", List.of("W".repeat(17))));
		} catch (Exception expected) {
			threw = true;
		} finally {
			poison.release();
		}

		helper.assertTrue(threw, "encoding an unsanitized 17-char whitelist name must throw; load sanitization prevents exactly this");
		helper.succeed();
	}

	/**
	 * (d) fuel_seconds/cooldown_until load clamps, plus the first-tick cooldown cap:
	 * a cooldown_until edited absurdly far into the future is capped at the maximum
	 * possible break cooldown on the first server tick after load (ShieldState.load
	 * has no game time, so the block entity applies the cap when the level clock is
	 * available). The tick-accumulator fields clamp to their firing thresholds too.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void fuelAndCooldownClampedOnLoad(GameTestHelper helper) {
		var registries = helper.getLevel().registryAccess();

		ShieldState original = new ShieldState();
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
		original.save(output);
		CompoundTag tag = output.buildResult();

		tag.putInt("fuel_seconds", Integer.MAX_VALUE);
		tag.putLong("cooldown_until", -12345L);
		tag.putInt("drain_accum", 999999);
		tag.putInt("regen_accum", 999999);
		ShieldState loaded = new ShieldState();
		loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
		helper.assertTrue(
				loaded.fuelSeconds == ShieldState.MAX_LOADED_FUEL_SECONDS,
				"a huge fuel_seconds must clamp to " + ShieldState.MAX_LOADED_FUEL_SECONDS + ", got " + loaded.fuelSeconds);
		helper.assertTrue(loaded.cooldownUntil == 0L, "a negative cooldown_until must clamp to 0, got " + loaded.cooldownUntil);
		helper.assertTrue(
				loaded.drainAccum <= ShieldLogic.MAX_DRAIN_INTERVAL_TICKS,
				"drain_accum must clamp to its firing threshold, got " + loaded.drainAccum);
		helper.assertTrue(
				loaded.regenAccum <= ShieldLogic.REGEN_PERIOD_TICKS,
				"regen_accum must clamp to its firing threshold, got " + loaded.regenAccum);

		tag.putInt("fuel_seconds", -50);
		ShieldState negative = new ShieldState();
		negative.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
		helper.assertTrue(negative.fuelSeconds == 0, "a negative fuel_seconds must clamp to 0, got " + negative.fuelSeconds);

		// First-tick cap: a live projector re-loaded with an absurd future cooldown.
		helper.setBlock(PROJECTOR_POS, ModBlocks.BUBBLE_SHIELD_PROJECTOR);
		BubbleShieldBlockEntity be = helper.getBlockEntity(PROJECTOR_POS, BubbleShieldBlockEntity.class);
		CompoundTag beTag = be.saveCustomOnly(registries);
		beTag.putLong("cooldown_until", helper.getLevel().getGameTime() + 100_000_000L);
		be.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, registries, beTag));

		helper.runAfterDelay(2, () -> {
			long remaining = be.getShieldState().cooldownUntil - helper.getLevel().getGameTime();
			helper.assertTrue(
					remaining <= ShieldLogic.breakCooldownTicks(0),
					"the first tick after load must cap the remaining cooldown at "
							+ ShieldLogic.breakCooldownTicks(0) + " ticks, got " + remaining);
			helper.assertTrue(remaining > 0, "the capped cooldown should still be pending, got " + remaining);
			helper.succeed();
		});
	}

	/**
	 * (e) A health/max_health of 1e9 edited into the NBT keeps every menu data slot
	 * 16-bit safe: health syncs as permille (&le; 1000, never the old health*10
	 * encoding that overflowed above 3276.7 HP), max health caps at 32767, and no
	 * slot ever goes negative. Read synchronously after load, before the max-health
	 * refresh on the next tick re-derives maxHealth — and then the refresh must WIN:
	 * the canonical model overrides the tamper, mapping the huge health by (clamped)
	 * fraction to full.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 100, padding = 16)
	public void hugeLoadedHealthKeepsMenuDataSafe(GameTestHelper helper) {
		helper.setBlock(PROJECTOR_POS, ModBlocks.BUBBLE_SHIELD_PROJECTOR);
		BubbleShieldBlockEntity be = helper.getBlockEntity(PROJECTOR_POS, BubbleShieldBlockEntity.class);

		var registries = helper.getLevel().registryAccess();
		CompoundTag tag = be.saveCustomOnly(registries);
		tag.putFloat("max_health", 1.0e9F);
		tag.putFloat("health", 1.0e9F);
		be.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));

		ShieldState state = be.getShieldState();
		helper.assertTrue(
				state.maxHealth == ShieldState.MAX_LOADED_HEALTH,
				"max_health should clamp to MAX_LOADED_HEALTH on load, got " + state.maxHealth);

		int permille = be.getMenuData().get(BubbleShieldMenu.DATA_HEALTH_PERMILLE);
		helper.assertTrue(
				permille >= 0 && permille <= 1000,
				"DATA_HEALTH_PERMILLE must stay in [0, 1000] at 1e6 HP, got " + permille);
		int maxHealth = be.getMenuData().get(BubbleShieldMenu.DATA_MAX_HEALTH);
		helper.assertTrue(
				maxHealth == Short.MAX_VALUE,
				"DATA_MAX_HEALTH must be min'd at 32767 for a 1e6 max health, got " + maxHealth);

		// 16-bit safety across the whole frozen layout: no slot may be negative or
		// above Short.MAX_VALUE (data slots replicate as 16-bit signed values).
		for (int slot = 0; slot < BubbleShieldMenu.DATA_COUNT; slot++) {
			int value = be.getMenuData().get(slot);
			helper.assertTrue(
					value >= 0 && value <= Short.MAX_VALUE,
					"menu data slot " + slot + " must stay 16-bit safe, got " + value);
		}

		// First tick after load: the canonical recompute overrides the tampered
		// max_health entirely (tier 0 at the default diameter 32 -> 200) and the
		// huge loaded health maps by its clamped fraction (1.0) to full.
		helper.runAfterDelay(2, () -> {
			float expected = ShieldLogic.maxHealthFor(0, state.targetRadius, 100);
			helper.assertTrue(
					state.maxHealth == expected,
					"the first-tick recompute must override the tampered max_health with " + expected + ", got " + state.maxHealth);
			helper.assertTrue(
					state.health == expected,
					"the huge loaded health must map by fraction to full (" + expected + "), got " + state.health);
			helper.succeed();
		});
	}

	/**
	 * (f) Two arrows crossing the boundary on the same tick: the first hit shrinks
	 * the shield, and the second arrow must be tested against the POST-shrink
	 * radius. The shield starts pre-damaged BELOW the 60% shrink plateau (85/150:
	 * boundary 8 x (85/150)/0.6 = 7.556) so a 3-damage arrow still moves the
	 * boundary (82/150 -> 7.289). Both arrows end the crossing tick ~7.41 blocks
	 * from the center — inside the pre-hit boundary but outside the post-first-hit
	 * one — so exactly one may be absorbed. With the stale radius cached before the
	 * interception loop, both were.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void volleySecondArrowSeesShrunkRadius(GameTestHelper helper) {
		helper.setBlock(PROJECTOR_POS, ModBlocks.BUBBLE_SHIELD_PROJECTOR);
		BubbleShieldBlockEntity be = helper.getBlockEntity(PROJECTOR_POS, BubbleShieldBlockEntity.class);
		ShieldState state = be.getShieldState();
		state.targetRadius = 8.0F;
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");
		helper.assertTrue(be.currentRadius() == 8.0F, "shield radius should start at 8");

		// A sequence instead of a runAfterDelay-nested onEachTick: onEachTick
		// registers one map entry per remaining tick, and entries added from WITHIN
		// another tick callback can be missed by the in-progress iteration — the
		// vanilla runner then fires TWO of them on the next tick, re-running the
		// asserts right after this test's own cleanup discards the surviving arrow.
		// thenWaitUntil/thenExecute run their closing asserts exactly once.
		Arrow[] arrows = new Arrow[2];
		helper.startSequence()
				// Let the first tick's recompute land (tier 0 at diameter 16: 150),
				// then pre-damage below the plateau and launch the volley.
				.thenExecuteAfter(2, () -> {
					helper.assertTrue(state.maxHealth == 150.0F, "tier 0 at diameter 16 should have maxHealth 150, got " + state.maxHealth);
					state.health = 85.0F;
					double boundary = be.currentRadius();
					helper.assertTrue(
							Math.abs(boundary - 8.0 * (85.0 / 150.0) / 0.6) < 0.01,
							"the pre-damaged shield should sit below the plateau at ~7.556, got " + boundary);

					// Both dive straight down in lockstep (no gravity, 1.2 blocks/tick),
					// from ~8.61 blocks above the center to ~7.41: one shared
					// outside->inside crossing tick, both ending in the (7.289, 7.556)
					// shell. Tier 0 with the combat gate untouched, but the first regen
					// pulse is 40 ticks away — long after this test succeeds — so no
					// regen can mask the accounting.
					arrows[0] = helper.spawn(EntityTypes.ARROW, new Vec3(4.5, 2.5 + 8.6, 4.1));
					arrows[1] = helper.spawn(EntityTypes.ARROW, new Vec3(4.5, 2.5 + 8.6, 4.9));
					for (Arrow arrow : arrows) {
						arrow.setNoGravity(true);
						arrow.setDeltaMovement(0.0, -1.2, 0.0);
					}
				})
				// The poll runs every tick, so the first passing check observes the
				// interception tick itself — before the survivor's next move.
				.thenWaitUntil(() -> helper.assertTrue(state.health != 85.0F, "waiting for the volley's crossing tick"))
				.thenExecute(() -> {
					// Exactly one absorb (3 damage) may have landed — the survivor sat
					// outside the recomputed 7.289 boundary.
					helper.assertTrue(
							state.health == 82.0F,
							"only ONE arrow of the same-tick volley may be absorbed, health is " + state.health);
					boolean firstAlive = !arrows[0].isRemoved();
					boolean secondAlive = !arrows[1].isRemoved();
					helper.assertTrue(
							firstAlive != secondAlive,
							"exactly one arrow should survive the volley (first=" + firstAlive + ", second=" + secondAlive + ")");
					// Stop the survivor before its continued flight crosses the shrunk boundary.
					(firstAlive ? arrows[0] : arrows[1]).discard();
				})
				.thenSucceed();
	}

	/**
	 * (g) The barrier expulsion probes for a collision-free landing spot: with a
	 * wall hugging the bubble along the pushback direction, the naive target sits
	 * inside the stone, but the expelled player must end up outside the shield in
	 * a spot where their bounding box collides with nothing.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, padding = 16)
	public void expulsionAgainstWallFindsFreeSpot(GameTestHelper helper) {
		// Projector at the -X edge so the +X pushback target (center + radius +
		// 0.75 = relative x 5.25) and the probe offsets stay inside the structure.
		BlockPos projectorPos = new BlockPos(0, 2, 4);
		helper.setBlock(projectorPos, ModBlocks.BUBBLE_SHIELD_PROJECTOR);
		BubbleShieldBlockEntity be = helper.getBlockEntity(projectorPos, BubbleShieldBlockEntity.class);
		ShieldState state = be.getShieldState();
		state.targetRadius = 4.0F;
		be.addFuelSeconds(PLENTY_OF_FUEL);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		// A 2-thick, 4-tall stone wall hugging the bubble along +X: the naive
		// pushback target lands inside it.
		for (int x = 5; x <= 6; x++) {
			for (int y = 2; y <= 5; y++) {
				for (int z = 3; z <= 5; z++) {
					helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
				}
			}
		}

		Vec3 center = Vec3.atCenterOf(helper.absolutePos(projectorPos));
		ServerPlayer stranger = MockPlayers.createUniqueMockPlayer(helper);
		try {
			stranger.snapTo(center.x + 3.0, center.y - 0.5, center.z);

			double radius = be.currentRadius();
			// Defect pin: the naive (pre-fix) pushback target collides with the wall.
			Vec3 naive = new Vec3(center.x + radius + ShieldLogic.PUSHBACK_MARGIN, center.y - 0.5, center.z);
			AABB naiveBox = stranger.getBoundingBox().move(
					naive.x - stranger.getX(), naive.y - stranger.getY(), naive.z - stranger.getZ());
			helper.assertTrue(
					!helper.getLevel().noCollision(stranger, naiveBox),
					"test setup: the naive pushback target must collide with the wall");

			helper.assertTrue(
					ShieldLogic.expelBlockedPlayers(helper.getLevel(), helper.absolutePos(projectorPos), state),
					"the barrier should expel the non-whitelisted stranger");
			helper.assertTrue(
					stranger.position().distanceTo(center) > radius,
					"the expelled player must end up outside the shield, distance is " + stranger.position().distanceTo(center));
			helper.assertTrue(
					helper.getLevel().noCollision(stranger, stranger.getBoundingBox()),
					"the expelled player must land in a collision-free spot, landed at " + stranger.position());
		} finally {
			MockPlayers.removeMockPlayer(helper, stranger);
		}

		helper.succeed();
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

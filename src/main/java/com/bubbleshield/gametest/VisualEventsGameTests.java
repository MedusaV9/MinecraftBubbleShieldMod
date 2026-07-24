package com.bubbleshield.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.net.ShieldPayloads;
import com.bubbleshield.registry.ModBlocks;
import com.bubbleshield.registry.ModItems;
import com.bubbleshield.shield.ShieldLogic;
import com.bubbleshield.shield.ShieldState;

import io.netty.buffer.Unpooled;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * Coverage for WP-Evt "visual events, impact batches &amp; audio": the
 * {@code ImpactEntry}/{@code ImpactBatchS2C} wire format, the emission sites
 * (IMPACT/BREAK/HEAL/CONTACT/PASSAGE), the per-tick batch coalescing + cap-8
 * (BREAK kept) + radius+32 receiver filter + diff-gate bypass + cleanup, and
 * the S2 audio layer (hit-point sound trio, family layers, antipode wave tail,
 * contact notify sound, per-tick sound rate limit).
 */
public class VisualEventsGameTests {
	/**
	 * A dedicated (but otherwise vanilla-default) test environment,
	 * {@code data/bubbleshield/test_environment/visual_events.json}: the vanilla
	 * runner batches tests by environment (50 per batch, ticked in parallel), and
	 * adding this class to any pre-existing batch would reshuffle which tests
	 * overlap in time (see ColorGameTests for the full rationale).
	 */
	private static final String ISOLATED_ENVIRONMENT = "bubbleshield:visual_events";
	private static final BlockPos PROJECTOR_POS = new BlockPos(4, 2, 4);
	private static final int PLENTY_OF_FUEL = 600;
	/** Tier-0 diameter-8 max health (200 x 0.625); pinned by several health asserts below. */
	private static final float T0_D8_MAX_HEALTH = 125.0F;

	private static BubbleShieldBlockEntity placeProjector(GameTestHelper helper, float targetRadius) {
		helper.setBlock(PROJECTOR_POS, ModBlocks.BUBBLE_SHIELD_PROJECTOR);
		BubbleShieldBlockEntity be = helper.getBlockEntity(PROJECTOR_POS, BubbleShieldBlockEntity.class);
		be.getShieldState().targetRadius = targetRadius;
		return be;
	}

	/** Absolute shield center (the projector block's center). */
	private static Vec3 shieldCenter(GameTestHelper helper) {
		return Vec3.atCenterOf(helper.absolutePos(PROJECTOR_POS));
	}

	/**
	 * Arrow spawn point for this class's radius-4 shields: just under the
	 * enclosure ceiling, 5 blocks above the center — one clean outside-to-inside
	 * boundary crossing on the first moved tick (same recipe as CombatGameTests).
	 */
	private static Vec3 arrowSpawnAboveBoundary() {
		return new Vec3(4.5, 7.5, 4.5);
	}

	private static Arrow spawnDivingArrow(GameTestHelper helper, Vec3 spawnPos) {
		Arrow arrow = helper.spawn(EntityTypes.ARROW, spawnPos);
		arrow.setDeltaMovement(0.0, -1.5, 0.0);
		return arrow;
	}

	/**
	 * A capture mock parked OUTSIDE the radius-4 bubble (6 blocks +x of the
	 * center) but inside both the impact-batch receive ring (radius + 32) and
	 * the 16-block sound broadcast radius. Created BEFORE any activation so the
	 * activation-time expel pass never has to move it.
	 */
	private static MockPlayers.CapturingMockPlayer captureOutsideBubble(GameTestHelper helper) {
		MockPlayers.CapturingMockPlayer capture = MockPlayers.createCapturingMockPlayer(helper, GameType.CREATIVE);
		Vec3 center = shieldCenter(helper);
		capture.player().snapTo(center.x + 6.0, center.y - 0.5, center.z);
		return capture;
	}

	/** The ImpactBatchS2C payloads for the given projector among {@code packets}. */
	private static List<ShieldPayloads.ImpactBatchS2C> batchesIn(List<Object> packets, BlockPos absPos) {
		List<ShieldPayloads.ImpactBatchS2C> batches = new ArrayList<>();
		for (Object message : packets) {
			if (message instanceof ClientboundCustomPayloadPacket packet
					&& packet.payload() instanceof ShieldPayloads.ImpactBatchS2C batch
					&& batch.pos().equals(absPos)) {
				batches.add(batch);
			}
		}

		return batches;
	}

	/** All entries across the given projector's batches among {@code packets}. */
	private static List<ShieldPayloads.ImpactEntry> entriesIn(List<Object> packets, BlockPos absPos) {
		List<ShieldPayloads.ImpactEntry> entries = new ArrayList<>();
		for (ShieldPayloads.ImpactBatchS2C batch : batchesIn(packets, absPos)) {
			entries.addAll(batch.entries());
		}

		return entries;
	}

	private static int countKind(List<ShieldPayloads.ImpactEntry> entries, int kind) {
		int count = 0;
		for (ShieldPayloads.ImpactEntry entry : entries) {
			if (entry.kind() == kind) {
				count++;
			}
		}

		return count;
	}

	/** The ShieldSyncS2C count for the given projector among {@code packets}. */
	private static int countSyncs(List<Object> packets, BlockPos absPos) {
		int count = 0;
		for (Object message : packets) {
			if (message instanceof ClientboundCustomPayloadPacket packet
					&& packet.payload() instanceof ShieldPayloads.ShieldSyncS2C sync
					&& sync.pos().equals(absPos)) {
				count++;
			}
		}

		return count;
	}

	/**
	 * Counts the sound packets among {@code packets} carrying exactly
	 * {@code sound} AND positioned within {@code maxDist} of {@code near}. The
	 * position filter isolates this test's projector from concurrent batch
	 * neighbors (structures are &ge; 32 blocks apart, sound broadcasts reach
	 * ~16 x volume blocks — filtering by the expected emission point keeps every
	 * count attributable). One drain is destructive across ALL packet types:
	 * drain once per window and count per sound out of the one list.
	 */
	private static int countSoundsNear(List<Object> packets, SoundEvent sound, Vec3 near, double maxDist) {
		int count = 0;
		for (Object message : packets) {
			if (message instanceof ClientboundSoundPacket packet && packet.getSound().value() == sound
					&& new Vec3(packet.getX(), packet.getY(), packet.getZ()).distanceTo(near) <= maxDist) {
				count++;
			}
		}

		return count;
	}

	/**
	 * (1) The wire format round-trips exactly: kind/direction bytes and the
	 * UNSIGNED strength byte (255 = the BREAK sentinel, which a signed read
	 * would flip to -1) survive encode/decode, list order preserved.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, padding = 16)
	public void impactBatchRoundTrip(GameTestHelper helper) {
		BlockPos absPos = helper.absolutePos(PROJECTOR_POS);
		List<ShieldPayloads.ImpactEntry> entries = List.of(
				ShieldPayloads.ImpactEntry.of(ShieldPayloads.ImpactEntry.KIND_IMPACT, new Vec3(0.0, 1.0, 0.0), 3.0F),
				ShieldPayloads.ImpactEntry.of(ShieldPayloads.ImpactEntry.KIND_BREAK, Vec3.ZERO, ShieldPayloads.ImpactEntry.MAX_STRENGTH),
				new ShieldPayloads.ImpactEntry((byte) ShieldPayloads.ImpactEntry.KIND_PASSAGE_OUT, (byte) -127, (byte) 0, (byte) 127, (byte) 255));
		ShieldPayloads.ImpactBatchS2C original = new ShieldPayloads.ImpactBatchS2C(absPos, helper.getLevel().dimension(), entries);

		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
		try {
			ShieldPayloads.ImpactBatchS2C.CODEC.encode(buf, original);
			ShieldPayloads.ImpactBatchS2C decoded = ShieldPayloads.ImpactBatchS2C.CODEC.decode(buf);
			helper.assertTrue(decoded.equals(original), "the batch must round-trip exactly, got " + decoded);
			helper.assertTrue(decoded.entries().get(0).strengthUnsigned() == 30,
					"the arrow-strength byte must read back 30 unsigned, got " + decoded.entries().get(0).strengthUnsigned());
			helper.assertTrue(decoded.entries().get(1).strengthUnsigned() == 255,
					"the BREAK sentinel must read back 255 UNSIGNED (not -1), got " + decoded.entries().get(1).strengthUnsigned());
			helper.assertTrue(decoded.entries().get(2).dir().x < -0.99 && decoded.entries().get(2).dir().z > 0.99,
					"the quantized direction bytes must round-trip, got " + decoded.entries().get(2).dir());
		} finally {
			buf.release();
		}

		helper.succeed();
	}

	/**
	 * (2) A real arrow interception emits exactly one IMPACT entry whose
	 * strength byte is the applied (post-DR) damage x10: tier 0 applies the raw
	 * 3.0 -&gt; byte 30.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void arrowImpactEmitsBatchEntry(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		ShieldState state = be.getShieldState();
		MockPlayers.CapturingMockPlayer capture = captureOutsideBubble(helper);
		BlockPos absPos = helper.absolutePos(PROJECTOR_POS);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.startSequence()
				.thenExecuteAfter(2, () -> {
					capture.drainPackets();
					spawnDivingArrow(helper, arrowSpawnAboveBoundary());
				})
				.thenWaitUntil(() -> helper.assertTrue(state.health == T0_D8_MAX_HEALTH - ShieldLogic.PROJECTILE_DAMAGE,
						"waiting for the interception, health is " + state.health))
				.thenExecuteAfter(2, () -> {
					List<ShieldPayloads.ImpactEntry> entries = entriesIn(capture.drainPackets(), absPos);
					helper.assertTrue(entries.size() == 1, "one interception must emit exactly ONE entry, got " + entries.size());
					ShieldPayloads.ImpactEntry entry = entries.get(0);
					helper.assertTrue(entry.kind() == ShieldPayloads.ImpactEntry.KIND_IMPACT,
							"the entry must be an IMPACT, kind " + entry.kind());
					helper.assertTrue(entry.strengthUnsigned() == 30,
							"strength must be applied x10 = 30 (tier 0 applies the raw 3.0), got " + entry.strengthUnsigned());
				})
				.thenSucceed();
	}

	/**
	 * (3) The IMPACT direction is the outward unit vector from the center to the
	 * hit point: a straight-down dive onto the bubble top hits at +Y, so the
	 * dequantized direction's dot with (0,1,0) must be at least 0.9.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void impactDirectionUnitOutward(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		ShieldState state = be.getShieldState();
		MockPlayers.CapturingMockPlayer capture = captureOutsideBubble(helper);
		BlockPos absPos = helper.absolutePos(PROJECTOR_POS);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.startSequence()
				.thenExecuteAfter(2, () -> {
					capture.drainPackets();
					spawnDivingArrow(helper, arrowSpawnAboveBoundary());
				})
				.thenWaitUntil(() -> helper.assertTrue(state.health < T0_D8_MAX_HEALTH, "waiting for the interception"))
				.thenExecuteAfter(2, () -> {
					List<ShieldPayloads.ImpactEntry> entries = entriesIn(capture.drainPackets(), absPos);
					helper.assertTrue(entries.size() == 1, "expected the one IMPACT entry, got " + entries.size());
					Vec3 dir = entries.get(0).dir();
					double dot = dir.dot(new Vec3(0.0, 1.0, 0.0));
					helper.assertTrue(dot >= 0.9,
							"a top hit's outward direction must dot >= 0.9 with +Y, got " + dot + " (dir " + dir + ")");
				})
				.thenSucceed();
	}

	/**
	 * (4) A same-tick 3-arrow volley coalesces into exactly ONE batch carrying
	 * three IMPACT entries (one flush per shield per tick, never one packet per
	 * hit).
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void volleyCoalescesToOneBatch(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		ShieldState state = be.getShieldState();
		MockPlayers.CapturingMockPlayer capture = captureOutsideBubble(helper);
		BlockPos absPos = helper.absolutePos(PROJECTOR_POS);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.startSequence()
				.thenExecuteAfter(2, () -> {
					capture.drainPackets();
					// Same y and velocity: all three cross the boundary on the same
					// moved tick (the shield stays above the 60% shrink plateau, so
					// the recomputed boundary never dodges the later arrows).
					spawnDivingArrow(helper, new Vec3(4.2, 7.5, 4.2));
					spawnDivingArrow(helper, new Vec3(4.5, 7.5, 4.5));
					spawnDivingArrow(helper, new Vec3(4.8, 7.5, 4.8));
				})
				.thenWaitUntil(() -> helper.assertTrue(
						state.health == T0_D8_MAX_HEALTH - 3.0F * ShieldLogic.PROJECTILE_DAMAGE,
						"waiting for all three interceptions, health is " + state.health))
				.thenExecuteAfter(2, () -> {
					List<ShieldPayloads.ImpactBatchS2C> batches = batchesIn(capture.drainPackets(), absPos);
					helper.assertTrue(batches.size() == 1,
							"a same-tick volley must coalesce into exactly ONE batch, got " + batches.size());
					List<ShieldPayloads.ImpactEntry> entries = batches.get(0).entries();
					helper.assertTrue(entries.size() == 3, "the one batch must carry 3 entries, got " + entries.size());
					helper.assertTrue(countKind(entries, ShieldPayloads.ImpactEntry.KIND_IMPACT) == 3,
							"all 3 entries must be IMPACTs");
				})
				.thenSucceed();
	}

	/**
	 * (5) The batch cap: 12 queued entries flush as 8 — the OLDEST kept — except
	 * that a BREAK entry is never dropped (it replaces the newest kept slot).
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void batchCapsAtEightBreakKept(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		MockPlayers.CapturingMockPlayer capture = captureOutsideBubble(helper);
		BlockPos absPos = helper.absolutePos(PROJECTOR_POS);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.startSequence()
				.thenExecuteAfter(2, () -> {
					capture.drainPackets();
					// 11 impacts (strength bytes 5, 10, ..., 55) then a BREAK: 12 queued.
					for (int i = 0; i < 11; i++) {
						be.queueImpact(ShieldPayloads.ImpactEntry.KIND_IMPACT, new Vec3(0.0, 1.0, 0.0), 0.5F * (i + 1));
					}

					be.queueImpact(ShieldPayloads.ImpactEntry.KIND_BREAK, Vec3.ZERO, ShieldPayloads.ImpactEntry.MAX_STRENGTH);
					helper.assertTrue(be.pendingImpactCount() == 12, "12 entries should be queued, got " + be.pendingImpactCount());
				})
				.thenExecuteAfter(2, () -> {
					List<ShieldPayloads.ImpactBatchS2C> batches = batchesIn(capture.drainPackets(), absPos);
					helper.assertTrue(batches.size() == 1, "the overflow must still flush as ONE batch, got " + batches.size());
					List<ShieldPayloads.ImpactEntry> entries = batches.get(0).entries();
					helper.assertTrue(entries.size() == ShieldPayloads.ImpactBatchS2C.MAX_ENTRIES,
							"the batch must cap at 8 entries, got " + entries.size());
					helper.assertTrue(countKind(entries, ShieldPayloads.ImpactEntry.KIND_BREAK) == 1,
							"the BREAK entry must survive the cap");
					helper.assertTrue(entries.get(0).strengthUnsigned() == 5,
							"the cap keeps the OLDEST entries, first strength should be 5, got " + entries.get(0).strengthUnsigned());
					helper.assertTrue(be.pendingImpactCount() == 0, "the flush must clear the queue");
				})
				.thenSucceed();
	}

	/**
	 * (6) BOTH break paths emit the BREAK entry (directionless, strength byte
	 * 255): the direct applyShieldDamage road and the projectile-interception
	 * road inside serverTick.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void breakEntryBothPaths(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		ShieldState state = be.getShieldState();
		MockPlayers.CapturingMockPlayer capture = captureOutsideBubble(helper);
		BlockPos absPos = helper.absolutePos(PROJECTOR_POS);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.startSequence()
				.thenExecuteAfter(2, () -> {
					capture.drainPackets();
					// Path 1: the direct damage road.
					be.applyShieldDamage(1000.0F);
					helper.assertTrue(!state.active, "the direct hit should break the shield");
				})
				.thenExecuteAfter(2, () -> {
					List<ShieldPayloads.ImpactEntry> entries = entriesIn(capture.drainPackets(), absPos);
					helper.assertTrue(countKind(entries, ShieldPayloads.ImpactEntry.KIND_BREAK) == 1,
							"the direct break path must emit exactly one BREAK entry, got " + entries);
					ShieldPayloads.ImpactEntry breakEntry = entries.stream()
							.filter(entry -> entry.kind() == ShieldPayloads.ImpactEntry.KIND_BREAK).findFirst().orElseThrow();
					helper.assertTrue(breakEntry.strengthUnsigned() == 255,
							"BREAK strength must be the saturated 255, got " + breakEntry.strengthUnsigned());
					helper.assertTrue(breakEntry.dx() == 0 && breakEntry.dy() == 0 && breakEntry.dz() == 0,
							"BREAK must be directionless");

					// Reset for path 2: skip the break cooldown, reactivate at 1 HP.
					state.cooldownUntil = 0L;
					helper.assertTrue(be.tryActivate(), "the reset shield should reactivate");
					state.health = 1.0F;
					spawnDivingArrow(helper, arrowSpawnAboveBoundary());
				})
				.thenWaitUntil(() -> helper.assertTrue(!state.active, "waiting for the arrow to break the 1-HP shield"))
				.thenExecuteAfter(2, () -> {
					List<ShieldPayloads.ImpactEntry> entries = entriesIn(capture.drainPackets(), absPos);
					helper.assertTrue(countKind(entries, ShieldPayloads.ImpactEntry.KIND_BREAK) == 1,
							"the interception break path must emit exactly one BREAK entry, got " + entries);
				})
				.thenSucceed();
	}

	/**
	 * (7) HEAL emission is patch-kit-only: the kit's mend emits one HEAL entry
	 * (strength = healed x10, saturating), while the passive regen pulses that
	 * follow emit nothing.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void healEntryPatchKitOnlyNotRegen(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		// Tier 1: pulses 3 HP per 40 ticks even in combat, so the regen window
		// below is guaranteed to contain real (non-emitting) regen pulses.
		be.getCoreContainer().setItem(0, new ItemStack(ModItems.RESONANT_CORE));
		ShieldState state = be.getShieldState();
		MockPlayers.CapturingMockPlayer capture = captureOutsideBubble(helper);
		ServerPlayer owner = capture.player();
		state.ownerUuid = owner.getUUID();
		BlockPos absPos = helper.absolutePos(PROJECTOR_POS);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		float[] healthAfterPatch = new float[1];
		helper.startSequence()
				.thenExecuteAfter(2, () -> {
					// Tier-1 D8 max is 250; raw 280 lands as 210 through the tier-1
					// 25% DR (health 40), so the kit heals its full 150 (-> 190)
					// AND regen still has 60 HP of room to pulse afterwards.
					helper.assertTrue(state.maxHealth == 250.0F, "tier-1 D8 max should be 250, got " + state.maxHealth);
					be.applyShieldDamage(280.0F);
					capture.drainPackets();
					helper.assertTrue(be.applyPatchKit(owner, new ItemStack(ModItems.PATCH_KIT)),
							"the owner's patch kit should apply");
					healthAfterPatch[0] = state.health;
				})
				.thenExecuteAfter(2, () -> {
					List<ShieldPayloads.ImpactEntry> entries = entriesIn(capture.drainPackets(), absPos);
					helper.assertTrue(countKind(entries, ShieldPayloads.ImpactEntry.KIND_HEAL) == 1,
							"the patch kit must emit exactly one HEAL entry, got " + entries);
					ShieldPayloads.ImpactEntry heal = entries.stream()
							.filter(entry -> entry.kind() == ShieldPayloads.ImpactEntry.KIND_HEAL).findFirst().orElseThrow();
					// healed = 150, saturating the x10 byte encoding at 255.
					helper.assertTrue(heal.strengthUnsigned() == 255,
							"a 150-HP mend saturates the strength byte at 255, got " + heal.strengthUnsigned());
				})
				.thenExecuteAfter(45, () -> {
					helper.assertTrue(state.health > healthAfterPatch[0],
							"the regen should have pulsed inside the window, health " + state.health);
					List<ShieldPayloads.ImpactEntry> entries = entriesIn(capture.drainPackets(), absPos);
					helper.assertTrue(countKind(entries, ShieldPayloads.ImpactEntry.KIND_HEAL) == 0,
							"regen pulses must emit NO HEAL entries, got " + entries);
				})
				.thenSucceed();
	}

	/**
	 * (8) CONTACT is rate-limited to one event (batch entry + personal notify
	 * sound) per {@link BubbleShieldBlockEntity#CONTACT_RATE_TICKS} per pressing
	 * player: press, press again inside the window (silent), press after the
	 * window (one more event).
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void contactRateLimited10Ticks(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		MockPlayers.CapturingMockPlayer capture = captureOutsideBubble(helper);
		ServerPlayer player = capture.player();
		Vec3 center = shieldCenter(helper);
		Vec3 inside = new Vec3(center.x + 1.5, center.y - 0.5, center.z);
		BlockPos absPos = helper.absolutePos(PROJECTOR_POS);
		// Non-whitelisted, non-owner: the barrier blocks (and expels) the mock.
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.startSequence()
				.thenExecuteAfter(2, () -> {
					capture.drainPackets();
					player.snapTo(inside.x, inside.y, inside.z);
				})
				.thenExecuteAfter(2, () -> {
					List<Object> packets = capture.drainPackets();
					helper.assertTrue(countKind(entriesIn(packets, absPos), ShieldPayloads.ImpactEntry.KIND_CONTACT) == 1,
							"the first press must emit exactly ONE CONTACT entry");
					helper.assertTrue(countSoundsNear(packets, SoundEvents.SLIME_BLOCK_HIT, center, 16.0) == 1,
							"the first press must send exactly ONE personal notify sound");
					// Second press, well inside the 10-tick window.
					player.snapTo(inside.x, inside.y, inside.z);
				})
				.thenExecuteAfter(2, () -> {
					List<Object> packets = capture.drainPackets();
					helper.assertTrue(countKind(entriesIn(packets, absPos), ShieldPayloads.ImpactEntry.KIND_CONTACT) == 0,
							"a press inside the rate window must emit NO CONTACT entry");
					helper.assertTrue(countSoundsNear(packets, SoundEvents.SLIME_BLOCK_HIT, center, 16.0) == 0,
							"a press inside the rate window must stay silent");
				})
				.thenExecuteAfter(9, () -> player.snapTo(inside.x, inside.y, inside.z))
				.thenExecuteAfter(2, () -> {
					List<Object> packets = capture.drainPackets();
					helper.assertTrue(countKind(entriesIn(packets, absPos), ShieldPayloads.ImpactEntry.KIND_CONTACT) == 1,
							"a press after the window must emit ONE new CONTACT entry");
					helper.assertTrue(countSoundsNear(packets, SoundEvents.SLIME_BLOCK_HIT, center, 16.0) == 1,
							"a press after the window must ring ONE new notify sound");
				})
				.thenSucceed();
	}

	/**
	 * (9) PASSAGE_IN/OUT emit for WHITELISTED players only, on the inside/outside
	 * flip: crossing in emits IN, crossing out emits OUT, and a BLOCKED player
	 * shoved back by the barrier emits CONTACT — never PASSAGE.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void passageFlipEmitsInOut(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		ShieldState state = be.getShieldState();
		MockPlayers.CapturingMockPlayer capture = captureOutsideBubble(helper);
		ServerPlayer whitelisted = capture.player();
		state.whitelistUuids.add(whitelisted.getUUID());
		MockPlayers.CapturingMockPlayer blockedCapture = MockPlayers.createCapturingMockPlayer(helper, GameType.CREATIVE);
		ServerPlayer blocked = blockedCapture.player();
		Vec3 center = shieldCenter(helper);
		blocked.snapTo(center.x - 6.0, center.y - 0.5, center.z);
		Vec3 inside = new Vec3(center.x + 1.5, center.y - 0.5, center.z);
		Vec3 outside = new Vec3(center.x + 6.0, center.y - 0.5, center.z);
		BlockPos absPos = helper.absolutePos(PROJECTOR_POS);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.startSequence()
				// A couple of active ticks seed the whitelisted mock's wasInside
				// state (outside); the first observation never counts as a flip.
				.thenExecuteAfter(3, () -> {
					capture.drainPackets();
					whitelisted.snapTo(inside.x, inside.y, inside.z);
				})
				.thenExecuteAfter(2, () -> {
					List<ShieldPayloads.ImpactEntry> entries = entriesIn(capture.drainPackets(), absPos);
					helper.assertTrue(countKind(entries, ShieldPayloads.ImpactEntry.KIND_PASSAGE_IN) == 1,
							"crossing in must emit exactly ONE PASSAGE_IN, got " + entries);
					helper.assertTrue(countKind(entries, ShieldPayloads.ImpactEntry.KIND_PASSAGE_OUT) == 0
									&& countKind(entries, ShieldPayloads.ImpactEntry.KIND_CONTACT) == 0,
							"a whitelisted crossing emits neither OUT nor CONTACT, got " + entries);
					whitelisted.snapTo(outside.x, outside.y, outside.z);
				})
				.thenExecuteAfter(2, () -> {
					List<ShieldPayloads.ImpactEntry> entries = entriesIn(capture.drainPackets(), absPos);
					helper.assertTrue(countKind(entries, ShieldPayloads.ImpactEntry.KIND_PASSAGE_OUT) == 1,
							"crossing out must emit exactly ONE PASSAGE_OUT, got " + entries);
					helper.assertTrue(countKind(entries, ShieldPayloads.ImpactEntry.KIND_PASSAGE_IN) == 0,
							"no IN on the way out, got " + entries);
					// Now the BLOCKED mock walks in: barrier + CONTACT, no PASSAGE.
					blocked.snapTo(inside.x, inside.y, inside.z);
				})
				.thenExecuteAfter(2, () -> {
					List<ShieldPayloads.ImpactEntry> entries = entriesIn(capture.drainPackets(), absPos);
					helper.assertTrue(countKind(entries, ShieldPayloads.ImpactEntry.KIND_PASSAGE_IN) == 0
									&& countKind(entries, ShieldPayloads.ImpactEntry.KIND_PASSAGE_OUT) == 0,
							"a BLOCKED player must never emit PASSAGE, got " + entries);
					helper.assertTrue(countKind(entries, ShieldPayloads.ImpactEntry.KIND_CONTACT) == 1,
							"the blocked press must emit its CONTACT instead, got " + entries);
				})
				.thenSucceed();
	}

	/**
	 * (10) The receiver filter: batches reach players within currentRadius + 32
	 * of the center and nobody beyond it. The two mocks park VERTICALLY (20 and
	 * 44 blocks up) so no concurrent test's structure sits anywhere near either.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void receiverFilterRadiusPlus32(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		Vec3 center = shieldCenter(helper);
		MockPlayers.CapturingMockPlayer near = MockPlayers.createCapturingMockPlayer(helper, GameType.CREATIVE);
		near.player().snapTo(center.x, center.y + 20.0, center.z);
		MockPlayers.CapturingMockPlayer far = MockPlayers.createCapturingMockPlayer(helper, GameType.CREATIVE);
		far.player().snapTo(center.x, center.y + 44.0, center.z);
		BlockPos absPos = helper.absolutePos(PROJECTOR_POS);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.startSequence()
				.thenExecuteAfter(2, () -> {
					near.drainPackets();
					far.drainPackets();
					be.queueImpact(ShieldPayloads.ImpactEntry.KIND_IMPACT, new Vec3(0.0, 1.0, 0.0), 5.0F);
				})
				.thenExecuteAfter(2, () -> {
					helper.assertTrue(batchesIn(near.drainPackets(), absPos).size() == 1,
							"the mock at r+16 must receive the batch");
					helper.assertTrue(batchesIn(far.drainPackets(), absPos).isEmpty(),
							"the mock at r+40 must stay silent");
				})
				.thenSucceed();
	}

	/**
	 * (17) The BREAK batch must reach everyone who could SEE the full-size
	 * bubble: the batch flushes AFTER the hit, when a break has already zeroed
	 * currentRadius, so ranging on the post-hit radius strands the collapse
	 * flash on a bare 32-block ring. The receiver range is
	 * {@code max(targetRadius, currentRadius) + 32}; the mock parks VERTICALLY
	 * at pre-break radius + 16 (46 blocks up — beyond the old 0 + 32 ring,
	 * inside the fixed 30 + 32, and nowhere near any concurrent structure).
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void breakBatchReachesPreBreakRadiusMock(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 30.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		ShieldState state = be.getShieldState();
		Vec3 center = shieldCenter(helper);
		MockPlayers.CapturingMockPlayer capture = MockPlayers.createCapturingMockPlayer(helper, GameType.CREATIVE);
		capture.player().snapTo(center.x, center.y + 46.0, center.z);
		BlockPos absPos = helper.absolutePos(PROJECTOR_POS);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.startSequence()
				.thenExecuteAfter(2, () -> {
					capture.drainPackets();
					helper.assertTrue(ShieldLogic.currentRadius(state) == 30.0F,
							"the full-health pre-break radius should be 30, got " + ShieldLogic.currentRadius(state));
					// One breaking hit; the tier-0 D60 max health is far below this.
					be.applyShieldDamage(100000.0F);
					helper.assertTrue(!state.active, "the hit should break the shield");
				})
				.thenExecuteAfter(2, () -> {
					List<ShieldPayloads.ImpactEntry> entries = entriesIn(capture.drainPackets(), absPos);
					helper.assertTrue(countKind(entries, ShieldPayloads.ImpactEntry.KIND_BREAK) == 1,
							"the BREAK batch must reach the mock at pre-break radius + 16, got " + entries);
				})
				.thenSucceed();
	}

	/**
	 * (11) Impacts bypass the sync diff gate: a pure visual event (no replicated
	 * state change) flushes its batch while ZERO ShieldSyncS2C go out in the same
	 * window — partitioned from ONE destructive drain.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void impactsBypassDiffGate(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		MockPlayers.CapturingMockPlayer capture = captureOutsideBubble(helper);
		BlockPos absPos = helper.absolutePos(PROJECTOR_POS);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.startSequence()
				.thenExecuteAfter(5, () -> {
					capture.drainPackets();
					// A pure event: no markUpdated, nothing replicated changes.
					be.queueImpact(ShieldPayloads.ImpactEntry.KIND_IMPACT, new Vec3(1.0, 0.0, 0.0), 4.0F);
				})
				.thenExecuteAfter(2, () -> {
					List<Object> packets = capture.drainPackets();
					helper.assertTrue(batchesIn(packets, absPos).size() == 1,
							"the pure event must flush exactly ONE batch");
					helper.assertTrue(countSyncs(packets, absPos) == 0,
							"no replicated change happened: the same drain must carry ZERO syncs");
				})
				.thenSucceed();
	}

	/**
	 * (16) Deactivation sweeps every transient queue: pending entries, both
	 * contact/passage maps and the delayed-sound queue empty synchronously, and
	 * nothing flushes afterwards.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void deactivateClearsQueues(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		MockPlayers.CapturingMockPlayer capture = captureOutsideBubble(helper);
		Vec3 center = shieldCenter(helper);
		BlockPos absPos = helper.absolutePos(PROJECTOR_POS);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.startSequence()
				.thenExecuteAfter(2, () -> {
					capture.drainPackets();
					be.queueImpact(ShieldPayloads.ImpactEntry.KIND_IMPACT, new Vec3(0.0, 1.0, 0.0), 3.0F);
					be.queueImpact(ShieldPayloads.ImpactEntry.KIND_CONTACT, new Vec3(1.0, 0.0, 0.0), 2.5F);
					be.queueAntipodeWaveTail(center.add(4.0, 0.0, 0.0), 4.0);
					be.queueAntipodeWaveTail(center.add(0.0, 4.0, 0.0), 4.0);
					be.tryContact(UUID.randomUUID(), helper.getLevel().getGameTime());
					be.swapWasInside(UUID.randomUUID(), true);
					helper.assertTrue(be.pendingImpactCount() == 2 && be.pendingSoundCount() == 2
									&& be.contactTrackedCount() == 1 && be.passageTrackedCount() == 1,
							"the queues should be populated before the deactivation");

					be.setActive(false);
					helper.assertTrue(be.pendingImpactCount() == 0, "deactivation must clear the pending entries");
					helper.assertTrue(be.pendingSoundCount() == 0, "deactivation must clear the delayed sounds");
					helper.assertTrue(be.contactTrackedCount() == 0, "deactivation must clear the contact map");
					helper.assertTrue(be.passageTrackedCount() == 0, "deactivation must clear the passage map");

					// The public hook stays silent on a downed shield: a direct
					// non-BREAK queueImpact is ignored (only BREAK may queue while
					// inactive), so nothing can flush a flash for a bubble that
					// does not exist.
					be.queueImpact(ShieldPayloads.ImpactEntry.KIND_IMPACT, new Vec3(0.0, 1.0, 0.0), 5.0F);
					helper.assertTrue(be.pendingImpactCount() == 0,
							"a non-BREAK queueImpact while inactive must be ignored, got " + be.pendingImpactCount());
				})
				.thenExecuteAfter(4, () -> {
					List<Object> packets = capture.drainPackets();
					helper.assertTrue(batchesIn(packets, absPos).isEmpty(),
							"nothing may flush after the sweep");
					helper.assertTrue(countSoundsNear(packets, SoundEvents.WARDEN_SONIC_CHARGE, center, 12.0) == 0,
							"no swept antipode tail may ring after the deactivation");
				})
				.thenSucceed();
	}

	/**
	 * (12) The S2 hit sounds play AT THE HIT POINT: both the HEAVY_CORE_HIT
	 * thump and the SHIELD_BLOCK ring land within ±1.5 of the boundary crossing
	 * (the old lone SHIELD_BLOCK at the projector is gone).
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void hitSoundsAtHitPoint(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		ShieldState state = be.getShieldState();
		MockPlayers.CapturingMockPlayer capture = captureOutsideBubble(helper);
		Vec3 center = shieldCenter(helper);
		// The dive crosses the radius-4 boundary directly above the center.
		Vec3 crossing = center.add(0.0, 4.0, 0.0);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.startSequence()
				.thenExecuteAfter(2, () -> {
					capture.drainPackets();
					spawnDivingArrow(helper, arrowSpawnAboveBoundary());
				})
				.thenWaitUntil(() -> helper.assertTrue(state.health < T0_D8_MAX_HEALTH, "waiting for the interception"))
				.thenExecuteAfter(2, () -> {
					List<Object> packets = capture.drainPackets();
					helper.assertTrue(countSoundsNear(packets, SoundEvents.HEAVY_CORE_HIT, crossing, 1.5) == 1,
							"HEAVY_CORE_HIT must ring within 1.5 of the boundary crossing");
					helper.assertTrue(countSoundsNear(packets, SoundEvents.SHIELD_BLOCK.value(), crossing, 1.5) == 1,
							"SHIELD_BLOCK must ring within 1.5 of the boundary crossing");
					helper.assertTrue(countSoundsNear(packets, SoundEvents.SHIELD_BLOCK.value(), center, 1.5) == 0,
							"the old projector-pos SHIELD_BLOCK site must be gone");
				})
				.thenSucceed();
	}

	/**
	 * (13) The family layer follows the effect's SurfaceSoundGroup: a plasma
	 * (ENERGY) effect rings the sculk-charge layer, a sparkle (CRYSTAL) effect
	 * rings the amethyst layer — and never each other's.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void familyLayerSelection(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		ShieldState state = be.getShieldState();
		MockPlayers.CapturingMockPlayer capture = captureOutsideBubble(helper);
		Vec3 center = shieldCenter(helper);
		// Effect 5 ("plasma" surface) is ENERGY; effect 4 ("sparkle") is CRYSTAL.
		// Neither's ambient sound collides with the asserted layer sounds.
		state.effectId = 5;
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.startSequence()
				.thenExecuteAfter(2, () -> {
					capture.drainPackets();
					spawnDivingArrow(helper, arrowSpawnAboveBoundary());
				})
				.thenWaitUntil(() -> helper.assertTrue(state.health < T0_D8_MAX_HEALTH, "waiting for the ENERGY hit"))
				.thenExecuteAfter(2, () -> {
					List<Object> packets = capture.drainPackets();
					helper.assertTrue(countSoundsNear(packets, SoundEvents.SCULK_BLOCK_CHARGE, center, 12.0) == 1,
							"an ENERGY-family hit must ring the sculk-charge layer");
					helper.assertTrue(countSoundsNear(packets, SoundEvents.AMETHYST_CLUSTER_BREAK, center, 12.0) == 0,
							"an ENERGY-family hit must not ring the amethyst layer");
				})
				// The second hit waits out the per-shield sound cooldown (4 ticks);
				// a back-to-back spawn would land inside the first hit's window and
				// (correctly) stay silent.
				.thenExecuteAfter(BubbleShieldBlockEntity.IMPACT_SOUND_COOLDOWN_TICKS, () -> {
					state.effectId = 4;
					spawnDivingArrow(helper, arrowSpawnAboveBoundary());
				})
				.thenWaitUntil(() -> helper.assertTrue(
						state.health == T0_D8_MAX_HEALTH - 2.0F * ShieldLogic.PROJECTILE_DAMAGE,
						"waiting for the CRYSTAL hit, health is " + state.health))
				.thenExecuteAfter(2, () -> {
					List<Object> packets = capture.drainPackets();
					helper.assertTrue(countSoundsNear(packets, SoundEvents.AMETHYST_CLUSTER_BREAK, center, 12.0) == 1,
							"a CRYSTAL-family hit must ring the amethyst layer");
					helper.assertTrue(countSoundsNear(packets, SoundEvents.SCULK_BLOCK_CHARGE, center, 12.0) == 0,
							"a CRYSTAL-family hit must not ring the sculk layer");
				})
				.thenSucceed();
	}

	/**
	 * (14) The antipode wave tail rings the mirrored point exactly
	 * {@code 2 + radius/8} ticks after the hit (radius 4 -&gt; 2 ticks), the
	 * delayed-sound queue caps at 4, and a deactivation drops every queued tail.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void antipodeDelayPositionAndCap(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		MockPlayers.CapturingMockPlayer capture = MockPlayers.createCapturingMockPlayer(helper, GameType.CREATIVE);
		Vec3 center = shieldCenter(helper);
		// Above the bubble, within 16 blocks of the antipode point.
		capture.player().snapTo(center.x, center.y + 6.0, center.z);
		Vec3 hit = center.add(4.0, 0.0, 0.0);
		Vec3 antipode = center.add(-4.0, 0.0, 0.0);
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.startSequence()
				.thenExecuteAfter(2, () -> {
					capture.drainPackets();
					be.queueAntipodeWaveTail(hit, 4.0);
				})
				.thenExecuteAfter(1, () -> helper.assertTrue(
						countSoundsNear(capture.drainPackets(), SoundEvents.WARDEN_SONIC_CHARGE, antipode, 1.0) == 0,
						"the tail must NOT ring one tick early"))
				.thenExecuteAfter(1, () -> {
					helper.assertTrue(
							countSoundsNear(capture.drainPackets(), SoundEvents.WARDEN_SONIC_CHARGE, antipode, 1.0) == 1,
							"the tail must ring at the MIRRORED point exactly 2 + 4/8 = 2 ticks after the hit");

					// Cap: 6 queued tails keep only 4; the deactivation sweeps them.
					for (int i = 0; i < 6; i++) {
						be.queueAntipodeWaveTail(hit, 4.0);
					}

					helper.assertTrue(be.pendingSoundCount() == 4,
							"the delayed-sound queue must cap at 4, got " + be.pendingSoundCount());
					be.setActive(false);
					helper.assertTrue(be.pendingSoundCount() == 0, "deactivation must drop every queued tail");
				})
				.thenExecuteAfter(4, () -> helper.assertTrue(
						countSoundsNear(capture.drainPackets(), SoundEvents.WARDEN_SONIC_CHARGE, antipode, 1.0) == 0,
						"no swept tail may ring after the deactivation"))
				.thenSucceed();
	}

	/**
	 * (15) The impact-sound rate limit under SUSTAINED fire: one arrow per tick
	 * for 10 consecutive ticks rings at most ceil(10/4) = 3 full trios (the
	 * per-shield cooldown is {@link BubbleShieldBlockEntity#IMPACT_SOUND_COOLDOWN_TICKS};
	 * the old equality gate only merged same-tick volleys and let sustained fire
	 * ring 20 trios per second). ALL new-layer sound packets are counted —
	 * HEAVY_CORE_HIT thump, SHIELD_BLOCK ring and the CRYSTAL family pair
	 * (effect 4: amethyst break + chime) — and every accepted window must ring
	 * the FULL trio (first hit of the window wins; the rest stay silent).
	 * Pre-existing sounds (alarm/heartbeat/ambient) are deliberately out of
	 * scope: none of the four counted events is played by anything else here.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void impactSoundCapPerSecond(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		ShieldState state = be.getShieldState();
		MockPlayers.CapturingMockPlayer capture = captureOutsideBubble(helper);
		Vec3 center = shieldCenter(helper);
		// Effect 4 ("sparkle") is CRYSTAL: its family pair (amethyst cluster break
		// + chime) collides with nothing else in this test — unlike ENERGY, whose
		// WARDEN_SONIC_CHARGE layer would double as the antipode tail sound.
		state.effectId = 4;
		helper.assertTrue(be.tryActivate(), "shield should activate");

		var sequence = helper.startSequence()
				.thenExecuteAfter(2, () -> capture.drainPackets());
		// One identical diving arrow per tick for 10 consecutive ticks: each
		// crosses the boundary on its first moved tick, so the hits land on 10
		// consecutive ticks (the shield never shrinks below the 60% plateau).
		for (int i = 0; i < 10; i++) {
			final double x = 4.05 + 0.1 * i;
			final double z = (i % 2 == 0) ? 4.3 : 4.7;
			sequence = sequence.thenExecuteAfter(1, () -> spawnDivingArrow(helper, new Vec3(x, 7.5, z)));
		}

		sequence
				.thenWaitUntil(() -> helper.assertTrue(
						state.health == T0_D8_MAX_HEALTH - 10.0F * ShieldLogic.PROJECTILE_DAMAGE,
						"waiting for all 10 hits, health is " + state.health))
				.thenExecuteAfter(20, () -> {
					List<Object> packets = capture.drainPackets();
					int thumps = countSoundsNear(packets, SoundEvents.HEAVY_CORE_HIT, center, 12.0);
					int rings = countSoundsNear(packets, SoundEvents.SHIELD_BLOCK.value(), center, 12.0);
					int famBreaks = countSoundsNear(packets, SoundEvents.AMETHYST_CLUSTER_BREAK, center, 12.0);
					int famChimes = countSoundsNear(packets, SoundEvents.AMETHYST_BLOCK_CHIME, center, 12.0);
					helper.assertTrue(thumps >= 2, "sustained fire must still ring trios, got " + thumps);
					helper.assertTrue(thumps <= 3 && rings <= 3 && famBreaks <= 3 && famChimes <= 3,
							"hits on 10 consecutive ticks must ring at most ceil(10/4) = 3 trios worth of packets, got "
									+ thumps + "/" + rings + "/" + famBreaks + "/" + famChimes);
					helper.assertTrue(rings == thumps && famBreaks == thumps && famChimes == thumps,
							"every accepted window must ring the FULL trio, got "
									+ thumps + "/" + rings + "/" + famBreaks + "/" + famChimes);
				})
				.thenSucceed();
	}
}

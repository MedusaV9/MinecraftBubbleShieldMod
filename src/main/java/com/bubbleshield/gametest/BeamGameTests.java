package com.bubbleshield.gametest;

import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.EffectRegistry;
import com.bubbleshield.menu.BubbleShieldMenu;
import com.bubbleshield.net.ServerNet;
import com.bubbleshield.net.ShieldPayloads;
import com.bubbleshield.registry.ModBlocks;
import com.bubbleshield.shield.BeamStyle;
import com.bubbleshield.shield.ShieldState;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/**
 * Coverage for the per-bubble projector BEAM setting: the {@link BeamStyle} enum
 * contract, NBT persistence + clamp-on-load hardening, the SetSettingsC2S /
 * ShieldVisual wire codecs, the block-entity setter + menu data slot, the
 * owner-only gate the SetSettings receiver runs behind, and the deterministic
 * AUTO preset derivation. The beam RENDER (BeamMesh + beam shaders) is
 * client-only and verified by the shader gate + build + the llvmpipe capture
 * harness, not here.
 */
public class BeamGameTests {
	/**
	 * A dedicated (but otherwise vanilla-default) test environment,
	 * {@code data/bubbleshield/test_environment/beam.json}: the vanilla runner
	 * batches 50 tests per environment and ticks a batch in parallel, so new test
	 * classes get their own batch instead of growing (and reshuffling) the shared
	 * default one — see AGENTS.md and {@code ColorGameTests.ISOLATED_ENVIRONMENT}.
	 */
	private static final String ISOLATED_ENVIRONMENT = "bubbleshield:beam";
	private static final BlockPos PROJECTOR_POS = new BlockPos(4, 2, 4);

	private static BubbleShieldBlockEntity placeProjector(GameTestHelper helper) {
		helper.setBlock(PROJECTOR_POS, ModBlocks.BUBBLE_SHIELD_PROJECTOR);
		return helper.getBlockEntity(PROJECTOR_POS, BubbleShieldBlockEntity.class);
	}

	/** (a) The enum contract: clamping byOrdinal, RENDERED set, renderIndex. Pure. */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void beamStyleEnumContract(GameTestHelper helper) {
		// NONE first and AUTO second are contractual (default + preset hook).
		helper.assertTrue(BeamStyle.NONE.ordinal() == 0, "NONE must stay ordinal 0 (the NBT/legacy default)");
		helper.assertTrue(BeamStyle.AUTO.ordinal() == 1, "AUTO must stay ordinal 1");

		// byOrdinal clamps exactly like ShieldShape/ShieldMode.
		for (BeamStyle style : BeamStyle.values()) {
			helper.assertTrue(BeamStyle.byOrdinal(style.ordinal()) == style,
					"byOrdinal must round-trip " + style);
		}
		helper.assertTrue(BeamStyle.byOrdinal(-1) == BeamStyle.NONE, "byOrdinal(-1) must clamp to NONE");
		helper.assertTrue(BeamStyle.byOrdinal(99) == BeamStyle.NONE, "byOrdinal(99) must clamp to NONE");

		// RENDERED covers exactly the styles past NONE/AUTO, indexable by renderIndex.
		helper.assertTrue(BeamStyle.RENDERED.length == BeamStyle.values().length - 2,
				"RENDERED must cover every style except NONE/AUTO");
		for (int i = 0; i < BeamStyle.RENDERED.length; i++) {
			BeamStyle rendered = BeamStyle.RENDERED[i];
			helper.assertTrue(rendered != BeamStyle.NONE && rendered != BeamStyle.AUTO,
					"RENDERED must never contain NONE/AUTO");
			helper.assertTrue(rendered.renderIndex() == i,
					rendered + ".renderIndex() should be " + i + ", got " + rendered.renderIndex());
		}
		helper.succeed();
	}

	/** (b) beamStyle NBT round-trip; legacy saves default to NONE; tampered ordinals clamp. */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void beamNbtRoundTripAndClamp(GameTestHelper helper) {
		var registries = helper.getLevel().registryAccess();

		// Every style round-trips through save/load.
		for (BeamStyle style : BeamStyle.values()) {
			ShieldState original = new ShieldState();
			original.beamStyle = style;
			TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
			original.save(output);
			CompoundTag tag = output.buildResult();
			helper.assertTrue(tag.contains("beam_style"), "saved shield NBT should include beam_style");

			ShieldState loaded = new ShieldState();
			loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
			helper.assertTrue(loaded.beamStyle == style,
					"beamStyle should round-trip through NBT, expected " + style + " got " + loaded.beamStyle);
		}

		// A legacy (pre-beam) save without the key loads as NONE — beam-free.
		ShieldState legacy = new ShieldState();
		legacy.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, new CompoundTag()));
		helper.assertTrue(legacy.beamStyle == BeamStyle.NONE,
				"NBT without beam_style should load as NONE, got " + legacy.beamStyle);

		// Tampered ordinals (/data, NBT editors) clamp back to NONE on load, the
		// same hardening as shape/mode.
		for (int invalid : new int[] {99, -1, BeamStyle.values().length}) {
			TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
			new ShieldState().save(output);
			CompoundTag tag = output.buildResult();
			tag.putInt("beam_style", invalid);
			ShieldState tampered = new ShieldState();
			tampered.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
			helper.assertTrue(tampered.beamStyle == BeamStyle.NONE,
					"a tampered beam_style ordinal (" + invalid + ") must clamp to NONE on load, got "
							+ tampered.beamStyle);
		}
		helper.succeed();
	}

	/** (c) SetSettingsC2S carries the beam ordinal through its wire codec. */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void beamSettingsCodecRoundTrip(GameTestHelper helper) {
		// SetSettingsC2S's codec is registry-friendly typed, so decode through a
		// RegistryFriendlyByteBuf wrapping a plain Unpooled buffer.
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
		try {
			ShieldPayloads.SetSettingsC2S original = new ShieldPayloads.SetSettingsC2S(
					new BlockPos(1, 2, 3), 24, 42, 1, 2, true, BeamStyle.STORM.ordinal());
			ShieldPayloads.SetSettingsC2S.CODEC.encode(buffer, original);
			ShieldPayloads.SetSettingsC2S decoded = ShieldPayloads.SetSettingsC2S.CODEC.decode(buffer);
			helper.assertTrue(decoded.beamStyleOrdinal() == BeamStyle.STORM.ordinal(),
					"the decoded settings should carry the beam ordinal, got " + decoded.beamStyleOrdinal());
			helper.assertTrue(original.equals(decoded), "the full SetSettingsC2S should round-trip, got " + decoded);
			helper.assertTrue(buffer.readableBytes() == 0, "the codec should consume the whole buffer");

			// The NONE default round-trips too.
			ShieldPayloads.SetSettingsC2S plain = new ShieldPayloads.SetSettingsC2S(
					BlockPos.ZERO, 8, 0, 0, 0, false, BeamStyle.NONE.ordinal());
			ShieldPayloads.SetSettingsC2S.CODEC.encode(buffer, plain);
			helper.assertTrue(plain.equals(ShieldPayloads.SetSettingsC2S.CODEC.decode(buffer)),
					"settings with beam NONE should round-trip");
		} finally {
			buffer.release();
		}

		helper.succeed();
	}

	/** (d) ShieldVisual (9 fields) carries the synced beam ordinal through its stream codec. */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void beamVisualCodecRoundTrip(GameTestHelper helper) {
		ByteBuf buffer = Unpooled.buffer();
		try {
			ShieldPayloads.ShieldVisual original = new ShieldPayloads.ShieldVisual(
					true, 7, 8.0F, 6.5F, 0.75F, 2, 1, ShieldState.NO_COLOR_OVERRIDE, BeamStyle.HELIX.ordinal());
			ShieldPayloads.ShieldVisual.STREAM_CODEC.encode(buffer, original);
			ShieldPayloads.ShieldVisual decoded = ShieldPayloads.ShieldVisual.STREAM_CODEC.decode(buffer);
			helper.assertTrue(decoded.beamStyle() == BeamStyle.HELIX.ordinal(),
					"the decoded visual should carry the beam ordinal, got " + decoded.beamStyle());
			helper.assertTrue(original.equals(decoded), "the full ShieldVisual should round-trip, got " + decoded);
			helper.assertTrue(buffer.readableBytes() == 0, "the codec should consume the whole buffer");

			// The NONE default round-trips too.
			ShieldPayloads.ShieldVisual plain = new ShieldPayloads.ShieldVisual(
					false, 0, 16.0F, 0.0F, 1.0F, 0, 0, ShieldState.NO_COLOR_OVERRIDE, BeamStyle.NONE.ordinal());
			ShieldPayloads.ShieldVisual.STREAM_CODEC.encode(buffer, plain);
			helper.assertTrue(plain.equals(ShieldPayloads.ShieldVisual.STREAM_CODEC.decode(buffer)),
					"a visual with beam NONE should round-trip");
		} finally {
			buffer.release();
		}

		helper.succeed();
	}

	/**
	 * (e) setSettings stores the (clamped) beam style and mirrors it into the
	 * DATA_BEAM menu slot; the SetSettings receiver's clamp expression maps any
	 * hostile ordinal into the enum range; and the receiver's owner gate
	 * ({@link ServerNet#isOwner}) rejects a non-owner.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void beamSetOwnerGatedAndClamped(GameTestHelper helper) {
		BubbleShieldBlockEntity be = placeProjector(helper);

		// A valid style lands in the state AND the synced menu data.
		be.setSettings(16, 0, 0, 0, false, BeamStyle.STORM.ordinal());
		helper.assertTrue(be.getShieldState().beamStyle == BeamStyle.STORM,
				"setSettings(STORM) should store STORM, got " + be.getShieldState().beamStyle);
		helper.assertTrue(be.getMenuData().get(BubbleShieldMenu.DATA_BEAM) == BeamStyle.STORM.ordinal(),
				"DATA_BEAM should mirror the stored style ordinal");

		// A hostile ordinal fed straight into the setter clamps to NONE (byOrdinal).
		be.setSettings(16, 0, 0, 0, false, 99);
		helper.assertTrue(be.getShieldState().beamStyle == BeamStyle.NONE,
				"setSettings(99) should clamp to NONE, got " + be.getShieldState().beamStyle);
		helper.assertTrue(be.getMenuData().get(BubbleShieldMenu.DATA_BEAM) == BeamStyle.NONE.ordinal(),
				"DATA_BEAM should mirror the clamped NONE");

		// The receiver-side clamp expression (mirrored from ServerNet's SetSettings
		// handler) maps any payload value into the valid ordinal range first.
		int max = BeamStyle.values().length - 1;
		helper.assertTrue(Mth.clamp(99, 0, max) == max && BeamStyle.byOrdinal(Mth.clamp(99, 0, max)) == BeamStyle.FROST,
				"the receiver clamp should map 99 to the last style");
		helper.assertTrue(Mth.clamp(-7, 0, max) == 0 && BeamStyle.byOrdinal(Mth.clamp(-7, 0, max)) == BeamStyle.NONE,
				"the receiver clamp should map negatives to NONE");

		// Owner gate: the SetSettings receiver only reaches setSettings when
		// ServerNet.isOwner passes. The first interacting player claims an
		// ownerless shield; afterwards only an exact UUID match is accepted.
		ServerPlayer owner = MockPlayers.createUniqueMockPlayer(helper);
		ServerPlayer intruder = MockPlayers.createUniqueMockPlayer(helper);
		helper.assertTrue(ServerNet.isOwner(owner, be), "the first interacting player should claim the shield");
		helper.assertTrue(!ServerNet.isOwner(intruder, be),
				"a non-owner must be rejected by the SetSettings receiver's gate");
		helper.assertTrue(ServerNet.isOwner(owner, be), "the owner should keep passing the gate");
		MockPlayers.removeMockPlayer(helper, owner);
		MockPlayers.removeMockPlayer(helper, intruder);
		helper.succeed();
	}

	/**
	 * (f) The AUTO preset derivation is total, deterministic and never resolves to
	 * NONE/AUTO: same surface family, same beam style — plus golden asserts pinning
	 * the family-to-style mapping so it can never silently reshuffle.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void beamPresetDerivationDeterministic(GameTestHelper helper) {
		for (EffectDefinition def : EffectRegistry.ALL) {
			BeamStyle preset = def.beamPreset();
			helper.assertTrue(preset != BeamStyle.NONE && preset != BeamStyle.AUTO,
					"effect " + def.id() + " beamPreset must be a rendered style, got " + preset);
			helper.assertTrue(preset == def.beamPreset(),
					"beamPreset must be deterministic for effect " + def.id());
			// The documented derivation: keyed to the surface technique family.
			BeamStyle expected = BeamStyle.RENDERED[def.surface().ordinal() % BeamStyle.RENDERED.length];
			helper.assertTrue(preset == expected,
					"effect " + def.id() + " beamPreset should be " + expected + " (surface "
							+ def.surface() + "), got " + preset);
		}

		// Golden spot-checks (surface families verified against the frozen rows):
		// effect 0 = AURORA (ordinal 3 % 8 = 3) -> PRISM; effect 74 = RINGS
		// (ordinal 5 % 8 = 5) -> EMBER; effect 104 = INTERFERENCE (ordinal 11 % 8
		// = 3) -> PRISM. (The RENDERED growth from 4 to 8 styles intentionally
		// redistributed the AUTO presets: RINGS moved from PULSE to EMBER.)
		helper.assertTrue(EffectRegistry.get(0).beamPreset() == BeamStyle.PRISM,
				"effect 0 (AURORA) should derive PRISM, got " + EffectRegistry.get(0).beamPreset());
		helper.assertTrue(EffectRegistry.get(74).beamPreset() == BeamStyle.EMBER,
				"effect 74 (RINGS) should derive EMBER, got " + EffectRegistry.get(74).beamPreset());
		helper.assertTrue(EffectRegistry.get(104).beamPreset() == BeamStyle.PRISM,
				"effect 104 (INTERFERENCE) should derive PRISM, got " + EffectRegistry.get(104).beamPreset());
		helper.succeed();
	}
}

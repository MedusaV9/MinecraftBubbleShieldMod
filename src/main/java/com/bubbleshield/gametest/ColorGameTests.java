package com.bubbleshield.gametest;

import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.effect.ContextModifier;
import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.ContextProfile;
import com.bubbleshield.effect.EffectRegistry;
import com.bubbleshield.net.ServerNet;
import com.bubbleshield.net.ShieldPayloads;
import com.bubbleshield.registry.ModBlocks;
import com.bubbleshield.shield.ShieldState;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.BossEvent;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/**
 * Coverage for milestone V5: the owner-picked dye recolor (colorOverride) flowing
 * through ContextState.pickColor, NBT persistence, the C2S validation rule and the
 * ShieldVisual sync payload, plus the boss bar color bucket.
 */
public class ColorGameTests {
	/**
	 * A dedicated (but otherwise vanilla-default) test environment,
	 * {@code data/bubbleshield/test_environment/color.json}. The vanilla runner batches
	 * tests by environment (50 per batch, ticked in parallel); adding these tests to the
	 * shared default batch would push the pre-existing suite past 50 and reshuffle which
	 * tests overlap in time, occasionally colliding two identically-named
	 * "test-mock-player" mocks in the PlayerList (getPlayerByName then resolves the
	 * wrong UUID). A separate environment keeps this class in its own batch, leaving
	 * the existing 50-test batch exactly as it was.
	 */
	private static final String ISOLATED_ENVIRONMENT = "bubbleshield:color";
	private static final BlockPos PROJECTOR_POS = new BlockPos(4, 2, 4);
	private static final int PLENTY_OF_FUEL = 600;
	/** Opaque red (negative as a signed int, like every real override). */
	private static final int OPAQUE_RED = 0xFFC81414;

	private static BubbleShieldBlockEntity placeProjector(GameTestHelper helper, float targetRadius) {
		helper.setBlock(PROJECTOR_POS, ModBlocks.BUBBLE_SHIELD_PROJECTOR);
		BubbleShieldBlockEntity be = helper.getBlockEntity(PROJECTOR_POS, BubbleShieldBlockEntity.class);
		be.getShieldState().targetRadius = targetRadius;
		return be;
	}

	/** (a) pickColor returns the override pair when set and the authored colors when unset. Pure. */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void pickColorHonorsOverride(GameTestHelper helper) {
		int authoredPrimary = 0xFF66FFAA;
		int authoredSecondary = 0xFF1E9E6E;

		// Unset (secondary constructor and NEUTRAL): authored palette flows through.
		ContextState unset = new ContextState(1.0F, 1, false, false);
		helper.assertTrue(unset.overridePrimary() == -1 && unset.overrideSecondary() == -1,
				"the 4-arg constructor should leave both override fields unset (-1)");
		helper.assertTrue(unset.pickColor(authoredPrimary, authoredSecondary) == authoredPrimary,
				"without an override, pickColor should return the authored primary");
		helper.assertTrue(ContextState.NEUTRAL.pickColor(authoredPrimary, authoredSecondary) == authoredPrimary,
				"NEUTRAL should stay override-free");
		helper.assertTrue(new ContextState(1.0F, 1, true, false).pickColor(authoredPrimary, authoredSecondary) == authoredSecondary,
				"without an override, useSecondaryColor should return the authored secondary");

		// Set: the override pair replaces the authored palette entirely.
		ContextState overridden = unset.withColorOverride(OPAQUE_RED);
		int expectedSecondary = ContextModifier.deriveOverrideSecondary(OPAQUE_RED);
		helper.assertTrue(overridden.pickColor(authoredPrimary, authoredSecondary) == OPAQUE_RED,
				"with an override, pickColor should return the override primary");
		helper.assertTrue(new ContextState(1.0F, 1, true, false).withColorOverride(OPAQUE_RED)
						.pickColor(authoredPrimary, authoredSecondary) == expectedSecondary,
				"with an override, useSecondaryColor should return the derived (x0.55) secondary");

		// The derived secondary is the RGB channels scaled x0.55 with alpha preserved.
		helper.assertTrue(expectedSecondary == 0xFF6E0B0B,
				"deriveOverrideSecondary(0xFFC81414) should be 0xFF6E0B0B, got "
						+ Integer.toHexString(expectedSecondary));
		helper.assertTrue((ContextModifier.deriveOverrideSecondary(0xFFFFFFFF) & 0xFF000000) == 0xFF000000,
				"deriveOverrideSecondary must keep the alpha byte");

		// withColorOverride preserves the modulation fields it wraps.
		ContextState modulated = new ContextState(2.0F, 2, true, true).withColorOverride(OPAQUE_RED);
		helper.assertTrue(modulated.countMult() == 2.0F && modulated.periodDivisor() == 2
						&& modulated.useSecondaryColor() && modulated.extraSparks(),
				"withColorOverride must not change the modulation fields");
		helper.succeed();
	}

	/**
	 * (a') secondaryColor resolves a dual-color behavior's SECOND strand/gradient
	 * color: the derived override secondary when an override is set, the authored
	 * secondary otherwise — so a recolor covers BOTH strands, not just the
	 * pickColor-routed primary. Pure.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void secondaryColorHonorsOverride(GameTestHelper helper) {
		int authoredSecondary = 0xFF1E9E6E;

		// Unset: the authored secondary flows through untouched.
		ContextState unset = new ContextState(1.0F, 1, false, false);
		helper.assertTrue(unset.secondaryColor(authoredSecondary) == authoredSecondary,
				"without an override, secondaryColor should return the authored secondary");
		helper.assertTrue(ContextState.NEUTRAL.secondaryColor(authoredSecondary) == authoredSecondary,
				"NEUTRAL should stay override-free for secondaryColor");

		// Set: the derived (x0.55) override secondary replaces the authored one.
		int expectedSecondary = ContextModifier.deriveOverrideSecondary(OPAQUE_RED);
		helper.assertTrue(unset.withColorOverride(OPAQUE_RED).secondaryColor(authoredSecondary) == expectedSecondary,
				"with an override, secondaryColor should return the derived override secondary");

		// Unlike pickColor, secondaryColor is independent of useSecondaryColor: it is
		// the fixed second color of a dual-color pattern, not the context-picked one.
		helper.assertTrue(new ContextState(1.0F, 1, true, false).withColorOverride(OPAQUE_RED)
						.secondaryColor(authoredSecondary) == expectedSecondary,
				"secondaryColor must resolve the override secondary regardless of useSecondaryColor");
		helper.assertTrue(new ContextState(1.0F, 1, true, false).secondaryColor(authoredSecondary) == authoredSecondary,
				"secondaryColor must resolve the authored secondary regardless of useSecondaryColor");
		helper.succeed();
	}

	/** (b) colorOverride NBT round-trip, including the -1 default for pre-V5 data. */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void colorOverrideNbtRoundTrip(GameTestHelper helper) {
		var registries = helper.getLevel().registryAccess();

		ShieldState original = new ShieldState();
		original.colorOverride = OPAQUE_RED;

		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
		original.save(output);
		CompoundTag tag = output.buildResult();
		helper.assertTrue(tag.contains("color_override"), "saved shield NBT should include color_override");

		ShieldState loaded = new ShieldState();
		loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
		helper.assertTrue(loaded.colorOverride == OPAQUE_RED,
				"colorOverride should round-trip through NBT, got " + Integer.toHexString(loaded.colorOverride));

		// Legacy NBT without the key (pre-V5 data) must load as "no override".
		ShieldState legacy = new ShieldState();
		legacy.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, new CompoundTag()));
		helper.assertTrue(legacy.colorOverride == ShieldState.NO_COLOR_OVERRIDE,
				"NBT without color_override should load as -1 (no override)");

		// NBT is untrusted (editable via /data): a translucent or zero-alpha value
		// edited into color_override would render an invisible HUD bar, so load
		// applies the same isValidColorOverride rule as the C2S path and resets
		// anything non-opaque back to the -1 sentinel.
		for (int invalid : new int[] {0, 0x00C81414, 0x7FC81414, 0x00FFFFFF}) {
			tag.putInt("color_override", invalid);
			ShieldState tampered = new ShieldState();
			tampered.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
			helper.assertTrue(tampered.colorOverride == ShieldState.NO_COLOR_OVERRIDE,
					"a non-opaque color_override (" + Integer.toHexString(invalid)
							+ ") edited into the NBT must reset to -1 on load, got "
							+ Integer.toHexString(tampered.colorOverride));
		}

		// The shared rule is one and the same object of truth for both paths.
		helper.assertTrue(ShieldState.isValidColorOverride(ShieldState.NO_COLOR_OVERRIDE)
						&& ShieldState.isValidColorOverride(OPAQUE_RED)
						&& !ShieldState.isValidColorOverride(0x7FC81414),
				"ShieldState.isValidColorOverride should accept -1/opaque and reject translucent");
		helper.succeed();
	}

	/** (c) isValidColorOverride: accept -1 and opaque 0xFFrrggbb, reject anything non-opaque. Pure. */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void colorOverrideValidation(GameTestHelper helper) {
		helper.assertTrue(ServerNet.isValidColorOverride(-1), "-1 (reset to authored palette) must be accepted");
		helper.assertTrue(ServerNet.isValidColorOverride(OPAQUE_RED), "an opaque ARGB color must be accepted");
		helper.assertTrue(ServerNet.isValidColorOverride(0xFF000000), "opaque black must be accepted");

		// Every dye swatch offered by the client picker must validate.
		for (DyeColor dye : DyeColor.values()) {
			helper.assertTrue(ServerNet.isValidColorOverride(dye.getTextureDiffuseColor()),
					"dye " + dye.getName() + " texture diffuse color must be a valid override");
		}

		helper.assertTrue(!ServerNet.isValidColorOverride(0), "fully transparent black must be rejected");
		helper.assertTrue(!ServerNet.isValidColorOverride(0x00C81414), "an alpha-less RGB must be rejected");
		helper.assertTrue(!ServerNet.isValidColorOverride(0x7FC81414), "a translucent ARGB must be rejected");
		helper.assertTrue(!ServerNet.isValidColorOverride(0x00FFFFFF), "0x00FFFFFF (no alpha) must be rejected");
		helper.succeed();
	}

	/** (d) The ShieldVisual sync payload carries the override through its stream codec. */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void syncPayloadCarriesOverride(GameTestHelper helper) {
		ShieldPayloads.ShieldVisual original = new ShieldPayloads.ShieldVisual(
				true, 7, 8.0F, 6.5F, 0.75F, 2, 1, OPAQUE_RED);

		ByteBuf buffer = Unpooled.buffer();
		try {
			ShieldPayloads.ShieldVisual.STREAM_CODEC.encode(buffer, original);
			ShieldPayloads.ShieldVisual decoded = ShieldPayloads.ShieldVisual.STREAM_CODEC.decode(buffer);
			helper.assertTrue(decoded.colorOverride() == OPAQUE_RED,
					"the decoded visual should carry the override, got " + Integer.toHexString(decoded.colorOverride()));
			helper.assertTrue(original.equals(decoded), "the full ShieldVisual should round-trip, got " + decoded);
			helper.assertTrue(buffer.readableBytes() == 0, "the codec should consume the whole buffer");

			// The -1 sentinel (no override) round-trips too.
			ShieldPayloads.ShieldVisual plain = new ShieldPayloads.ShieldVisual(
					false, 0, 16.0F, 0.0F, 1.0F, 0, 0, ShieldState.NO_COLOR_OVERRIDE);
			ShieldPayloads.ShieldVisual.STREAM_CODEC.encode(buffer, plain);
			helper.assertTrue(plain.equals(ShieldPayloads.ShieldVisual.STREAM_CODEC.decode(buffer)),
					"a visual without an override should round-trip");
		} finally {
			buffer.release();
		}

		helper.succeed();
	}

	/** (e) The boss bar bucket follows the override and returns to the authored bucket on reset. */
	@GameTest(environment = ISOLATED_ENVIRONMENT, maxTicks = 200, padding = 16)
	public void bossBarBucketRespectsOverride(GameTestHelper helper) {
		// Effect 0's authored primary (0x66FFAA) buckets to GREEN.
		helper.assertTrue((EffectRegistry.get(0).argbPrimary() & 0xFFFFFF) == 0x66FFAA,
				"effect 0's authored primary should be 0x66FFAA");

		BubbleShieldBlockEntity be = placeProjector(helper, 4.0F);
		be.addFuelSeconds(PLENTY_OF_FUEL);
		be.getShieldState().effectId = 0;
		helper.assertTrue(be.tryActivate(), "shield should activate");

		helper.runAfterDelay(2, () -> {
			ServerBossEvent event = be.getBossEvent();
			helper.assertTrue(event != null, "an active shield should lazily create its boss event");
			helper.assertTrue(event.getColor() == BossEvent.BossBarColor.GREEN,
					"without an override the bar should use the authored bucket (GREEN), got " + event.getColor());

			be.setColorOverride(OPAQUE_RED);
			helper.runAfterDelay(2, () -> {
				helper.assertTrue(event.getColor() == BossEvent.BossBarColor.RED,
						"a red override should re-bucket the bar to RED, got " + event.getColor());

				be.setColorOverride(ShieldState.NO_COLOR_OVERRIDE);
				helper.runAfterDelay(2, () -> {
					helper.assertTrue(event.getColor() == BossEvent.BossBarColor.GREEN,
							"resetting the override should restore the authored bucket (GREEN), got " + event.getColor());
					helper.succeed();
				});
			});
		});
	}
}

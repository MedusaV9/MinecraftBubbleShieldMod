package com.bubbleshield.gametest;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.ContextProfile;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.EffectRegistry;
import com.bubbleshield.effect.GuardStyle;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.effect.SurfaceTemplate;
import com.bubbleshield.registry.ModBlocks;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Machine-enforcement of the 350-effect catalogue invariants: registry validity,
 * the uniqueness matrix, EN/DE lang parity and the screen-fx JSON cross-check.
 */
public class EffectCatalogGameTests {
	@GameTest
	public void allEffectsValid(GameTestHelper helper) {
		EffectRegistry.validate();
		helper.assertTrue(EffectRegistry.COUNT == 350, "catalogue should contain exactly 350 effects");
		helper.assertTrue(EffectRegistry.ALL.size() == EffectRegistry.COUNT, "registry should expose exactly " + EffectRegistry.COUNT + " effect definitions");
		helper.assertTrue(InsideEffectBehavior.REGISTRY.size() == 50, "exactly 50 inside behaviors should be registered");

		// Exact-cover usage rule: the 350-row catalogue uses every registered
		// behavior (each exactly CATALOGUE_VARIANTS = 7 times, per validate()).
		Set<String> used = new HashSet<>();
		for (EffectDefinition def : EffectRegistry.ALL) {
			used.add(def.insideBehaviorId());
		}
		helper.assertTrue(
				used.equals(InsideEffectBehavior.REGISTRY.keySet()),
				"the catalogue should use exactly the " + InsideEffectBehavior.REGISTRY.size()
						+ " registered behaviors, used " + used.size());

		helper.succeed();
	}

	/**
	 * Ticks every effect's inside behavior directly for several game times and checks
	 * that every effect's ambient sound id resolves in the vanilla sound registry.
	 * Split into two id ranges (0..174 here, 175..349 in
	 * {@link #allBehaviorsSmokeUpper}) so neither half's synchronous tick storm
	 * dominates its batch at the 350-effect catalogue size.
	 */
	@GameTest(padding = 16)
	public void allBehaviorsSmoke(GameTestHelper helper) {
		smokeIdRange(helper, 0, 175);

		// Full matrix: every registered behavior x variants 0..6 under a synthetic
		// definition, on both shapes' geometry inputs via the placed projector's shape.
		BlockPos projectorPos = new BlockPos(4, 2, 4);
		helper.setBlock(projectorPos, ModBlocks.BUBBLE_SHIELD_PROJECTOR);
		BubbleShieldBlockEntity be = helper.getBlockEntity(projectorPos, BubbleShieldBlockEntity.class);
		ServerLevel level = helper.getLevel();
		Vec3 center = Vec3.atCenterOf(helper.absolutePos(projectorPos));
		for (Map.Entry<String, InsideEffectBehavior> entry : InsideEffectBehavior.REGISTRY.entrySet()) {
			for (int variant = 0; variant <= 6; variant++) {
				EffectDefinition def = EffectDefinition.of(0, 0xFFFF8800, 0xFF884400, SurfaceTemplate.AURORA, 0.5F, 0.8F,
						entry.getKey(), variant, 1.0F, GuardStyle.NONE, ContextProfile.NONE, "block.beacon.ambient", 1.0F, 160, "tint");
				for (long gameTime : new long[] {0L, 10L, 20L, 30L, 40L, 100L, 200L}) {
					entry.getValue().tick(level, center, 6.0F, be.getShieldState().shape, def, gameTime, ContextState.NEUTRAL);
				}
			}
		}

		helper.succeed();
	}

	/** Second half of the per-effect smoke: ids 175..349 (see {@link #allBehaviorsSmoke}). */
	@GameTest(padding = 16)
	public void allBehaviorsSmokeUpper(GameTestHelper helper) {
		smokeIdRange(helper, 175, 350);
		helper.succeed();
	}

	/** Ticks each effect in [firstId, endExclusive) and checks its ambient sound resolves. */
	private static void smokeIdRange(GameTestHelper helper, int firstId, int endExclusive) {
		BlockPos projectorPos = new BlockPos(4, 2, 4);
		helper.setBlock(projectorPos, ModBlocks.BUBBLE_SHIELD_PROJECTOR);
		BubbleShieldBlockEntity be = helper.getBlockEntity(projectorPos, BubbleShieldBlockEntity.class);
		ServerLevel level = helper.getLevel();
		Vec3 center = Vec3.atCenterOf(helper.absolutePos(projectorPos));

		for (int id = firstId; id < endExclusive; id++) {
			EffectDefinition def = EffectRegistry.get(id);
			be.getShieldState().effectId = def.id();

			InsideEffectBehavior behavior = InsideEffectBehavior.get(def.insideBehaviorId());
			helper.assertTrue(behavior != null, "effect " + def.id() + " references unregistered behavior " + def.insideBehaviorId());
			for (long gameTime : new long[] {0L, 10L, 20L, 30L, 40L}) {
				behavior.tick(level, center, 6.0F, be.getShieldState().shape, def, gameTime, ContextState.NEUTRAL);
			}

			Identifier soundId = Identifier.parse("minecraft:" + def.ambientSoundId());
			helper.assertTrue(
					BuiltInRegistries.SOUND_EVENT.containsKey(soundId),
					"effect " + def.id() + " ambient sound does not resolve: " + soundId);
		}
	}

	/**
	 * The surface-family axis is complete: 24 technique families exist and every one of
	 * them is used by the catalogue. The bubble shader files themselves live in the CLIENT
	 * source set (not on this dedicated-server classpath), so their GLSL is verified
	 * out-of-band by {@code tools/validate_shaders.py} (glslangValidator) plus the build.
	 */
	@GameTest
	public void surfaceTemplateCatalogComplete(GameTestHelper helper) {
		helper.assertTrue(SurfaceTemplate.values().length == 24, "exactly 24 surface families should exist, found " + SurfaceTemplate.values().length);

		Set<SurfaceTemplate> used = new HashSet<>();
		for (EffectDefinition def : EffectRegistry.ALL) {
			used.add(def.surface());
		}
		for (SurfaceTemplate template : SurfaceTemplate.values()) {
			helper.assertTrue(used.contains(template), "surface template " + template + " is not used by any effect");
		}

		helper.succeed();
	}

	/**
	 * The screen-template axis is complete: 16 templates exist and every one of them is
	 * used by the catalogue. The shader files behind them are checked by
	 * {@code ShieldGameTests.postEffectAssetsExist} and {@code tools/validate_shaders.py}.
	 */
	@GameTest
	public void screenTemplateCatalogComplete(GameTestHelper helper) {
		helper.assertTrue(EffectRegistry.SCREEN_TEMPLATES.size() == 16, "exactly 16 screen templates should exist, found " + EffectRegistry.SCREEN_TEMPLATES.size());

		Set<String> used = new HashSet<>();
		for (EffectDefinition def : EffectRegistry.ALL) {
			used.add(def.screenTemplate());
		}
		for (String template : EffectRegistry.SCREEN_TEMPLATES) {
			helper.assertTrue(used.contains(template), "screen template " + template + " is not used by any effect");
		}

		helper.succeed();
	}

	/**
	 * The per-family (id/5) "no repeated surface/screenTemplate" invariant lives in
	 * {@link EffectRegistry#validate()} (checked by {@link #allEffectsValid}) so it
	 * also runs at mod init; this test keeps the remaining pairwise-uniqueness axes,
	 * plus the global "(surface, screenTemplate) pair used at most 3 times" cap and
	 * the per-family "no repeated behavior id" rule. The cap of 3 is the tightest
	 * the 350-row table satisfies: three legacy pairs (frozen ids 0..104) already
	 * sit at 3 and the expansion rows never push any pair past it.
	 */
	@GameTest
	public void uniquenessMatrixHolds(GameTestHelper helper) {
		Set<Long> palettes = new HashSet<>();
		Set<String> behaviorVariants = new HashSet<>();
		Map<String, Integer> surfaceScreenPairCounts = new HashMap<>();
		Map<Integer, Set<String>> behaviorsPerFamily = new HashMap<>();

		for (EffectDefinition def : EffectRegistry.ALL) {
			long palette = ((long) def.argbPrimary() << 32) | (def.argbSecondary() & 0xFFFFFFFFL);
			helper.assertTrue(palettes.add(palette), "effect " + def.id() + " reuses another effect's palette pair");

			String behaviorVariant = def.insideBehaviorId() + "@" + def.behaviorVariant();
			helper.assertTrue(behaviorVariants.add(behaviorVariant), "effect " + def.id() + " reuses behavior/variant pair " + behaviorVariant);

			String surfaceScreenPair = def.surface() + "+" + def.screenTemplate();
			int pairCount = surfaceScreenPairCounts.merge(surfaceScreenPair, 1, Integer::sum);
			helper.assertTrue(pairCount <= 3, "(surface, screenTemplate) pair " + surfaceScreenPair + " used more than 3 times (effect " + def.id() + ")");

			int family = def.id() / 5;
			helper.assertTrue(
					behaviorsPerFamily.computeIfAbsent(family, f -> new HashSet<>()).add(def.insideBehaviorId()),
					"family " + family + " repeats behavior " + def.insideBehaviorId() + " (effect " + def.id() + ")");
		}

		helper.succeed();
	}

	/**
	 * EN/DE parity over the ENTIRE key set (not just effect names): the key sets must be
	 * identical, so every gui/advancement/axis key added in one language must exist in
	 * the other. Effect names 00..349 must additionally be present in both.
	 */
	@GameTest
	public void langKeysComplete(GameTestHelper helper) {
		Set<String> enKeys = readLangKeys(helper, "/assets/bubbleshield/lang/en_us.json");
		Set<String> deKeys = readLangKeys(helper, "/assets/bubbleshield/lang/de_de.json");
		helper.assertTrue(enKeys.equals(deKeys), "en_us.json and de_de.json must have identical key sets");

		for (int i = 0; i < EffectRegistry.COUNT; i++) {
			String key = String.format(Locale.ROOT, "effect.bubbleshield.%02d", i);
			helper.assertTrue(enKeys.contains(key), "missing lang key: " + key);
		}

		for (String key : new String[] {"gui.bubbleshield.shape.sphere", "gui.bubbleshield.shape.dome", "gui.bubbleshield.tier"}) {
			helper.assertTrue(enKeys.contains(key), "missing lang key: " + key);
		}

		helper.succeed();
	}

	/**
	 * Every axis label used by the effect-picker tooltips resolves to a lang key:
	 * 24 surface families, 50 inside behaviors, 7 guard
	 * styles and 6 context profiles. Keys are derived from the live enums/registry
	 * so the tooltip composition in {@code EffectPickerScreen} and the lang files
	 * cannot drift apart.
	 */
	@GameTest
	public void axisLangKeysComplete(GameTestHelper helper) {
		Set<String> enKeys = readLangKeys(helper, "/assets/bubbleshield/lang/en_us.json");

		for (SurfaceTemplate template : SurfaceTemplate.values()) {
			String key = "surface.bubbleshield." + template.name().toLowerCase(Locale.ROOT);
			helper.assertTrue(enKeys.contains(key), "missing lang key: " + key);
		}

		helper.assertTrue(InsideEffectBehavior.REGISTRY.size() == 50, "expected 50 registered behaviors");
		for (String behaviorId : InsideEffectBehavior.REGISTRY.keySet()) {
			String key = "behavior.bubbleshield." + behaviorId;
			helper.assertTrue(enKeys.contains(key), "missing lang key: " + key);
		}

		for (GuardStyle guard : GuardStyle.values()) {
			String key = "guard.bubbleshield." + guard.name().toLowerCase(Locale.ROOT);
			helper.assertTrue(enKeys.contains(key), "missing lang key: " + key);
		}

		for (ContextProfile context : ContextProfile.values()) {
			String key = "context.bubbleshield." + context.name().toLowerCase(Locale.ROOT);
			helper.assertTrue(enKeys.contains(key), "missing lang key: " + key);
		}

		helper.succeed();
	}

	@GameTest
	public void screenTemplateMatchesJson(GameTestHelper helper) {
		for (EffectDefinition def : EffectRegistry.ALL) {
			String jsonPath = String.format(Locale.ROOT, "/assets/bubbleshield/post_effect/%s.json", def.screenEffectName());
			JsonObject config = readJson(helper, jsonPath);
			JsonArray passes = config.getAsJsonArray("passes");
			helper.assertTrue(passes != null && !passes.isEmpty(), jsonPath + " should declare at least one pass");

			String fragmentShader = passes.get(0).getAsJsonObject().get("fragment_shader").getAsString();
			String expected = "bubbleshield:screenfx/" + def.screenTemplate();
			helper.assertTrue(
					expected.equals(fragmentShader),
					jsonPath + " first pass should use " + expected + " but uses " + fragmentShader);
		}

		helper.succeed();
	}

	private static Set<String> readLangKeys(GameTestHelper helper, String path) {
		return readJson(helper, path).keySet();
	}

	private static JsonObject readJson(GameTestHelper helper, String path) {
		try (InputStream in = EffectCatalogGameTests.class.getResourceAsStream(path)) {
			helper.assertTrue(in != null, "missing classpath resource: " + path);
			return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (Exception e) {
			throw helper.assertionException("failed to read/parse " + path + ": " + e);
		}
	}
}

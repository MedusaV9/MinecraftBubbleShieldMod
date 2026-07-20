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
 * frozen-row golden values (PARAM_CYCLE + spot-checked V1/V2 rows), the uniqueness
 * matrix, EN/DE lang parity incl. pairwise-distinct effect display names, and the
 * screen-fx JSON cross-check.
 */
public class EffectCatalogGameTests {
	@GameTest
	public void allEffectsValid(GameTestHelper helper) {
		EffectRegistry.validate();
		helper.assertTrue(EffectRegistry.COUNT == 350, "catalogue should contain exactly 350 effects");
		helper.assertTrue(EffectRegistry.ALL.size() == EffectRegistry.COUNT, "registry should expose exactly " + EffectRegistry.COUNT + " effect definitions");
		helper.assertTrue(InsideEffectBehavior.REGISTRY.size() == 50, "exactly 50 inside behaviors should be registered");

		// PARAM_CYCLE is FROZEN at the V1 catalogue size: retuning it would silently
		// change the derived params (and the generated post-effect JSON uniforms) of
		// ids 0..74, which must stay stable across catalogue expansions.
		helper.assertTrue(EffectRegistry.PARAM_CYCLE == 75, "PARAM_CYCLE must stay frozen at 75, found " + EffectRegistry.PARAM_CYCLE);

		// Golden freeze spot-checks: the core row fields of the frozen V1/V2 ids
		// 0..104 must never change (spot-asserted at the range edges). The float
		// literals are the exact float32 results of the frozen derivations
		// paramB = 0.4 + ((id * 37) % 75) / 75 and
		// behaviorStrength = 0.8 + 0.7 * ((id * 23) % 75) / 75.
		assertFrozenRow(helper, 0, 0xFF66FFAA, 0xFF1E9E6E, 0.4F, 0.8F);
		assertFrozenRow(helper, 74, 0xFFCFD8DC, 0xFF4E342E, 0.9066666F, 1.2853334F);
		assertFrozenRow(helper, 104, 0xFFFFE600, 0xFF3D0099, 0.7066667F, 1.4253333F);

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

	/** Asserts one frozen catalogue row's palette/paramB/behaviorStrength golden values. */
	private static void assertFrozenRow(GameTestHelper helper, int id, int argbPrimary, int argbSecondary,
			float paramB, float behaviorStrength) {
		EffectDefinition def = EffectRegistry.get(id);
		helper.assertTrue(def.id() == id, "get(" + id + ") should return the row with that id, got " + def.id());
		helper.assertTrue(def.argbPrimary() == argbPrimary,
				String.format(Locale.ROOT, "frozen id %d argbPrimary changed: expected 0x%08X, found 0x%08X", id, argbPrimary, def.argbPrimary()));
		helper.assertTrue(def.argbSecondary() == argbSecondary,
				String.format(Locale.ROOT, "frozen id %d argbSecondary changed: expected 0x%08X, found 0x%08X", id, argbSecondary, def.argbSecondary()));
		helper.assertTrue(Math.abs(def.paramB() - paramB) < 1.0e-4F,
				"frozen id " + id + " paramB changed: expected " + paramB + ", found " + def.paramB());
		helper.assertTrue(Math.abs(def.behaviorStrength() - behaviorStrength) < 1.0e-4F,
				"frozen id " + id + " behaviorStrength changed: expected " + behaviorStrength + ", found " + def.behaviorStrength());
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
	 * The screen-family axis is complete: 20 technique families exist and every one of
	 * them is used by the catalogue. The families are metadata (like the surface
	 * families) -- the actual per-effect sfx shaders behind them are checked by
	 * {@code ShieldGameTests.postEffectAssetsExist}, {@link #screenTemplateMatchesJson}
	 * and {@code tools/validate_shaders.py}.
	 */
	@GameTest
	public void screenTemplateCatalogComplete(GameTestHelper helper) {
		helper.assertTrue(EffectRegistry.SCREEN_TEMPLATES.size() == 20, "exactly 20 screen families should exist, found " + EffectRegistry.SCREEN_TEMPLATES.size());

		Set<String> used = new HashSet<>();
		for (EffectDefinition def : EffectRegistry.ALL) {
			used.add(def.screenTemplate());
		}
		for (String template : EffectRegistry.SCREEN_TEMPLATES) {
			helper.assertTrue(used.contains(template), "screen family " + template + " is not used by any effect");
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
	 * the other. Effect names 00..349 must additionally be present in both, and the
	 * 350 effect display names must be pairwise distinct in BOTH languages (duplicate
	 * names would make two effects indistinguishable in the picker/boss bar).
	 */
	@GameTest
	public void langKeysComplete(GameTestHelper helper) {
		JsonObject en = readJson(helper, "/assets/bubbleshield/lang/en_us.json");
		JsonObject de = readJson(helper, "/assets/bubbleshield/lang/de_de.json");
		Set<String> enKeys = en.keySet();
		Set<String> deKeys = de.keySet();
		helper.assertTrue(enKeys.equals(deKeys), "en_us.json and de_de.json must have identical key sets");

		Set<String> enNames = new HashSet<>();
		Set<String> deNames = new HashSet<>();
		for (int i = 0; i < EffectRegistry.COUNT; i++) {
			String key = String.format(Locale.ROOT, "effect.bubbleshield.%02d", i);
			helper.assertTrue(enKeys.contains(key), "missing lang key: " + key);
			helper.assertTrue(enNames.add(en.get(key).getAsString()),
					"duplicate en_us effect display name: '" + en.get(key).getAsString() + "' (" + key + ")");
			helper.assertTrue(deNames.add(de.get(key).getAsString()),
					"duplicate de_de effect display name: '" + de.get(key).getAsString() + "' (" + key + ")");
		}

		helper.assertTrue(enNames.size() == EffectRegistry.COUNT, "expected " + EffectRegistry.COUNT + " distinct en_us effect names, found " + enNames.size());
		helper.assertTrue(deNames.size() == EffectRegistry.COUNT, "expected " + EffectRegistry.COUNT + " distinct de_de effect names, found " + deNames.size());

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

	/**
	 * Cross-checks the three screen-fx artifacts per effect: the generated
	 * post_effect JSON's first pass must reference the effect's OWN
	 * {@code screenfx/sfx_NNN} shader, and the row's screen family must match what
	 * tools/gen_screen_shaders.py actually baked into that shader, via the generated
	 * screen manifest (a classpath copy is emitted next to the assets at
	 * generation time).
	 */
	@GameTest
	public void screenTemplateMatchesJson(GameTestHelper helper) {
		JsonObject manifest = readJson(helper, "/assets/bubbleshield/screen_manifest.json");

		for (EffectDefinition def : EffectRegistry.ALL) {
			String jsonPath = String.format(Locale.ROOT, "/assets/bubbleshield/post_effect/%s.json", def.screenEffectName());
			JsonObject config = readJson(helper, jsonPath);
			JsonArray passes = config.getAsJsonArray("passes");
			helper.assertTrue(passes != null && !passes.isEmpty(), jsonPath + " should declare at least one pass");

			String fragmentShader = passes.get(0).getAsJsonObject().get("fragment_shader").getAsString();
			String expected = "bubbleshield:" + def.screenShaderId();
			helper.assertTrue(
					expected.equals(fragmentShader),
					jsonPath + " first pass should use " + expected + " but uses " + fragmentShader);

			JsonObject entry = manifest.getAsJsonObject(Integer.toString(def.id()));
			helper.assertTrue(entry != null, "screen_manifest.json is missing an entry for effect " + def.id());
			String manifestFamily = entry.get("family").getAsString();
			helper.assertTrue(
					def.screenTemplate().equals(manifestFamily),
					"effect " + def.id() + " row family " + def.screenTemplate()
							+ " does not match screen_manifest.json family " + manifestFamily);
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

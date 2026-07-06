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
 * Machine-enforcement of the 75-effect catalogue invariants: registry validity,
 * the uniqueness matrix, EN/DE lang parity and the screen-fx JSON cross-check.
 */
public class EffectCatalogGameTests {
	@GameTest
	public void allEffectsValid(GameTestHelper helper) {
		EffectRegistry.validate();
		helper.assertTrue(EffectRegistry.COUNT == 75, "catalogue should contain exactly 75 effects");
		helper.assertTrue(EffectRegistry.ALL.size() == EffectRegistry.COUNT, "registry should expose exactly " + EffectRegistry.COUNT + " effect definitions");
		helper.assertTrue(InsideEffectBehavior.REGISTRY.size() == 25, "exactly 25 inside behaviors should be registered");
		helper.succeed();
	}

	/**
	 * Ticks every effect's inside behavior directly for several game times and checks
	 * that every effect's ambient sound id resolves in the vanilla sound registry.
	 */
	@GameTest(padding = 16)
	public void allBehaviorsSmoke(GameTestHelper helper) {
		BlockPos projectorPos = new BlockPos(4, 2, 4);
		helper.setBlock(projectorPos, ModBlocks.BUBBLE_SHIELD_PROJECTOR);
		BubbleShieldBlockEntity be = helper.getBlockEntity(projectorPos, BubbleShieldBlockEntity.class);
		ServerLevel level = helper.getLevel();
		Vec3 center = Vec3.atCenterOf(helper.absolutePos(projectorPos));

		for (EffectDefinition def : EffectRegistry.ALL) {
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

		helper.succeed();
	}

	/**
	 * The surface-template axis is complete: 12 templates exist and every one of them is
	 * used by the catalogue. The bubble shader files themselves live in the CLIENT source
	 * set (not on this dedicated-server classpath), so their GLSL is verified out-of-band
	 * by {@code tools/validate_shaders.py} (glslangValidator) plus the build.
	 */
	@GameTest
	public void surfaceTemplateCatalogComplete(GameTestHelper helper) {
		helper.assertTrue(SurfaceTemplate.values().length == 12, "exactly 12 surface templates should exist, found " + SurfaceTemplate.values().length);

		Set<SurfaceTemplate> used = new HashSet<>();
		for (EffectDefinition def : EffectRegistry.ALL) {
			used.add(def.surface());
		}
		for (SurfaceTemplate template : SurfaceTemplate.values()) {
			helper.assertTrue(used.contains(template), "surface template " + template + " is not used by any effect");
		}

		helper.succeed();
	}

	@GameTest
	public void uniquenessMatrixHolds(GameTestHelper helper) {
		Set<Long> palettes = new HashSet<>();
		Set<String> behaviorVariants = new HashSet<>();
		Map<Integer, Set<String>> surfacesPerFamily = new HashMap<>();
		Map<Integer, Set<String>> screenFxPerFamily = new HashMap<>();

		for (EffectDefinition def : EffectRegistry.ALL) {
			long palette = ((long) def.argbPrimary() << 32) | (def.argbSecondary() & 0xFFFFFFFFL);
			helper.assertTrue(palettes.add(palette), "effect " + def.id() + " reuses another effect's palette pair");

			String behaviorVariant = def.insideBehaviorId() + "@" + def.behaviorVariant();
			helper.assertTrue(behaviorVariants.add(behaviorVariant), "effect " + def.id() + " reuses behavior/variant pair " + behaviorVariant);

			int family = def.id() / 5;
			String surfaceName = def.surface().name().toLowerCase(Locale.ROOT);
			helper.assertTrue(
					surfacesPerFamily.computeIfAbsent(family, f -> new HashSet<>()).add(surfaceName),
					"family " + family + " repeats surface " + surfaceName);
			helper.assertTrue(
					screenFxPerFamily.computeIfAbsent(family, f -> new HashSet<>()).add(def.screenTemplate()),
					"family " + family + " repeats screen template " + def.screenTemplate());
		}

		helper.succeed();
	}

	/**
	 * EN/DE parity over the ENTIRE key set (not just effect names): the key sets must be
	 * identical, so every gui/advancement/axis key added in one language must exist in
	 * the other. Effect names 00..74 must additionally be present in both.
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
	 * 12 surface templates, 25 inside behaviors, 7 guard styles and 6 context profiles.
	 * Keys are derived from the live enums/registry so the tooltip composition in
	 * {@code EffectPickerScreen} and the lang files cannot drift apart.
	 */
	@GameTest
	public void axisLangKeysComplete(GameTestHelper helper) {
		Set<String> enKeys = readLangKeys(helper, "/assets/bubbleshield/lang/en_us.json");

		for (SurfaceTemplate template : SurfaceTemplate.values()) {
			String key = "surface.bubbleshield." + template.name().toLowerCase(Locale.ROOT);
			helper.assertTrue(enKeys.contains(key), "missing lang key: " + key);
		}

		helper.assertTrue(InsideEffectBehavior.REGISTRY.size() == 25, "expected 25 registered behaviors");
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

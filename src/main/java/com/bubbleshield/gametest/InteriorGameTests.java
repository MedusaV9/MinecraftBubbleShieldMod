package com.bubbleshield.gametest;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import com.bubbleshield.effect.EffectRegistry;
import com.bubbleshield.effect.SurfaceTemplate;
import com.bubbleshield.interior.InteriorScatter;
import com.bubbleshield.interior.InteriorThemes;
import com.bubbleshield.shield.ShieldGeometry;
import com.bubbleshield.shield.ShieldShape;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;

/**
 * Coverage for WP-Int "interior elements": the two sprite sheets ship on the
 * classpath with the exact expected geometry, the {@link InteriorScatter} is
 * deterministic / shape-contained / clamped, its primitive containment mirror
 * agrees with the real {@link ShieldGeometry}, and the {@link InteriorThemes}
 * catalogue is exhaustive (all 840 effect ids, all 60 surface templates, the
 * novelty overrides confined to the non-frozen band).
 */
public class InteriorGameTests {
	/**
	 * A dedicated (but otherwise vanilla-default) test environment,
	 * {@code data/bubbleshield/test_environment/interior.json}: the vanilla runner
	 * batches tests by environment, and adding this class to a pre-existing batch
	 * would reshuffle which tests overlap in time (see ColorGameTests).
	 */
	private static final String ISOLATED_ENVIRONMENT = "bubbleshield:interior";

	private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
	/** Both sheets are exactly 512x512 (8x8 x 64px pixel cells; 4x4 x 128px soft cells). */
	private static final int SHEET_SIZE = 512;

	/**
	 * (1) Both interior sprite sheets ship on the classpath (main resources, so
	 * this headless server-side test can see them — the same reasoning as the
	 * surface atlas), start with the PNG signature, and their IHDR declares
	 * exactly 512x512. The soft sheet's .mcmeta sibling must exist and parse
	 * (blur+clamp = LINEAR sampling for vanilla consumers; the render setup pins
	 * its own sampler anyway).
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void interiorSheetsShipAndParse(GameTestHelper helper) {
		assertSheet(helper, "/assets/bubbleshield/textures/interior/interior_pixel.png");
		assertSheet(helper, "/assets/bubbleshield/textures/interior/interior_soft.png");

		String mcmetaPath = "/assets/bubbleshield/textures/interior/interior_soft.png.mcmeta";
		try (InputStream in = InteriorGameTests.class.getResourceAsStream(mcmetaPath)) {
			helper.assertTrue(in != null, "missing " + mcmetaPath);
			JsonObject meta = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			JsonObject texture = meta.getAsJsonObject("texture");
			helper.assertTrue(texture != null && texture.get("blur").getAsBoolean(),
					mcmetaPath + " should declare texture.blur = true");
		} catch (Exception e) {
			throw helper.assertionException("failed to read/parse " + mcmetaPath + ": " + e);
		}

		helper.succeed();
	}

	/** PNG signature + IHDR geometry of one sheet (IHDR is always the first chunk). */
	private static void assertSheet(GameTestHelper helper, String path) {
		try (InputStream in = InteriorGameTests.class.getResourceAsStream(path)) {
			helper.assertTrue(in != null, "missing interior sheet: " + path);
			byte[] header = in.readNBytes(33);
			helper.assertTrue(header.length >= 24, path + " is truncated (" + header.length + " bytes)");
			helper.assertTrue(Arrays.equals(Arrays.copyOf(header, 8), PNG_SIGNATURE),
					path + " does not start with the PNG signature");
			// Bytes 12..15 are the first chunk's type; 16..23 its width/height.
			helper.assertTrue(header[12] == 'I' && header[13] == 'H' && header[14] == 'D' && header[15] == 'R',
					path + " first chunk is not IHDR");
			int width = ((header[16] & 0xFF) << 24) | ((header[17] & 0xFF) << 16) | ((header[18] & 0xFF) << 8) | (header[19] & 0xFF);
			int height = ((header[20] & 0xFF) << 24) | ((header[21] & 0xFF) << 16) | ((header[22] & 0xFF) << 8) | (header[23] & 0xFF);
			helper.assertTrue(width == SHEET_SIZE && height == SHEET_SIZE,
					path + " should be " + SHEET_SIZE + "x" + SHEET_SIZE + ", got " + width + "x" + height);
		} catch (Exception e) {
			throw helper.assertionException("failed to read " + path + ": " + e);
		}
	}

	/**
	 * (2) The scatter is a pure function of its key: the same
	 * (pos, effectId, shape, count) yields a bitwise-identical array on repeated
	 * calls, and changing the effect id or the position decorrelates the stream.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void scatterIsDeterministic(GameTestHelper helper) {
		GlobalPos pos = new GlobalPos(helper.getLevel().dimension(), new BlockPos(100, 64, -200));
		float[] first = InteriorScatter.scatter(pos, 633, ShieldShape.SPHERE, 48);
		float[] second = InteriorScatter.scatter(pos, 633, ShieldShape.SPHERE, 48);
		helper.assertTrue(Arrays.equals(first, second), "the same key must produce an identical array");
		helper.assertTrue(first.length == 48 * InteriorScatter.STRIDE,
				"48 elements must pack " + (48 * InteriorScatter.STRIDE) + " floats, got " + first.length);

		float[] otherEffect = InteriorScatter.scatter(pos, 575, ShieldShape.SPHERE, 48);
		helper.assertTrue(!Arrays.equals(first, otherEffect), "a different effect id must decorrelate the scatter");

		GlobalPos otherPos = new GlobalPos(helper.getLevel().dimension(), new BlockPos(101, 64, -200));
		float[] moved = InteriorScatter.scatter(otherPos, 633, ShieldShape.SPHERE, 48);
		helper.assertTrue(!Arrays.equals(first, moved), "a different position must decorrelate the scatter");

		helper.succeed();
	}

	/**
	 * (3) Containment for ALL 10 shapes: every rest position (including the
	 * shelled VOID/cage layers) lies inside the unit shape at the scatter margin,
	 * verified against the REAL {@link ShieldGeometry#isInside} — not the mirror.
	 * Runs across several effect ids so free, shelled, pixel and soft layers are
	 * all exercised (839 = Void Absolute carries the 0.85R dome shell, 809 the
	 * disco layers, 0 a plain template theme).
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void scatterContainmentAllShapes(GameTestHelper helper) {
		int[] effectIds = {0, 442, 575, 809, 839};
		// A hair above the margin absorbs the double->float rounding of the
		// packed unit coordinates.
		double bound = InteriorScatter.MARGIN + 1.0e-4;
		for (ShieldShape shape : ShieldShape.values()) {
			for (int effectId : effectIds) {
				GlobalPos pos = new GlobalPos(helper.getLevel().dimension(), new BlockPos(7, 80, 7));
				float[] data = InteriorScatter.scatter(pos, effectId, shape, 64);
				for (int i = 0; i < 64; i++) {
					int slot = i * InteriorScatter.STRIDE;
					Vec3 point = new Vec3(data[slot], data[slot + 1], data[slot + 2]);
					helper.assertTrue(ShieldGeometry.isInside(shape, Vec3.ZERO, bound, point),
							shape + "/effect " + effectId + " element " + i + " escapes the margin: " + point);
				}
			}
		}

		helper.succeed();
	}

	/**
	 * (4) The primitive containment mirror ({@link InteriorScatter#isInsideUnit},
	 * the renderer's per-frame allocation-free re-clamp) agrees with the real
	 * {@link ShieldGeometry#isInside} on thousands of random samples per shape,
	 * at both the scatter margin and the full radius — the mirror can never
	 * drift from the authoritative geometry silently.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void containmentMirrorAgreesWithShieldGeometry(GameTestHelper helper) {
		Random random = new Random(0x1B7E11);
		double[] radii = {InteriorScatter.MARGIN, 1.0};
		for (ShieldShape shape : ShieldShape.values()) {
			for (int sample = 0; sample < 2000; sample++) {
				double x = random.nextDouble() * 2.4 - 1.2;
				double y = random.nextDouble() * 2.4 - 1.2;
				double z = random.nextDouble() * 2.4 - 1.2;
				for (double radius : radii) {
					boolean mirror = InteriorScatter.isInsideUnit(shape, radius, x, y, z);
					boolean real = ShieldGeometry.isInside(shape, Vec3.ZERO, radius, new Vec3(x, y, z));
					helper.assertTrue(mirror == real, "mirror disagrees with ShieldGeometry for " + shape
							+ " r=" + radius + " at (" + x + ", " + y + ", " + z + "): mirror=" + mirror + " real=" + real);
				}
			}
		}

		helper.succeed();
	}

	/**
	 * (5) Count clamps and packed-slot sanity: counts clamp into
	 * [0, {@link InteriorScatter#MAX_COUNT}]; every packed slot is in its
	 * documented range (sheet-valid sprite ordinal outside the reserved
	 * transparent pixel cells, sizeMul in [0.7, 1.3), phase in [0, 2pi), a known
	 * motion id, seed in [0, 1)).
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void scatterCountClampsAndSlotRanges(GameTestHelper helper) {
		GlobalPos pos = new GlobalPos(helper.getLevel().dimension(), new BlockPos(-30, 90, 45));
		helper.assertTrue(InteriorScatter.scatter(pos, 5, ShieldShape.SPHERE, 0).length == 0,
				"count 0 must pack nothing");
		helper.assertTrue(InteriorScatter.scatter(pos, 5, ShieldShape.SPHERE, -3).length == 0,
				"negative counts must clamp to 0");
		helper.assertTrue(InteriorScatter.scatter(pos, 5, ShieldShape.SPHERE, InteriorScatter.MAX_COUNT + 50).length
						== InteriorScatter.MAX_COUNT * InteriorScatter.STRIDE,
				"oversized counts must clamp to MAX_COUNT");

		for (int effectId : new int[]{0, 526, 633, 717, 728, 756, 839}) {
			float[] data = InteriorScatter.scatter(pos, effectId, ShieldShape.CUBE, 40);
			for (int i = 0; i < 40; i++) {
				int slot = i * InteriorScatter.STRIDE;
				int sprite = (int) data[slot + 3];
				boolean pixelSprite = sprite >= 0 && sprite < 58; // 58..63 are reserved transparent cells
				boolean softSprite = sprite >= InteriorThemes.SOFT_BASE && sprite < InteriorThemes.SPRITE_ORDINAL_COUNT;
				helper.assertTrue(pixelSprite || softSprite, "effect " + effectId + ": invalid sprite ordinal " + sprite);
				helper.assertTrue(data[slot + 4] >= 0.7F && data[slot + 4] < 1.3F,
						"sizeMul out of range: " + data[slot + 4]);
				helper.assertTrue(data[slot + 5] >= 0.0F && data[slot + 5] < (float) (2.0 * Math.PI) + 1.0e-3F,
						"phase out of range: " + data[slot + 5]);
				int motion = (int) data[slot + 6];
				helper.assertTrue(motion >= 0 && motion < InteriorThemes.MOTION_COUNT,
						"unknown motion id " + motion);
				helper.assertTrue(data[slot + 7] >= 0.0F && data[slot + 7] < 1.0F,
						"seed out of range: " + data[slot + 7]);
			}
		}

		helper.succeed();
	}

	/**
	 * (6) The theme catalogue is exhaustive and well-formed: every one of the 840
	 * effect ids resolves to a non-null theme with non-empty layers whose shares
	 * sum to ~1 and whose layer boundaries tile the element count exactly; every
	 * one of the 60 {@link SurfaceTemplate}s maps to a family theme; the novelty
	 * overrides stay a subset of the non-frozen 420..839 band.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void themesAreExhaustive(GameTestHelper helper) {
		for (int id = 0; id < EffectRegistry.COUNT; id++) {
			InteriorThemes.Theme theme = InteriorThemes.themeFor(id);
			helper.assertTrue(theme != null, "effect " + id + " resolves no interior theme");
			helper.assertTrue(!theme.layers().isEmpty(), theme.id() + " has no layers");

			float shareSum = 0.0F;
			for (InteriorThemes.Layer layer : theme.layers()) {
				shareSum += layer.share();
				helper.assertTrue(layer.motion() >= 0 && layer.motion() < InteriorThemes.MOTION_COUNT,
						theme.id() + " uses an unknown motion id " + layer.motion());
				for (int sprite : layer.sprites()) {
					boolean pixelSprite = sprite >= 0 && sprite < 58;
					boolean softSprite = sprite >= InteriorThemes.SOFT_BASE && sprite < InteriorThemes.SPRITE_ORDINAL_COUNT;
					helper.assertTrue(pixelSprite || softSprite, theme.id() + " references invalid sprite " + sprite);
				}
			}

			helper.assertTrue(Math.abs(shareSum - 1.0F) < 1.0e-3F,
					theme.id() + " layer shares sum to " + shareSum + ", expected 1");

			// Layer boundaries must tile [0, count) monotonically for any count.
			for (int count : new int[]{1, 8, 33, 80}) {
				int previous = 0;
				for (int layer = 0; layer <= theme.layers().size(); layer++) {
					int start = InteriorThemes.layerStart(theme, count, layer);
					helper.assertTrue(start >= previous && start <= count,
							theme.id() + " layerStart(" + count + ", " + layer + ") = " + start + " breaks monotonicity");
					previous = start;
				}

				helper.assertTrue(InteriorThemes.layerStart(theme, count, theme.layers().size()) == count,
						theme.id() + " boundaries must end exactly at " + count);
			}
		}

		for (SurfaceTemplate template : SurfaceTemplate.values()) {
			helper.assertTrue(InteriorThemes.templateTheme(template) != null,
					"template " + template + " has no interior theme");
		}

		for (int overrideId : InteriorThemes.overrideIds()) {
			helper.assertTrue(overrideId >= 420 && overrideId <= 839,
					"novelty override " + overrideId + " is outside the non-frozen 420..839 band");
		}

		helper.assertTrue(InteriorThemes.overrideIds().size() == 10,
				"expected exactly 10 novelty overrides, got " + InteriorThemes.overrideIds().size());

		helper.succeed();
	}
}

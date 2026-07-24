package com.bubbleshield.gametest;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.Set;
import java.util.zip.Inflater;

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
	 * surface atlas), start with the PNG signature, declare exactly 512x512
	 * 8-bit RGBA in their IHDR, and their concatenated IDAT stream FULLY zlib
	 * inflates to exactly {@code height * (1 + width * 4)} bytes (one filter
	 * byte plus one RGBA row per scanline). The soft sheet's .mcmeta sibling
	 * must exist and declare BOTH blur and clamp true (LINEAR + edge clamp for
	 * vanilla consumers; the render setup pins its own sampler anyway); the
	 * pixel sheet must have NO .mcmeta at all (NEAREST by default). The soft
	 * sheet's alpha channel must be BINARY (0/255): the interior pipelines are
	 * non-blending cutout (discard only at alpha == 0), so a semitransparent
	 * texel would render fully opaque — the generator Bayer-dithers the soft
	 * falloffs instead. The alpha scan reads the inflated scanlines directly,
	 * asserting the generator's filter-0 (None) row contract first, so no PNG
	 * unfiltering is needed.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void interiorSheetsShipAndParse(GameTestHelper helper) {
		decodeSheet(helper, "/assets/bubbleshield/textures/interior/interior_pixel.png");
		byte[] soft = decodeSheet(helper, "/assets/bubbleshield/textures/interior/interior_soft.png");

		// Binary soft alpha, straight off the (filter-0) inflated scanlines.
		int stride = 1 + SHEET_SIZE * 4;
		for (int y = 0; y < SHEET_SIZE; y++) {
			int rowStart = y * stride;
			if (soft[rowStart] != 0) {
				throw helper.assertionException("interior_soft.png row " + y + " uses PNG filter "
						+ soft[rowStart] + "; the generator writes filter 0 (None) rows");
			}

			for (int x = 0; x < SHEET_SIZE; x++) {
				int alpha = soft[rowStart + 1 + x * 4 + 3] & 0xFF;
				if (alpha != 0 && alpha != 255) {
					throw helper.assertionException("interior_soft.png alpha at (" + x + ", " + y + ") is "
							+ alpha + "; the soft sheet must be binary 0/255 (Bayer-dithered) for the cutout pipeline");
				}
			}
		}

		String mcmetaPath = "/assets/bubbleshield/textures/interior/interior_soft.png.mcmeta";
		try (InputStream in = InteriorGameTests.class.getResourceAsStream(mcmetaPath)) {
			helper.assertTrue(in != null, "missing " + mcmetaPath);
			JsonObject meta = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			JsonObject texture = meta.getAsJsonObject("texture");
			helper.assertTrue(texture != null && texture.get("blur").getAsBoolean(),
					mcmetaPath + " should declare texture.blur = true");
			helper.assertTrue(texture != null && texture.get("clamp").getAsBoolean(),
					mcmetaPath + " should declare texture.clamp = true");
		} catch (Exception e) {
			throw helper.assertionException("failed to read/parse " + mcmetaPath + ": " + e);
		}

		String pixelMcmeta = "/assets/bubbleshield/textures/interior/interior_pixel.png.mcmeta";
		try (InputStream in = InteriorGameTests.class.getResourceAsStream(pixelMcmeta)) {
			helper.assertTrue(in == null,
					pixelMcmeta + " must NOT exist: the pixel-art sheet samples NEAREST (no blur mcmeta)");
		} catch (Exception e) {
			throw helper.assertionException("failed probing " + pixelMcmeta + ": " + e);
		}

		helper.succeed();
	}

	/**
	 * Full decode of one sheet: PNG signature, IHDR geometry
	 * ({@value #SHEET_SIZE} square, 8-bit color type 6 = RGBA, no interlace),
	 * then a complete zlib inflate of the concatenated IDAT chunks whose
	 * decompressed length must be EXACTLY {@code height * (1 + width * 4)}.
	 * Returns the inflated (still filter-prefixed) scanline stream.
	 */
	private static byte[] decodeSheet(GameTestHelper helper, String path) {
		try (InputStream in = InteriorGameTests.class.getResourceAsStream(path)) {
			helper.assertTrue(in != null, "missing interior sheet: " + path);
			byte[] data = in.readAllBytes();
			helper.assertTrue(data.length >= 45, path + " is truncated (" + data.length + " bytes)");
			helper.assertTrue(Arrays.equals(Arrays.copyOf(data, 8), PNG_SIGNATURE),
					path + " does not start with the PNG signature");
			// Bytes 12..15 are the first chunk's type; IHDR is always first.
			helper.assertTrue(data[12] == 'I' && data[13] == 'H' && data[14] == 'D' && data[15] == 'R',
					path + " first chunk is not IHDR");
			int width = readBigEndianInt(data, 16);
			int height = readBigEndianInt(data, 20);
			int bitDepth = data[24] & 0xFF;
			int colorType = data[25] & 0xFF;
			int interlace = data[28] & 0xFF;
			helper.assertTrue(width == SHEET_SIZE && height == SHEET_SIZE,
					path + " should be " + SHEET_SIZE + "x" + SHEET_SIZE + ", got " + width + "x" + height);
			helper.assertTrue(bitDepth == 8 && colorType == 6 && interlace == 0,
					path + " should be 8-bit RGBA non-interlaced, got depth " + bitDepth
							+ " type " + colorType + " interlace " + interlace);

			ByteArrayOutputStream idat = new ByteArrayOutputStream();
			boolean sawEnd = false;
			int pos = 8;
			while (pos + 12 <= data.length) {
				int length = readBigEndianInt(data, pos);
				if (data[pos + 4] == 'I' && data[pos + 5] == 'D' && data[pos + 6] == 'A' && data[pos + 7] == 'T') {
					idat.write(data, pos + 8, length);
				} else if (data[pos + 4] == 'I' && data[pos + 5] == 'E' && data[pos + 6] == 'N' && data[pos + 7] == 'D') {
					sawEnd = true;
					break;
				}

				pos += 12 + length;
			}

			helper.assertTrue(sawEnd, path + " has no IEND chunk");

			int expected = height * (1 + width * 4);
			Inflater inflater = new Inflater();
			inflater.setInput(idat.toByteArray());
			// One spare byte so an over-long stream is detectable.
			byte[] raster = new byte[expected + 1];
			int total = 0;
			while (!inflater.finished() && total < raster.length) {
				int inflated = inflater.inflate(raster, total, raster.length - total);
				if (inflated == 0) {
					break;
				}

				total += inflated;
			}

			boolean finished = inflater.finished();
			inflater.end();
			helper.assertTrue(finished && total == expected,
					path + " IDAT inflates to " + total + " bytes (finished=" + finished
							+ "), expected exactly " + expected);
			return Arrays.copyOf(raster, expected);
		} catch (Exception e) {
			throw helper.assertionException("failed to read " + path + ": " + e);
		}
	}

	/** Big-endian int at {@code offset} (PNG chunk lengths, IHDR dims). */
	private static int readBigEndianInt(byte[] data, int offset) {
		return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
				| ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
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
	 * override id set EQUALS the pinned ten ids inside the non-frozen 420..839 band.
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

		// The exact novelty-override id set is pinned: all inside the non-frozen
		// 420..839 band, and any table change must consciously update this test.
		Set<Integer> expectedOverrides = Set.of(442, 526, 575, 612, 633, 717, 728, 756, 809, 839);
		helper.assertTrue(InteriorThemes.overrideIds().equals(expectedOverrides),
				"novelty override ids " + InteriorThemes.overrideIds() + " != pinned set " + expectedOverrides);

		helper.succeed();
	}
}

package com.bubbleshield.interior;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.bubbleshield.effect.EffectRegistry;
import com.bubbleshield.effect.SurfaceTemplate;

/**
 * Pure-data catalogue of the in-bubble INTERIOR treatments: which sprites float
 * inside a bubble, how they move and how they are tinted. Main source set (no
 * rendering imports) so the exhaustive-coverage gametests can drive it headlessly;
 * the client {@code InteriorRenderer} is the only runtime consumer.
 *
 * <p>Resolution order ({@link #themeFor}): the 10 signature NOVELTY effect ids get
 * a dedicated per-id override (tacos, ducks, disco...); every other effect falls
 * back to its {@link SurfaceTemplate} family treatment ({@link #templateTheme} is
 * an EXHAUSTIVE switch over all 60 templates — adding a template without an
 * interior mapping becomes a compile error, and a gametest asserts all 840 ids
 * resolve non-null).
 *
 * <p>A {@link Theme} is an ordered list of {@link Layer}s; each layer owns a
 * fraction ({@code share}) of the per-bubble element budget, a sprite pool on ONE
 * of the two sheets, one motion program and one tint mode. Element-to-layer
 * assignment is deterministic and contiguous ({@link #layerStart}), so the scatter
 * (main) and the renderer (client) agree without storing layer indices per element.
 */
public final class InteriorThemes {
	// --- motion programs (packed into the scatter array as a float) ---
	public static final int MOTION_DRIFT = 0;
	public static final int MOTION_ORBIT = 1;
	public static final int MOTION_BOB = 2;
	public static final int MOTION_FALL = 3;
	public static final int MOTION_SWIM = 4;
	public static final int MOTION_BLINK = 5;
	public static final int MOTION_RISE = 6;
	public static final int MOTION_RING_ORBIT = 7;
	public static final int MOTION_WATERLINE = 8;
	public static final int MOTION_SPIRAL = 9;
	public static final int MOTION_SHAFT = 10;
	public static final int MOTION_PERCH = 11;
	public static final int MOTION_TOP_CENTER = 12;
	public static final int MOTION_COUNT = 13;

	/**
	 * Sprite-ordinal encoding: {@code 0..63} = a cell of the 8x8 PIXEL sheet
	 * ({@code interior_pixel.png}; cells 58..63 are reserved transparent),
	 * {@code 64..79} = {@code 64 +} a cell of the 4x4 SOFT sheet
	 * ({@code interior_soft.png}). One float slot in the packed scatter array
	 * carries the ordinal; {@link #isSoftSprite} splits the sheets back apart.
	 */
	public static final int SOFT_BASE = 64;
	public static final int SPRITE_ORDINAL_COUNT = SOFT_BASE + 16;

	// Pixel-sheet cells, exactly as assembled by tools/gen_interior_sprites.py.
	private static final int[] PX_TACO = range(0, 8);
	private static final int[] PX_DONUT = range(8, 4);
	private static final int[] PX_DUCK = range(12, 4);
	private static final int[] PX_DISCO_BALL = range(16, 4);
	private static final int[] PX_FISH = range(20, 8);
	private static final int[] PX_BOOK = range(28, 4);
	private static final int[] PX_GLYPH = range(32, 16);
	private static final int[] PX_CAT = range(48, 4);
	private static final int[] PX_LAVA = range(52, 6);

	// Soft-sheet cells (4x4 grid order pinned by the generator's SOFT_ELEMENTS).
	public static final int SOFT_GLOW_DOT = SOFT_BASE;
	public static final int SOFT_STAR = SOFT_BASE + 1;
	public static final int SOFT_RING = SOFT_BASE + 2;
	public static final int SOFT_SHARD = SOFT_BASE + 3;
	public static final int SOFT_STREAK = SOFT_BASE + 4;
	public static final int SOFT_PETAL = SOFT_BASE + 5;
	public static final int SOFT_SMOKE_WISP = SOFT_BASE + 6;
	public static final int SOFT_VEIL = SOFT_BASE + 7;
	public static final int SOFT_SPORE = SOFT_BASE + 8;
	public static final int SOFT_TENDRIL = SOFT_BASE + 9;
	public static final int SOFT_RIPPLE = SOFT_BASE + 10;
	public static final int SOFT_RIBBON = SOFT_BASE + 11;
	public static final int SOFT_FLAKE = SOFT_BASE + 12;
	public static final int SOFT_LIGHT_SHAFT = SOFT_BASE + 13;
	public static final int SOFT_DOME_GRADIENT = SOFT_BASE + 14;
	public static final int SOFT_ARC_BOLT = SOFT_BASE + 15;

	/** How the layer's sprites are vertex-tinted (the soft sheet is grayscale+alpha). */
	public enum Tint {
		/** No tint — the pixel art's own quantized colors. */
		WHITE,
		/** The effect's live primary palette color (recolor-safe). */
		PRIMARY,
		/** The effect's live secondary palette color. */
		SECONDARY,
		/** The secondary darkened hard (~x0.3) — the VOID inner-shell treatment. */
		DARK
	}

	/**
	 * One interior layer: {@code share} of the element budget drawn from
	 * {@code sprites} (all on one sheet), animated by {@code motion}, scaled by
	 * {@code sizeScale} on top of the radius-derived base size.
	 *
	 * @param flash when true the layer joins the ONE shared &le;2Hz blink envelope
	 *              (per-element hash-gated, so at most ~30% of its elements pulse
	 *              in any given flash)
	 * @param fog   when true the layer counts as volumetric smoke/fog and is
	 *              thinned by the client's volumetric mode (OFF x0 / LOW x0.5 / FULL x1)
	 * @param shell when &gt; 0 the element is re-anchored at this fraction of the
	 *              bubble's surface distance along its scatter direction (the VOID
	 *              inner dome shell at 0.85, the wireframe cage rings at 0.8)
	 */
	public record Layer(int[] sprites, int motion, float share, float sizeScale, Tint tint,
			boolean flash, boolean fog, float shell) {
		public Layer {
			if (sprites.length == 0) {
				throw new IllegalArgumentException("a layer needs at least one sprite");
			}
		}

		static Layer of(int[] sprites, int motion, float share, float sizeScale, Tint tint) {
			return new Layer(sprites, motion, share, sizeScale, tint, false, false, 0.0F);
		}

		static Layer flashing(int[] sprites, int motion, float share, float sizeScale, Tint tint) {
			return new Layer(sprites, motion, share, sizeScale, tint, true, false, 0.0F);
		}

		static Layer foggy(int[] sprites, int motion, float share, float sizeScale, Tint tint) {
			return new Layer(sprites, motion, share, sizeScale, tint, false, true, 0.0F);
		}

		static Layer shelled(int[] sprites, int motion, float share, float sizeScale, Tint tint, float shell) {
			return new Layer(sprites, motion, share, sizeScale, tint, false, false, shell);
		}
	}

	/** A named, ordered stack of layers; shares must sum to ~1 (gametested). */
	public record Theme(String id, List<Layer> layers) {
	}

	// --- template-family themes (the planner table) ---

	private static final Theme RIBBONS = new Theme("ribbons", List.of(
			Layer.of(new int[]{SOFT_RIBBON}, MOTION_DRIFT, 0.7F, 1.5F, Tint.PRIMARY),
			Layer.of(new int[]{SOFT_GLOW_DOT}, MOTION_DRIFT, 0.3F, 0.6F, Tint.SECONDARY)));
	private static final Theme RIPPLES = new Theme("ripples", List.of(
			Layer.of(new int[]{SOFT_RIPPLE}, MOTION_BOB, 0.7F, 1.3F, Tint.PRIMARY),
			Layer.of(new int[]{SOFT_GLOW_DOT}, MOTION_RISE, 0.3F, 0.5F, Tint.SECONDARY)));
	private static final Theme RINGS = new Theme("rings", List.of(
			Layer.of(new int[]{SOFT_RING}, MOTION_ORBIT, 1.0F, 1.2F, Tint.PRIMARY)));
	private static final Theme FILM_PETALS = new Theme("film_petals", List.of(
			Layer.of(new int[]{SOFT_PETAL}, MOTION_DRIFT, 0.75F, 1.1F, Tint.PRIMARY),
			Layer.flashing(new int[]{SOFT_GLOW_DOT}, MOTION_BLINK, 0.25F, 0.5F, Tint.SECONDARY)));
	private static final Theme PETALS = new Theme("petals", List.of(
			Layer.of(new int[]{SOFT_PETAL}, MOTION_FALL, 1.0F, 1.0F, Tint.PRIMARY)));
	private static final Theme FLAKES = new Theme("flakes", List.of(
			Layer.of(new int[]{SOFT_FLAKE}, MOTION_FALL, 1.0F, 0.9F, Tint.PRIMARY)));
	/**
	 * The 3-shell parallax star treatment: the size spread of the dot layers reads
	 * as depth shells under motion; the streak layer is the occasional shooting star.
	 */
	private static final Theme STARS = new Theme("stars", List.of(
			Layer.of(new int[]{SOFT_GLOW_DOT}, MOTION_DRIFT, 0.45F, 0.45F, Tint.WHITE),
			Layer.of(new int[]{SOFT_STAR}, MOTION_DRIFT, 0.4F, 0.7F, Tint.PRIMARY),
			Layer.flashing(new int[]{SOFT_STREAK}, MOTION_SWIM, 0.15F, 1.2F, Tint.SECONDARY)));
	private static final Theme GLINTS = new Theme("glints", List.of(
			Layer.flashing(new int[]{SOFT_STAR}, MOTION_BLINK, 1.0F, 0.7F, Tint.PRIMARY)));
	private static final Theme EMBERS = new Theme("embers", List.of(
			Layer.of(new int[]{SOFT_GLOW_DOT}, MOTION_RISE, 0.7F, 0.5F, Tint.PRIMARY),
			Layer.flashing(new int[]{SOFT_SPORE}, MOTION_RISE, 0.3F, 0.7F, Tint.SECONDARY)));
	private static final Theme ARC_STREAKS = new Theme("arc_streaks", List.of(
			Layer.flashing(new int[]{SOFT_ARC_BOLT}, MOTION_BLINK, 0.8F, 1.2F, Tint.PRIMARY),
			Layer.flashing(new int[]{SOFT_GLOW_DOT}, MOTION_DRIFT, 0.2F, 0.4F, Tint.SECONDARY)));
	private static final Theme SPIRAL_SHARDS = new Theme("spiral_shards", List.of(
			Layer.of(new int[]{SOFT_SHARD}, MOTION_SPIRAL, 0.8F, 0.9F, Tint.PRIMARY),
			Layer.of(new int[]{SOFT_GLOW_DOT}, MOTION_SPIRAL, 0.2F, 0.4F, Tint.SECONDARY)));
	private static final Theme TUMBLING_SHARDS = new Theme("tumbling_shards", List.of(
			Layer.of(new int[]{SOFT_SHARD}, MOTION_DRIFT, 0.7F, 1.0F, Tint.PRIMARY),
			Layer.of(new int[]{SOFT_FLAKE}, MOTION_DRIFT, 0.3F, 0.7F, Tint.SECONDARY)));
	/** Wireframe cage: rings pinned to a 0.8R shell, slowly orbiting in place. */
	private static final Theme CAGE_RINGS = new Theme("cage_rings", List.of(
			Layer.shelled(new int[]{SOFT_RING}, MOTION_ORBIT, 0.8F, 1.3F, Tint.PRIMARY, 0.8F),
			Layer.flashing(new int[]{SOFT_GLOW_DOT}, MOTION_BLINK, 0.2F, 0.4F, Tint.SECONDARY)));
	private static final Theme GLYPHS = new Theme("glyphs", List.of(
			Layer.of(PX_GLYPH, MOTION_DRIFT, 1.0F, 0.9F, Tint.PRIMARY)));
	private static final Theme SMOKE_WISPS = new Theme("smoke_wisps", List.of(
			Layer.foggy(new int[]{SOFT_SMOKE_WISP}, MOTION_DRIFT, 0.8F, 1.6F, Tint.SECONDARY),
			Layer.foggy(new int[]{SOFT_VEIL}, MOTION_DRIFT, 0.2F, 1.8F, Tint.PRIMARY)));
	private static final Theme GHOST_VEILS = new Theme("ghost_veils", List.of(
			Layer.foggy(new int[]{SOFT_VEIL}, MOTION_DRIFT, 0.7F, 1.7F, Tint.PRIMARY),
			Layer.flashing(new int[]{SOFT_GLOW_DOT}, MOTION_BOB, 0.3F, 0.5F, Tint.SECONDARY)));
	private static final Theme GLOW_SPORES = new Theme("glow_spores", List.of(
			Layer.flashing(new int[]{SOFT_SPORE}, MOTION_BOB, 0.6F, 0.7F, Tint.PRIMARY),
			Layer.flashing(new int[]{SOFT_GLOW_DOT}, MOTION_BOB, 0.4F, 0.45F, Tint.SECONDARY)));
	/** VOID: dark inner dome shell at 0.85R + sparse stars + drifting tendrils. */
	private static final Theme VOID = new Theme("void", List.of(
			Layer.shelled(new int[]{SOFT_DOME_GRADIENT}, MOTION_PERCH, 0.3F, 2.2F, Tint.DARK, 0.85F),
			Layer.of(new int[]{SOFT_GLOW_DOT}, MOTION_DRIFT, 0.4F, 0.35F, Tint.WHITE),
			Layer.of(new int[]{SOFT_TENDRIL}, MOTION_DRIFT, 0.3F, 1.4F, Tint.SECONDARY)));

	// --- novelty per-id overrides (the 10 rethemed signature effects) ---

	private static final Theme TACO_FIESTA = new Theme("taco_fiesta", List.of(
			Layer.of(PX_TACO, MOTION_ORBIT, 0.6F, 1.3F, Tint.WHITE),
			Layer.of(PX_TACO, MOTION_BOB, 0.4F, 1.1F, Tint.WHITE)));
	private static final Theme DONUT_DRIFT = new Theme("donut_drift", List.of(
			Layer.of(PX_DONUT, MOTION_RING_ORBIT, 0.8F, 1.3F, Tint.WHITE),
			Layer.of(new int[]{SOFT_SPORE}, MOTION_DRIFT, 0.2F, 0.5F, Tint.PRIMARY)));
	private static final Theme RUBBER_DUCK_POND = new Theme("rubber_duck_pond", List.of(
			Layer.of(PX_DUCK, MOTION_WATERLINE, 0.6F, 1.2F, Tint.WHITE),
			Layer.of(new int[]{SOFT_RIPPLE}, MOTION_WATERLINE, 0.4F, 1.1F, Tint.PRIMARY)));
	private static final Theme DISCO_DOME = new Theme("disco_dome", List.of(
			Layer.of(PX_DISCO_BALL, MOTION_TOP_CENTER, 0.1F, 2.0F, Tint.WHITE),
			Layer.of(new int[]{SOFT_LIGHT_SHAFT}, MOTION_SHAFT, 0.45F, 2.2F, Tint.PRIMARY),
			Layer.flashing(new int[]{SOFT_GLOW_DOT, SOFT_STAR}, MOTION_BLINK, 0.45F, 0.5F, Tint.SECONDARY)));
	private static final Theme AQUARIUM = new Theme("aquarium", List.of(
			Layer.of(PX_FISH, MOTION_SWIM, 0.7F, 1.1F, Tint.WHITE),
			Layer.of(new int[]{SOFT_SPORE}, MOTION_RISE, 0.3F, 0.4F, Tint.SECONDARY)));
	private static final Theme MATRIX_RAIN = new Theme("matrix_rain", List.of(
			Layer.of(PX_GLYPH, MOTION_FALL, 0.85F, 0.9F, Tint.PRIMARY),
			Layer.flashing(new int[]{SOFT_STREAK}, MOTION_FALL, 0.15F, 1.1F, Tint.PRIMARY)));
	private static final Theme LAVA_LAMP = new Theme("lava_lamp", List.of(
			Layer.of(PX_LAVA, MOTION_RISE, 0.85F, 1.5F, Tint.WHITE),
			Layer.of(new int[]{SOFT_GLOW_DOT}, MOTION_RISE, 0.15F, 0.5F, Tint.PRIMARY)));
	/** Void Absolute: the maxed-out VOID treatment (denser shell, more tendrils). */
	private static final Theme VOID_ABSOLUTE = new Theme("void_absolute", List.of(
			Layer.shelled(new int[]{SOFT_DOME_GRADIENT}, MOTION_PERCH, 0.4F, 2.4F, Tint.DARK, 0.85F),
			Layer.of(new int[]{SOFT_GLOW_DOT}, MOTION_DRIFT, 0.3F, 0.3F, Tint.WHITE),
			Layer.of(new int[]{SOFT_TENDRIL}, MOTION_SPIRAL, 0.3F, 1.6F, Tint.SECONDARY)));
	private static final Theme WHISPERING_LIBRARY = new Theme("whispering_library", List.of(
			Layer.of(PX_BOOK, MOTION_ORBIT, 0.7F, 1.2F, Tint.WHITE),
			Layer.of(PX_GLYPH, MOTION_DRIFT, 0.3F, 0.6F, Tint.PRIMARY)));
	private static final Theme CAT_CLOUD = new Theme("cat_cloud", List.of(
			Layer.flashing(PX_CAT, MOTION_PERCH, 0.6F, 1.3F, Tint.WHITE),
			Layer.foggy(new int[]{SOFT_SMOKE_WISP}, MOTION_DRIFT, 0.4F, 1.7F, Tint.SECONDARY)));

	/** The novelty override table; keys are gametested to stay inside the non-frozen 420..839 band. */
	private static final Map<Integer, Theme> OVERRIDES = Map.ofEntries(
			Map.entry(442, AQUARIUM),
			Map.entry(526, RUBBER_DUCK_POND),
			Map.entry(575, MATRIX_RAIN),
			Map.entry(612, WHISPERING_LIBRARY),
			Map.entry(633, TACO_FIESTA),
			Map.entry(717, LAVA_LAMP),
			Map.entry(728, CAT_CLOUD),
			Map.entry(756, DONUT_DRIFT),
			Map.entry(809, DISCO_DOME),
			Map.entry(839, VOID_ABSOLUTE));

	/** Themes resolved once per effect id (themes are shared immutable constants). */
	private static final Theme[] BY_ID = buildById();

	private InteriorThemes() {
	}

	private static int[] range(int start, int count) {
		int[] out = new int[count];
		for (int i = 0; i < count; i++) {
			out[i] = start + i;
		}

		return out;
	}

	private static Theme[] buildById() {
		Theme[] themes = new Theme[EffectRegistry.COUNT];
		for (int id = 0; id < themes.length; id++) {
			Theme override = OVERRIDES.get(id);
			themes[id] = override != null ? override : templateTheme(EffectRegistry.get(id).surface());
		}

		return themes;
	}

	/**
	 * The interior treatment of one {@link SurfaceTemplate} family. EXHAUSTIVE
	 * switch expression (no default): a new template constant fails compilation
	 * here until it is mapped, mirroring {@code SurfaceSoundGroup.of}.
	 */
	public static Theme templateTheme(SurfaceTemplate template) {
		return switch (template) {
			case AURORA, RIBBONAURORA -> RIBBONS;
			case WAVES, CAUSTIC, FLUIDINK -> RIPPLES;
			case RINGS -> RINGS;
			case INTERFERENCE, MOIRE, IRISFILM, OILSLICK, KALEIDO -> FILM_PETALS;
			case PETALS, STAINEDGLASS -> PETALS;
			case SCALES -> FLAKES;
			case STARFIELD, NEBULA, GALAXYSWIRL, KALISET, ORBITTRAP -> STARS;
			case SPARKLE, PRISMDISPERSE, CHROME, THINFILM -> GLINTS;
			case PLASMA, EMBERSTORM, SOLARFLARE, LAVAFLOW -> EMBERS;
			case ARCS, LIGHTNING, PLASMAGLOBE -> ARC_STREAKS;
			case VORTEX, GRAVLENS -> SPIRAL_SHARDS;
			case VORONOI, SHARDTESS, CRYSTALREFRACT, CRYSTALSDF, DEEPICE, FROSTFERN, RIDGED -> TUMBLING_SHARDS;
			case HEX, HOLOGRID, HOLOPARALLAX, SACREDGEO, TRIWEAVE -> CAGE_RINGS;
			case CIRCUIT, RUNECIRCUIT, TRUCHET -> GLYPHS;
			case CURLSMOKE, AETHERSMOKE, RAYMARCHFOG, VOLUMECLOUD -> SMOKE_WISPS;
			case PHANTOMECHO, ECTOPLASM, SPECTRALVEIL -> GHOST_VEILS;
			case BIOLUME, MYCELIA -> GLOW_SPORES;
			case PORTALVOID, VOIDTENDRIL, VOIDRIFT, TENDRILNET -> VOID;
		};
	}

	/** The interior theme of an effect id; ids are clamped like {@link EffectRegistry#get}. */
	public static Theme themeFor(int effectId) {
		return BY_ID[Math.clamp(effectId, 0, BY_ID.length - 1)];
	}

	/** The novelty override ids (test hook: must stay a subset of the non-frozen 420..839). */
	public static Set<Integer> overrideIds() {
		return OVERRIDES.keySet();
	}

	/**
	 * First element index owned by {@code layerIndex} when {@code count} elements
	 * are split across the theme's layers by share (cumulative rounding — layer k
	 * owns {@code [layerStart(k), layerStart(k + 1))}, and {@code layerStart(size)}
	 * is exactly {@code count}). The scatter and the renderer both use this, which
	 * is what keeps the per-element layer assignment implicit (stride-8 array, no
	 * stored layer slot).
	 */
	public static int layerStart(Theme theme, int count, int layerIndex) {
		List<Layer> layers = theme.layers();
		if (layerIndex >= layers.size()) {
			return count;
		}

		float cumulative = 0.0F;
		for (int i = 0; i < layerIndex; i++) {
			cumulative += layers.get(i).share();
		}

		return Math.min(count, Math.round(cumulative * count));
	}

	/** The layer owning element {@code index} out of {@code count} (see {@link #layerStart}). */
	public static Layer layerOf(Theme theme, int count, int index) {
		List<Layer> layers = theme.layers();
		for (int i = layers.size() - 1; i >= 1; i--) {
			if (index >= layerStart(theme, count, i)) {
				return layers.get(i);
			}
		}

		return layers.get(0);
	}

	/** True when the packed sprite ordinal addresses the SOFT sheet (see {@link #SOFT_BASE}). */
	public static boolean isSoftSprite(int spriteOrdinal) {
		return spriteOrdinal >= SOFT_BASE;
	}
}

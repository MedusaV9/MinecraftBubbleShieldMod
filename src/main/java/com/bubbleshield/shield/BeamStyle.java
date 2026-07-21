package com.bubbleshield.shield;

/**
 * The style of the central energy beam rising vertically from the projector through
 * the bubble's center and out past its apex — the "storm shield" column that visually
 * holds the bubble up. A per-bubble setting, cycled in the projector GUI.
 *
 * <p>Ordinals are persisted (NBT) and synced (payload/menu data), so values are
 * APPEND-ONLY. Only the first two entries are contractual:
 * <ul>
 *   <li>{@link #NONE} — no beam (the default; a missing NBT key loads as ordinal 0,
 *       which keeps pre-beam saves beam-free);</li>
 *   <li>{@link #AUTO} — resolve to the effect's derived preset
 *       ({@code EffectDefinition.beamPreset()}), so every effect gets a coherent
 *       surface/screen/behavior/beam bundle without any catalogue row changes.</li>
 * </ul>
 * The remaining values are the RENDERED styles, each mapping 1:1 to a hand-written
 * fragment shader {@code assets/bubbleshield/shaders/beam/beam_<name>.fsh} (client).
 */
public enum BeamStyle {
	NONE,
	AUTO,
	STORM,
	PULSE,
	HELIX,
	PRISM,
	VOID,
	EMBER,
	RUNIC,
	FROST;

	private static final BeamStyle[] VALUES = values();

	/**
	 * The rendered styles (everything past NONE/AUTO), indexable by
	 * {@link #renderIndex()}. Cached; never mutate.
	 */
	public static final BeamStyle[] RENDERED = {STORM, PULSE, HELIX, PRISM, VOID, EMBER, RUNIC, FROST};

	/** @return the style with the given ordinal, clamped to the valid range (default {@link #NONE}). */
	public static BeamStyle byOrdinal(int ordinal) {
		return VALUES[ordinal >= 0 && ordinal < VALUES.length ? ordinal : 0];
	}

	/**
	 * @return this style's index into {@link #RENDERED} (0 = STORM .. 7 = FROST), or -1
	 * for the non-rendered NONE/AUTO. The client clamps defensively before indexing
	 * its render-type array, so a stale/foreign ordinal can never go out of bounds.
	 */
	public int renderIndex() {
		return this.ordinal() - STORM.ordinal();
	}
}

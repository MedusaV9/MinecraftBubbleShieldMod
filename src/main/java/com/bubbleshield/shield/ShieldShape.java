package com.bubbleshield.shield;

/**
 * The volumetric shape of a bubble shield. {@link #SPHERE} is the classic full bubble;
 * {@link #DOME} is the upper hemisphere only (open below the projector's center plane);
 * {@link #CYLINDER} is an upright column; {@link #CUBE} an axis-aligned box;
 * {@link #DIAMOND} an octahedron (L1 ball); {@link #RING} a torus with a deliberately
 * passable central hole; {@link #PYRAMID} an upright square pyramid; {@link #LENS} an
 * oblate spheroid (flattened sphere); {@link #HOURGLASS} two cones tip-to-tip at the
 * center (the pinched waist is a deliberately passable region); {@link #STAR} a
 * six-lobed star prism. Exact containment math (all shapes are subsets of the closed
 * ball of the shield radius) lives in {@link ShieldGeometry}.
 *
 * <p>Ordinals are persisted (NBT) and synced (payload/menu data), so values are
 * APPEND-ONLY: {@code SPHERE = 0} and {@code DOME = 1} must stay stable for
 * save-compat, and new shapes may only be added at the end.
 */
public enum ShieldShape {
	SPHERE,
	DOME,
	CYLINDER,
	CUBE,
	DIAMOND,
	RING,
	PYRAMID,
	LENS,
	HOURGLASS,
	STAR;

	private static final ShieldShape[] VALUES = values();

	/** @return the shape with the given ordinal, clamped to the valid range (default {@link #SPHERE}). */
	public static ShieldShape byOrdinal(int ordinal) {
		return VALUES[ordinal >= 0 && ordinal < VALUES.length ? ordinal : 0];
	}
}

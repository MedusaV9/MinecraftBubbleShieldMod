package com.bubbleshield.interior;

import com.bubbleshield.shield.ShieldGeometry;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.GlobalPos;
import net.minecraft.world.phys.Vec3;

/**
 * Deterministic interior-element scatter: for one shield (keyed by its
 * {@link GlobalPos}, effect id and element count) it produces the same packed
 * float array on every call, on every machine — the client caches it per shield
 * and replays it every frame, and the gametests replay it headlessly.
 *
 * <p>Packed layout, stride {@value #STRIDE} floats per element:
 * <ol start="0">
 *   <li>{@code ux, uy, uz} — the element's rest position in UNIT-shape space
 *       (the bubble with radius 1; the renderer multiplies by the live radius),
 *       rejection-sampled inside the {@link ShieldShape} at margin
 *       {@value #MARGIN} so elements never poke through the membrane;</li>
 *   <li value="3">{@code spriteOrdinal} — the {@link InteriorThemes} sheet-encoded
 *       sprite cell (0..63 pixel, 64..79 soft);</li>
 *   <li>{@code sizeMul} — per-element size multiplier in [0.7, 1.3);</li>
 *   <li>{@code phase} — motion phase offset in [0, 2&pi;);</li>
 *   <li>{@code motionType} — the layer's {@code InteriorThemes.MOTION_*} id;</li>
 *   <li>{@code seed} — a per-element uniform in [0, 1) (speed jitter, flash gating).</li>
 * </ol>
 *
 * <p>Elements are assigned to theme layers contiguously via
 * {@link InteriorThemes#layerStart}, so the renderer recovers each element's layer
 * (tint, flash, fog, shell) without any extra per-element storage.
 *
 * <p>{@link #isInsideUnit} is a primitive-argument mirror of
 * {@link ShieldGeometry#isInside} (no {@link Vec3} allocation — the renderer calls
 * it per element per frame to re-clamp animated positions). A gametest asserts the
 * mirror agrees with the real geometry on thousands of random samples per shape,
 * so the two can never drift apart silently.
 */
public final class InteriorScatter {
	/** Floats per element in the packed array. */
	public static final int STRIDE = 8;
	/** Rest positions stay inside the unit shape scaled by this margin. */
	public static final float MARGIN = 0.92F;
	/** Hard sanity cap on the element count (the client budget maxes out at 80). */
	public static final int MAX_COUNT = 256;

	private static final long GOLDEN = 0x9E3779B97F4A7C15L;
	/** Rejection-sampling attempts per element (the thinnest shape, RING, fills ~15% of the cube). */
	private static final int MAX_ATTEMPTS = 96;

	private InteriorScatter() {
	}

	/**
	 * The packed elements of one shield, deterministic in (pos, effectId, count):
	 * seed {@code pos.asLong() ^ effectId * golden} (plus the dimension hash, so
	 * same-coordinate shields in different dimensions decorrelate) driving a
	 * splitmix64 stream. The {@code shape} steers the containment volume; count is
	 * clamped to [0, {@value #MAX_COUNT}].
	 */
	public static float[] scatter(GlobalPos pos, int effectId, ShieldShape shape, int count) {
		int n = Math.clamp(count, 0, MAX_COUNT);
		InteriorThemes.Theme theme = InteriorThemes.themeFor(effectId);
		float[] out = new float[n * STRIDE];
		long state = pos.pos().asLong() ^ (effectId * GOLDEN) ^ ((long) pos.dimension().identifier().hashCode() << 17);

		for (int i = 0; i < n; i++) {
			InteriorThemes.Layer layer = InteriorThemes.layerOf(theme, n, i);

			// Rejection-sample the unit shape at the margin. The fallback below is
			// only reachable with astronomically bad luck (RING accepts ~15% of the
			// cube; 96 misses ~ 4e-7): a shape-safe interior point.
			double x = 0.0;
			double y = 0.0;
			double z = 0.0;
			boolean accepted = false;
			for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
				state = next(state);
				x = toSigned(state);
				state = next(state);
				y = toSigned(state);
				state = next(state);
				z = toSigned(state);
				if (isInsideUnit(shape, MARGIN, x, y, z)) {
					accepted = true;
					break;
				}
			}

			if (!accepted) {
				x = shape == ShieldShape.RING ? ShieldGeometry.RING_MAJOR_FRAC * MARGIN : 0.0;
				y = 0.0;
				z = 0.0;
			}

			// Shelled layers (VOID dome, cage rings) re-anchor along the sampled
			// direction at shell x the surface distance. The shell point must keep
			// the SAME margin invariant as free samples (gametested); when it does
			// not (RING's hole, grazing directions), the free sample is kept as-is.
			if (layer.shell() > 0.0F) {
				double length = Math.sqrt(x * x + y * y + z * z);
				if (length > 1.0e-6) {
					double dx = x / length;
					double dy = y / length;
					double dz = z / length;
					double surface = surfaceDistance(shape, dx, dy, dz);
					double t = surface * layer.shell();
					if (isInsideUnit(shape, MARGIN, dx * t, dy * t, dz * t)) {
						x = dx * t;
						y = dy * t;
						z = dz * t;
					}
				}
			}

			state = next(state);
			int sprite = layer.sprites()[(int) Long.remainderUnsigned(state, layer.sprites().length)];
			state = next(state);
			float sizeMul = 0.7F + 0.6F * toUnit(state);
			state = next(state);
			float phase = (float) (toUnit(state) * Math.PI * 2.0);
			state = next(state);
			float elementSeed = toUnit(state);

			int base = i * STRIDE;
			out[base] = (float) x;
			out[base + 1] = (float) y;
			out[base + 2] = (float) z;
			out[base + 3] = sprite;
			out[base + 4] = sizeMul;
			out[base + 5] = phase;
			out[base + 6] = layer.motion();
			out[base + 7] = elementSeed;
		}

		return out;
	}

	/** splitmix64 step: deterministic, well-mixed, allocation-free. */
	private static long next(long state) {
		long z = state + GOLDEN;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	/** Uniform in [0, 1) from the top 24 bits (exact as float). */
	private static float toUnit(long state) {
		return (state >>> 40) / (float) (1 << 24);
	}

	/** Uniform in [-1, 1). */
	private static double toSigned(long state) {
		return toUnit(state) * 2.0 - 1.0;
	}

	/**
	 * The distance from the origin to the shape's surface along the unit direction
	 * {@code (dx, dy, dz)} (unit-shape space, margin-less): the OUTERMOST inside
	 * point found by a fine outside-in scan, robust even for the non-star-convex
	 * RING (a binary search from the origin would sit in its hole). Only used at
	 * scatter build time (cached), never per frame.
	 */
	private static double surfaceDistance(ShieldShape shape, double dx, double dy, double dz) {
		for (int step = 150; step >= 1; step--) {
			double t = step * 0.01;
			if (isInsideUnit(shape, 1.0, dx * t, dy * t, dz * t)) {
				return t;
			}
		}

		return 0.0;
	}

	/**
	 * Primitive-argument mirror of
	 * {@link ShieldGeometry#isInside(ShieldShape, Vec3, double, Vec3)} with the
	 * center at the origin: is {@code (x, y, z)} inside the shape of the given
	 * {@code radius}? Same comparisons, same {@link ShieldGeometry} constants and
	 * taper helpers — kept allocation-free for the renderer's per-frame re-clamp.
	 * Agreement with the real geometry is gametested per shape.
	 */
	public static boolean isInsideUnit(ShieldShape shape, double radius, double x, double y, double z) {
		return switch (shape) {
			case SPHERE -> Math.sqrt(x * x + y * y + z * z) <= radius;
			case DOME -> Math.sqrt(x * x + y * y + z * z) <= radius && y >= 0.0;
			case CYLINDER -> Math.sqrt(x * x + z * z) <= ShieldGeometry.CYLINDER_RADIUS_FRAC * radius
					&& Math.abs(y) <= ShieldGeometry.CYLINDER_HALF_HEIGHT_FRAC * radius;
			case CUBE -> Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z))) <= ShieldGeometry.CUBE_HALF_EXTENT_FRAC * radius;
			case DIAMOND -> Math.abs(x) + Math.abs(y) + Math.abs(z) <= radius;
			case RING -> {
				double rho = Math.sqrt(x * x + z * z);
				double ringDist = rho - ShieldGeometry.RING_MAJOR_FRAC * radius;
				yield Math.sqrt(ringDist * ringDist + y * y) <= ShieldGeometry.RING_MINOR_FRAC * radius;
			}
			case PYRAMID -> y >= -ShieldGeometry.PYRAMID_BASE_FRAC * radius && y <= ShieldGeometry.PYRAMID_APEX_FRAC * radius
					&& Math.max(Math.abs(x), Math.abs(z)) <= ShieldGeometry.pyramidTaper(radius, y);
			case LENS -> {
				double b = ShieldGeometry.LENS_HALF_HEIGHT_FRAC * radius;
				yield (x * x + z * z) / (radius * radius) + (y * y) / (b * b) <= 1.0;
			}
			case HOURGLASS -> Math.abs(y) <= ShieldGeometry.HOURGLASS_HALF_HEIGHT_FRAC * radius
					&& Math.sqrt(x * x + z * z) <= ShieldGeometry.hourglassTaper(radius, y);
			case STAR -> Math.abs(y) <= ShieldGeometry.STAR_HALF_HEIGHT_FRAC * radius
					&& Math.sqrt(x * x + z * z) <= ShieldGeometry.starRadius(radius, x, z);
		};
	}
}

package com.bubbleshield.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.util.Mth;

/**
 * The projector's central energy column: two nested open cylinders (no caps) emitted
 * as {@code POSITION_TEX_COLOR} quads, exactly like {@link SphereMesh} (raw UVs, the
 * per-vertex color channel carries the recolor-aware palette + alpha). The pose is
 * expected to be translated to {@code shieldCenter - cameraPos} (unscaled), the same
 * camera-relative frame the sphere uses; all positions here are world-unit offsets
 * from the shield center.
 *
 * <p>Geometry: an inner bright core (radius {@value #INNER_RADIUS}) plus an outer soft
 * glow shell (radius {@value #OUTER_RADIUS}), {@value #SEGMENTS} segments around,
 * {@value #ROWS} rows tall, from the projector's block top ({@code y = -0.5}) up to
 * {@code max(radius * 1.35, radius + 6.0)} so the column always pierces the bubble
 * apex and reads as "coming from the sky" even on small bubbles (same extent for
 * domes, whose apex also sits at {@code +radius}).
 *
 * <p>UVs: {@code u} = angle fraction and {@code v} = height fraction, both in [0, 1];
 * the beam fragment shaders animate via the GameTime global. The OUTER shell's v is
 * flipped so its pattern scrolls opposite the inner core — cheap parallax depth,
 * baked into the UV emission (the shaders stay shell-agnostic). The COLOR gradient
 * always follows the true height fraction, so only the animated pattern flips.
 *
 * <p>The vertical intensity profile is CPU-side, in the vertex alpha (the beam blends
 * additively, so alpha IS intensity): a sharp bright ramp at the base, a fade-out over
 * the top quarter, a subtle standing lift where the column crosses the bubble surface
 * ({@code y = +radius}), and a MOVING bright band (alpha boost + toward-white color
 * lift) that rises along the column and across the apex — the "energy feeding the
 * shield" read. Colors blend primary to secondary bottom-to-top through the same
 * recolor pipeline as the sphere.
 */
public final class BeamMesh {
	/** Radius (blocks) of the bright core shell. */
	public static final float INNER_RADIUS = 0.55F;
	/** Radius (blocks) of the soft outer glow shell. */
	public static final float OUTER_RADIUS = 0.95F;
	private static final int SEGMENTS = 12;
	private static final int ROWS = 16;
	/** The beam starts at the projector block's top face (center is block-centered). */
	private static final float BASE_Y = -0.5F;
	/** Alpha ramps 0 to 1 over the bottom 3% of the column. */
	private static final float BASE_RAMP_FRAC = 0.03F;
	/** Alpha fades 1 to 0 over the top 25% (the column dissolves into the sky). */
	private static final float TOP_FADE_FRAC = 0.25F;
	/** Half-width (height fraction) of the moving bright band. */
	private static final float BAND_HALF_WIDTH = 2.5F / ROWS;
	/** The band rises through the full column once per this many seconds. */
	private static final float BAND_PERIOD_SECONDS = 5.0F;
	/** Standing lift half-width around the apex-crossing height. */
	private static final float APEX_HALF_WIDTH = 1.5F / ROWS;
	/** The outer glow shell renders at a fraction of the core's intensity. */
	private static final float OUTER_ALPHA_SCALE = 0.45F;

	private BeamMesh() {
	}

	/** @return the beam's top height (world units above the shield center) for {@code radius}. */
	public static float topY(float radius) {
		return Math.max(radius * 1.35F, radius + 6.0F);
	}

	/**
	 * Emits both shells.
	 *
	 * @param radius      the shield's current radius; sets the column height and the
	 *                    apex-crossing height ({@code y = +radius})
	 * @param alphaBase   base intensity, already health-scaled like the sphere's
	 * @param timeSeconds animation clock (world game time in seconds) driving the
	 *                    moving band; only sampled CPU-side, the shaders use GameTime
	 */
	public static void emit(PoseStack.Pose pose, VertexConsumer buffer, float radius,
			int argbPrimary, int argbSecondary, float alphaBase, float timeSeconds) {
		float top = topY(radius);
		// Height fraction of the bubble-surface crossing and of the moving band. The
		// band travels base -> above-apex and wraps, so it crosses the apex each pass.
		float apexFrac = (radius - BASE_Y) / (top - BASE_Y);
		float bandFrac = (timeSeconds / BAND_PERIOD_SECONDS) % 1.0F;

		emitShell(pose, buffer, INNER_RADIUS, false, radius, top, apexFrac, bandFrac,
				argbPrimary, argbSecondary, alphaBase);
		emitShell(pose, buffer, OUTER_RADIUS, true, radius, top, apexFrac, bandFrac,
				argbPrimary, argbSecondary, alphaBase * OUTER_ALPHA_SCALE);
	}

	private static void emitShell(PoseStack.Pose pose, VertexConsumer buffer, float shellRadius,
			boolean outer, float radius, float top, float apexFrac, float bandFrac,
			int argbPrimary, int argbSecondary, float alphaBase) {
		// Ring vertices are shared between the two quads flanking each row boundary,
		// so precompute one (ROWS + 1) x (SEGMENTS + 1) grid per shell per frame —
		// small (2 x 17 x 13 vertices) and the heights depend on the live radius.
		int rowStride = SEGMENTS + 1;
		float[] xs = new float[rowStride];
		float[] zs = new float[rowStride];
		for (int seg = 0; seg <= SEGMENTS; seg++) {
			float angle = (float) seg / SEGMENTS * Mth.TWO_PI;
			xs[seg] = Mth.cos(angle) * shellRadius;
			zs[seg] = Mth.sin(angle) * shellRadius;
		}

		float[] ys = new float[ROWS + 1];
		int[] colors = new int[ROWS + 1];
		for (int row = 0; row <= ROWS; row++) {
			float h = (float) row / ROWS;
			ys[row] = Mth.lerp(h, BASE_Y, top);
			colors[row] = rowColor(h, apexFrac, bandFrac, argbPrimary, argbSecondary, alphaBase);
		}

		for (int row = 0; row < ROWS; row++) {
			float v0 = rowV(row, outer);
			float v1 = rowV(row + 1, outer);
			for (int seg = 0; seg < SEGMENTS; seg++) {
				float u0 = (float) seg / SEGMENTS;
				float u1 = (float) (seg + 1) / SEGMENTS;
				buffer.addVertex(pose, xs[seg], ys[row], zs[seg]).setUv(u0, v0).setColor(colors[row]);
				buffer.addVertex(pose, xs[seg], ys[row + 1], zs[seg]).setUv(u0, v1).setColor(colors[row + 1]);
				buffer.addVertex(pose, xs[seg + 1], ys[row + 1], zs[seg + 1]).setUv(u1, v1).setColor(colors[row + 1]);
				buffer.addVertex(pose, xs[seg + 1], ys[row], zs[seg + 1]).setUv(u1, v0).setColor(colors[row]);
			}
		}
	}

	/** The outer shell's v runs top-to-bottom, so its pattern scrolls opposite the core's. */
	private static float rowV(int row, boolean outer) {
		float v = (float) row / ROWS;
		return outer ? 1.0F - v : v;
	}

	/**
	 * The CPU-side vertical profile for one ring: base ramp x top fade, plus the
	 * standing apex-crossing lift and the moving bright band (both also pull the
	 * color toward white so they read as heat, not just opacity).
	 */
	private static int rowColor(float h, float apexFrac, float bandFrac,
			int argbPrimary, int argbSecondary, float alphaBase) {
		float profile = Mth.clamp(h / BASE_RAMP_FRAC, 0.0F, 1.0F)
				* Mth.clamp((1.0F - h) / TOP_FADE_FRAC, 0.0F, 1.0F);
		float apexLift = 1.0F - Mth.clamp(Math.abs(h - apexFrac) / APEX_HALF_WIDTH, 0.0F, 1.0F);
		float band = 1.0F - Mth.clamp(Math.abs(h - bandFrac) / BAND_HALF_WIDTH, 0.0F, 1.0F);

		float whiten = Mth.clamp(0.55F * band + 0.35F * apexLift, 0.0F, 0.8F);
		float alpha = Mth.clamp(alphaBase * profile * (1.0F + 0.9F * band + 0.5F * apexLift), 0.0F, 1.0F);

		float mix = Mth.clamp(h, 0.0F, 1.0F);
		int r = Mth.lerpInt(mix, argbPrimary >> 16 & 0xFF, argbSecondary >> 16 & 0xFF);
		int g = Mth.lerpInt(mix, argbPrimary >> 8 & 0xFF, argbSecondary >> 8 & 0xFF);
		int b = Mth.lerpInt(mix, argbPrimary & 0xFF, argbSecondary & 0xFF);
		r = Mth.lerpInt(whiten, r, 255);
		g = Mth.lerpInt(whiten, g, 255);
		b = Mth.lerpInt(whiten, b, 255);
		int a = Mth.clamp((int) (alpha * 255.0F), 0, 255);
		return a << 24 | r << 16 | g << 8 | b;
	}
}

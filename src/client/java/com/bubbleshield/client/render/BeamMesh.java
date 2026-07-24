package com.bubbleshield.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.util.Mth;

/**
 * The projector's central energy column: two crossed CAMERA-FACING vertical planes
 * through the beam axis, emitted as {@code POSITION_TEX_COLOR} quads, exactly like
 * {@link SphereMesh} (raw UVs, the per-vertex color channel carries the recolor-aware
 * palette + alpha). The pose is expected to be translated to
 * {@code shieldCenter - cameraPos} (unscaled), the same camera-relative frame the
 * sphere uses; all positions here are world-unit offsets from the shield center.
 *
 * <p>Geometry: the planes are re-oriented every frame at &plusmn;45&deg; to the
 * horizontal camera direction (both always face the camera at cos&nbsp;45&deg;, so the
 * summed additive intensity is view-angle stable), each spanning
 * {@code x in [-HALF_WIDTH, +HALF_WIDTH]} across and {@link #ROWS_BELOW} +
 * {@link #ROWS_ABOVE} rows tall, from the projector's block top ({@code y = -0.5}) up
 * to {@code max(radius * 1.35, radius + 6.0)} so the column always pierces the bubble
 * apex and reads as "coming from the sky" even on small bubbles (same extent for
 * domes, whose apex also sits at {@code +radius}). Winding is counter-clockwise seen
 * from the camera side, because the beam pipeline back-face CULLS (additive blending
 * gains nothing from back faces — they only double the overdraw).
 *
 * <p>UVs: {@code u} runs ACROSS the plane width ({@code u = 0.5} is the beam axis;
 * the fragment shaders derive the signed cross-beam coordinate {@code x = 2u - 1} and
 * shape the whole radial cross-section — thin bright core inside a soft wide
 * {@code exp(-k*x*x)} glow — in one pass). {@code v} is the height fraction with the
 * MEMBRANE CROSSING PINNED at {@code v =} {@value #APEX_V}: rows below the crossing
 * map {@code [BASE_Y, +radius]} onto {@code [0, APEX_V]} and rows above map
 * {@code [+radius, top]} onto {@code [APEX_V, 1]}. The pin is how the shaders (which
 * take no custom uniforms, per the frozen fragment contract) know where to place the
 * apex bloom/ring; they hardcode the same 0.75.
 *
 * <p>The vertical intensity profile is CPU-side, in the vertex alpha (the beam blends
 * additively, so alpha IS intensity), and is deliberately minimal: a sharp bright ramp
 * at the base and a fade-out over the top quarter. All MOTION (the rising energy band,
 * the base impact flare, the apex bloom) lives in the fragment shaders, which animate
 * smoothly per-pixel via the GameTime global instead of the old chunky per-row band.
 * Colors blend primary to secondary bottom-to-top through the same recolor pipeline as
 * the sphere; the base "impact heat" brighten is CAPPED and lerps toward a brightened
 * PALETTE shade (never plain white), keeping the column hue-true.
 */
public final class BeamMesh {
	/** Half-width (blocks) of each crossed plane — the outer edge of the soft glow skirt. */
	public static final float HALF_WIDTH = 1.4F;
	/**
	 * The height fraction {@code v} at which the beam crosses the bubble membrane
	 * ({@code y = +radius}). Hardcoded identically in all eight beam_*.fsh shaders
	 * (frozen contract: no custom uniforms), so keep the two in sync.
	 */
	public static final float APEX_V = 0.75F;
	/** Rows between the base and the membrane crossing ({@code v in [0, APEX_V]}). */
	private static final int ROWS_BELOW = 12;
	/** Rows between the membrane crossing and the top ({@code v in [APEX_V, 1]}). */
	private static final int ROWS_ABOVE = 6;
	/** The beam starts at the projector block's top face (center is block-centered). */
	private static final float BASE_Y = -0.5F;
	/** Alpha ramps 0 to 1 over the bottom 3% of the column. */
	private static final float BASE_RAMP_FRAC = 0.03F;
	/** Alpha fades 1 to 0 over the top 25% (the column dissolves into the sky). */
	private static final float TOP_FADE_FRAC = 0.25F;
	/**
	 * The beam's intensity budget: vertex alpha runs at 40% of the sphere's base
	 * alpha. The shaders' radial falloff + hue-preserving soft clip do the rest, so
	 * the additive column reads as a saturated palette-tinted energy beam instead of
	 * clipping to a white bar.
	 */
	private static final float BEAM_ALPHA_SCALE = 0.4F;
	/** Base "impact heat" zone: the brighten fades out over the bottom 8% of the column. */
	private static final float BASE_HEAT_FRAC = 0.08F;
	/** Hard cap on the CPU-side brighten lerp (which targets a brightened PALETTE shade). */
	private static final float BRIGHTEN_CAP = 0.35F;

	private BeamMesh() {
	}

	/** @return the beam's top height (world units above the shield center) for {@code radius}. */
	public static float topY(float radius) {
		return Math.max(radius * 1.35F, radius + 6.0F);
	}

	/**
	 * Emits both crossed planes, oriented toward the camera.
	 *
	 * @param radius    the shield's current radius; sets the column height and the
	 *                  membrane-crossing height ({@code y = +radius})
	 * @param alphaBase base intensity, already health-scaled like the sphere's; the
	 *                  beam runs at {@link #BEAM_ALPHA_SCALE} of it
	 * @param camDx     camera X offset from the shield center (world units)
	 * @param camDz     camera Z offset from the shield center (world units)
	 */
	public static void emit(PoseStack.Pose pose, VertexConsumer buffer, float radius,
			int argbPrimary, int argbSecondary, float alphaBase, float camDx, float camDz) {
		// Horizontal camera bearing; directly above/below the axis any bearing works
		// (the crossed pair is bearing-symmetric), so fall back to 0.
		float camAngle = camDx * camDx + camDz * camDz < 1.0E-4F ? 0.0F : (float) Math.atan2(camDz, camDx);

		float top = topY(radius);
		int totalRows = ROWS_BELOW + ROWS_ABOVE;
		float[] ys = new float[totalRows + 1];
		float[] vs = new float[totalRows + 1];
		int[] colors = new int[totalRows + 1];
		for (int row = 0; row <= totalRows; row++) {
			// Piecewise v map: the membrane crossing is pinned at v = APEX_V.
			float v;
			float y;
			if (row <= ROWS_BELOW) {
				v = APEX_V * row / ROWS_BELOW;
				y = Mth.lerp((float) row / ROWS_BELOW, BASE_Y, radius);
			} else {
				v = APEX_V + (1.0F - APEX_V) * (row - ROWS_BELOW) / ROWS_ABOVE;
				y = Mth.lerp((float) (row - ROWS_BELOW) / ROWS_ABOVE, radius, top);
			}

			ys[row] = y;
			vs[row] = v;
			// The profile follows the PHYSICAL height fraction (v is remapped).
			float h = (y - BASE_Y) / (top - BASE_Y);
			colors[row] = rowColor(h, argbPrimary, argbSecondary, alphaBase * BEAM_ALPHA_SCALE);
		}

		// Plane width axes at -45 and -135 degrees from the camera bearing: each
		// plane's front normal (width axis rotated +90 degrees) then sits within 45
		// degrees of the camera, so both planes always show their front face.
		emitPlane(pose, buffer, camAngle - Mth.PI / 4.0F, ys, vs, colors);
		emitPlane(pose, buffer, camAngle - 3.0F * Mth.PI / 4.0F, ys, vs, colors);
	}

	private static void emitPlane(PoseStack.Pose pose, VertexConsumer buffer, float widthAngle,
			float[] ys, float[] vs, int[] colors) {
		float wx = Mth.cos(widthAngle) * HALF_WIDTH;
		float wz = Mth.sin(widthAngle) * HALF_WIDTH;

		// One full-width quad per row (the shaders shape everything lateral);
		// bottom-left, bottom-right, top-right, top-left = counter-clockwise seen
		// from the front-normal side the camera is on (the pipeline culls the back).
		for (int row = 0; row < ys.length - 1; row++) {
			float y0 = ys[row];
			float y1 = ys[row + 1];
			buffer.addVertex(pose, -wx, y0, -wz).setUv(0.0F, vs[row]).setColor(colors[row]);
			buffer.addVertex(pose, wx, y0, wz).setUv(1.0F, vs[row]).setColor(colors[row]);
			buffer.addVertex(pose, wx, y1, wz).setUv(1.0F, vs[row + 1]).setColor(colors[row + 1]);
			buffer.addVertex(pose, -wx, y1, -wz).setUv(0.0F, vs[row + 1]).setColor(colors[row + 1]);
		}
	}

	/**
	 * The minimal CPU-side vertical profile for one ring of vertices: base ramp x top
	 * fade in the alpha, the primary-to-secondary palette gradient, and a CAPPED base
	 * "impact heat" brighten that lerps toward a BRIGHTENED PALETTE shade (channel x
	 * 1.6, clamped) — never toward plain white, so the hue survives the additive
	 * blend. The moving band / flare / bloom all live in the fragment shaders.
	 */
	private static int rowColor(float h, int argbPrimary, int argbSecondary, float alphaBase) {
		float profile = Mth.clamp(h / BASE_RAMP_FRAC, 0.0F, 1.0F)
				* Mth.clamp((1.0F - h) / TOP_FADE_FRAC, 0.0F, 1.0F);
		float alpha = Mth.clamp(alphaBase * profile, 0.0F, 1.0F);

		float mix = Mth.clamp(h, 0.0F, 1.0F);
		int r = Mth.lerpInt(mix, argbPrimary >> 16 & 0xFF, argbSecondary >> 16 & 0xFF);
		int g = Mth.lerpInt(mix, argbPrimary >> 8 & 0xFF, argbSecondary >> 8 & 0xFF);
		int b = Mth.lerpInt(mix, argbPrimary & 0xFF, argbSecondary & 0xFF);

		float brighten = Math.min(BRIGHTEN_CAP, BRIGHTEN_CAP * (1.0F - Mth.clamp(h / BASE_HEAT_FRAC, 0.0F, 1.0F)));
		if (brighten > 0.0F) {
			r = Mth.lerpInt(brighten, r, Math.min(255, Math.round(r * 1.6F)));
			g = Mth.lerpInt(brighten, g, Math.min(255, Math.round(g * 1.6F)));
			b = Mth.lerpInt(brighten, b, Math.min(255, Math.round(b * 1.6F)));
		}

		int a = Mth.clamp((int) (alpha * 255.0F), 0, 255);
		return a << 24 | r << 16 | g << 8 | b;
	}
}

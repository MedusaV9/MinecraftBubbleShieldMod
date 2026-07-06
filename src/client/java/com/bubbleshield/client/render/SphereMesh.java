package com.bubbleshield.client.render;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Cached unit-radius UV sphere emitted as quads into a {@link VertexConsumer} using the
 * {@code POSITION_TEX_COLOR} format ({@code addVertex(pose, x, y, z).setUv(u, v).setColor(argb)}).
 *
 * <p>Positions and base UVs are precomputed once on a (latSteps + 1) x (lonSteps + 1)
 * grid; the two pole rows collapse into degenerate quads, which vanilla's quad
 * rendering handles fine. Per-vertex color blends primary to secondary along the
 * latitude, and per-vertex alpha fades near "dissolve centers" (whitelisted players
 * close to the surface) so they can see/walk through the shield wall.
 */
public final class SphereMesh {
	/** Distance (blocks) from a dissolve center over which the surface fades back in. */
	public static final float DISSOLVE_RANGE = 6.0F;

	private final int lonSteps;
	private final int latSteps;
	/** Unit sphere positions, indexed [lat * (lonSteps + 1) + lon] * 3. */
	private final float[] positions;
	/** Base UVs (u = longitude 0..1, v = latitude 0..1), same indexing, stride 2. */
	private final float[] uvs;

	public SphereMesh(int lonSteps, int latSteps) {
		this.lonSteps = lonSteps;
		this.latSteps = latSteps;
		int vertexCount = (latSteps + 1) * (lonSteps + 1);
		this.positions = new float[vertexCount * 3];
		this.uvs = new float[vertexCount * 2];

		for (int lat = 0; lat <= latSteps; lat++) {
			float v = (float) lat / latSteps;
			float theta = v * Mth.PI;
			float sinTheta = Mth.sin(theta);
			float cosTheta = Mth.cos(theta);
			for (int lon = 0; lon <= lonSteps; lon++) {
				float u = (float) lon / lonSteps;
				float phi = u * Mth.TWO_PI;
				int i = lat * (lonSteps + 1) + lon;
				this.positions[i * 3] = sinTheta * Mth.cos(phi);
				this.positions[i * 3 + 1] = cosTheta;
				this.positions[i * 3 + 2] = sinTheta * Mth.sin(phi);
				this.uvs[i * 2] = u;
				this.uvs[i * 2 + 1] = v;
			}
		}
	}

	/** Emits the sphere with untransformed UVs; see the main overload. */
	public void emit(PoseStack.Pose pose, VertexConsumer buffer, float radius, int argbPrimary, int argbSecondary, float alphaBase, List<Vec3> dissolveCentersRelative) {
		emit(pose, buffer, radius, argbPrimary, argbSecondary, alphaBase, dissolveCentersRelative, 1.0F, 0.0F, 0.0F);
	}

	/**
	 * Emits the sphere as quads.
	 *
	 * @param radius                  world radius the unit sphere is scaled to (CPU-side, so
	 *                                dissolve distances stay in world units)
	 * @param alphaBase               base surface opacity; per-vertex alpha is
	 *                                {@code alphaBase * clamp(minDistToAnyDissolveCenter / DISSOLVE_RANGE, 0, 1)}
	 * @param dissolveCentersRelative dissolve centers relative to the sphere center
	 * @param uvScale                 pre-baked pattern scale (effect paramA); multiplies the base UVs
	 * @param uvOffsetU               pre-baked scroll offset (effect paramB * time), u axis
	 * @param uvOffsetV               pre-baked scroll offset, v axis
	 */
	public void emit(PoseStack.Pose pose, VertexConsumer buffer, float radius, int argbPrimary, int argbSecondary, float alphaBase, List<Vec3> dissolveCentersRelative, float uvScale, float uvOffsetU, float uvOffsetV) {
		int rowStride = this.lonSteps + 1;
		int vertexCount = (this.latSteps + 1) * rowStride;
		int[] colors = new int[vertexCount];

		for (int i = 0; i < vertexCount; i++) {
			float x = this.positions[i * 3] * radius;
			float y = this.positions[i * 3 + 1] * radius;
			float z = this.positions[i * 3 + 2] * radius;
			float alpha = alphaBase * dissolveFactor(x, y, z, dissolveCentersRelative);
			colors[i] = packColor(argbPrimary, argbSecondary, this.uvs[i * 2 + 1], alpha);
		}

		for (int lat = 0; lat < this.latSteps; lat++) {
			for (int lon = 0; lon < this.lonSteps; lon++) {
				int i00 = lat * rowStride + lon;
				int i10 = (lat + 1) * rowStride + lon;
				int i11 = (lat + 1) * rowStride + lon + 1;
				int i01 = lat * rowStride + lon + 1;
				emitVertex(pose, buffer, i00, radius, colors[i00], uvScale, uvOffsetU, uvOffsetV);
				emitVertex(pose, buffer, i10, radius, colors[i10], uvScale, uvOffsetU, uvOffsetV);
				emitVertex(pose, buffer, i11, radius, colors[i11], uvScale, uvOffsetU, uvOffsetV);
				emitVertex(pose, buffer, i01, radius, colors[i01], uvScale, uvOffsetU, uvOffsetV);
			}
		}
	}

	/** Emits the dome (upper hemisphere + bottom disc) with untransformed UVs; see the main overload. */
	public void emitHemisphere(PoseStack.Pose pose, VertexConsumer buffer, float radius, int argbPrimary, int argbSecondary, float alphaBase, List<Vec3> dissolveCentersRelative) {
		emitHemisphere(pose, buffer, radius, argbPrimary, argbSecondary, alphaBase, dissolveCentersRelative, 1.0F, 0.0F, 0.0F);
	}

	/**
	 * Emits the upper hemisphere (lat rows {@code 0..latSteps / 2}, top pole to equator)
	 * plus a flat disc closing the dome at {@code y = 0}, matching the server's
	 * {@link com.bubbleshield.shield.ShieldShape#DOME} containment volume. The disc
	 * reuses the equator ring vertices and fans to the center, whose color continues the
	 * latitude blend downward (latFrac 1.0 = full secondary) so the rim doesn't end in a
	 * hard hollow edge. Parameters match {@link #emit}. Requires an even {@code latSteps}
	 * so one lat row lies exactly on the equator.
	 */
	public void emitHemisphere(PoseStack.Pose pose, VertexConsumer buffer, float radius, int argbPrimary, int argbSecondary, float alphaBase, List<Vec3> dissolveCentersRelative, float uvScale, float uvOffsetU, float uvOffsetV) {
		int rowStride = this.lonSteps + 1;
		int equatorLat = this.latSteps / 2;
		int vertexCount = (equatorLat + 1) * rowStride;
		int[] colors = new int[vertexCount];

		for (int i = 0; i < vertexCount; i++) {
			float x = this.positions[i * 3] * radius;
			float y = this.positions[i * 3 + 1] * radius;
			float z = this.positions[i * 3 + 2] * radius;
			float alpha = alphaBase * dissolveFactor(x, y, z, dissolveCentersRelative);
			colors[i] = packColor(argbPrimary, argbSecondary, this.uvs[i * 2 + 1], alpha);
		}

		for (int lat = 0; lat < equatorLat; lat++) {
			for (int lon = 0; lon < this.lonSteps; lon++) {
				int i00 = lat * rowStride + lon;
				int i10 = (lat + 1) * rowStride + lon;
				int i11 = (lat + 1) * rowStride + lon + 1;
				int i01 = lat * rowStride + lon + 1;
				emitVertex(pose, buffer, i00, radius, colors[i00], uvScale, uvOffsetU, uvOffsetV);
				emitVertex(pose, buffer, i10, radius, colors[i10], uvScale, uvOffsetU, uvOffsetV);
				emitVertex(pose, buffer, i11, radius, colors[i11], uvScale, uvOffsetU, uvOffsetV);
				emitVertex(pose, buffer, i01, radius, colors[i01], uvScale, uvOffsetU, uvOffsetV);
			}
		}

		// Bottom disc: one degenerate quad (triangle) per longitude step, from the equator
		// ring to the dome center, so the dome reads as a closed shell from below.
		float centerAlpha = alphaBase * dissolveFactor(0.0F, 0.0F, 0.0F, dissolveCentersRelative);
		int centerColor = packColor(argbPrimary, argbSecondary, 1.0F, centerAlpha);
		for (int lon = 0; lon < this.lonSteps; lon++) {
			int i0 = equatorLat * rowStride + lon;
			int i1 = equatorLat * rowStride + lon + 1;
			float centerU = ((this.uvs[i0 * 2] + this.uvs[i1 * 2]) * 0.5F) * uvScale + uvOffsetU;
			float centerV = 1.0F * uvScale + uvOffsetV;
			emitVertex(pose, buffer, i0, radius, colors[i0], uvScale, uvOffsetU, uvOffsetV);
			emitVertex(pose, buffer, i1, radius, colors[i1], uvScale, uvOffsetU, uvOffsetV);
			// The two collapsed center vertices turn the quad into a triangle, exactly like
			// the sphere's pole rows.
			buffer.addVertex(pose, 0.0F, 0.0F, 0.0F).setUv(centerU, centerV).setColor(centerColor);
			buffer.addVertex(pose, 0.0F, 0.0F, 0.0F).setUv(centerU, centerV).setColor(centerColor);
		}
	}

	private void emitVertex(PoseStack.Pose pose, VertexConsumer buffer, int index, float radius, int argb, float uvScale, float uvOffsetU, float uvOffsetV) {
		buffer.addVertex(pose, this.positions[index * 3] * radius, this.positions[index * 3 + 1] * radius, this.positions[index * 3 + 2] * radius)
				.setUv(this.uvs[index * 2] * uvScale + uvOffsetU, this.uvs[index * 2 + 1] * uvScale + uvOffsetV)
				.setColor(argb);
	}

	private static float dissolveFactor(float x, float y, float z, List<Vec3> dissolveCentersRelative) {
		if (dissolveCentersRelative.isEmpty()) {
			return 1.0F;
		}

		double minDistSqr = Double.MAX_VALUE;
		for (Vec3 center : dissolveCentersRelative) {
			minDistSqr = Math.min(minDistSqr, center.distanceToSqr(x, y, z));
		}

		return Mth.clamp((float) Math.sqrt(minDistSqr) / DISSOLVE_RANGE, 0.0F, 1.0F);
	}

	/** Blends primary to secondary ARGB along the latitude and applies the computed alpha. */
	private static int packColor(int argbPrimary, int argbSecondary, float latFrac, float alpha) {
		float mix = Mth.clamp(latFrac, 0.0F, 1.0F);
		int r = Mth.lerpInt(mix, argbPrimary >> 16 & 0xFF, argbSecondary >> 16 & 0xFF);
		int g = Mth.lerpInt(mix, argbPrimary >> 8 & 0xFF, argbSecondary >> 8 & 0xFF);
		int b = Mth.lerpInt(mix, argbPrimary & 0xFF, argbSecondary & 0xFF);
		int a = Mth.clamp((int) (alpha * 255.0F), 0, 255);
		return a << 24 | r << 16 | g << 8 | b;
	}
}

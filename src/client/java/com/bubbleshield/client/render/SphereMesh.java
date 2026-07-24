package com.bubbleshield.client.render;

import java.util.ArrayList;
import java.util.List;

import com.bubbleshield.net.ShieldPayloads;
import com.bubbleshield.shield.ShieldGeometry;
import com.bubbleshield.shield.SurfaceWaveMath;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Cached unit-radius UV sphere emitted as quads into a {@link VertexConsumer} using the
 * {@code POSITION_TEX_COLOR} format ({@code addVertex(pose, x, y, z).setUv(u, v).setColor(argb)}).
 *
 * <p>Positions and UVs are precomputed once on a (latSteps + 1) x (lonSteps + 1)
 * grid; the two pole rows collapse into degenerate quads, which vanilla's quad
 * rendering handles fine. UVs are emitted RAW: u is the longitude fraction and v the
 * latitude fraction, both in [0, 1], with no per-effect scale or time-based offset —
 * the bubble fragment shaders assume UVs in [0, 1] and animate via the GameTime
 * shader global instead. Per-vertex color blends primary to secondary along the
 * latitude; WP-Dyn's {@link DeformState} then drives per-vertex surface dynamics
 * (all CPU-side — the mesh is re-emitted per frame, and the fragment contract
 * {@code alpha = clamp(bodyAlpha, 0, aMax) * vertexColor.a} keeps the vertex
 * alpha authoritative while every fx grades from {@code vertexColor.rgb}):
 * traveling damped impact waves displace vertices along their stored normals
 * with a crest color pulse, whitelisted players get a physical APERTURE
 * (displacement lip + rim color ring + UV flow-aside + a hole in the alpha
 * field, replacing the old flat dissolve) and a nearly-broken shield trembles.
 *
 * <p><b>Shape variants:</b> {@link #emitCylinder}, {@link #emitCube},
 * {@link #emitDiamond}, {@link #emitRing}, {@link #emitPyramid},
 * {@link #emitLens}, {@link #emitHourglass} and {@link #emitStar} emit cached
 * unit meshes for the non-spherical {@link com.bubbleshield.shield.ShieldShape}s,
 * built to the EXACT inscribed dimensions of {@link ShieldGeometry} (cylinder
 * 0.6/0.8, cube 1/sqrt(3), octahedron L1 ball, torus 0.7/0.3, pyramid apex 0.9 /
 * base -0.5 / half-extent 0.6, lens y-scale 0.45, hourglass 0.8/0.55, star
 * 0.55 &plusmn; 0.25 over six lobes at half-height 0.55) — that agreement is the
 * whole render-to-server contract. They follow the same conventions as the
 * sphere: unit-sized positions scaled by {@code radius} CPU-side (deformation
 * and aperture distances stay in world units), raw UVs in [0, 1], and the same
 * per-vertex aperture alpha. The primary-to-secondary color blend runs on a separate
 * per-vertex top-to-bottom fraction so it reads like the sphere's latitude blend
 * even where the shader UV is face-local (cube, pyramid base). The torus maps
 * BOTH its shader v and its blend fraction to the seam-free polar quantity
 * {@code (1 - cos(psi)) * 0.5}: the fx shaders treat v as non-periodic latitude,
 * so a wrapping minor-angle v would paint a hard seam ring along the tube top.
 * Every other shape keeps the sphere's "v = 0 at the top, 1 at the bottom"
 * orientation so the shaders and the color blend read identically across
 * shapes. The sphere/dome emitters are untouched, so their output stays
 * byte-identical.
 */
public final class SphereMesh {
	private final int lonSteps;
	private final int latSteps;
	/**
	 * Unit sphere positions, indexed [lat * (lonSteps + 1) + lon] * 3. On the unit
	 * sphere the position IS the outward unit normal, so the sphere/dome emitters
	 * reuse this array as their per-vertex normals (the dome's disc center vertex,
	 * emitted separately, carries the flat (0, -1, 0)).
	 */
	private final float[] positions;
	/** Raw UVs (u = longitude 0..1, v = latitude 0..1), same indexing, stride 2. */
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

	/**
	 * Emits the sphere as quads.
	 *
	 * @param radius    world radius the unit sphere is scaled to (CPU-side, so
	 *                  deformation and aperture distances stay in world units)
	 * @param alphaBase base surface opacity; per-vertex alpha is
	 *                  {@code alphaBase * min over apertures of SurfaceWaveMath.apertureAlphaFactor}
	 * @param deform    this shield's per-frame surface dynamics ({@link DeformState#NONE} when idle)
	 */
	public void emit(PoseStack.Pose pose, VertexConsumer buffer, float radius, int argbPrimary, int argbSecondary, float alphaBase, DeformState deform) {
		int rowStride = this.lonSteps + 1;
		int vertexCount = (this.latSteps + 1) * rowStride;
		Deformer deformer = new Deformer(deform, radius, true, argbPrimary, argbSecondary, alphaBase);
		float[] outPositions = new float[vertexCount * 3];
		float[] outUvs = new float[vertexCount * 2];
		int[] colors = new int[vertexCount];
		deformGrid(deformer, vertexCount, outPositions, outUvs, colors);

		for (int lat = 0; lat < this.latSteps; lat++) {
			for (int lon = 0; lon < this.lonSteps; lon++) {
				int i00 = lat * rowStride + lon;
				int i10 = (lat + 1) * rowStride + lon;
				int i11 = (lat + 1) * rowStride + lon + 1;
				int i01 = lat * rowStride + lon + 1;
				emitVertex(pose, buffer, i00, outPositions, outUvs, colors);
				emitVertex(pose, buffer, i10, outPositions, outUvs, colors);
				emitVertex(pose, buffer, i11, outPositions, outUvs, colors);
				emitVertex(pose, buffer, i01, outPositions, outUvs, colors);
			}
		}
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
	public void emitHemisphere(PoseStack.Pose pose, VertexConsumer buffer, float radius, int argbPrimary, int argbSecondary, float alphaBase, DeformState deform) {
		int rowStride = this.lonSteps + 1;
		int equatorLat = this.latSteps / 2;
		int vertexCount = (equatorLat + 1) * rowStride;
		Deformer deformer = new Deformer(deform, radius, true, argbPrimary, argbSecondary, alphaBase);
		float[] outPositions = new float[vertexCount * 3];
		float[] outUvs = new float[vertexCount * 2];
		int[] colors = new int[vertexCount];
		deformGrid(deformer, vertexCount, outPositions, outUvs, colors);

		for (int lat = 0; lat < equatorLat; lat++) {
			for (int lon = 0; lon < this.lonSteps; lon++) {
				int i00 = lat * rowStride + lon;
				int i10 = (lat + 1) * rowStride + lon;
				int i11 = (lat + 1) * rowStride + lon + 1;
				int i01 = lat * rowStride + lon + 1;
				emitVertex(pose, buffer, i00, outPositions, outUvs, colors);
				emitVertex(pose, buffer, i10, outPositions, outUvs, colors);
				emitVertex(pose, buffer, i11, outPositions, outUvs, colors);
				emitVertex(pose, buffer, i01, outPositions, outUvs, colors);
			}
		}

		// Bottom disc: one degenerate quad (triangle) per longitude step, from the equator
		// ring to the dome center, so the dome reads as a closed shell from below. The
		// center vertex sits at the origin with the flat disc normal (0, -1, 0); its
		// UV-flow u-shift is skipped (the fan's per-quad centerU spans every u, so a
		// single shifted u would tear the seam) — only the deformed v is reused.
		deformer.deform(0.0F, 0.0F, 0.0F, 0.0F, -1.0F, 0.0F, 0.5F, 1.0F, 1.0F, true);
		float centerX = deformer.outX;
		float centerY = deformer.outY;
		float centerZ = deformer.outZ;
		float centerV = deformer.outV;
		int centerColor = deformer.outColor;
		for (int lon = 0; lon < this.lonSteps; lon++) {
			int i0 = equatorLat * rowStride + lon;
			int i1 = equatorLat * rowStride + lon + 1;
			float centerU = (this.uvs[i0 * 2] + this.uvs[i1 * 2]) * 0.5F;
			emitVertex(pose, buffer, i0, outPositions, outUvs, colors);
			emitVertex(pose, buffer, i1, outPositions, outUvs, colors);
			// The two collapsed center vertices turn the quad into a triangle, exactly like
			// the sphere's pole rows.
			buffer.addVertex(pose, centerX, centerY, centerZ).setUv(centerU, centerV).setColor(centerColor);
			buffer.addVertex(pose, centerX, centerY, centerZ).setUv(centerU, centerV).setColor(centerColor);
		}
	}

	/**
	 * Runs the deformer over the first {@code vertexCount} sphere-grid vertices.
	 * On the unit sphere the position doubles as the outward unit normal.
	 */
	private void deformGrid(Deformer deformer, int vertexCount, float[] outPositions, float[] outUvs, int[] colors) {
		for (int i = 0; i < vertexCount; i++) {
			float x = this.positions[i * 3];
			float y = this.positions[i * 3 + 1];
			float z = this.positions[i * 3 + 2];
			float v = this.uvs[i * 2 + 1];
			deformer.deform(x, y, z, x, y, z, this.uvs[i * 2], v, v, false);
			outPositions[i * 3] = deformer.outX;
			outPositions[i * 3 + 1] = deformer.outY;
			outPositions[i * 3 + 2] = deformer.outZ;
			outUvs[i * 2] = deformer.outU;
			outUvs[i * 2 + 1] = deformer.outV;
			colors[i] = deformer.outColor;
		}
	}

	private static void emitVertex(PoseStack.Pose pose, VertexConsumer buffer, int index, float[] outPositions, float[] outUvs, int[] colors) {
		buffer.addVertex(pose, outPositions[index * 3], outPositions[index * 3 + 1], outPositions[index * 3 + 2])
				.setUv(outUvs[index * 2], outUvs[index * 2 + 1])
				.setColor(colors[index]);
	}

	/** Upright column: side wall at horizontal radius 0.6, caps at y = ±0.8 (unit scale). */
	public void emitCylinder(PoseStack.Pose pose, VertexConsumer buffer, float radius, int argbPrimary, int argbSecondary, float alphaBase, DeformState deform) {
		emitQuadMesh(CYLINDER_MESH, false, pose, buffer, radius, argbPrimary, argbSecondary, alphaBase, deform);
	}

	/** Axis-aligned box of half-extent 1/sqrt(3) (unit scale), 6 faces subdivided 16x16. */
	public void emitCube(PoseStack.Pose pose, VertexConsumer buffer, float radius, int argbPrimary, int argbSecondary, float alphaBase, DeformState deform) {
		emitQuadMesh(CUBE_MESH, false, pose, buffer, radius, argbPrimary, argbSecondary, alphaBase, deform);
	}

	/** Octahedron (L1 ball of radius 1, unit scale), 8 faces subdivided, spherical UVs. */
	public void emitDiamond(PoseStack.Pose pose, VertexConsumer buffer, float radius, int argbPrimary, int argbSecondary, float alphaBase, DeformState deform) {
		emitQuadMesh(DIAMOND_MESH, true, pose, buffer, radius, argbPrimary, argbSecondary, alphaBase, deform);
	}

	/** Torus with major radius 0.7 and tube radius 0.3 (unit scale); the hole is open. */
	public void emitRing(PoseStack.Pose pose, VertexConsumer buffer, float radius, int argbPrimary, int argbSecondary, float alphaBase, DeformState deform) {
		emitQuadMesh(RING_MESH, false, pose, buffer, radius, argbPrimary, argbSecondary, alphaBase, deform);
	}

	/** Square pyramid: apex at +0.9, base plane at -0.5, base half-extent 0.6 (unit scale). */
	public void emitPyramid(PoseStack.Pose pose, VertexConsumer buffer, float radius, int argbPrimary, int argbSecondary, float alphaBase, DeformState deform) {
		emitQuadMesh(PYRAMID_MESH, false, pose, buffer, radius, argbPrimary, argbSecondary, alphaBase, deform);
	}

	/** Oblate spheroid: equatorial radius 1, vertical semi-axis 0.45 (unit scale). */
	public void emitLens(PoseStack.Pose pose, VertexConsumer buffer, float radius, int argbPrimary, int argbSecondary, float alphaBase, DeformState deform) {
		emitQuadMesh(LENS_MESH, true, pose, buffer, radius, argbPrimary, argbSecondary, alphaBase, deform);
	}

	/** Two cones tip-to-tip: waist at the center, caps at y = ±0.8 with radius 0.55 (unit scale). */
	public void emitHourglass(PoseStack.Pose pose, VertexConsumer buffer, float radius, int argbPrimary, int argbSecondary, float alphaBase, DeformState deform) {
		emitQuadMesh(HOURGLASS_MESH, false, pose, buffer, radius, argbPrimary, argbSecondary, alphaBase, deform);
	}

	/** Six-lobed star prism: R(theta) = 0.55 + 0.25 cos(6 theta), caps at y = ±0.55 (unit scale). */
	public void emitStar(PoseStack.Pose pose, VertexConsumer buffer, float radius, int argbPrimary, int argbSecondary, float alphaBase, DeformState deform) {
		emitQuadMesh(STAR_MESH, false, pose, buffer, radius, argbPrimary, argbSecondary, alphaBase, deform);
	}

	/**
	 * Cached unit-scale quad soup for one non-spherical shape: unique vertices
	 * (position + outward unit surface normal + shader UV + color-blend fraction)
	 * plus 4-per-quad indices. Degenerate quads (a repeated index) render as
	 * triangles, exactly like the sphere's pole rows and the dome's disc fan.
	 * The stored normals are the displacement directions for WP-Dyn's per-vertex
	 * surface deformation (waves, aperture lip, tremble); cap-rim vertices shared
	 * with a side wall keep the WALL normal so wall and cap displace together
	 * without tearing.
	 */
	private record QuadMesh(float[] positions, float[] normals, float[] uvs, float[] latFracs, int[] quadIndices) {
	}

	/** Growable builder for {@link QuadMesh}; only runs once per shape at class init. */
	private static final class QuadMeshBuilder {
		private final List<float[]> vertices = new ArrayList<>();
		private final List<int[]> quads = new ArrayList<>();

		/** Adds one unique vertex; {@code (nx, ny, nz)} is the outward unit surface normal. */
		int vertex(float x, float y, float z, float nx, float ny, float nz, float u, float v, float latFrac) {
			this.vertices.add(new float[] {x, y, z, nx, ny, nz, u, v, latFrac});
			return this.vertices.size() - 1;
		}

		void quad(int i0, int i1, int i2, int i3) {
			this.quads.add(new int[] {i0, i1, i2, i3});
		}

		/** A triangle emitted as a degenerate quad (last vertex repeated). */
		void triangle(int i0, int i1, int i2) {
			this.quads.add(new int[] {i0, i1, i2, i2});
		}

		QuadMesh build() {
			float[] positions = new float[this.vertices.size() * 3];
			float[] normals = new float[this.vertices.size() * 3];
			float[] uvs = new float[this.vertices.size() * 2];
			float[] latFracs = new float[this.vertices.size()];
			for (int i = 0; i < this.vertices.size(); i++) {
				float[] vertex = this.vertices.get(i);
				positions[i * 3] = vertex[0];
				positions[i * 3 + 1] = vertex[1];
				positions[i * 3 + 2] = vertex[2];
				normals[i * 3] = vertex[3];
				normals[i * 3 + 1] = vertex[4];
				normals[i * 3 + 2] = vertex[5];
				uvs[i * 2] = vertex[6];
				uvs[i * 2 + 1] = vertex[7];
				latFracs[i] = vertex[8];
			}

			int[] quadIndices = new int[this.quads.size() * 4];
			for (int q = 0; q < this.quads.size(); q++) {
				int[] quad = this.quads.get(q);
				System.arraycopy(quad, 0, quadIndices, q * 4, 4);
			}

			return new QuadMesh(positions, normals, uvs, latFracs, quadIndices);
		}
	}

	// Cube/diamond/pyramid subdivisions raised (8 -> 16 / 12 / 16) so WP-Dyn's
	// per-vertex waves have enough vertices on the flat faces to read as waves.
	private static final QuadMesh CYLINDER_MESH = buildCylinder(48, 16);
	private static final QuadMesh CUBE_MESH = buildCube(16);
	private static final QuadMesh DIAMOND_MESH = buildDiamond(12);
	private static final QuadMesh RING_MESH = buildRing(48, 24);
	private static final QuadMesh PYRAMID_MESH = buildPyramid(16);
	private static final QuadMesh LENS_MESH = buildLens(48, 32);
	private static final QuadMesh HOURGLASS_MESH = buildHourglass(48, 16);
	private static final QuadMesh STAR_MESH = buildStar(96, 12);

	/**
	 * Shared emitter: deformation/colors computed per unique vertex, quads emitted
	 * by index. {@code geodesic} picks the impact-wave distance metric (arc length
	 * {@code R * acos(dir dot)} for the round-ish diamond/lens, Euclidean distance
	 * to the hit point for the prism-like shapes).
	 */
	private static void emitQuadMesh(QuadMesh mesh, boolean geodesic, PoseStack.Pose pose, VertexConsumer buffer, float radius, int argbPrimary, int argbSecondary, float alphaBase, DeformState deform) {
		int vertexCount = mesh.positions.length / 3;
		Deformer deformer = new Deformer(deform, radius, geodesic, argbPrimary, argbSecondary, alphaBase);
		float[] outPositions = new float[vertexCount * 3];
		float[] outUvs = new float[vertexCount * 2];
		int[] colors = new int[vertexCount];
		for (int i = 0; i < vertexCount; i++) {
			deformer.deform(mesh.positions[i * 3], mesh.positions[i * 3 + 1], mesh.positions[i * 3 + 2],
					mesh.normals[i * 3], mesh.normals[i * 3 + 1], mesh.normals[i * 3 + 2],
					mesh.uvs[i * 2], mesh.uvs[i * 2 + 1], mesh.latFracs[i], false);
			outPositions[i * 3] = deformer.outX;
			outPositions[i * 3 + 1] = deformer.outY;
			outPositions[i * 3 + 2] = deformer.outZ;
			outUvs[i * 2] = deformer.outU;
			outUvs[i * 2 + 1] = deformer.outV;
			colors[i] = deformer.outColor;
		}

		for (int index : mesh.quadIndices) {
			buffer.addVertex(pose, outPositions[index * 3], outPositions[index * 3 + 1], outPositions[index * 3 + 2])
					.setUv(outUvs[index * 2], outUvs[index * 2 + 1])
					.setColor(colors[index]);
		}
	}

	/**
	 * The v span each cylinder cap fan covers from its rim (v = 0 or 1) toward its
	 * center vertex: a small radial band so the caps show a live shader gradient
	 * instead of collapsing to a single constant-v (visually flat/striped) value.
	 */
	private static final float CAP_V_BAND = 0.1F;

	/**
	 * Side wall (u = azimuth fraction, v = top-to-bottom fraction) plus top/bottom
	 * discs as center fans of degenerate quads (the dome-disc precedent). The cap
	 * center vertices carry {@code v = CAP_V_BAND} (top) / {@code 1 - CAP_V_BAND}
	 * (bottom) so each cap sweeps a small radial v band rim-to-center — the rim
	 * vertices are shared with the side wall, so the wall-to-cap v is continuous
	 * and the fx shaders (which read v as non-periodic latitude) render the caps
	 * as concentric latitude rings instead of one flat color. The color-blend
	 * fraction stays 0/1 at the centers (unchanged cap coloring). Dimensions from
	 * {@link ShieldGeometry}: horizontal radius 0.6, half-height 0.8.
	 */
	private static QuadMesh buildCylinder(int lonSteps, int heightSteps) {
		float rho = (float) ShieldGeometry.CYLINDER_RADIUS_FRAC;
		float halfHeight = (float) ShieldGeometry.CYLINDER_HALF_HEIGHT_FRAC;
		QuadMeshBuilder builder = new QuadMeshBuilder();

		int[][] side = new int[heightSteps + 1][lonSteps + 1];
		for (int row = 0; row <= heightSteps; row++) {
			float v = (float) row / heightSteps;
			float y = halfHeight - 2.0F * halfHeight * v;
			for (int lon = 0; lon <= lonSteps; lon++) {
				float u = (float) lon / lonSteps;
				float phi = u * Mth.TWO_PI;
				// Side-wall normal: radially outward, no vertical component.
				side[row][lon] = builder.vertex(rho * Mth.cos(phi), y, rho * Mth.sin(phi),
						Mth.cos(phi), 0.0F, Mth.sin(phi), u, v, v);
			}
		}

		for (int row = 0; row < heightSteps; row++) {
			for (int lon = 0; lon < lonSteps; lon++) {
				builder.quad(side[row][lon], side[row + 1][lon], side[row + 1][lon + 1], side[row][lon + 1]);
			}
		}

		// Caps: fan from the rim ring to a per-quad center vertex whose u averages
		// the rim pair (exactly like the dome's closing disc). The center's v steps
		// CAP_V_BAND inward from the rim's 0/1 so the cap is a radial v gradient.
		// Cap-center normals are the flat disc normals (0, +-1, 0); the rim ring
		// keeps the side-wall normals (shared vertices).
		for (int lon = 0; lon < lonSteps; lon++) {
			float centerU = ((float) lon + 0.5F) / lonSteps;
			int topCenter = builder.vertex(0.0F, halfHeight, 0.0F, 0.0F, 1.0F, 0.0F, centerU, CAP_V_BAND, 0.0F);
			builder.triangle(side[0][lon], side[0][lon + 1], topCenter);
			int bottomCenter = builder.vertex(0.0F, -halfHeight, 0.0F, 0.0F, -1.0F, 0.0F, centerU, 1.0F - CAP_V_BAND, 1.0F);
			builder.triangle(side[heightSteps][lon + 1], side[heightSteps][lon], bottomCenter);
		}

		return builder.build();
	}

	/**
	 * Six faces subdivided {@code n x n} so the aperture fade and impact waves have vertices to act on.
	 * The four side faces unwrap around the perimeter (u = (face + s) / 4) with
	 * v = top-to-bottom fraction; the caps use face-local UVs with a constant blend
	 * fraction (0 top, 1 bottom — the sphere's pole rows behave the same way).
	 * Half-extent from {@link ShieldGeometry}: 1/sqrt(3).
	 */
	private static QuadMesh buildCube(int n) {
		float h = (float) ShieldGeometry.CUBE_HALF_EXTENT_FRAC;
		QuadMeshBuilder builder = new QuadMeshBuilder();

		// Side faces in azimuth order (+X, +Z, -X, -Z), each sweeping s so u increases
		// continuously around the perimeter. Each face carries its flat outward normal.
		float[][] sideNormals = {{1.0F, 0.0F, 0.0F}, {0.0F, 0.0F, 1.0F}, {-1.0F, 0.0F, 0.0F}, {0.0F, 0.0F, -1.0F}};
		for (int face = 0; face < 4; face++) {
			float[] normal = sideNormals[face];
			int[][] grid = new int[n + 1][n + 1];
			for (int si = 0; si <= n; si++) {
				float s = (float) si / n;
				for (int ti = 0; ti <= n; ti++) {
					float t = (float) ti / n;
					float y = h - 2.0F * h * t;
					float a = -h + 2.0F * h * s;
					float x;
					float z;
					switch (face) {
						case 0 -> {
							x = h;
							z = a;
						}
						case 1 -> {
							x = -a;
							z = h;
						}
						case 2 -> {
							x = -h;
							z = -a;
						}
						default -> {
							x = a;
							z = -h;
						}
					}

					grid[si][ti] = builder.vertex(x, y, z, normal[0], normal[1], normal[2], (face + s) / 4.0F, t, t);
				}
			}

			addGridQuads(builder, grid, n);
		}

		// Caps: full 2D face-local UVs (no degenerate stripe), constant blend fraction.
		for (int cap = 0; cap < 2; cap++) {
			float y = cap == 0 ? h : -h;
			float ny = cap == 0 ? 1.0F : -1.0F;
			float latFrac = cap == 0 ? 0.0F : 1.0F;
			int[][] grid = new int[n + 1][n + 1];
			for (int si = 0; si <= n; si++) {
				float s = (float) si / n;
				for (int ti = 0; ti <= n; ti++) {
					float t = (float) ti / n;
					grid[si][ti] = builder.vertex(-h + 2.0F * h * s, y, -h + 2.0F * h * t, 0.0F, ny, 0.0F, s, t, latFrac);
				}
			}

			addGridQuads(builder, grid, n);
		}

		return builder.build();
	}

	private static void addGridQuads(QuadMeshBuilder builder, int[][] grid, int n) {
		for (int si = 0; si < n; si++) {
			for (int ti = 0; ti < n; ti++) {
				builder.quad(grid[si][ti], grid[si][ti + 1], grid[si + 1][ti + 1], grid[si + 1][ti]);
			}
		}
	}

	/**
	 * The L1 ball: 8 planar faces between the ±X/±Y/±Z unit corners, each subdivided
	 * into an {@code n}-row triangular grid emitted as degenerate quads. UVs come
	 * from the normalized direction (spherical mapping: u = azimuth fraction within
	 * the face's quadrant, v = polar fraction) so the surface shaders animate
	 * coherently; the blend fraction equals v, matching the sphere's latitude blend.
	 */
	private static QuadMesh buildDiamond(int n) {
		QuadMeshBuilder builder = new QuadMeshBuilder();
		float[] top = {0.0F, 1.0F, 0.0F};
		float[] bottom = {0.0F, -1.0F, 0.0F};
		// Equatorial corners in azimuth order: +X (0), +Z (PI/2), -X (PI), -Z (3PI/2).
		float[][] equator = {{1.0F, 0.0F, 0.0F}, {0.0F, 0.0F, 1.0F}, {-1.0F, 0.0F, 0.0F}, {0.0F, 0.0F, -1.0F}};

		for (int quadrant = 0; quadrant < 4; quadrant++) {
			float azimuthBase = quadrant * (Mth.PI / 2.0F);
			float[] c1 = equator[quadrant];
			float[] c2 = equator[(quadrant + 1) % 4];
			addOctantFace(builder, n, top, c1, c2, azimuthBase);
			addOctantFace(builder, n, bottom, c1, c2, azimuthBase);
		}

		return builder.build();
	}

	/** One octahedron face: barycentric subdivision from the apex toward the equator edge. */
	private static void addOctantFace(QuadMeshBuilder builder, int n, float[] apex, float[] c1, float[] c2, float azimuthBase) {
		// The face through the apex and the two unit-axis corners is planar with
		// outward normal normalize(apex + c1 + c2) = normalize(+-1, +-1, +-1).
		float invSqrt3 = (float) (1.0 / Math.sqrt(3.0));
		float nx = (apex[0] + c1[0] + c2[0]) * invSqrt3;
		float ny = (apex[1] + c1[1] + c2[1]) * invSqrt3;
		float nz = (apex[2] + c1[2] + c2[2]) * invSqrt3;
		int[][] rows = new int[n + 1][];
		for (int i = 0; i <= n; i++) {
			rows[i] = new int[i + 1];
			for (int j = 0; j <= i; j++) {
				float wApex = (float) (n - i) / n;
				float w1 = (float) (i - j) / n;
				float w2 = (float) j / n;
				float x = apex[0] * wApex + c1[0] * w1 + c2[0] * w2;
				float y = apex[1] * wApex + c1[1] * w1 + c2[1] * w2;
				float z = apex[2] * wApex + c1[2] * w1 + c2[2] * w2;
				rows[i][j] = builder.vertex(x, y, z, nx, ny, nz,
						sphericalU(x, z, azimuthBase),
						sphericalV(x, y, z),
						sphericalV(x, y, z));
			}
		}

		for (int i = 1; i <= n; i++) {
			for (int j = 0; j < i; j++) {
				builder.triangle(rows[i - 1][j], rows[i][j], rows[i][j + 1]);
				if (j < i - 1) {
					builder.triangle(rows[i - 1][j], rows[i][j + 1], rows[i - 1][j + 1]);
				}
			}
		}
	}

	/**
	 * Azimuth fraction in [0, 1] resolved within one quadrant so no quad ever spans
	 * the u = 0/1 seam (each face owns its own vertex copies, like the sphere's
	 * duplicated seam column). Apex vertices (x = z = 0) use the face-center azimuth,
	 * the same way each sphere pole vertex carries its own column's u.
	 */
	private static float sphericalU(float x, float z, float azimuthBase) {
		if (x == 0.0F && z == 0.0F) {
			return (azimuthBase + Mth.PI / 4.0F) / Mth.TWO_PI;
		}

		float azimuth = (float) Math.atan2(z, x);
		if (azimuth < 0.0F) {
			azimuth += Mth.TWO_PI;
		}

		// Points exactly on the +X axis read 0 but belong at 2*PI for the last quadrant.
		if (azimuth < azimuthBase - 1.0e-4F) {
			azimuth += Mth.TWO_PI;
		}

		return azimuth / Mth.TWO_PI;
	}

	/** Polar fraction in [0, 1] of the normalized direction (0 = up, 1 = down). */
	private static float sphericalV(float x, float y, float z) {
		float length = (float) Math.sqrt(x * x + y * y + z * z);
		return (float) Math.acos(Mth.clamp(y / length, -1.0F, 1.0F)) / Mth.PI;
	}

	/**
	 * Torus: u = major-angle fraction; v = the tube's top-to-bottom POLAR fraction
	 * {@code (1 - cos(psi)) * 0.5} — NOT the raw minor-angle fraction, which wraps
	 * (0 and 1 meet at the tube top) and would paint a hard seam ring there in
	 * every surface shader, since the fx shaders treat v as a non-periodic
	 * latitude. The polar mapping is seam-free (cos is continuous across psi =
	 * 0/2PI) and matches the sphere's "v = latitude" convention; the color-blend
	 * fraction uses the same quantity, so blend and shader v agree. Dimensions
	 * from {@link ShieldGeometry}: major radius 0.7, tube radius 0.3.
	 */
	private static QuadMesh buildRing(int majorSteps, int minorSteps) {
		float major = (float) ShieldGeometry.RING_MAJOR_FRAC;
		float minor = (float) ShieldGeometry.RING_MINOR_FRAC;
		QuadMeshBuilder builder = new QuadMeshBuilder();

		int[][] grid = new int[majorSteps + 1][minorSteps + 1];
		for (int a = 0; a <= majorSteps; a++) {
			float u = (float) a / majorSteps;
			float phi = u * Mth.TWO_PI;
			for (int b = 0; b <= minorSteps; b++) {
				float psi = (float) b / minorSteps * Mth.TWO_PI;
				// psi = 0 is the tube's top; sin swings the ring radius outward first.
				float y = minor * Mth.cos(psi);
				float ringRadius = major + minor * Mth.sin(psi);
				float latFrac = (1.0F - Mth.cos(psi)) * 0.5F;
				// Tube normal: (p - nearest major-circle point) / minor, i.e. the
				// unit vector from the tube's core circle out to the surface.
				grid[a][b] = builder.vertex(ringRadius * Mth.cos(phi), y, ringRadius * Mth.sin(phi),
						Mth.sin(psi) * Mth.cos(phi), Mth.cos(psi), Mth.sin(psi) * Mth.sin(phi),
						u, latFrac, latFrac);
			}
		}

		for (int a = 0; a < majorSteps; a++) {
			for (int b = 0; b < minorSteps; b++) {
				builder.quad(grid[a][b], grid[a][b + 1], grid[a + 1][b + 1], grid[a + 1][b]);
			}
		}

		return builder.build();
	}

	/**
	 * Square pyramid: apex at {@code +0.9} collapsing like a sphere pole row (all
	 * apex vertices coincide, each keeping its own column's u), base plane at
	 * {@code -0.5} with half-extent {@code 0.6}. The four taper faces follow the
	 * cube's side-face scheme (u unwraps the perimeter as {@code (face + s) / 4},
	 * v = top-to-bottom fraction = blend fraction), with the per-row half-extent
	 * shrinking linearly per {@link ShieldGeometry#pyramidTaper} (at unit radius
	 * that taper is exactly {@code 0.6 * t} for v = t). The base is a cube-style
	 * cap grid: face-local UVs, constant blend fraction 1 (bottom). Dimensions
	 * from {@link ShieldGeometry}: apex 0.9, base -0.5, half-extent 0.6.
	 */
	private static QuadMesh buildPyramid(int n) {
		float apexY = (float) ShieldGeometry.PYRAMID_APEX_FRAC;
		float baseY = (float) -ShieldGeometry.PYRAMID_BASE_FRAC;
		float halfExtent = (float) ShieldGeometry.PYRAMID_BASE_HALF_EXTENT_FRAC;
		QuadMeshBuilder builder = new QuadMeshBuilder();

		// Slant-face outward normal: each taper face's plane satisfies
		// dir_h * (apexY - baseY) + y * halfExtent = const, so the normal is
		// normalize(dir_h * 1.4, 0.6) — leaning outward and slightly up.
		float slantH = apexY - baseY;
		float slantLen = (float) Math.sqrt(slantH * slantH + halfExtent * halfExtent);
		float slantOut = slantH / slantLen;
		float slantUp = halfExtent / slantLen;
		float[][] slantNormals = {
			{slantOut, slantUp, 0.0F},
			{0.0F, slantUp, slantOut},
			{-slantOut, slantUp, 0.0F},
			{0.0F, slantUp, -slantOut},
		};

		// Taper faces in azimuth order (+X, +Z, -X, -Z), the cube's perimeter unwrap.
		for (int face = 0; face < 4; face++) {
			float[] normal = slantNormals[face];
			int[][] grid = new int[n + 1][n + 1];
			for (int si = 0; si <= n; si++) {
				float s = (float) si / n;
				for (int ti = 0; ti <= n; ti++) {
					float t = (float) ti / n;
					float y = apexY - (apexY - baseY) * t;
					// pyramidTaper(1, y) = 0.6 * (0.9 - y) / 1.4 = halfExtent * t.
					float e = halfExtent * t;
					float a = -e + 2.0F * e * s;
					float x;
					float z;
					switch (face) {
						case 0 -> {
							x = e;
							z = a;
						}
						case 1 -> {
							x = -a;
							z = e;
						}
						case 2 -> {
							x = -e;
							z = -a;
						}
						default -> {
							x = a;
							z = -e;
						}
					}

					grid[si][ti] = builder.vertex(x, y, z, normal[0], normal[1], normal[2], (face + s) / 4.0F, t, t);
				}
			}

			addGridQuads(builder, grid, n);
		}

		// Base cap: full 2D face-local UVs, constant blend fraction (cube-cap style).
		int[][] grid = new int[n + 1][n + 1];
		for (int si = 0; si <= n; si++) {
			float s = (float) si / n;
			for (int ti = 0; ti <= n; ti++) {
				float t = (float) ti / n;
				grid[si][ti] = builder.vertex(-halfExtent + 2.0F * halfExtent * s, baseY, -halfExtent + 2.0F * halfExtent * t,
						0.0F, -1.0F, 0.0F, s, t, 1.0F);
			}
		}

		addGridQuads(builder, grid, n);
		return builder.build();
	}

	/**
	 * Oblate spheroid: the sphere's lat/lon grid with the y coordinate scaled by
	 * {@link ShieldGeometry#LENS_HALF_HEIGHT_FRAC} (0.45) and the UVs unchanged
	 * (u = longitude, v = latitude fraction — the exact sphere mapping, so every
	 * surface shader animates identically to the sphere). The scaled grid lies
	 * exactly on the server's containment ellipsoid
	 * {@code (dx^2 + dz^2) / r^2 + dy^2 / (0.45 r)^2 = 1}.
	 */
	private static QuadMesh buildLens(int lonSteps, int latSteps) {
		float yScale = (float) ShieldGeometry.LENS_HALF_HEIGHT_FRAC;
		QuadMeshBuilder builder = new QuadMeshBuilder();

		int[][] grid = new int[latSteps + 1][lonSteps + 1];
		for (int lat = 0; lat <= latSteps; lat++) {
			float v = (float) lat / latSteps;
			float theta = v * Mth.PI;
			float sinTheta = Mth.sin(theta);
			float cosTheta = Mth.cos(theta);
			for (int lon = 0; lon <= lonSteps; lon++) {
				float u = (float) lon / lonSteps;
				float phi = u * Mth.TWO_PI;
				float px = sinTheta * Mth.cos(phi);
				float py = cosTheta * yScale;
				float pz = sinTheta * Mth.sin(phi);
				// Ellipsoid gradient normal: normalize(px, py / yScale^2, pz) —
				// the y term un-scales the stored (already y-scaled) position.
				float gy = py / (yScale * yScale);
				float invLen = 1.0F / (float) Math.sqrt(px * px + gy * gy + pz * pz);
				grid[lat][lon] = builder.vertex(px, py, pz, px * invLen, gy * invLen, pz * invLen, u, v, v);
			}
		}

		for (int lat = 0; lat < latSteps; lat++) {
			for (int lon = 0; lon < lonSteps; lon++) {
				builder.quad(grid[lat][lon], grid[lat + 1][lon], grid[lat + 1][lon + 1], grid[lat][lon + 1]);
			}
		}

		return builder.build();
	}

	/**
	 * Two cones tip-to-tip: the cylinder's side-wall scheme (u = azimuth fraction,
	 * v = top-to-bottom fraction = blend fraction) with the per-row radius following
	 * {@link ShieldGeometry#hourglassTaper} — {@code 0.55 * |y| / 0.8}, zero at the
	 * waist row (whose vertices all collapse onto the axis point, each keeping its
	 * own column's u, like a sphere pole row) — plus cylinder-style top/bottom cap
	 * fans whose center vertices step {@link #CAP_V_BAND} inward from the rim's
	 * v = 0/1. Requires an even {@code heightSteps} so one row lands exactly on the
	 * waist. Dimensions from {@link ShieldGeometry}: half-height 0.8, cap radius 0.55.
	 */
	private static QuadMesh buildHourglass(int lonSteps, int heightSteps) {
		float halfHeight = (float) ShieldGeometry.HOURGLASS_HALF_HEIGHT_FRAC;
		float maxRadius = (float) ShieldGeometry.HOURGLASS_MAX_RADIUS_FRAC;
		QuadMeshBuilder builder = new QuadMeshBuilder();

		// Cone-wall normal: the surface rho = slope * |y| (slope = 0.55 / 0.8) tilts
		// the radial normal by the slope — downward on the upper cone (which widens
		// upward), upward on the lower. The waist row (y = 0) keeps the purely
		// radial (cos phi, 0, sin phi).
		float slope = maxRadius / halfHeight;
		float coneInvLen = 1.0F / (float) Math.sqrt(1.0F + slope * slope);

		int[][] side = new int[heightSteps + 1][lonSteps + 1];
		for (int row = 0; row <= heightSteps; row++) {
			float v = (float) row / heightSteps;
			float y = halfHeight - 2.0F * halfHeight * v;
			// hourglassTaper(1, y) = 0.55 * |y| / 0.8.
			float rho = maxRadius * Math.abs(y) / halfHeight;
			float ny = y > 1.0e-6F ? -slope * coneInvLen : (y < -1.0e-6F ? slope * coneInvLen : 0.0F);
			float nRadial = y == 0.0F ? 1.0F : coneInvLen;
			for (int lon = 0; lon <= lonSteps; lon++) {
				float u = (float) lon / lonSteps;
				float phi = u * Mth.TWO_PI;
				side[row][lon] = builder.vertex(rho * Mth.cos(phi), y, rho * Mth.sin(phi),
						nRadial * Mth.cos(phi), ny, nRadial * Mth.sin(phi), u, v, v);
			}
		}

		for (int row = 0; row < heightSteps; row++) {
			for (int lon = 0; lon < lonSteps; lon++) {
				builder.quad(side[row][lon], side[row + 1][lon], side[row + 1][lon + 1], side[row][lon + 1]);
			}
		}

		// Cap fans, exactly the cylinder's: per-quad center vertices averaging the
		// rim pair's u, v stepped CAP_V_BAND inward so each cap is a radial v band.
		for (int lon = 0; lon < lonSteps; lon++) {
			float centerU = ((float) lon + 0.5F) / lonSteps;
			int topCenter = builder.vertex(0.0F, halfHeight, 0.0F, 0.0F, 1.0F, 0.0F, centerU, CAP_V_BAND, 0.0F);
			builder.triangle(side[0][lon], side[0][lon + 1], topCenter);
			int bottomCenter = builder.vertex(0.0F, -halfHeight, 0.0F, 0.0F, -1.0F, 0.0F, centerU, 1.0F - CAP_V_BAND, 1.0F);
			builder.triangle(side[heightSteps][lon + 1], side[heightSteps][lon], bottomCenter);
		}

		return builder.build();
	}

	/**
	 * Six-lobed star prism: the cylinder's side-wall scheme (u = azimuth fraction,
	 * v = top-to-bottom fraction = blend fraction) with the per-column radius
	 * following {@link ShieldGeometry#starRadius} — {@code 0.55 + 0.25 * cos(6 *
	 * theta)}, so the wall lies exactly on the server's containment bound — plus
	 * cylinder-style cap fans (the star polygon is star-shaped about the axis, so
	 * a center fan tiles each cap without overlap) whose center vertices step
	 * {@link #CAP_V_BAND} inward from the rim's v = 0/1. Dimensions from
	 * {@link ShieldGeometry}: half-height 0.55, radius 0.55 &plusmn; 0.25, 6 lobes.
	 */
	private static QuadMesh buildStar(int lonSteps, int heightSteps) {
		float halfHeight = (float) ShieldGeometry.STAR_HALF_HEIGHT_FRAC;
		float radiusMid = (float) ShieldGeometry.STAR_RADIUS_MID_FRAC;
		float radiusWave = (float) ShieldGeometry.STAR_RADIUS_WAVE_FRAC;
		QuadMeshBuilder builder = new QuadMeshBuilder();

		int[][] side = new int[heightSteps + 1][lonSteps + 1];
		for (int row = 0; row <= heightSteps; row++) {
			float v = (float) row / heightSteps;
			float y = halfHeight - 2.0F * halfHeight * v;
			for (int lon = 0; lon <= lonSteps; lon++) {
				float u = (float) lon / lonSteps;
				float phi = u * Mth.TWO_PI;
				// starRadius(1, dx, dz) = 0.55 + 0.25 * cos(6 * atan2(dz, dx)).
				float rho = radiusMid + radiusWave * Mth.cos(ShieldGeometry.STAR_LOBES * phi);
				// Wall normal of the surface rho = R(theta): the radial direction
				// corrected by R'(theta) along the tangential direction —
				// normalize(rho_hat - (R' / rho) * theta_hat), R' = -0.25 * 6 * sin(6 theta).
				float rPrime = -radiusWave * ShieldGeometry.STAR_LOBES * Mth.sin(ShieldGeometry.STAR_LOBES * phi);
				float tangentialScale = -rPrime / rho;
				float nx = Mth.cos(phi) + tangentialScale * -Mth.sin(phi);
				float nz = Mth.sin(phi) + tangentialScale * Mth.cos(phi);
				float invLen = 1.0F / (float) Math.sqrt(nx * nx + nz * nz);
				side[row][lon] = builder.vertex(rho * Mth.cos(phi), y, rho * Mth.sin(phi),
						nx * invLen, 0.0F, nz * invLen, u, v, v);
			}
		}

		for (int row = 0; row < heightSteps; row++) {
			for (int lon = 0; lon < lonSteps; lon++) {
				builder.quad(side[row][lon], side[row + 1][lon], side[row + 1][lon + 1], side[row][lon + 1]);
			}
		}

		for (int lon = 0; lon < lonSteps; lon++) {
			float centerU = ((float) lon + 0.5F) / lonSteps;
			int topCenter = builder.vertex(0.0F, halfHeight, 0.0F, 0.0F, 1.0F, 0.0F, centerU, CAP_V_BAND, 0.0F);
			builder.triangle(side[0][lon], side[0][lon + 1], topCenter);
			int bottomCenter = builder.vertex(0.0F, -halfHeight, 0.0F, 0.0F, -1.0F, 0.0F, centerU, 1.0F - CAP_V_BAND, 1.0F);
			builder.triangle(side[heightSteps][lon + 1], side[heightSteps][lon], bottomCenter);
		}

		return builder.build();
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

	/**
	 * Per-vertex surface-dynamics evaluator for one shield and frame (WP-Dyn).
	 * For every unique mesh vertex it computes:
	 *
	 * <ul>
	 * <li><b>Displacement</b> (world units, applied AFTER the radius scaling — the
	 * stored positions are unit-scale, so adding the world-unit offset to the
	 * scaled position is exactly {@code (unitPos + disp/radius * n) * radius}):
	 * the sum of every impact wave along the stored normal (surface distance
	 * {@code d} is the arc {@code R * acos(clamp(unitDir dot impactDir, -1, 1))}
	 * for the geodesic shapes — sphere/dome/lens/diamond, using the normalized
	 * unit-scale POSITION as the direction — and the Euclidean
	 * {@code |worldPos - impactDir * R|} for the prism-like shapes), plus every
	 * aperture lip along {@code 0.7 * tangentAway + 0.3 * normal}, plus the
	 * last-stand tremble along the normal, the total clamped to
	 * {@link SurfaceWaveMath#TOTAL_DISPLACEMENT_CLAMP};</li>
	 * <li><b>Color</b>: the latitude blend, then ONE lerp toward the hot color of
	 * whichever source has the larger weight — the impact crest (primary lerped
	 * 0.35 toward white; toward the SECONDARY for a HEAL wave) or the aperture
	 * rim ring (primary lerped 0.45 toward white);</li>
	 * <li><b>Alpha</b>: {@code alphaBase * min over apertures of apertureAlphaFactor}
	 * (the aperture hole replacing the legacy dissolve);</li>
	 * <li><b>UV flow</b>: {@code 0.04 * exp(-(d - holeR)^2 / 2.88)} radially away
	 * from the aperture's spherical UV projection, skipping the u component
	 * across the u = 0/1 seam ({@code |u - uAp| > 0.5}) and clamping a shifted u
	 * into [0.002, 0.998].</li>
	 * </ul>
	 *
	 * <p>With {@link DeformState#isIdle()} the evaluator short-circuits to the
	 * legacy path (identity positions/UVs, latitude blend + alphaBase), keeping
	 * the idle mesh byte-identical to the pre-WP-Dyn output.
	 */
	private static final class Deformer {
		/** UV flow peak magnitude. */
		private static final float UV_FLOW_MAX = 0.04F;
		/** UV flow Gaussian variance (2 * LIP_SIGMA_SQ). */
		private static final float UV_FLOW_SIGMA_SQ = 2.88F;
		/** Crest hot color: primary lerped this far toward white. */
		private static final float CREST_WHITE_LERP = 0.35F;
		/** Rim-ring hot color: primary lerped this far toward white. */
		private static final float RIM_WHITE_LERP = 0.45F;

		private final float radius;
		private final boolean geodesic;
		private final int argbPrimary;
		private final int argbSecondary;
		private final float alphaBase;
		private final boolean idle;
		private final float healthFrac;
		private final float timeSec;
		private final boolean tremble;

		private final int impactCount;
		private final float[] impactDirs;
		private final boolean[] impactOmni;
		private final boolean[] impactHeal;
		private final float[] impactStrengths;
		private final float[] impactAges;

		private final int apertureCount;
		private final float[] aperturePositions;
		private final float[] apertureHoleRs;
		private final float[] apertureUvs;

		// Outputs of the last deform() call.
		float outX;
		float outY;
		float outZ;
		float outU;
		float outV;
		int outColor;

		Deformer(DeformState deform, float radius, boolean geodesic, int argbPrimary, int argbSecondary, float alphaBase) {
			this.radius = radius;
			this.geodesic = geodesic;
			this.argbPrimary = argbPrimary;
			this.argbSecondary = argbSecondary;
			this.alphaBase = alphaBase;
			this.idle = deform.isIdle();
			this.healthFrac = deform.healthFrac();
			this.timeSec = deform.timeSec();
			this.tremble = deform.healthFrac() < DeformState.TREMBLE_HEALTH_FRAC;

			List<DeformState.Impact> impacts = deform.impacts();
			this.impactCount = impacts.size();
			this.impactDirs = new float[this.impactCount * 3];
			this.impactOmni = new boolean[this.impactCount];
			this.impactHeal = new boolean[this.impactCount];
			this.impactStrengths = new float[this.impactCount];
			this.impactAges = new float[this.impactCount];
			for (int i = 0; i < this.impactCount; i++) {
				DeformState.Impact impact = impacts.get(i);
				Vec3 dir = impact.dirUnit();
				this.impactDirs[i * 3] = (float) dir.x;
				this.impactDirs[i * 3 + 1] = (float) dir.y;
				this.impactDirs[i * 3 + 2] = (float) dir.z;
				this.impactOmni[i] = dir.lengthSqr() < 1.0e-6;
				this.impactHeal[i] = impact.kind() == ShieldPayloads.ImpactEntry.KIND_HEAL;
				this.impactStrengths[i] = impact.strength01();
				this.impactAges[i] = impact.ageSec();
			}

			List<DeformState.Aperture> apertures = deform.apertures();
			this.apertureCount = apertures.size();
			this.aperturePositions = new float[this.apertureCount * 3];
			this.apertureHoleRs = new float[this.apertureCount];
			this.apertureUvs = new float[this.apertureCount * 2];
			for (int i = 0; i < this.apertureCount; i++) {
				DeformState.Aperture aperture = apertures.get(i);
				Vec3 rel = aperture.relPos();
				this.aperturePositions[i * 3] = (float) rel.x;
				this.aperturePositions[i * 3 + 1] = (float) rel.y;
				this.aperturePositions[i * 3 + 2] = (float) rel.z;
				this.apertureHoleRs[i] = aperture.holeR();
				// Spherical UV projection of the aperture point (exact on the
				// sphere, an approximation on the other shapes' UV layouts).
				double length = rel.length();
				float azimuth = (float) Math.atan2(rel.z, rel.x);
				if (azimuth < 0.0F) {
					azimuth += Mth.TWO_PI;
				}

				this.apertureUvs[i * 2] = azimuth / Mth.TWO_PI;
				this.apertureUvs[i * 2 + 1] = length > 1.0e-4
						? (float) Math.acos(Mth.clamp(rel.y / length, -1.0, 1.0)) / Mth.PI
						: 0.5F;
			}
		}

		/**
		 * Evaluates one unique vertex: {@code (x, y, z)} the unit-scale position,
		 * {@code (nx, ny, nz)} the stored outward unit normal, {@code (u, v)} the
		 * raw shader UV, {@code latFrac} the color-blend fraction and
		 * {@code skipUFlow} true for seam-spanning vertices (the dome disc
		 * center) whose u must not shift.
		 */
		void deform(float x, float y, float z, float nx, float ny, float nz, float u, float v, float latFrac, boolean skipUFlow) {
			float worldX = x * this.radius;
			float worldY = y * this.radius;
			float worldZ = z * this.radius;
			if (this.idle) {
				this.outX = worldX;
				this.outY = worldY;
				this.outZ = worldZ;
				this.outU = u;
				this.outV = v;
				this.outColor = packColor(this.argbPrimary, this.argbSecondary, latFrac, this.alphaBase);
				return;
			}

			// Unit direction of the vertex position (the geodesic distance basis);
			// degenerate positions (the dome disc center) fall back to the normal.
			float posLength = (float) Math.sqrt(x * x + y * y + z * z);
			float ux;
			float uy;
			float uz;
			if (posLength > 1.0e-6F) {
				ux = x / posLength;
				uy = y / posLength;
				uz = z / posLength;
			} else {
				ux = nx;
				uy = ny;
				uz = nz;
			}

			float dispX = 0.0F;
			float dispY = 0.0F;
			float dispZ = 0.0F;
			float maxCrest = 0.0F;
			boolean crestIsHeal = false;

			for (int i = 0; i < this.impactCount; i++) {
				float strength = this.impactStrengths[i];
				float age = this.impactAges[i];
				float wave;
				float crest;
				if (this.impactOmni[i]) {
					// BREAK: a whole-surface breathing pulse, distance-independent.
					wave = SurfaceWaveMath.breakPulseDisplacement(age, this.healthFrac, this.radius) * strength;
					crest = SurfaceWaveMath.breakCrestWeight(age, this.radius) * strength;
				} else {
					float ix = this.impactDirs[i * 3];
					float iy = this.impactDirs[i * 3 + 1];
					float iz = this.impactDirs[i * 3 + 2];
					float d;
					if (this.geodesic) {
						float dot = Mth.clamp(ux * ix + uy * iy + uz * iz, -1.0F, 1.0F);
						d = this.radius * (float) Math.acos(dot);
					} else {
						float dx = worldX - ix * this.radius;
						float dy = worldY - iy * this.radius;
						float dz = worldZ - iz * this.radius;
						d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
					}

					wave = SurfaceWaveMath.impactDisplacement(d, age, strength, this.healthFrac, this.radius);
					crest = SurfaceWaveMath.crestWeight(d, age, strength, this.radius);
				}

				dispX += wave * nx;
				dispY += wave * ny;
				dispZ += wave * nz;
				if (crest > maxCrest) {
					maxCrest = crest;
					crestIsHeal = this.impactHeal[i];
				}
			}

			float alphaFactor = 1.0F;
			float maxRim = 0.0F;
			float uShift = 0.0F;
			float vShift = 0.0F;
			for (int i = 0; i < this.apertureCount; i++) {
				float holeR = this.apertureHoleRs[i];
				float dx = worldX - this.aperturePositions[i * 3];
				float dy = worldY - this.aperturePositions[i * 3 + 1];
				float dz = worldZ - this.aperturePositions[i * 3 + 2];
				float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
				alphaFactor = Math.min(alphaFactor, SurfaceWaveMath.apertureAlphaFactor(d, holeR));

				float lip = SurfaceWaveMath.lipDisplacement(d, holeR);
				if (lip > 1.0e-4F) {
					// The lip leans outward along 0.7 * tangent-away + 0.3 * normal;
					// tangent = the away-from-aperture direction minus its normal
					// component. A vertex AT the aperture point has no tangent and
					// takes the pure normal.
					float dot = dx * nx + dy * ny + dz * nz;
					float tx = dx - dot * nx;
					float ty = dy - dot * ny;
					float tz = dz - dot * nz;
					float tangentLength = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
					if (tangentLength > 1.0e-4F) {
						float inv = 0.7F / tangentLength;
						dispX += lip * (tx * inv + 0.3F * nx);
						dispY += lip * (ty * inv + 0.3F * ny);
						dispZ += lip * (tz * inv + 0.3F * nz);
					} else {
						dispX += lip * nx;
						dispY += lip * ny;
						dispZ += lip * nz;
					}
				}

				maxRim = Math.max(maxRim, SurfaceWaveMath.rimRingWeight(d, holeR));

				float offset = d - holeR;
				float flow = UV_FLOW_MAX * (float) Math.exp(-(offset * offset) / UV_FLOW_SIGMA_SQ);
				if (flow > 1.0e-4F) {
					float du = u - this.apertureUvs[i * 2];
					float dv = v - this.apertureUvs[i * 2 + 1];
					boolean skipU = skipUFlow || Math.abs(du) > 0.5F;
					float radialU = skipU ? 0.0F : du;
					float radialLength = (float) Math.sqrt(radialU * radialU + dv * dv);
					if (radialLength > 1.0e-5F) {
						uShift += flow * radialU / radialLength;
						vShift += flow * dv / radialLength;
					}
				}
			}

			if (this.tremble) {
				float offset = SurfaceWaveMath.trembleOffset(x, y, z, this.timeSec);
				dispX += offset * nx;
				dispY += offset * ny;
				dispZ += offset * nz;
			}

			float dispLength = (float) Math.sqrt(dispX * dispX + dispY * dispY + dispZ * dispZ);
			if (dispLength > SurfaceWaveMath.TOTAL_DISPLACEMENT_CLAMP) {
				float scale = SurfaceWaveMath.TOTAL_DISPLACEMENT_CLAMP / dispLength;
				dispX *= scale;
				dispY *= scale;
				dispZ *= scale;
			}

			this.outX = worldX + dispX;
			this.outY = worldY + dispY;
			this.outZ = worldZ + dispZ;
			this.outU = uShift != 0.0F ? Mth.clamp(u + uShift, 0.002F, 0.998F) : u;
			this.outV = v + vShift;
			this.outColor = this.gradedColor(latFrac, maxCrest, crestIsHeal, maxRim, alphaFactor);
		}

		/**
		 * The latitude blend, one hot lerp from the MAX-weight source only (crest
		 * vs rim ring), and the aperture alpha.
		 */
		private int gradedColor(float latFrac, float maxCrest, boolean crestIsHeal, float maxRim, float alphaFactor) {
			float mix = Mth.clamp(latFrac, 0.0F, 1.0F);
			int r = Mth.lerpInt(mix, this.argbPrimary >> 16 & 0xFF, this.argbSecondary >> 16 & 0xFF);
			int g = Mth.lerpInt(mix, this.argbPrimary >> 8 & 0xFF, this.argbSecondary >> 8 & 0xFF);
			int b = Mth.lerpInt(mix, this.argbPrimary & 0xFF, this.argbSecondary & 0xFF);

			float weight = Math.max(maxCrest, maxRim);
			if (weight > 0.0F) {
				int hotR;
				int hotG;
				int hotB;
				if (maxCrest >= maxRim && crestIsHeal) {
					// HEAL: the inverted grade, toward the secondary instead of white.
					hotR = this.argbSecondary >> 16 & 0xFF;
					hotG = this.argbSecondary >> 8 & 0xFF;
					hotB = this.argbSecondary & 0xFF;
				} else {
					float whiteLerp = maxCrest >= maxRim ? CREST_WHITE_LERP : RIM_WHITE_LERP;
					hotR = Mth.lerpInt(whiteLerp, this.argbPrimary >> 16 & 0xFF, 255);
					hotG = Mth.lerpInt(whiteLerp, this.argbPrimary >> 8 & 0xFF, 255);
					hotB = Mth.lerpInt(whiteLerp, this.argbPrimary & 0xFF, 255);
				}

				float lerp = Math.min(weight, 1.0F);
				r = Mth.lerpInt(lerp, r, hotR);
				g = Mth.lerpInt(lerp, g, hotG);
				b = Mth.lerpInt(lerp, b, hotB);
			}

			int a = Mth.clamp((int) (this.alphaBase * alphaFactor * 255.0F), 0, 255);
			return a << 24 | r << 16 | g << 8 | b;
		}
	}
}

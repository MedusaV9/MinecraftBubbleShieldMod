package com.bubbleshield.client.render;

import java.util.ArrayList;
import java.util.List;

import com.bubbleshield.shield.ShieldGeometry;
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
 * latitude, and per-vertex alpha fades near "dissolve centers" (whitelisted players
 * close to the surface) so they can see/walk through the shield wall.
 *
 * <p><b>Shape variants:</b> {@link #emitCylinder}, {@link #emitCube},
 * {@link #emitDiamond} and {@link #emitRing} emit cached unit meshes for the
 * non-spherical {@link com.bubbleshield.shield.ShieldShape}s, built to the EXACT
 * inscribed dimensions of {@link ShieldGeometry} (cylinder 0.6/0.8, cube
 * 1/sqrt(3), octahedron L1 ball, torus 0.7/0.3) — that agreement is the whole
 * render-to-server contract. They follow the same conventions as the sphere:
 * unit-sized positions scaled by {@code radius} CPU-side (dissolve distances stay
 * in world units), raw UVs in [0, 1], and the same per-vertex dissolve alpha. The
 * primary-to-secondary color blend runs on a separate per-vertex top-to-bottom
 * fraction so it reads like the sphere's latitude blend even where the shader UV
 * is face-local (cube). The torus maps BOTH its shader v and its blend fraction
 * to the seam-free polar quantity {@code (1 - cos(psi)) * 0.5}: the fx shaders
 * treat v as non-periodic latitude, so a wrapping minor-angle v would paint a
 * hard seam ring along the tube top. The sphere/dome emitters are untouched, so
 * their output stays byte-identical.
 */
public final class SphereMesh {
	/** Distance (blocks) from a dissolve center over which the surface fades back in. */
	public static final float DISSOLVE_RANGE = 6.0F;

	private final int lonSteps;
	private final int latSteps;
	/** Unit sphere positions, indexed [lat * (lonSteps + 1) + lon] * 3. */
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
	 * @param radius                  world radius the unit sphere is scaled to (CPU-side, so
	 *                                dissolve distances stay in world units)
	 * @param alphaBase               base surface opacity; per-vertex alpha is
	 *                                {@code alphaBase * clamp(minDistToAnyDissolveCenter / DISSOLVE_RANGE, 0, 1)}
	 * @param dissolveCentersRelative dissolve centers relative to the sphere center
	 */
	public void emit(PoseStack.Pose pose, VertexConsumer buffer, float radius, int argbPrimary, int argbSecondary, float alphaBase, List<Vec3> dissolveCentersRelative) {
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
				emitVertex(pose, buffer, i00, radius, colors[i00]);
				emitVertex(pose, buffer, i10, radius, colors[i10]);
				emitVertex(pose, buffer, i11, radius, colors[i11]);
				emitVertex(pose, buffer, i01, radius, colors[i01]);
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
	public void emitHemisphere(PoseStack.Pose pose, VertexConsumer buffer, float radius, int argbPrimary, int argbSecondary, float alphaBase, List<Vec3> dissolveCentersRelative) {
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
				emitVertex(pose, buffer, i00, radius, colors[i00]);
				emitVertex(pose, buffer, i10, radius, colors[i10]);
				emitVertex(pose, buffer, i11, radius, colors[i11]);
				emitVertex(pose, buffer, i01, radius, colors[i01]);
			}
		}

		// Bottom disc: one degenerate quad (triangle) per longitude step, from the equator
		// ring to the dome center, so the dome reads as a closed shell from below.
		float centerAlpha = alphaBase * dissolveFactor(0.0F, 0.0F, 0.0F, dissolveCentersRelative);
		int centerColor = packColor(argbPrimary, argbSecondary, 1.0F, centerAlpha);
		for (int lon = 0; lon < this.lonSteps; lon++) {
			int i0 = equatorLat * rowStride + lon;
			int i1 = equatorLat * rowStride + lon + 1;
			float centerU = (this.uvs[i0 * 2] + this.uvs[i1 * 2]) * 0.5F;
			float centerV = 1.0F;
			emitVertex(pose, buffer, i0, radius, colors[i0]);
			emitVertex(pose, buffer, i1, radius, colors[i1]);
			// The two collapsed center vertices turn the quad into a triangle, exactly like
			// the sphere's pole rows.
			buffer.addVertex(pose, 0.0F, 0.0F, 0.0F).setUv(centerU, centerV).setColor(centerColor);
			buffer.addVertex(pose, 0.0F, 0.0F, 0.0F).setUv(centerU, centerV).setColor(centerColor);
		}
	}

	private void emitVertex(PoseStack.Pose pose, VertexConsumer buffer, int index, float radius, int argb) {
		buffer.addVertex(pose, this.positions[index * 3] * radius, this.positions[index * 3 + 1] * radius, this.positions[index * 3 + 2] * radius)
				.setUv(this.uvs[index * 2], this.uvs[index * 2 + 1])
				.setColor(argb);
	}

	/** Upright column: side wall at horizontal radius 0.6, caps at y = ±0.8 (unit scale). */
	public void emitCylinder(PoseStack.Pose pose, VertexConsumer buffer, float radius, int argbPrimary, int argbSecondary, float alphaBase, List<Vec3> dissolveCentersRelative) {
		emitQuadMesh(CYLINDER_MESH, pose, buffer, radius, argbPrimary, argbSecondary, alphaBase, dissolveCentersRelative);
	}

	/** Axis-aligned box of half-extent 1/sqrt(3) (unit scale), 6 faces subdivided 8x8. */
	public void emitCube(PoseStack.Pose pose, VertexConsumer buffer, float radius, int argbPrimary, int argbSecondary, float alphaBase, List<Vec3> dissolveCentersRelative) {
		emitQuadMesh(CUBE_MESH, pose, buffer, radius, argbPrimary, argbSecondary, alphaBase, dissolveCentersRelative);
	}

	/** Octahedron (L1 ball of radius 1, unit scale), 8 faces subdivided, spherical UVs. */
	public void emitDiamond(PoseStack.Pose pose, VertexConsumer buffer, float radius, int argbPrimary, int argbSecondary, float alphaBase, List<Vec3> dissolveCentersRelative) {
		emitQuadMesh(DIAMOND_MESH, pose, buffer, radius, argbPrimary, argbSecondary, alphaBase, dissolveCentersRelative);
	}

	/** Torus with major radius 0.7 and tube radius 0.3 (unit scale); the hole is open. */
	public void emitRing(PoseStack.Pose pose, VertexConsumer buffer, float radius, int argbPrimary, int argbSecondary, float alphaBase, List<Vec3> dissolveCentersRelative) {
		emitQuadMesh(RING_MESH, pose, buffer, radius, argbPrimary, argbSecondary, alphaBase, dissolveCentersRelative);
	}

	/**
	 * Cached unit-scale quad soup for one non-spherical shape: unique vertices
	 * (position + shader UV + color-blend fraction) plus 4-per-quad indices.
	 * Degenerate quads (a repeated index) render as triangles, exactly like the
	 * sphere's pole rows and the dome's disc fan.
	 */
	private record QuadMesh(float[] positions, float[] uvs, float[] latFracs, int[] quadIndices) {
	}

	/** Growable builder for {@link QuadMesh}; only runs once per shape at class init. */
	private static final class QuadMeshBuilder {
		private final List<float[]> vertices = new ArrayList<>();
		private final List<int[]> quads = new ArrayList<>();

		int vertex(float x, float y, float z, float u, float v, float latFrac) {
			this.vertices.add(new float[] {x, y, z, u, v, latFrac});
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
			float[] uvs = new float[this.vertices.size() * 2];
			float[] latFracs = new float[this.vertices.size()];
			for (int i = 0; i < this.vertices.size(); i++) {
				float[] vertex = this.vertices.get(i);
				positions[i * 3] = vertex[0];
				positions[i * 3 + 1] = vertex[1];
				positions[i * 3 + 2] = vertex[2];
				uvs[i * 2] = vertex[3];
				uvs[i * 2 + 1] = vertex[4];
				latFracs[i] = vertex[5];
			}

			int[] quadIndices = new int[this.quads.size() * 4];
			for (int q = 0; q < this.quads.size(); q++) {
				int[] quad = this.quads.get(q);
				System.arraycopy(quad, 0, quadIndices, q * 4, 4);
			}

			return new QuadMesh(positions, uvs, latFracs, quadIndices);
		}
	}

	private static final QuadMesh CYLINDER_MESH = buildCylinder(48, 16);
	private static final QuadMesh CUBE_MESH = buildCube(8);
	private static final QuadMesh DIAMOND_MESH = buildDiamond(8);
	private static final QuadMesh RING_MESH = buildRing(48, 24);

	/** Shared emitter: colors computed per unique vertex, quads emitted by index. */
	private static void emitQuadMesh(QuadMesh mesh, PoseStack.Pose pose, VertexConsumer buffer, float radius, int argbPrimary, int argbSecondary, float alphaBase, List<Vec3> dissolveCentersRelative) {
		int vertexCount = mesh.positions.length / 3;
		int[] colors = new int[vertexCount];
		for (int i = 0; i < vertexCount; i++) {
			float x = mesh.positions[i * 3] * radius;
			float y = mesh.positions[i * 3 + 1] * radius;
			float z = mesh.positions[i * 3 + 2] * radius;
			float alpha = alphaBase * dissolveFactor(x, y, z, dissolveCentersRelative);
			colors[i] = packColor(argbPrimary, argbSecondary, mesh.latFracs[i], alpha);
		}

		for (int index : mesh.quadIndices) {
			buffer.addVertex(pose, mesh.positions[index * 3] * radius, mesh.positions[index * 3 + 1] * radius, mesh.positions[index * 3 + 2] * radius)
					.setUv(mesh.uvs[index * 2], mesh.uvs[index * 2 + 1])
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
				side[row][lon] = builder.vertex(rho * Mth.cos(phi), y, rho * Mth.sin(phi), u, v, v);
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
		for (int lon = 0; lon < lonSteps; lon++) {
			float centerU = ((float) lon + 0.5F) / lonSteps;
			int topCenter = builder.vertex(0.0F, halfHeight, 0.0F, centerU, CAP_V_BAND, 0.0F);
			builder.triangle(side[0][lon], side[0][lon + 1], topCenter);
			int bottomCenter = builder.vertex(0.0F, -halfHeight, 0.0F, centerU, 1.0F - CAP_V_BAND, 1.0F);
			builder.triangle(side[heightSteps][lon + 1], side[heightSteps][lon], bottomCenter);
		}

		return builder.build();
	}

	/**
	 * Six faces subdivided {@code n x n} so the dissolve fade has vertices to act on.
	 * The four side faces unwrap around the perimeter (u = (face + s) / 4) with
	 * v = top-to-bottom fraction; the caps use face-local UVs with a constant blend
	 * fraction (0 top, 1 bottom — the sphere's pole rows behave the same way).
	 * Half-extent from {@link ShieldGeometry}: 1/sqrt(3).
	 */
	private static QuadMesh buildCube(int n) {
		float h = (float) ShieldGeometry.CUBE_HALF_EXTENT_FRAC;
		QuadMeshBuilder builder = new QuadMeshBuilder();

		// Side faces in azimuth order (+X, +Z, -X, -Z), each sweeping s so u increases
		// continuously around the perimeter.
		for (int face = 0; face < 4; face++) {
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

					grid[si][ti] = builder.vertex(x, y, z, (face + s) / 4.0F, t, t);
				}
			}

			addGridQuads(builder, grid, n);
		}

		// Caps: full 2D face-local UVs (no degenerate stripe), constant blend fraction.
		for (int cap = 0; cap < 2; cap++) {
			float y = cap == 0 ? h : -h;
			float latFrac = cap == 0 ? 0.0F : 1.0F;
			int[][] grid = new int[n + 1][n + 1];
			for (int si = 0; si <= n; si++) {
				float s = (float) si / n;
				for (int ti = 0; ti <= n; ti++) {
					float t = (float) ti / n;
					grid[si][ti] = builder.vertex(-h + 2.0F * h * s, y, -h + 2.0F * h * t, s, t, latFrac);
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
				rows[i][j] = builder.vertex(x, y, z,
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
				grid[a][b] = builder.vertex(ringRadius * Mth.cos(phi), y, ringRadius * Mth.sin(phi), u, latFrac, latFrac);
			}
		}

		for (int a = 0; a < majorSteps; a++) {
			for (int b = 0; b < minorSteps; b++) {
				builder.quad(grid[a][b], grid[a][b + 1], grid[a + 1][b + 1], grid[a + 1][b]);
			}
		}

		return builder.build();
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

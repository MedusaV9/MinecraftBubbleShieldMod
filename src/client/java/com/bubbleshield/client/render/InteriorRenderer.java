package com.bubbleshield.client.render;

import java.util.HashMap;
import java.util.Map;

import com.bubbleshield.client.BubbleShieldClientConfig;
import com.bubbleshield.client.ClientShieldManager;
import com.bubbleshield.client.fx.FlashIntensity;
import com.bubbleshield.effect.ContextModifier;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.EffectRegistry;
import com.bubbleshield.interior.InteriorScatter;
import com.bubbleshield.interior.InteriorThemes;
import com.bubbleshield.shield.ShieldShape;
import com.bubbleshield.shield.ShieldState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.GlobalPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.joml.Vector3fc;

/**
 * Draws every bubble's themed INTERIOR elements — purely visual billboards
 * (tacos, glyph rain, ember motes, void tendrils...) scattered by
 * {@link InteriorScatter} and themed by {@link InteriorThemes} — through the same
 * submit-based level renderer as the membrane, but on the NON-BLENDING cutout
 * {@link InteriorPipelines}. Non-blending render types are routed into the SOLID
 * feature phase by {@code SubmitNodeCollection.submitCustomGeometry}, which runs
 * BEFORE the {@link SceneCopy} blit at {@code AFTER_SOLID_FEATURES} — so the
 * translucent membrane's refraction shader sees (and bends) the interiors like
 * any other world content. No explicit ordering against the bubble surface is
 * needed.
 *
 * <p>Per frame and per shield: frustum gate (projector AABB inflated by
 * radius + 1), element budget {@code clamp(4 + 1.2 * radius, 8, 80) * density},
 * distance LOD (x1 to 48 blocks, x0.5 to 96, x0.25 to 160, culled beyond), a
 * global cap of {@value #GLOBAL_QUAD_CAP} quads, and a skip for elements closer
 * than {@value #NEAR_SKIP_DIST} blocks to the camera. Billboards face the camera
 * using the frame's captured left/up basis ({@code C ± left*s ± up*s}); motion
 * programs animate the unit-space rest positions and every animated position is
 * re-clamped inside the unit shape ({@link InteriorScatter#isInsideUnit}, falling
 * back toward the rest position). The per-element pipeline allocates nothing:
 * the packed scatter arrays are cached per shield, and all per-element math is
 * primitive-only (the only per-shield allocations are the two submit lambdas and
 * the frustum AABB, matching the membrane renderer's pattern).
 *
 * <p>Elements are truncated prefix-first when LOD/density/global-cap shrink the
 * budget (stable membership, no popping mid-LOD); themes therefore list their
 * signature layer FIRST (the disco ball before its light shafts, the void shell
 * before its stars).
 */
public final class InteriorRenderer {
	/** Global per-frame quad budget across all shields. */
	private static final int GLOBAL_QUAD_CAP = 240;
	/** Elements closer to the camera than this many blocks are skipped. */
	private static final double NEAR_SKIP_DIST = 2.5;
	/** Squared form of {@link #NEAR_SKIP_DIST}, for the per-element test. */
	private static final double NEAR_SKIP_DIST_SQ = NEAR_SKIP_DIST * NEAR_SKIP_DIST;
	/** Matches {@code ShieldRenderer.MIN_VISIBLE_RADIUS} (kept private there). */
	private static final float MIN_VISIBLE_RADIUS = 0.05F;
	/** Animated positions must stay inside the unit shape at this margin. */
	private static final double RECLAMP_MARGIN = 0.98;
	/** LOD bands: full detail, half, quarter, culled (camera-to-center distance). */
	private static final double LOD_FULL_DIST = 48.0;
	private static final double LOD_HALF_DIST = 96.0;
	private static final double LOD_QUARTER_DIST = 160.0;
	/** Fraction of a flash layer's elements that join each shared flash (hash-gated). */
	private static final float FLASH_GATE = 0.3F;
	/** The game-time modulus keeping the float time small (mirrors ShieldRenderer). */
	private static final long TIME_WRAP_TICKS = 100000L;
	/** Scatter caches are wiped when more shields than this have been seen. */
	private static final int CACHE_LIMIT = 64;

	/** Camera basis captured at extraction: position + billboard left/up axes. */
	private record CamBasis(double x, double y, double z,
			float leftX, float leftY, float leftZ,
			float upX, float upY, float upZ) {
	}

	/** One shield's cached scatter, invalidated when effect/shape/budget change. */
	private record CacheEntry(int effectId, int shapeOrdinal, int count, float[] data) {
	}

	/**
	 * Keyed by dimension + position (like {@code ClientShieldManager}'s replica
	 * map) so same-coordinate shields in different dimensions never share a
	 * scatter; cleared by {@code ImpactFxManager}'s disconnect / level-change
	 * reset alongside the other per-shield trackers.
	 */
	private static final Map<GlobalPos, CacheEntry> CACHE = new HashMap<>();

	private static volatile CamBasis camBasis;

	private InteriorRenderer() {
	}

	/** Drops every cached scatter (disconnect / level change). */
	public static void clearCache() {
		CACHE.clear();
	}

	public static void register() {
		LevelExtractionEvents.END_EXTRACTION.register(context -> {
			Vec3 pos = context.camera().position();
			Vector3fc left = context.camera().leftVector();
			Vector3fc up = context.camera().upVector();
			camBasis = new CamBasis(pos.x, pos.y, pos.z,
					left.x(), left.y(), left.z(),
					up.x(), up.y(), up.z());
		});
		LevelRenderEvents.COLLECT_SUBMITS.register(InteriorRenderer::collectSubmits);
	}

	private static void collectSubmits(LevelRenderContext context) {
		float density = BubbleShieldClientConfig.interiorDensity();
		if (density <= 0.0F) {
			return;
		}

		var shields = ClientShieldManager.currentDimensionShields();
		if (shields.isEmpty()) {
			return;
		}

		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		CamBasis cam = camBasis;
		if (level == null || cam == null) {
			return;
		}

		float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		float timeSec = ((level.getGameTime() % TIME_WRAP_TICKS) + partialTick) * 0.05F;
		// The ONE shared <= 2Hz flash envelope (1.5 Hz pulse train, sharpened ^8),
		// scaled by the configured flash intensity; every flash-gated element in
		// every bubble pulses in unison against it.
		float envelope = (float) Math.pow(Math.max(0.0, Math.sin(2.0 * Math.PI * 1.5 * timeSec)), 8.0)
				* FlashIntensity.get();

		CameraRenderState cameraState = context.levelState().cameraRenderState;
		PoseStack poseStack = context.poseStack();
		SubmitNodeCollector collector = context.submitNodeCollector();
		float fogMultiplier = BubbleShieldClientConfig.volumetricMode().fogCountMultiplier();

		if (CACHE.size() > CACHE_LIMIT) {
			CACHE.clear();
		}

		int remaining = GLOBAL_QUAD_CAP;
		for (ClientShieldManager.ClientShield shield : shields) {
			float radius = shield.currentRadius();
			if (!shield.active() || radius < MIN_VISIBLE_RADIUS) {
				continue;
			}

			if (cameraState.initialized
					&& !cameraState.cullFrustum.isVisible(new AABB(shield.pos()).inflate(radius + 1.0))) {
				continue;
			}

			double centerX = shield.pos().getX() + 0.5;
			double centerY = shield.pos().getY() + 0.5;
			double centerZ = shield.pos().getZ() + 0.5;
			double camDx = centerX - cam.x;
			double camDy = centerY - cam.y;
			double camDz = centerZ - cam.z;
			double camDist = Math.sqrt(camDx * camDx + camDy * camDy + camDz * camDz);
			float lod;
			if (camDist <= LOD_FULL_DIST) {
				lod = 1.0F;
			} else if (camDist <= LOD_HALF_DIST) {
				lod = 0.5F;
			} else if (camDist <= LOD_QUARTER_DIST) {
				lod = 0.25F;
			} else {
				continue;
			}

			int maxCount = (int) Mth.clamp(4.0F + 1.2F * radius, 8.0F, 80.0F);
			int count = Math.min((int) (maxCount * density * lod), remaining);
			if (count <= 0) {
				continue;
			}

			remaining -= count;
			ShieldShape shape = ShieldShape.byOrdinal(shield.shape());
			GlobalPos globalPos = new GlobalPos(shield.dimension(), shield.pos());
			CacheEntry entry = CACHE.get(globalPos);
			if (entry == null || entry.effectId() != shield.effectId()
					|| entry.shapeOrdinal() != shape.ordinal() || entry.count() != maxCount) {
				entry = new CacheEntry(shield.effectId(), shape.ordinal(), maxCount,
						InteriorScatter.scatter(globalPos, shield.effectId(), shape, maxCount));
				CACHE.put(globalPos, entry);
			}

			// Live palette (owner recolor replaces it, like the membrane renderer).
			EffectDefinition def = EffectRegistry.get(shield.effectId());
			int colorOverride = shield.colorOverride();
			boolean recolored = colorOverride != ShieldState.NO_COLOR_OVERRIDE;
			int argbPrimary = recolored ? colorOverride : def.argbPrimary();
			int argbSecondary = recolored ? ContextModifier.deriveOverrideSecondary(colorOverride) : def.argbSecondary();

			Draw draw = new Draw(entry.data(), maxCount, count, InteriorThemes.themeFor(shield.effectId()),
					shape, radius, argbPrimary, argbSecondary, timeSec, envelope, fogMultiplier,
					cam, centerX - cam.x, centerY - cam.y, centerZ - cam.z);

			poseStack.pushPose();
			poseStack.translate(centerX - cam.x, centerY - cam.y, centerZ - cam.z);
			collector.submitCustomGeometry(poseStack, InteriorPipelines.pixel(),
					(pose, buffer) -> draw.emit(pose, buffer, false));
			collector.submitCustomGeometry(poseStack, InteriorPipelines.soft(),
					(pose, buffer) -> draw.emit(pose, buffer, true));
			poseStack.popPose();
		}
	}

	/**
	 * One shield's per-frame draw state, captured once and replayed by the two
	 * submit callbacks (one per sheet). All per-element work inside {@link #emit}
	 * is primitive math on the cached packed array — no allocation.
	 */
	private record Draw(float[] data, int scatterCount, int drawCount, InteriorThemes.Theme theme,
			ShieldShape shape, float radius, int argbPrimary, int argbSecondary,
			float timeSec, float envelope, float fogMultiplier,
			CamBasis cam, double relX, double relY, double relZ) {

		void emit(PoseStack.Pose pose, VertexConsumer buffer, boolean softSheet) {
			float baseSize = Mth.clamp(this.radius * 0.06F, 0.15F, 1.5F);
			for (int i = 0; i < this.drawCount; i++) {
				int slot = i * InteriorScatter.STRIDE;
				int sprite = (int) this.data[slot + 3];
				if (InteriorThemes.isSoftSprite(sprite) != softSheet) {
					continue;
				}

				InteriorThemes.Layer layer = InteriorThemes.layerOf(this.theme, this.scatterCount, i);
				float seed = this.data[slot + 7];
				if (layer.fog()) {
					// Volumetric thinning: OFF drops all fog elements, LOW every other.
					if (this.fogMultiplier <= 0.0F || (this.fogMultiplier < 1.0F && seed >= this.fogMultiplier)) {
						continue;
					}
				}

				float baseX = this.data[slot];
				float baseY = this.data[slot + 1];
				float baseZ = this.data[slot + 2];
				float phase = this.data[slot + 5];
				int motion = (int) this.data[slot + 6];

				// Motion program -> animated unit position, then re-clamp: keep the
				// animated point only while it stays inside the shape; otherwise
				// pull halfway back toward the (always-inside) rest position, then
				// give up and rest.
				double x = animatedX(motion, baseX, baseZ, phase, seed);
				double y = animatedY(motion, baseX, baseY, baseZ, phase, seed);
				double z = animatedZ(motion, baseX, baseZ, phase, seed);
				if (!InteriorScatter.isInsideUnit(this.shape, RECLAMP_MARGIN, x, y, z)) {
					x = baseX + (x - baseX) * 0.5;
					y = baseY + (y - baseY) * 0.5;
					z = baseZ + (z - baseZ) * 0.5;
					if (!InteriorScatter.isInsideUnit(this.shape, RECLAMP_MARGIN, x, y, z)) {
						x = baseX;
						y = baseY;
						z = baseZ;
					}
				}

				// Shield-local block offsets; the pose is already at (center - camera).
				float ex = (float) (x * this.radius);
				float ey = (float) (y * this.radius);
				float ez = (float) (z * this.radius);
				double worldDx = this.relX + ex;
				double worldDy = this.relY + ey;
				double worldDz = this.relZ + ez;
				if (worldDx * worldDx + worldDy * worldDy + worldDz * worldDz < NEAR_SKIP_DIST_SQ) {
					continue;
				}

				float brightness = 1.0F;
				if (layer.flash() && seed < FLASH_GATE) {
					brightness = 0.3F + 0.7F * this.envelope;
				}

				int argb = scaleRgb(switch (layer.tint()) {
					case WHITE -> 0xFFFFFFFF;
					case PRIMARY -> this.argbPrimary;
					case SECONDARY -> this.argbSecondary;
					case DARK -> scaleRgb(this.argbSecondary, 0.3F);
				}, brightness);

				float size = baseSize * this.data[slot + 4] * layer.sizeScale();
				float u0;
				float v0;
				float span;
				if (softSheet) {
					int cell = sprite - InteriorThemes.SOFT_BASE;
					u0 = (cell & 3) * 0.25F;
					v0 = (cell >> 2) * 0.25F;
					span = 0.25F;
				} else {
					u0 = (sprite & 7) * 0.125F;
					v0 = (sprite >> 3) * 0.125F;
					span = 0.125F;
				}

				float u1 = u0 + span;
				float v1 = v0 + span;
				CamBasis cam = this.cam;
				float lx = cam.leftX() * size;
				float ly = cam.leftY() * size;
				float lz = cam.leftZ() * size;
				float ux = cam.upX() * size;
				float uy = cam.upY() * size;
				float uz = cam.upZ() * size;

				// C + left + up is the sprite's top-left texel (u0, v0); cull is off,
				// so the winding never hides a face.
				buffer.addVertex(pose, ex + lx + ux, ey + ly + uy, ez + lz + uz).setUv(u0, v0).setColor(argb);
				buffer.addVertex(pose, ex - lx + ux, ey - ly + uy, ez - lz + uz).setUv(u1, v0).setColor(argb);
				buffer.addVertex(pose, ex - lx - ux, ey - ly - uy, ez - lz - uz).setUv(u1, v1).setColor(argb);
				buffer.addVertex(pose, ex + lx - ux, ey + ly - uy, ez + lz - uz).setUv(u0, v1).setColor(argb);
			}
		}

		/** Per-element speed jitter derived from the scatter seed. */
		private float speed(float seed) {
			return 0.7F + 0.6F * seed;
		}

		private double animatedX(int motion, float baseX, float baseZ, float phase, float seed) {
			float t = this.timeSec;
			float sp = speed(seed);
			return switch (motion) {
				case InteriorThemes.MOTION_DRIFT -> baseX + 0.05 * Math.sin(0.31 * sp * t + phase);
				case InteriorThemes.MOTION_ORBIT -> rotX(baseX, baseZ, (0.2 + 0.3 * seed) * t);
				case InteriorThemes.MOTION_SWIM -> rotX(baseX, baseZ, (0.25 + 0.25 * seed) * t);
				case InteriorThemes.MOTION_RING_ORBIT -> 0.5 * Math.cos((0.2 + 0.2 * seed) * t + phase);
				case InteriorThemes.MOTION_WATERLINE -> baseX + 0.02 * Math.sin(0.3 * t + phase);
				case InteriorThemes.MOTION_SPIRAL -> rotX(baseX, baseZ, (0.4 + 0.3 * seed) * t) * spiralShrink(t, seed);
				case InteriorThemes.MOTION_SHAFT -> 0.45 * Math.cos(0.5 * t + phase);
				case InteriorThemes.MOTION_TOP_CENTER -> 0.0;
				default -> baseX; // BOB, FALL, RISE, BLINK, PERCH: x rests
			};
		}

		private double animatedY(int motion, float baseX, float baseY, float baseZ, float phase, float seed) {
			float t = this.timeSec;
			float sp = speed(seed);
			return switch (motion) {
				case InteriorThemes.MOTION_DRIFT -> baseY + 0.05 * Math.sin(0.23 * sp * t + 1.7 * phase);
				case InteriorThemes.MOTION_BOB -> baseY + 0.07 * Math.sin((0.7 + 0.4 * seed) * t + phase);
				case InteriorThemes.MOTION_FALL -> wrapFall(baseY - (0.10 + 0.15 * seed) * t);
				case InteriorThemes.MOTION_SWIM -> baseY + 0.03 * Math.sin(1.3 * t + phase);
				case InteriorThemes.MOTION_RISE -> baseY * 0.25 + 0.55 * Math.sin((0.12 + 0.10 * seed) * t + phase);
				case InteriorThemes.MOTION_RING_ORBIT -> baseY * 0.3 + 0.03 * Math.sin(0.9 * t + phase);
				case InteriorThemes.MOTION_WATERLINE -> 0.02 * Math.sin(0.9 * t + phase);
				case InteriorThemes.MOTION_SPIRAL -> baseY * spiralShrink(t, seed);
				case InteriorThemes.MOTION_SHAFT -> 0.15;
				case InteriorThemes.MOTION_TOP_CENTER -> 0.55;
				default -> baseY; // BLINK, PERCH: y rests
			};
		}

		private double animatedZ(int motion, float baseX, float baseZ, float phase, float seed) {
			float t = this.timeSec;
			float sp = speed(seed);
			return switch (motion) {
				case InteriorThemes.MOTION_DRIFT -> baseZ + 0.05 * Math.cos(0.27 * sp * t + phase);
				case InteriorThemes.MOTION_ORBIT -> rotZ(baseX, baseZ, (0.2 + 0.3 * seed) * t);
				case InteriorThemes.MOTION_SWIM -> rotZ(baseX, baseZ, (0.25 + 0.25 * seed) * t);
				case InteriorThemes.MOTION_RING_ORBIT -> 0.5 * Math.sin((0.2 + 0.2 * seed) * t + phase);
				case InteriorThemes.MOTION_WATERLINE -> baseZ + 0.02 * Math.cos(0.27 * t + phase);
				case InteriorThemes.MOTION_SPIRAL -> rotZ(baseX, baseZ, (0.4 + 0.3 * seed) * t) * spiralShrink(t, seed);
				case InteriorThemes.MOTION_SHAFT -> 0.45 * Math.sin(0.5 * t + phase);
				case InteriorThemes.MOTION_TOP_CENTER -> 0.0;
				default -> baseZ; // BOB, FALL, RISE, BLINK, PERCH: z rests
			};
		}

		private static double rotX(float x, float z, double angle) {
			return x * Math.cos(angle) + z * Math.sin(angle);
		}

		private static double rotZ(float x, float z, double angle) {
			return -x * Math.sin(angle) + z * Math.cos(angle);
		}

		/** Inward spiral: radius factor sawtooths 1 -> 0.3, then wraps back out. */
		private double spiralShrink(float t, float seed) {
			double cycle = 0.06 * speed(seed) * t + seed;
			return 1.0 - 0.7 * (cycle - Math.floor(cycle));
		}

		/** FALL wrap into [-0.85, 0.85) (matrix glyphs / petals rain and restart on top). */
		private static double wrapFall(double y) {
			double span = 1.7;
			double shifted = (y + 0.85) / span;
			return (shifted - Math.floor(shifted)) * span - 0.85;
		}
	}

	/** Scales the RGB channels of an opaque ARGB color, keeping alpha at 0xFF. */
	private static int scaleRgb(int argb, float factor) {
		int r = (int) (((argb >> 16) & 0xFF) * factor);
		int g = (int) (((argb >> 8) & 0xFF) * factor);
		int b = (int) ((argb & 0xFF) * factor);
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}
}

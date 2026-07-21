package com.bubbleshield.client.render;

import java.util.function.Supplier;

import com.bubbleshield.BubbleShield;
import com.bubbleshield.client.ClientShieldManager;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;

/**
 * Scene-copy provider for the refraction pipeline: after the main pass has drawn all
 * opaque/solid geometry — but BEFORE the translucent phase where the bubble membrane
 * draws ({@code SubmitNodeCollection.submitCustomGeometry} routes blending render
 * types into {@code translucentCustomGeometry}, executed by
 * {@code FeatureRenderDispatcher.PreparedFrame.executeTranslucent}) — the main render
 * target's color+depth textures are copied into a mod-owned {@link TextureTarget}.
 * The bubble fragment shaders can then SAMPLE the copy ({@code Sampler1} = scene
 * color, {@code Sampler2} = scene depth) to bend/tint the world behind the membrane,
 * which a world-pass shader cannot do against the live framebuffer (feedback loop).
 *
 * <p>Hooks (both inside {@code LevelRenderer.render}, in frame order):
 * <ol>
 *   <li>{@code LevelRenderEvents.COLLECT_SUBMITS} (fires at {@code submitFeatures}
 *       RETURN, before {@code FeatureRenderDispatcher.prepareFrame} resolves texture
 *       views via {@code RenderSetup.prepareTextures}): allocate/resize the copy
 *       target to the main target's size and register the wrapper textures — never
 *       later, or the frame's already-prepared draws would reference closed views;</li>
 *   <li>{@code LevelRenderEvents.AFTER_SOLID_FEATURES} (fires inside the main pass at
 *       the {@code profiler.pop()} right after {@code featureFrame.executeSolid()},
 *       with no {@code RenderPass} open): blit main color+depth into the copy via
 *       {@link CommandEncoder#copyTextureToTexture} — legal because every
 *       {@code RenderTarget} texture is created with usage 15 (COPY_SRC | COPY_DST |
 *       TEXTURE_BINDING | RENDER_ATTACHMENT).</li>
 * </ol>
 *
 * <p>Gating, two tiers so the expensive per-frame full-resolution copy only happens
 * when a bubble is actually ON SCREEN:
 * <ul>
 *   <li>allocation + wrapper-texture registration (cheap after the first frame) run
 *       whenever any shield could be SUBMITTED — the same condition as
 *       {@link ShieldRenderer#collectSubmits} (active, radius above the visibility
 *       floor, camera's dimension). This must stay at least as broad as the submit
 *       condition: the bubble render types bind {@code Sampler1}/{@code Sampler2}
 *       unconditionally, and an unregistered id would make
 *       {@code TextureManager.getTexture} fall back to loading a nonexistent
 *       resource;</li>
 *   <li>the color+depth blit itself is additionally gated on at least one of those
 *       shields intersecting the camera's cull {@link net.minecraft.client.renderer.culling.Frustum}
 *       (the frame's own {@code CameraRenderState.cullFrustum}, tested against the
 *       shield's world-space bounding box). An off-screen bubble is clipped by the
 *       GPU before any fragment could sample the copy, so skipping the blit is
 *       free of visual consequence.</li>
 * </ul>
 *
 * <p>The wrapper {@link AbstractTexture}s are
 * registered with {@link TextureManager#register} under
 * {@code bubbleshield:scene_color} / {@code bubbleshield:scene_depth}; every bubble
 * draw re-resolves them by id ({@code RenderSetup.prepareTextures} calls
 * {@code TextureManager.getTexture} + {@code getTextureView()} per
 * {@code RenderType.prepare()}), so window resizes are picked up automatically.
 */
public final class SceneCopy {
	/** Identifier the bubble render types bind as {@code Sampler1} (scene color copy). */
	public static final Identifier SCENE_COLOR_ID = BubbleShield.id("scene_color");
	/** Identifier the bubble render types bind as {@code Sampler2} (scene depth copy). */
	public static final Identifier SCENE_DEPTH_ID = BubbleShield.id("scene_depth");

	/** Matches {@code ShieldRenderer.MIN_VISIBLE_RADIUS} (kept private there). */
	private static final float MIN_VISIBLE_RADIUS = 0.05F;
	/**
	 * Slack added around the shield's radius for the frustum bounds test: absorbs the
	 * block-center offset and float wobble so a bubble grazing the screen edge can
	 * never be culled a frame early. Every {@link SphereMesh} shape variant fits
	 * inside the radius-scaled unit box, so radius + margin bounds the membrane.
	 */
	private static final double FRUSTUM_MARGIN = 1.0;

	private static TextureTarget copyTarget;
	private static boolean texturesRegistered;
	/** Set by the per-frame prepare step; the copy step only runs when true. */
	private static boolean copyArmed;

	private SceneCopy() {
	}

	public static void register() {
		LevelRenderEvents.COLLECT_SUBMITS.register(SceneCopy::prepare);
		LevelRenderEvents.AFTER_SOLID_FEATURES.register(context -> copy());
	}

	/**
	 * Frustum gate for the expensive per-frame blit: the shield's world-space bounds
	 * (projector block cube inflated by radius + margin, covering the membrane of
	 * every shape variant) tested against the frame's cull frustum. The beam column
	 * extends above {@code +radius}, but beam shaders never sample the scene copy, so
	 * the membrane bounds are the right gate. Falls OPEN (treats the shield as on
	 * screen) while the camera state is not initialized yet, preserving correctness.
	 */
	private static boolean isOnScreen(CameraRenderState camera, ClientShieldManager.ClientShield shield, float radius) {
		if (!camera.initialized) {
			return true;
		}

		return camera.cullFrustum.isVisible(new AABB(shield.pos()).inflate(radius + FRUSTUM_MARGIN));
	}

	/**
	 * Frame step 1 (before texture views are resolved for this frame's draws):
	 * allocate/resize the copy target and register the sampler textures whenever any
	 * shield could be submitted (mirroring {@link ShieldRenderer#collectSubmits}'s
	 * condition — this must stay at least as broad, because the bubble render types
	 * bind the scene-copy ids unconditionally), and arm the blit only when one of
	 * those shields actually intersects the camera frustum.
	 */
	private static void prepare(LevelRenderContext context) {
		copyArmed = false;
		CameraRenderState camera = context.levelState().cameraRenderState;
		boolean anySubmittable = false;
		boolean anyOnScreen = false;
		for (ClientShieldManager.ClientShield shield : ClientShieldManager.currentDimensionShields()) {
			float radius = shield.currentRadius();
			if (!shield.active() || radius < MIN_VISIBLE_RADIUS) {
				continue;
			}

			anySubmittable = true;
			if (isOnScreen(camera, shield, radius)) {
				anyOnScreen = true;
				break;
			}
		}

		if (!anySubmittable) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
		if (copyTarget == null) {
			// useDepth = true creates a D32_FLOAT depth texture matching MainTarget's;
			// the color format matches MainTarget's RGBA8_UNORM. Both usage 15, so they
			// are valid copy destinations AND sampleable (USAGE_TEXTURE_BINDING).
			copyTarget = new TextureTarget("bubbleshield scene copy", main.width, main.height, true, GpuFormat.RGBA8_UNORM);
		} else if (copyTarget.width != main.width || copyTarget.height != main.height) {
			copyTarget.resize(main.width, main.height);
		}

		if (!texturesRegistered) {
			TextureManager textures = minecraft.getTextureManager();
			textures.register(SCENE_COLOR_ID, new TargetViewTexture(() -> copyTarget.getColorTextureView()));
			textures.register(SCENE_DEPTH_ID, new TargetViewTexture(() -> copyTarget.getDepthTextureView()));
			texturesRegistered = true;
		}

		copyArmed = anyOnScreen;
	}

	/**
	 * Frame step 2 (after solid geometry, before the translucent phase draws the
	 * bubble, no render pass open): blit the main target's color+depth into the copy.
	 */
	private static void copy() {
		if (!copyArmed || copyTarget == null) {
			return;
		}

		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		if (main.width != copyTarget.width || main.height != copyTarget.height) {
			// The sizes cannot drift within one frame (GameRenderer resizes before
			// renderLevel); this is a pure belt-and-braces guard against the
			// copyTextureToTexture bounds check throwing.
			return;
		}

		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		encoder.copyTextureToTexture(main.getColorTexture(), copyTarget.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
		encoder.copyTextureToTexture(main.getDepthTexture(), copyTarget.getDepthTexture(), 0, 0, 0, 0, 0, main.width, main.height);
	}

	/**
	 * Thin {@link AbstractTexture} adapter exposing one of the copy target's texture
	 * views to the {@link TextureManager} by id, so {@code RenderSetup.withTexture}
	 * resolves it per draw. It never owns the underlying GPU texture: the inherited
	 * {@code texture}/{@code textureView} fields stay {@code null}, so the inherited
	 * {@code close()} is a no-op and the {@link TextureTarget} (owned by
	 * {@link SceneCopy}) can never be double-freed through the manager.
	 */
	private static final class TargetViewTexture extends AbstractTexture {
		private final Supplier<GpuTextureView> view;

		TargetViewTexture(Supplier<GpuTextureView> view) {
			this.view = view;
		}

		@Override
		public GpuTexture getTexture() {
			return this.view.get().texture();
		}

		@Override
		public GpuTextureView getTextureView() {
			return this.view.get();
		}
	}
}

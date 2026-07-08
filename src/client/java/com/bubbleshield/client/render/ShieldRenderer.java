package com.bubbleshield.client.render;

import java.util.ArrayList;
import java.util.List;

import com.bubbleshield.client.ClientShieldManager;
import com.bubbleshield.effect.ContextModifier;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.EffectRegistry;
import com.bubbleshield.shield.ShieldShape;
import com.bubbleshield.shield.ShieldState;
import com.mojang.blaze3d.vertex.PoseStack;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.Vec3;

/**
 * Draws every synced shield as a translucent procedural sphere (or dome, per the
 * synced shape) through the 26.2 submit-based level renderer
 * ({@code LevelRenderEvents.COLLECT_SUBMITS} + {@code SubmitNodeCollector.submitCustomGeometry}).
 *
 * <p>The camera position is captured during extraction
 * ({@code LevelExtractionEvents.END_EXTRACTION}); the submit callback then translates
 * the pose by {@code shieldCenter - cameraPos} and emits the cached {@link SphereMesh}.
 * The mesh carries raw sphere UVs in [0, 1] (no per-effect scale or time offset —
 * the surface shaders animate via the GameTime global); the primary/secondary colors
 * plus the dissolve alpha ride in the vertex color channel.
 */
public final class ShieldRenderer {
	private static final SphereMesh SPHERE = new SphereMesh(48, 32);
	private static final float MIN_VISIBLE_RADIUS = 0.05F;

	private static volatile Vec3 cameraPos = Vec3.ZERO;

	private ShieldRenderer() {
	}

	public static void register() {
		LevelExtractionEvents.END_EXTRACTION.register(context -> cameraPos = context.camera().position());
		LevelRenderEvents.COLLECT_SUBMITS.register(ShieldRenderer::collectSubmits);
	}

	private static void collectSubmits(LevelRenderContext context) {
		// Only shields in the camera's current dimension; other dimensions would be ghosts.
		List<ClientShieldManager.ClientShield> shields = ClientShieldManager.currentDimensionShields();
		if (shields.isEmpty()) {
			return;
		}

		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return;
		}

		PoseStack poseStack = context.poseStack();
		SubmitNodeCollector collector = context.submitNodeCollector();
		Vec3 camera = cameraPos;

		for (ClientShieldManager.ClientShield shield : shields) {
			float radius = shield.currentRadius();
			if (!shield.active() || radius < MIN_VISIBLE_RADIUS) {
				continue;
			}

			EffectDefinition def = EffectRegistry.get(shield.effectId());
			RenderType renderType = ShieldPipelines.renderType(def.surface());
			Vec3 center = Vec3.atCenterOf(shield.pos());
			List<Vec3> dissolveCenters = collectDissolveCenters(level, shield, center, radius);

			// Owner recolor: the override (opaque ARGB, so negative; -1 = unset) replaces the
			// authored palette, with the secondary derived as the same darkened (x0.55) shade.
			int colorOverride = shield.colorOverride();
			boolean recolored = colorOverride != ShieldState.NO_COLOR_OVERRIDE;
			int argbPrimary = recolored ? colorOverride : def.argbPrimary();
			int argbSecondary = recolored ? ContextModifier.deriveOverrideSecondary(colorOverride) : def.argbSecondary();

			// Weaker shields render fainter.
			float alphaBase = 0.25F + 0.5F * shield.healthFrac();

			// The synced shape picks the mesh: full sphere or dome (upper hemisphere + disc).
			boolean dome = ShieldShape.byOrdinal(shield.shape()) == ShieldShape.DOME;

			poseStack.pushPose();
			poseStack.translate(center.x - camera.x, center.y - camera.y, center.z - camera.z);
			// The pose stays unscaled: SphereMesh scales positions by radius CPU-side so the
			// dissolve distances (per-vertex alpha) are computed in world units.
			collector.submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
				if (dome) {
					SPHERE.emitHemisphere(pose, buffer, radius, argbPrimary, argbSecondary, alphaBase, dissolveCenters);
				} else {
					SPHERE.emit(pose, buffer, radius, argbPrimary, argbSecondary, alphaBase, dissolveCenters);
				}
			});
			poseStack.popPose();
		}
	}

	/**
	 * Positions (relative to the shield center) of whitelisted players and the shield's
	 * owner close enough to the surface that it should dissolve around them.
	 */
	private static List<Vec3> collectDissolveCenters(ClientLevel level, ClientShieldManager.ClientShield shield, Vec3 center, float radius) {
		if (shield.whitelist().isEmpty() && shield.ownerUuid().isEmpty()) {
			return List.of();
		}

		List<Vec3> centers = new ArrayList<>();
		for (AbstractClientPlayer player : level.players()) {
			boolean isOwner = shield.ownerUuid().map(player.getUUID()::equals).orElse(false);
			if (!isOwner && !shield.whitelist().contains(player.getUUID())) {
				continue;
			}

			Vec3 relative = player.position().add(0.0, player.getBbHeight() * 0.5, 0.0).subtract(center);
			if (relative.length() <= radius + SphereMesh.DISSOLVE_RANGE) {
				centers.add(relative);
			}
		}

		return centers;
	}
}

package com.bubbleshield.client.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.bubbleshield.client.ClientShieldManager;
import com.bubbleshield.client.fx.ApertureTracker;
import com.bubbleshield.client.fx.ImpactTracker;
import com.bubbleshield.effect.ContextModifier;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.EffectRegistry;
import com.bubbleshield.net.ShieldPayloads;
import com.bubbleshield.shield.BeamStyle;
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
import net.minecraft.core.GlobalPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Draws every synced shield as a translucent procedural membrane (sphere, dome,
 * cylinder, cube, diamond, ring, pyramid, lens, hourglass or star, per the synced
 * shape) through the 26.2 submit-based level renderer
 * ({@code LevelRenderEvents.COLLECT_SUBMITS} + {@code SubmitNodeCollector.submitCustomGeometry}).
 *
 * <p>The camera position is captured during extraction
 * ({@code LevelExtractionEvents.END_EXTRACTION}); the submit callback then translates
 * the pose by {@code shieldCenter - cameraPos} and emits the cached {@link SphereMesh}.
 * The mesh carries raw sphere UVs in [0, 1] (no per-effect scale or time offset —
 * the surface shaders animate via the GameTime global); the primary/secondary colors
 * plus the aperture alpha ride in the vertex color channel.
 *
 * <p><b>WP-Dyn:</b> each shield gets one {@link DeformState} per frame — the
 * strongest recent impacts from the {@link ImpactTracker} (per-kind effective
 * strengths, partial-tick-interpolated ages), the nearest whitelisted-player
 * apertures with their {@link ApertureTracker}-animated hole radii, the
 * interpolated time for the tremble and the synced health fraction — which the
 * mesh emitters turn into per-vertex displacement along stored normals, crest /
 * rim color grading, aperture alpha and UV flow.
 */
public final class ShieldRenderer {
	private static final SphereMesh SPHERE = new SphereMesh(48, 32);
	private static final float MIN_VISIBLE_RADIUS = 0.05F;
	/** At most this many wave sources are consumed per shield per frame (strongest first). */
	private static final int MAX_IMPACTS = 6;
	/** At most this many apertures are consumed per shield per frame (nearest the wall first). */
	private static final int MAX_APERTURES = 4;
	/** Impacts older than this (seconds) are visually dead and skipped. */
	private static final float MAX_IMPACT_AGE_SEC = 2.0F;
	/** Effective wave strength of a CONTACT press (localized shimmer). */
	private static final float CONTACT_WAVE_STRENGTH = 0.3F;
	/** Effective wave strength of a HEAL mend (gentle, graded toward the secondary). */
	private static final float HEAL_WAVE_STRENGTH = 0.4F;
	/** Effective wave strength of a PASSAGE crossing. */
	private static final float PASSAGE_WAVE_STRENGTH = 0.6F;
	/** The game-time modulus keeping the float time small (phase wraps once per ~83 min). */
	private static final long TIME_WRAP_TICKS = 100000L;

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

		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null) {
			return;
		}

		// The 26.2 partial-tick accessor: Minecraft#getDeltaTracker() +
		// DeltaTracker#getGameTimeDeltaPartialTick(false) — the same quantity
		// vanilla passes to the world renderer.
		float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		float timeSec = ((level.getGameTime() % TIME_WRAP_TICKS) + partialTick) * 0.05F;

		PoseStack poseStack = context.poseStack();
		SubmitNodeCollector collector = context.submitNodeCollector();
		Vec3 camera = cameraPos;

		for (ClientShieldManager.ClientShield shield : shields) {
			float radius = shield.currentRadius();
			if (!shield.active() || radius < MIN_VISIBLE_RADIUS) {
				continue;
			}

			EffectDefinition def = EffectRegistry.get(shield.effectId());
			RenderType renderType = ShieldPipelines.renderType(shield.effectId());
			Vec3 center = Vec3.atCenterOf(shield.pos());
			DeformState deform = buildDeformState(level, shield, center, radius, partialTick, timeSec);

			// Owner recolor: the override (opaque ARGB, so negative; -1 = unset) replaces the
			// authored palette, with the secondary derived as the same darkened (x0.55) shade.
			int colorOverride = shield.colorOverride();
			boolean recolored = colorOverride != ShieldState.NO_COLOR_OVERRIDE;
			int argbPrimary = recolored ? colorOverride : def.argbPrimary();
			int argbSecondary = recolored ? ContextModifier.deriveOverrideSecondary(colorOverride) : def.argbSecondary();

			// Weaker shields render fainter. Full health reads as SOLID refractive
			// glass (0.95): the translucent pipeline re-blends the shader's refracted
			// scene sample over the straight background by (1 - a), so a low vertex
			// alpha washes the visible refraction out entirely.
			float alphaBase = 0.35F + 0.6F * shield.healthFrac();

			// The synced shape picks the mesh; every variant shares the sphere's
			// vertex conventions (POSITION_TEX_COLOR quads, UV in [0, 1], CPU-side
			// radius scaling) and the dimensions match ShieldGeometry exactly.
			ShieldShape shape = ShieldShape.byOrdinal(shield.shape());

			poseStack.pushPose();
			poseStack.translate(center.x - camera.x, center.y - camera.y, center.z - camera.z);
			// The pose stays unscaled: SphereMesh scales positions by radius CPU-side so the
			// deformation and aperture distances are computed in world units.
			collector.submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
				switch (shape) {
					case DOME -> SPHERE.emitHemisphere(pose, buffer, radius, argbPrimary, argbSecondary, alphaBase, deform);
					case CYLINDER -> SPHERE.emitCylinder(pose, buffer, radius, argbPrimary, argbSecondary, alphaBase, deform);
					case CUBE -> SPHERE.emitCube(pose, buffer, radius, argbPrimary, argbSecondary, alphaBase, deform);
					case DIAMOND -> SPHERE.emitDiamond(pose, buffer, radius, argbPrimary, argbSecondary, alphaBase, deform);
					case RING -> SPHERE.emitRing(pose, buffer, radius, argbPrimary, argbSecondary, alphaBase, deform);
					case PYRAMID -> SPHERE.emitPyramid(pose, buffer, radius, argbPrimary, argbSecondary, alphaBase, deform);
					case LENS -> SPHERE.emitLens(pose, buffer, radius, argbPrimary, argbSecondary, alphaBase, deform);
					case HOURGLASS -> SPHERE.emitHourglass(pose, buffer, radius, argbPrimary, argbSecondary, alphaBase, deform);
					case STAR -> SPHERE.emitStar(pose, buffer, radius, argbPrimary, argbSecondary, alphaBase, deform);
					default -> SPHERE.emit(pose, buffer, radius, argbPrimary, argbSecondary, alphaBase, deform);
				}
			});

			// The projector's energy beam, per the synced per-bubble setting: AUTO
			// resolves to the effect's derived preset, NONE renders nothing. Submitted
			// AFTER the sphere so the additive column reads on top of the membrane.
			// Deliberately untouched by WP-Dyn.
			BeamStyle beamStyle = BeamStyle.byOrdinal(shield.beamStyle());
			if (beamStyle == BeamStyle.AUTO) {
				beamStyle = def.beamPreset();
			}

			if (beamStyle != BeamStyle.NONE) {
				RenderType beamRenderType = ShieldPipelines.beamRenderType(beamStyle.renderIndex());
				// The beam's crossed planes re-orient toward the camera every frame
				// (the horizontal camera offset picks the bearing); all animation runs
				// shader-side on the GameTime global.
				float camDx = (float) (camera.x - center.x);
				float camDz = (float) (camera.z - center.z);
				collector.submitCustomGeometry(poseStack, beamRenderType, (pose, buffer) ->
						BeamMesh.emit(pose, buffer, radius, argbPrimary, argbSecondary, alphaBase, camDx, camDz));
			}

			poseStack.popPose();
		}
	}

	/**
	 * Builds one shield's per-frame {@link DeformState}: the {@value #MAX_IMPACTS}
	 * strongest live impacts (by {@code strength * e^(-1.6 * age)}) with per-kind
	 * effective strengths, plus the {@value #MAX_APERTURES} apertures nearest the
	 * wall. Returns {@link DeformState#NONE} when nothing is happening so the mesh
	 * takes its idle fast path.
	 */
	private static DeformState buildDeformState(ClientLevel level, ClientShieldManager.ClientShield shield,
			Vec3 center, float radius, float partialTick, float timeSec) {
		GlobalPos globalPos = new GlobalPos(shield.dimension(), shield.pos());
		float healthFrac = shield.healthFrac();

		List<DeformState.Impact> impacts = null;
		long now = ImpactTracker.currentTick();
		for (ImpactTracker.Impact impact : ImpactTracker.impactsAt(globalPos)) {
			float ageSec = (now - impact.clientTick() + partialTick) * 0.05F;
			if (ageSec < 0.0F || ageSec > MAX_IMPACT_AGE_SEC) {
				continue;
			}

			float strength = effectiveStrength(impact.kind(), impact.strength01());
			if (strength <= 0.0F) {
				continue;
			}

			if (impacts == null) {
				impacts = new ArrayList<>();
			}

			impacts.add(new DeformState.Impact(safeNormalize(impact.dir()), strength, ageSec, impact.kind()));
		}

		if (impacts != null && impacts.size() > MAX_IMPACTS) {
			impacts.sort(Comparator.comparingDouble(impact ->
					-(impact.strength01() * Math.exp(-1.6 * impact.ageSec()))));
			impacts = impacts.subList(0, MAX_IMPACTS);
		}

		List<DeformState.Aperture> apertures = collectApertures(level, shield, globalPos, center, radius);
		if ((impacts == null || impacts.isEmpty()) && apertures.isEmpty()
				&& healthFrac >= DeformState.TREMBLE_HEALTH_FRAC) {
			return DeformState.NONE;
		}

		return new DeformState(impacts == null ? List.of() : impacts, apertures, timeSec, radius, healthFrac);
	}

	/**
	 * Per-kind effective wave strength: IMPACT keeps its damage-proportional
	 * strength byte, BREAK is the full-surface pulse at 1, HEAL / CONTACT /
	 * PASSAGE use fixed gentle strengths (their wire strength bytes are
	 * bookkeeping values, not damage scales).
	 */
	private static float effectiveStrength(int kind, float strength01) {
		return switch (kind) {
			case ShieldPayloads.ImpactEntry.KIND_BREAK -> 1.0F;
			case ShieldPayloads.ImpactEntry.KIND_HEAL -> HEAL_WAVE_STRENGTH;
			case ShieldPayloads.ImpactEntry.KIND_CONTACT -> CONTACT_WAVE_STRENGTH;
			case ShieldPayloads.ImpactEntry.KIND_PASSAGE_IN, ShieldPayloads.ImpactEntry.KIND_PASSAGE_OUT -> PASSAGE_WAVE_STRENGTH;
			default -> Mth.clamp(strength01, 0.0F, 1.0F);
		};
	}

	/** BREAK's zero direction is preserved (the omni marker); anything else normalizes. */
	private static Vec3 safeNormalize(Vec3 dir) {
		double length = dir.length();
		return length < 1.0e-4 ? Vec3.ZERO : dir.scale(1.0 / length);
	}

	/**
	 * Apertures (player-relative position + animated hole radius) of whitelisted
	 * players and the shield's owner, replacing the legacy flat dissolve: the same
	 * owner/whitelist scan, but the hole radius is the {@link ApertureTracker}'s
	 * animated value (hysteresis + easing live there, on the tick), and only the
	 * {@value #MAX_APERTURES} nearest the wall are consumed.
	 */
	private static List<DeformState.Aperture> collectApertures(ClientLevel level, ClientShieldManager.ClientShield shield,
			GlobalPos globalPos, Vec3 center, float radius) {
		if (shield.whitelist().isEmpty() && shield.ownerUuid().isEmpty()) {
			return List.of();
		}

		List<DeformState.Aperture> apertures = null;
		for (AbstractClientPlayer player : level.players()) {
			boolean isOwner = shield.ownerUuid().map(player.getUUID()::equals).orElse(false);
			if (!isOwner && !shield.whitelist().contains(player.getUUID())) {
				continue;
			}

			float holeR = ApertureTracker.holeRadius(globalPos, player.getUUID());
			if (holeR < 0.01F) {
				continue;
			}

			if (apertures == null) {
				apertures = new ArrayList<>();
			}

			Vec3 relative = player.position().add(0.0, player.getBbHeight() * 0.5, 0.0).subtract(center);
			apertures.add(new DeformState.Aperture(relative, holeR));
		}

		if (apertures == null) {
			return List.of();
		}

		if (apertures.size() > MAX_APERTURES) {
			apertures.sort(Comparator.comparingDouble(aperture ->
					Math.abs(aperture.relPos().length() - radius)));
			apertures = apertures.subList(0, MAX_APERTURES);
		}

		return apertures;
	}
}

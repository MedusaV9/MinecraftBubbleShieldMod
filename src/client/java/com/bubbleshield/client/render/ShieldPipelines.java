package com.bubbleshield.client.render;

import java.util.Locale;

import com.bubbleshield.BubbleShield;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.EffectRegistry;
import com.bubbleshield.shield.BeamStyle;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;

import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * One {@link RenderPipeline} + {@link RenderType} per effect id: pipeline
 * {@code bubbleshield:pipeline/bubble_fx_NNN} with fragment shader
 * {@code bubbleshield:bubble/fx_NNN.fsh} for every entry in {@link EffectRegistry#ALL}
 * ({@link EffectRegistry#COUNT} pipelines — 420 today, grows automatically with COUNT).
 *
 * <p>The snippet is modeled on vanilla's {@code RenderPipelines.END_PORTAL_SNIPPET}
 * (GLOBALS + MATRICES_PROJECTION + FOG bind groups, QUADS), with the sampler layouts
 * dropped (the bubble shaders are purely procedural), the vertex binding switched to
 * {@code POSITION_TEX_COLOR} and the translucent blend + no-cull + non-depth-writing
 * depth state copied from vanilla's translucent pipelines (e.g.
 * {@code BEACON_BEAM_TRANSLUCENT}).
 *
 * <p>All pipelines share one vertex shader ({@code bubbleshield:bubble/surface});
 * only the fragment shader differs. They are registered through
 * {@link RenderPipelines#register} (access-widened by Fabric) during client init so the
 * shader manager precompiles them with the vanilla static pipelines on resource load.
 * That precompilation is the cost of this design: every registered pipeline is compiled
 * on every resource load/reload (first load and each F3+T), so COUNT extra GLSL
 * compile+links land there — estimated in the sub-second range per hundred pipelines on
 * typical drivers, and any single invalid fragment shader fails the whole resource load
 * ({@code tools/validate_shaders.py} is the pre-commit gate for that).
 */
public final class ShieldPipelines {
	private static final RenderPipeline.Snippet BUBBLE_SNIPPET = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
			.withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
			.withBindGroupLayout(BindGroupLayouts.FOG)
			.withVertexShader(BubbleShield.id("bubble/surface"))
			.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
			.withCull(false)
			// Depth-test but never depth-write, matching vanilla translucent pipelines
			// (BEACON_BEAM_TRANSLUCENT / ENTITY_TRANSLUCENT_EMISSIVE): a depth-writing dome
			// would occlude translucent world content and the mod's own inside particles.
			.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
			.buildSnippet();

	/**
	 * The projector-beam pipelines: identical bind groups / vertex shader / vertex
	 * binding / depth state to the bubble snippet, but with vanilla's
	 * {@code BlendFunction.LIGHTNING} (SRC_ALPHA, ONE — verified in the decompiled
	 * 26.2 {@code BlendFunction}): additive-scaled-by-alpha, so the CPU-side vertex
	 * alpha profile IS the beam's intensity profile and overlapping shells simply add
	 * up. Additive output is order-independent against itself, so no
	 * {@code sortOnUpload} is needed.
	 */
	private static final RenderPipeline.Snippet BEAM_SNIPPET = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
			.withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
			.withBindGroupLayout(BindGroupLayouts.FOG)
			// The bubble vertex shader is a pure POSITION_TEX_COLOR passthrough and
			// provides exactly the varyings the frozen fragment contract needs; the
			// beam fragment shaders follow the same contract, so it is reused as-is.
			.withVertexShader(BubbleShield.id("bubble/surface"))
			.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			.withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
			.withCull(false)
			.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
			.buildSnippet();

	/**
	 * The rendered beam styles, in {@link BeamStyle#RENDERED} order (STORM, PULSE,
	 * HELIX, PRISM). A small fixed NAMED set — one hand-written shader per style under
	 * {@code assets/bubbleshield/shaders/beam/beam_<name>.fsh} — unlike the per-effect
	 * fx_* set, so their registration cost is negligible.
	 */
	private static final String[] BEAM_STYLE_NAMES = {"storm", "pulse", "helix", "prism"};

	private static final RenderType[] RENDER_TYPES = buildRenderTypes();
	private static final RenderType[] BEAM_RENDER_TYPES = buildBeamRenderTypes();

	private ShieldPipelines() {
	}

	private static RenderType[] buildRenderTypes() {
		RenderType[] types = new RenderType[EffectRegistry.COUNT];
		for (EffectDefinition def : EffectRegistry.ALL) {
			String name = String.format(Locale.ROOT, "bubble_fx_%03d", def.id());
			RenderPipeline pipeline = RenderPipelines.register(
					RenderPipeline.builder(BUBBLE_SNIPPET)
							.withLocation(BubbleShield.id("pipeline/" + name))
							.withFragmentShader(BubbleShield.id(def.surfaceShaderId()))
							.build()
			);
			// sortOnUpload matches vanilla translucent render types (e.g. item_translucent).
			types[def.id()] = RenderType.create(name, RenderSetup.builder(pipeline).sortOnUpload().createRenderSetup());
		}

		return types;
	}

	private static RenderType[] buildBeamRenderTypes() {
		RenderType[] types = new RenderType[BEAM_STYLE_NAMES.length];
		for (int i = 0; i < BEAM_STYLE_NAMES.length; i++) {
			String name = "beam_" + BEAM_STYLE_NAMES[i];
			RenderPipeline pipeline = RenderPipelines.register(
					RenderPipeline.builder(BEAM_SNIPPET)
							.withLocation(BubbleShield.id("pipeline/" + name))
							.withFragmentShader(BubbleShield.id("beam/" + name))
							.build()
			);
			types[i] = RenderType.create(name, RenderSetup.builder(pipeline).createRenderSetup());
		}

		return types;
	}

	/** The effect's dedicated render type; ids are clamped like {@link EffectRegistry#get}. */
	public static RenderType renderType(int effectId) {
		return RENDER_TYPES[Math.clamp(effectId, 0, RENDER_TYPES.length - 1)];
	}

	/**
	 * The render type for a rendered beam style, by {@code BeamStyle.renderIndex()}
	 * (0 = STORM .. 3 = PRISM). Indices are clamped defensively so a stale/foreign
	 * synced ordinal can never index out of bounds.
	 */
	public static RenderType beamRenderType(int styleIndex) {
		return BEAM_RENDER_TYPES[Math.clamp(styleIndex, 0, BEAM_RENDER_TYPES.length - 1)];
	}

	/** Forces static init; must run during client init, before the first resource (shader) load. */
	public static void bootstrap() {
	}
}

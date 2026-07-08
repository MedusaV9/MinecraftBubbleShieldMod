package com.bubbleshield.client.render;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import com.bubbleshield.BubbleShield;
import com.bubbleshield.effect.SurfaceTemplate;
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
 * One {@link RenderPipeline} + {@link RenderType} per {@link SurfaceTemplate}.
 *
 * <p>The snippet is modeled on vanilla's {@code RenderPipelines.END_PORTAL_SNIPPET}
 * (GLOBALS + MATRICES_PROJECTION + FOG bind groups, QUADS), with the sampler layouts
 * dropped (the bubble shaders are purely procedural), the vertex binding switched to
 * {@code POSITION_TEX_COLOR} and the translucent blend + no-cull + non-depth-writing
 * depth state copied from vanilla's translucent pipelines (e.g.
 * {@code BEACON_BEAM_TRANSLUCENT}).
 *
 * <p>All sixteen pipelines share one vertex shader ({@code bubbleshield:bubble/surface});
 * only the fragment shader differs. They are registered through
 * {@link RenderPipelines#register} (access-widened by Fabric) during client init so the
 * shader manager precompiles them with the vanilla static pipelines on resource load.
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

	private static final Map<SurfaceTemplate, RenderType> RENDER_TYPES = buildRenderTypes();

	private ShieldPipelines() {
	}

	private static Map<SurfaceTemplate, RenderType> buildRenderTypes() {
		Map<SurfaceTemplate, RenderType> types = new EnumMap<>(SurfaceTemplate.class);
		for (SurfaceTemplate surface : SurfaceTemplate.values()) {
			String name = surface.name().toLowerCase(Locale.ROOT);
			RenderPipeline pipeline = RenderPipelines.register(
					RenderPipeline.builder(BUBBLE_SNIPPET)
							.withLocation(BubbleShield.id("pipeline/bubble_" + name))
							.withFragmentShader(BubbleShield.id("bubble/" + name))
							.build()
			);
			// sortOnUpload matches vanilla translucent render types (e.g. item_translucent).
			types.put(surface, RenderType.create("bubble_" + name, RenderSetup.builder(pipeline).sortOnUpload().createRenderSetup()));
		}

		return types;
	}

	public static RenderType renderType(SurfaceTemplate surface) {
		return RENDER_TYPES.get(surface);
	}

	/** Forces static init; must run during client init, before the first resource (shader) load. */
	public static void bootstrap() {
	}
}

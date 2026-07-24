package com.bubbleshield.client.render;

import com.bubbleshield.BubbleShield;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;

import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/**
 * The two interior-billboard render types, both CUTOUT and NON-BLENDING on the
 * VANILLA {@code core/position_tex_color} shader pair — no new GLSL ships with
 * this feature (the shader-validator sweep is fail-closed on unknown files).
 * Verified in the decompiled 26.2 sources: {@code position_tex_color.fsh}
 * discards fragments at {@code color.a == 0.0}, so the sheets' binary alpha
 * (0/255 on the pixel sheet, the soft sheet's alpha times the vertex alpha)
 * gives clean cutout edges without a dedicated cutout shader.
 *
 * <p><b>Solid-phase routing (the load-bearing trick):</b> both pipelines use
 * {@link ColorTargetState#DEFAULT} — no blend function — so
 * {@code RenderType.hasBlending()} is false and
 * {@code SubmitNodeCollection.submitCustomGeometry} routes the submits into the
 * {@code solid} feature phase (verified: {@code if (renderType.hasBlending())
 * translucentCustomGeometry ... else solid.submit(...)}). The {@link SceneCopy}
 * blit at {@code AFTER_SOLID_FEATURES} therefore already CONTAINS the interior
 * elements, and the translucent bubble membrane refracts them like any other
 * world content behind it. Depth is tested AND written
 * ({@link DepthStencilState#DEFAULT}), so interiors occlude correctly among
 * themselves and against the world; culling is off because billboards flip
 * winding with the camera.
 *
 * <p>Bind groups mirror vanilla's own world-space {@code position_tex_color}
 * pipeline ({@code END_SKY}: GLOBALS + MATRICES_PROJECTION + SAMPLER0). The
 * samplers are pinned per sheet through the render setup's explicit sampler
 * supplier (the {@code Sampler2} pattern from {@link ShieldPipelines}): NEAREST
 * for the 64px-cell pixel-art sheet, LINEAR for the pre-blurred soft sheet.
 */
public final class InteriorPipelines {
	/** 512x512, 8x8 grid of 64px pixel-art cells (tacos, ducks, glyphs...); NEAREST. */
	private static final Identifier PIXEL_SHEET = BubbleShield.id("textures/interior/interior_pixel.png");
	/** 512x512, 4x4 grid of 128px grayscale+alpha soft elements; LINEAR. */
	private static final Identifier SOFT_SHEET = BubbleShield.id("textures/interior/interior_soft.png");

	private static final RenderPipeline.Snippet INTERIOR_SNIPPET = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
			.withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
			.withBindGroupLayout(BindGroupLayouts.SAMPLER0)
			.withVertexShader("core/position_tex_color")
			.withFragmentShader("core/position_tex_color")
			.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			// NON-blending (DEFAULT has an empty blend function): this is what makes
			// hasBlending() false and routes the submit into the SOLID phase.
			.withColorTargetState(ColorTargetState.DEFAULT)
			// Depth test + WRITE (vanilla DEFAULT), unlike the membrane: cutout
			// geometry wants real depth so elements sort among themselves for free.
			.withDepthStencilState(DepthStencilState.DEFAULT)
			.withCull(false)
			.buildSnippet();

	private static final RenderType PIXEL = build("interior_pixel", PIXEL_SHEET, FilterMode.NEAREST);
	private static final RenderType SOFT = build("interior_soft", SOFT_SHEET, FilterMode.LINEAR);

	private InteriorPipelines() {
	}

	private static RenderType build(String name, Identifier sheet, FilterMode filter) {
		RenderPipeline pipeline = RenderPipelines.register(
				RenderPipeline.builder(INTERIOR_SNIPPET)
						.withLocation(BubbleShield.id("pipeline/" + name))
						.build()
		);
		// No sortOnUpload: the solid phase depth-writes, so draw order is free.
		return RenderType.create(name, RenderSetup.builder(pipeline)
				.withTexture("Sampler0", sheet,
						() -> RenderSystem.getSamplerCache().getClampToEdge(filter))
				.createRenderSetup());
	}

	/** The pixel-art sheet's cutout render type (NEAREST sampling). */
	public static RenderType pixel() {
		return PIXEL;
	}

	/** The soft-element sheet's cutout render type (LINEAR sampling). */
	public static RenderType soft() {
		return SOFT;
	}

	/** Forces static init; must run during client init, before the first resource load. */
	public static void bootstrap() {
	}
}

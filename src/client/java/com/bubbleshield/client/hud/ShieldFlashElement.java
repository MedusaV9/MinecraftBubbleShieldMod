package com.bubbleshield.client.hud;

import com.bubbleshield.client.ClientShieldManager;
import com.bubbleshield.client.ClientShieldManager.ClientShield;
import com.bubbleshield.client.fx.ContactFlash;
import com.bubbleshield.client.fx.FlashIntensity;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.EffectRegistry;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.GlobalPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Full-screen contact-flash overlay: while {@link ContactFlash} reports a live
 * envelope, screen-edge vignetting in the pressed shield's tint shows WHERE the
 * player hit the barrier and WHAT the shield looks like. Vanilla 2D primitives
 * only ({@code fill}/{@code fillGradient}/{@code outline}) — deliberately NO
 * scene distortion, this is a HUD layer registered {@code HudElementRegistry.addLast}
 * after the shield status element.
 *
 * <p><b>Character:</b> the shield effect's screen-template family (28 families,
 * {@link EffectDefinition#screenTemplate()}, same source as
 * {@code tools/screen_manifest.json}) picks one of five overlay styles so the
 * flash rhymes with the in-bubble post effect: grade = 4 edge gradient strips;
 * chromatic = per-channel strips at &plusmn;{@value #CHROMA_OFFSET_PX} px offsets;
 * distortion = 3 expanding nested outline rings; structural = 2px scanlines every
 * 8px in the pressed-side band; glow = nested bright rims.
 *
 * <p><b>Tint</b> honors the owner recolor ({@code colorOverride}, -1 = authored
 * {@code argbPrimary}) and is floored at luma {@value #LUMA_FLOOR} (mixed toward
 * white) so near-black palettes still read against a dark world. The contact
 * normal, projected on the camera's right/up axes, thickens the screen side
 * facing the wall. Everything is multiplied by {@link FlashIntensity}.
 *
 * <p>{@code fillGradient} only interpolates vertically (top color &rarr; bottom
 * color), so the left/right grade strips approximate their horizontal fade with
 * {@value #SIDE_GRADIENT_STEPS} stepped fills — the one place the "4 fillGradient
 * strips" spec meets a vertical-only primitive.
 */
public final class ShieldFlashElement implements HudElement {
	/** Edge-strip thickness as a fraction of the smaller screen dimension. */
	private static final float EDGE_FRACTION = 0.18F;
	/** Extra thickness multiplier applied to the screen side facing the contact. */
	private static final float DIRECTIONAL_BIAS = 0.75F;
	/** Minimum tint luma; darker tints are mixed toward white up to this floor. */
	private static final float LUMA_FLOOR = 0.35F;
	/** Chromatic style: per-channel strip offset in gui-scaled pixels. */
	private static final int CHROMA_OFFSET_PX = 4;
	/** Stepped-fill count approximating the horizontal gradient on left/right strips. */
	private static final int SIDE_GRADIENT_STEPS = 6;
	private static final Vec3 WORLD_UP = new Vec3(0.0, 1.0, 0.0);

	/** The five overlay characters, keyed off the 28 screen-template families. */
	private enum Character {
		GRADE, CHROMATIC, DISTORTION, STRUCTURAL, GLOW;

		/** Family -> character map mirroring tools/screen_manifest.json's grouping. */
		static Character of(String screenTemplate) {
			return switch (screenTemplate) {
				case "tint", "desat", "duotone", "posterize", "huedrift", "thermal", "gloom", "vignette" -> GRADE;
				case "chroma", "aberration", "spectral", "vhs" -> CHROMATIC;
				case "wobble", "ripple", "heathaze", "radialblur", "underwater", "dreamblur", "kaleido", "frostlens" -> DISTORTION;
				case "scanlines", "pixelate", "glitch", "moire", "sketch" -> STRUCTURAL;
				case "bloomglow", "edgeglow", "starburst" -> GLOW;
				// The registry invariant-checks families, so this only guards a
				// future family added without updating this map.
				default -> GRADE;
			};
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		float alpha = ContactFlash.alpha() * FlashIntensity.get();
		if (alpha < 0.01F || mc.level == null || mc.player == null) {
			return;
		}

		GlobalPos contactPos = ContactFlash.contactShield();
		ClientShield shield = contactPos != null ? ClientShieldManager.shields().get(contactPos) : null;
		EffectDefinition def = EffectRegistry.get(shield != null ? shield.effectId() : 0);
		int tint = lumaFloored(shield != null && shield.colorOverride() != -1 ? shield.colorOverride() : def.argbPrimary());

		// Project the contact normal on the camera basis: the wall lies OPPOSITE the
		// outward normal, and the screen side facing it is thickened. Right/up are
		// derived from the view vector (up-look degenerates to an unbiased flash).
		Vec3 wallDir = ContactFlash.contactNormal().scale(-1.0);
		Vec3 forward = mc.player.getViewVector(1.0F);
		Vec3 right = forward.cross(WORLD_UP);
		double wallX = 0.0;
		double wallUp = 0.0;
		if (right.lengthSqr() > 1.0E-4 && wallDir.lengthSqr() > 1.0E-4) {
			right = right.normalize();
			Vec3 up = right.cross(forward).normalize();
			wallX = wallDir.dot(right);
			wallUp = wallDir.dot(up);
		}

		float wLeft = 1.0F + DIRECTIONAL_BIAS * (float) Math.max(0.0, -wallX);
		float wRight = 1.0F + DIRECTIONAL_BIAS * (float) Math.max(0.0, wallX);
		float wTop = 1.0F + DIRECTIONAL_BIAS * (float) Math.max(0.0, wallUp);
		float wBottom = 1.0F + DIRECTIONAL_BIAS * (float) Math.max(0.0, -wallUp);

		int w = graphics.guiWidth();
		int h = graphics.guiHeight();
		int base = Math.round(Math.min(w, h) * EDGE_FRACTION);
		switch (Character.of(def.screenTemplate())) {
			case GRADE -> drawGrade(graphics, w, h, base, alpha, tint, wLeft, wRight, wTop, wBottom);
			case CHROMATIC -> drawChromatic(graphics, w, h, base, alpha, tint, wLeft, wRight, wTop, wBottom);
			case DISTORTION -> drawDistortion(graphics, w, h, alpha, tint);
			case STRUCTURAL -> drawStructural(graphics, w, h, alpha, tint, wLeft, wRight, wTop, wBottom);
			case GLOW -> drawGlow(graphics, w, h, alpha, tint);
		}
	}

	/** Grade: four tint-to-transparent edge strips (stepped fills on the sides). */
	private static void drawGrade(GuiGraphicsExtractor g, int w, int h, int base, float alpha, int tint,
			float wLeft, float wRight, float wTop, float wBottom) {
		int solid = argb(alpha, tint);
		int clear = tint & 0xFFFFFF;
		g.fillGradient(0, 0, w, Math.round(base * wTop), solid, clear);
		g.fillGradient(0, h - Math.round(base * wBottom), w, h, clear, solid);
		sideGradient(g, Math.round(base * wLeft), h, alpha, tint, true);
		sideGradient(g, Math.round(base * wRight), h, alpha, tint, false);
	}

	/**
	 * A horizontal tint-to-transparent fade as {@value #SIDE_GRADIENT_STEPS} stepped
	 * fills (quadratic falloff), since {@code fillGradient} is vertical-only.
	 */
	private static void sideGradient(GuiGraphicsExtractor g, int thickness, int h, float alpha, int tint, boolean left) {
		int step = Math.max(1, thickness / SIDE_GRADIENT_STEPS);
		for (int i = 0; i < SIDE_GRADIENT_STEPS; i++) {
			float fade = 1.0F - (float) i / SIDE_GRADIENT_STEPS;
			int col = argb(alpha * fade * fade, tint);
			int near = i * step;
			int far = Math.min(thickness, near + step);
			if (left) {
				g.fill(near, 0, far, h, col);
			} else {
				int guiW = g.guiWidth();
				g.fill(guiW - far, 0, guiW - near, h, col);
			}
		}
	}

	/** Chromatic: the edge band three times, one RGB channel each, offset +-4px. */
	private static void drawChromatic(GuiGraphicsExtractor g, int w, int h, int base, float alpha, int tint,
			float wLeft, float wRight, float wTop, float wBottom) {
		int band = Math.max(2, base / 3);
		// One third of the alpha per channel: the overlapped region re-sums to ~alpha.
		float channelAlpha = alpha / 3.0F;
		int[] channels = {tint & 0xFF0000, tint & 0x00FF00, tint & 0x0000FF};
		int[] offsets = {-CHROMA_OFFSET_PX, 0, CHROMA_OFFSET_PX};
		for (int i = 0; i < 3; i++) {
			int col = argb(channelAlpha, channels[i]);
			int off = offsets[i];
			g.fill(0, Math.max(0, off), w, Math.max(0, off) + Math.round(band * wTop), col);
			g.fill(0, h - Math.round(band * wBottom) + Math.min(0, off), w, h + Math.min(0, off), col);
			g.fill(Math.max(0, off), 0, Math.max(0, off) + Math.round(band * wLeft), h, col);
			g.fill(w - Math.round(band * wRight) + Math.min(0, off), 0, w + Math.min(0, off), h, col);
		}
	}

	/** Distortion: three nested outline rings expanding outward as the flash decays. */
	private static void drawDistortion(GuiGraphicsExtractor g, int w, int h, float alpha, int tint) {
		// 0 at the flash peak -> 1 when faded: rings start deep and travel to the edge.
		float progress = 1.0F - Math.min(1.0F, alpha / 0.35F);
		int maxInset = Math.round(Math.min(w, h) * 0.25F);
		for (int i = 0; i < 3; i++) {
			float ringPhase = (2 - i + progress * 3.0F) / 5.0F;
			int inset = Math.max(0, Math.round(maxInset * (1.0F - Math.min(1.0F, ringPhase))));
			int col = argb(alpha * (1.0F - 0.25F * i), tint);
			g.outline(inset, inset, w - 2 * inset, h - 2 * inset, col);
			// Second 1px pass makes each traveling ring a readable 2px rim.
			if (inset + 1 < w / 2 && inset + 1 < h / 2) {
				g.outline(inset + 1, inset + 1, w - 2 * (inset + 1), h - 2 * (inset + 1), col);
			}
		}
	}

	/** Structural: 2px scanlines every 8px inside the band on the pressed side. */
	private static void drawStructural(GuiGraphicsExtractor g, int w, int h, float alpha, int tint,
			float wLeft, float wRight, float wTop, float wBottom) {
		int col = argb(alpha, tint);
		// The single most-pressed side hosts the band (ties resolve left->right->top->bottom).
		float max = Math.max(Math.max(wLeft, wRight), Math.max(wTop, wBottom));
		if (max <= 1.0F || max == wLeft || max == wRight) {
			int band = Math.round(w * 0.25F);
			int x0 = (max > 1.0F && max == wRight) ? w - band : 0;
			for (int y = 0; y < h; y += 8) {
				g.fill(x0, y, x0 + band, Math.min(h, y + 2), col);
			}
		} else {
			int band = Math.round(h * 0.25F);
			int y0 = max == wTop ? 0 : h - band;
			for (int y = y0; y < y0 + band; y += 8) {
				g.fill(0, y, w, Math.min(y0 + band, y + 2), col);
			}
		}
	}

	/** Glow: three nested 2px bright rims hugging the screen border. */
	private static void drawGlow(GuiGraphicsExtractor g, int w, int h, float alpha, int tint) {
		int bright = mixTowardWhite(tint, 0.5F);
		for (int i = 0; i < 3; i++) {
			int inset = i * 4;
			int col = argb(alpha * (1.0F - 0.3F * i), bright);
			g.outline(inset, inset, w - 2 * inset, h - 2 * inset, col);
			g.outline(inset + 1, inset + 1, w - 2 * (inset + 1), h - 2 * (inset + 1), col);
		}
	}

	private static int argb(float alpha, int rgb) {
		return (Math.round(Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F) << 24) | (rgb & 0xFFFFFF);
	}

	/** Rec. 601 luma floor: tints darker than {@value #LUMA_FLOOR} mix toward white. */
	private static int lumaFloored(int rgb) {
		float r = (rgb >> 16 & 0xFF) / 255.0F;
		float gc = (rgb >> 8 & 0xFF) / 255.0F;
		float b = (rgb & 0xFF) / 255.0F;
		float luma = 0.299F * r + 0.587F * gc + 0.114F * b;
		if (luma >= LUMA_FLOOR) {
			return rgb & 0xFFFFFF;
		}

		return mixTowardWhite(rgb, (LUMA_FLOOR - luma) / (1.0F - luma));
	}

	private static int mixTowardWhite(int rgb, float t) {
		int r = rgb >> 16 & 0xFF;
		int g = rgb >> 8 & 0xFF;
		int b = rgb & 0xFF;
		r = Math.round(r + (255 - r) * t);
		g = Math.round(g + (255 - g) * t);
		b = Math.round(b + (255 - b) * t);
		return (r << 16) | (g << 8) | b;
	}
}

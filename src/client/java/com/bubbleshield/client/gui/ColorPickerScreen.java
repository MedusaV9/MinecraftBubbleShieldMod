package com.bubbleshield.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.bubbleshield.client.ClientShieldManager;
import com.bubbleshield.net.ShieldPayloads;
import com.bubbleshield.shield.ShieldState;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;

/**
 * Owner recolor picker for a shield projector: 16 vanilla dye swatches
 * ({@link DyeColor#getTextureDiffuseColor()}, an opaque ARGB) plus an
 * "Effect default" reset that sends -1. The chosen color drives the bubble
 * surface, HUD bar, server-side particle colors and the boss bar bucket; the
 * in-bubble screen post-effect keeps the effect's authored palette (its colors
 * are baked into the static post_effect JSON uniforms), which the
 * {@code gui.bubbleshield.color.note} tooltip documents. The server owner-gates
 * and validates the request ({@code ServerNet.isValidColorOverride}).
 */
public class ColorPickerScreen extends Screen {
	private static final int COLUMNS = 8;
	private static final int SWATCH_SIZE = 24;
	private static final int SPACING = 4;
	private static final int SWATCH_INSET = 3;
	private static final int SELECTED_FRAME = 0xFFFFFFFF;

	private final Screen parent;
	private final BlockPos pos;
	/** Swatch buttons paired with their opaque ARGB fill, for the overlay drawn in extractRenderState. */
	private final List<Swatch> swatches = new ArrayList<>();

	private record Swatch(Button button, int argb, boolean selected) {
	}

	public ColorPickerScreen(Screen parent, BlockPos pos) {
		super(Component.translatable("gui.bubbleshield.color.title"));
		this.parent = parent;
		this.pos = pos;
	}

	@Override
	protected void init() {
		this.swatches.clear();

		// The current override comes from the synced client replica (-1 = authored palette).
		ClientShieldManager.ClientShield shield = ClientShieldManager.get(this.pos);
		int current = shield != null ? shield.colorOverride() : ShieldState.NO_COLOR_OVERRIDE;

		DyeColor[] dyes = DyeColor.values();
		int rows = (dyes.length + COLUMNS - 1) / COLUMNS;
		int gridWidth = COLUMNS * SWATCH_SIZE + (COLUMNS - 1) * SPACING;
		int startX = (this.width - gridWidth) / 2;
		int startY = 40;

		for (DyeColor dye : dyes) {
			int cell = dye.ordinal();
			int x = startX + (cell % COLUMNS) * (SWATCH_SIZE + SPACING);
			int y = startY + (cell / COLUMNS) * (SWATCH_SIZE + SPACING);
			int argb = dye.getTextureDiffuseColor();
			Button button = this.addRenderableWidget(
				Button.builder(Component.empty(), b -> this.send(argb))
					.bounds(x, y, SWATCH_SIZE, SWATCH_SIZE)
					.tooltip(Tooltip.create(Component.translatable("color.minecraft." + dye.getName())))
					.build()
			);
			boolean selected = argb == current;
			button.active = !selected;
			this.swatches.add(new Swatch(button, argb, selected));
		}

		int belowGridY = startY + rows * (SWATCH_SIZE + SPACING) + 8;
		Button defaultButton = this.addRenderableWidget(
			Button.builder(Component.translatable("gui.bubbleshield.color.default"), b -> this.send(ShieldState.NO_COLOR_OVERRIDE))
				.bounds(this.width / 2 - 75, belowGridY, 150, 20)
				.tooltip(Tooltip.create(Component.translatable("gui.bubbleshield.color.note")))
				.build()
		);
		defaultButton.active = current != ShieldState.NO_COLOR_OVERRIDE;

		this.addRenderableWidget(
			Button.builder(Component.translatable("gui.bubbleshield.back"), b -> this.onClose())
				.bounds(this.width / 2 - 75, this.height - 28, 150, 20)
				.build()
		);
	}

	private void send(int argb) {
		ClientPlayNetworking.send(new ShieldPayloads.SetColorC2S(this.pos, argb));
		this.onClose();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.text(this.font, this.title, this.width / 2 - this.font.width(this.title) / 2, 16, -1, true);

		// Color fills drawn over the plain buttons: an inset swatch, plus a white
		// frame ring marking the currently selected color.
		for (Swatch swatch : this.swatches) {
			Button b = swatch.button();
			if (swatch.selected()) {
				graphics.fill(b.getX() + SWATCH_INSET - 1, b.getY() + SWATCH_INSET - 1,
						b.getX() + b.getWidth() - SWATCH_INSET + 1, b.getY() + b.getHeight() - SWATCH_INSET + 1, SELECTED_FRAME);
			}

			graphics.fill(b.getX() + SWATCH_INSET, b.getY() + SWATCH_INSET,
					b.getX() + b.getWidth() - SWATCH_INSET, b.getY() + b.getHeight() - SWATCH_INSET, swatch.argb());
		}
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}
}

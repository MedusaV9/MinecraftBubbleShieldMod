package com.bubbleshield.client.gui;

import java.util.Locale;

import com.bubbleshield.effect.EffectRegistry;
import com.bubbleshield.net.ShieldPayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Paged 5x5 grid of the selectable shield effects (ids 0..{@link EffectRegistry#COUNT} - 1).
 * Clicking an effect sends {@code SetSettingsC2S} keeping the current diameter.
 */
public class EffectPickerScreen extends Screen {
	private static final int EFFECT_COUNT = EffectRegistry.COUNT;
	private static final int COLUMNS = 5;
	private static final int ROWS = 5;
	private static final int PER_PAGE = COLUMNS * ROWS;
	private static final int PAGE_COUNT = (EFFECT_COUNT + PER_PAGE - 1) / PER_PAGE;
	private static final int BUTTON_WIDTH = 56;
	private static final int BUTTON_HEIGHT = 16;
	private static final int SPACING = 2;

	private final Screen parent;
	private final BlockPos pos;
	private final int diameter;
	private final int currentEffectId;
	/** Echoed back unchanged in {@code SetSettingsC2S} so picking an effect keeps the shape. */
	private final int shapeOrdinal;
	private int page;

	public EffectPickerScreen(Screen parent, BlockPos pos, int diameter, int currentEffectId, int shapeOrdinal) {
		super(Component.translatable("gui.bubbleshield.effects"));
		this.parent = parent;
		this.pos = pos;
		this.diameter = diameter;
		this.currentEffectId = currentEffectId;
		this.shapeOrdinal = shapeOrdinal;
	}

	@Override
	protected void init() {
		int gridWidth = COLUMNS * BUTTON_WIDTH + (COLUMNS - 1) * SPACING;
		int startX = (this.width - gridWidth) / 2;
		int startY = 32;

		int first = this.page * PER_PAGE;
		int last = Math.min(first + PER_PAGE, EFFECT_COUNT);
		for (int effectId = first; effectId < last; effectId++) {
			int cell = effectId - first;
			int x = startX + (cell % COLUMNS) * (BUTTON_WIDTH + SPACING);
			int y = startY + (cell / COLUMNS) * (BUTTON_HEIGHT + SPACING);
			int chosenId = effectId;
			Button button = this.addRenderableWidget(
				Button.builder(Component.translatable("effect.bubbleshield." + String.format(Locale.ROOT, "%02d", effectId)), b -> this.pick(chosenId))
					.bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
					.build()
			);
			button.active = effectId != this.currentEffectId;
		}

		int navY = startY + ROWS * (BUTTON_HEIGHT + SPACING) + 8;
		Button prev = this.addRenderableWidget(
			Button.builder(Component.literal("<"), b -> this.flipPage(-1)).bounds(startX, navY, 20, 20).build()
		);
		prev.active = this.page > 0;
		Button next = this.addRenderableWidget(
			Button.builder(Component.literal(">"), b -> this.flipPage(1)).bounds(startX + gridWidth - 20, navY, 20, 20).build()
		);
		next.active = this.page < PAGE_COUNT - 1;

		this.addRenderableWidget(
			Button.builder(Component.translatable("gui.bubbleshield.back"), b -> this.onClose())
				.bounds(this.width / 2 - 75, this.height - 28, 150, 20)
				.build()
		);
	}

	private void flipPage(int direction) {
		this.page = Math.floorMod(this.page + direction, PAGE_COUNT);
		this.rebuildWidgets();
	}

	private void pick(int effectId) {
		ClientPlayNetworking.send(new ShieldPayloads.SetSettingsC2S(this.pos, this.diameter, effectId, this.shapeOrdinal));
		this.onClose();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		Component header = Component.translatable("gui.bubbleshield.effects_page", this.page + 1, PAGE_COUNT);
		graphics.text(this.font, header, this.width / 2 - this.font.width(header) / 2, 16, -1, true);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}
}

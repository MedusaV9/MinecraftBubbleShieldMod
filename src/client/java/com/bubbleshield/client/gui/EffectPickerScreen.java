package com.bubbleshield.client.gui;

import java.util.Locale;

import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.EffectRegistry;
import com.bubbleshield.net.ShieldPayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

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
	/** Echoed back unchanged in {@code SetSettingsC2S} so picking an effect keeps the mode. */
	private final int modeOrdinal;
	/** Tracks the effect-cycle toggle locally; flipped by the nav-row Cycle button. */
	private boolean cycleEnabled;
	private int page;
	private Button cycleButton;

	public EffectPickerScreen(Screen parent, BlockPos pos, int diameter, int currentEffectId, int shapeOrdinal, int modeOrdinal, boolean cycleEnabled) {
		super(Component.translatable("gui.bubbleshield.effects"));
		this.parent = parent;
		this.pos = pos;
		this.diameter = diameter;
		this.currentEffectId = currentEffectId;
		this.shapeOrdinal = shapeOrdinal;
		this.modeOrdinal = modeOrdinal;
		this.cycleEnabled = cycleEnabled;
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
					.tooltip(effectTooltip(EffectRegistry.get(effectId)))
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

		// Effect-cycle toggle and the recolor picker share the nav row between the
		// page-flip buttons. The color note tooltip documents that the in-bubble
		// screen post-effect keeps the effect's authored palette.
		this.cycleButton = this.addRenderableWidget(
			Button.builder(this.cycleLabel(), b -> this.toggleCycle())
				.bounds(startX + gridWidth / 2 - 78, navY, 76, 20)
				.build()
		);

		this.addRenderableWidget(
			Button.builder(Component.translatable("gui.bubbleshield.color"), b ->
				this.minecraft.gui.setScreen(new ColorPickerScreen(this, this.pos)))
				.bounds(startX + gridWidth / 2 + 2, navY, 76, 20)
				.tooltip(Tooltip.create(Component.translatable("gui.bubbleshield.color.note")))
				.build()
		);

		this.addRenderableWidget(
			Button.builder(Component.translatable("gui.bubbleshield.back"), b -> this.onClose())
				.bounds(this.width / 2 - 75, this.height - 28, 150, 20)
				.build()
		);
	}

	/**
	 * Composes the per-effect tooltip from the four translatable axis labels
	 * (surface template, inside behavior, guard style, context profile), one per line.
	 */
	private static Tooltip effectTooltip(EffectDefinition def) {
		MutableComponent text = Component.translatable("surface.bubbleshield." + def.surface().name().toLowerCase(Locale.ROOT))
			.append(Component.literal("\n"))
			.append(Component.translatable("behavior.bubbleshield." + def.insideBehaviorId()))
			.append(Component.literal("\n"))
			.append(Component.translatable("guard.bubbleshield." + def.guard().name().toLowerCase(Locale.ROOT)))
			.append(Component.literal("\n"))
			.append(Component.translatable("context.bubbleshield." + def.context().name().toLowerCase(Locale.ROOT)));
		return Tooltip.create(text);
	}

	private void flipPage(int direction) {
		this.page = Math.floorMod(this.page + direction, PAGE_COUNT);
		this.rebuildWidgets();
	}

	private void pick(int effectId) {
		ClientPlayNetworking.send(new ShieldPayloads.SetSettingsC2S(
			this.pos, this.diameter, effectId, this.shapeOrdinal, this.modeOrdinal, this.cycleEnabled));
		this.onClose();
	}

	/** Flips the effect-cycle toggle and sends it, echoing all other synced settings. */
	private void toggleCycle() {
		this.cycleEnabled = !this.cycleEnabled;
		ClientPlayNetworking.send(new ShieldPayloads.SetSettingsC2S(
			this.pos, this.diameter, this.currentEffectId, this.shapeOrdinal, this.modeOrdinal, this.cycleEnabled));
		this.cycleButton.setMessage(this.cycleLabel());
	}

	private Component cycleLabel() {
		return Component.translatable(this.cycleEnabled ? "gui.bubbleshield.cycle.on" : "gui.bubbleshield.cycle.off");
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

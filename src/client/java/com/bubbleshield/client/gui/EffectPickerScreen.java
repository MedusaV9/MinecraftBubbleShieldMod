package com.bubbleshield.client.gui;

import java.util.Locale;

import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.EffectRegistry;
import com.bubbleshield.menu.BubbleShieldMenu;
import com.bubbleshield.net.ShieldPayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Paged 5x5 grid of the selectable shield effects (ids 0..{@link EffectRegistry#COUNT} - 1).
 * Clicking an effect sends {@code SetSettingsC2S} keeping the current diameter.
 *
 * <p>All echoed-back settings (diameter/effect/shape/mode/cycle) are read LIVE from the
 * still-open {@link BubbleShieldMenu} at send time, never captured at construction:
 * the menu keeps receiving ContainerData updates while this screen is open, and the
 * server may change settings underneath it (most notably the effect-cycle timer
 * re-rolling the effect id) — a stale construction-time snapshot would snap those
 * server-side changes back. Only the picked effect id (and, for the toggle click, the
 * locally-flipped cycle flag) are locals.
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
	/** The open projector menu; the source of truth for every synced setting. */
	private final BubbleShieldMenu menu;
	private int page;
	private Button cycleButton;

	public EffectPickerScreen(Screen parent, BubbleShieldMenu menu) {
		super(Component.translatable("gui.bubbleshield.effects"));
		this.parent = parent;
		this.menu = menu;
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
			button.active = effectId != this.menu.effectId();
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
				this.minecraft.gui.setScreen(new ColorPickerScreen(this, this.menu.pos())))
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

	/** Sends the picked effect, echoing the live (server-synced) diameter/shape/mode/cycle. */
	private void pick(int effectId) {
		ClientPlayNetworking.send(new ShieldPayloads.SetSettingsC2S(
			this.menu.pos(), this.menu.diameter(), effectId, this.menu.shape(), this.menu.mode(), this.menu.cycleEffect()));
		this.onClose();
	}

	/** Sends the flipped effect-cycle toggle, echoing the live values of everything else. */
	private void toggleCycle() {
		ClientPlayNetworking.send(new ShieldPayloads.SetSettingsC2S(
			this.menu.pos(), this.menu.diameter(), this.menu.effectId(), this.menu.shape(), this.menu.mode(), !this.menu.cycleEffect()));
	}

	private Component cycleLabel() {
		return Component.translatable(this.menu.cycleEffect() ? "gui.bubbleshield.cycle.on" : "gui.bubbleshield.cycle.off");
	}

	@Override
	public void tick() {
		super.tick();
		// The cycle flag is server-authoritative and arrives via the menu's data slots;
		// deriving the label every tick resyncs it after a toggle round-trip (or a
		// rejected request), exactly like BubbleShieldScreen's mode/shape buttons.
		this.cycleButton.setMessage(this.cycleLabel());
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

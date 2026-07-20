package com.bubbleshield.client.gui;

import com.bubbleshield.BubbleShield;
import com.bubbleshield.menu.BubbleShieldMenu;
import com.bubbleshield.net.ShieldPayloads;
import com.bubbleshield.shield.BeamStyle;
import com.bubbleshield.shield.ShieldMode;
import com.bubbleshield.shield.ShieldShape;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;

/**
 * Furnace-like screen for the bubble shield projector. Uses the 26.2 extractor-based
 * GUI system: background/labels are emitted via {@code extractBackground}/{@code extractLabels}.
 */
public class BubbleShieldScreen extends AbstractContainerScreen<BubbleShieldMenu> {
	private static final Identifier TEXTURE = BubbleShield.id("textures/gui/bubble_shield.png");
	private static final int LABEL_COLOR = -12566464;

	public static final int MIN_DIAMETER = 8;
	public static final int MAX_DIAMETER = 200;

	private Button activateButton;
	private DiameterSlider diameterSlider;
	private Button shapeButton;
	private Button modeButton;
	private Button beamButton;

	public BubbleShieldScreen(BubbleShieldMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, 176, 166);
		this.inventoryLabelY = this.imageHeight - 94;
	}

	@Override
	protected void init() {
		super.init();

		// Five 13px rows (12..81) fit above the player inventory (y = 84).
		int x = this.leftPos + 80;
		int width = 88;

		this.activateButton = this.addRenderableWidget(
			Button.builder(this.activateLabel(), button -> this.toggleActive())
				.bounds(x, this.topPos + 12, width, 13)
				.build()
		);

		this.diameterSlider = this.addRenderableWidget(new DiameterSlider(x, this.topPos + 26, width, 13, this.menu));

		// Shape and mode share the third row: 42px shape + 2px gap + 44px mode = 88px.
		this.shapeButton = this.addRenderableWidget(
			Button.builder(this.shapeLabel(), button -> this.toggleShape())
				.bounds(x, this.topPos + 40, 42, 13)
				.build()
		);

		this.modeButton = this.addRenderableWidget(
			Button.builder(this.modeLabel(), button -> this.cycleMode())
				.bounds(x + 44, this.topPos + 40, 44, 13)
				.build()
		);

		this.addRenderableWidget(
			Button.builder(Component.translatable("gui.bubbleshield.effects"), button ->
				this.minecraft.gui.setScreen(new EffectPickerScreen(this, this.menu))
			).bounds(x, this.topPos + 54, width, 13).build()
		);

		// Whitelist and beam share the fifth row, split exactly like the shape/mode
		// row: 42px whitelist + 2px gap + 44px beam = 88px (no slot/button overlap).
		this.addRenderableWidget(
			Button.builder(Component.translatable("gui.bubbleshield.whitelist"), button ->
				this.minecraft.gui.setScreen(new WhitelistScreen(this, this.menu.pos()))
			).bounds(x, this.topPos + 68, 42, 13).build()
		);

		this.beamButton = this.addRenderableWidget(
			Button.builder(this.beamLabel(), button -> this.cycleBeam())
				.bounds(x + 44, this.topPos + 68, 44, 13)
				.tooltip(Tooltip.create(Component.translatable("gui.bubbleshield.beam.tooltip")))
				.build()
		);

		// Left column, between the fuel (y=20) and tier (y=44) labels: a free 13px spot.
		// Width 44 ends the button at x=52, safely left of the device slot column
		// (the capacitor/fuel/core frames start at x=55, hit-regions at x=56): a 64px
		// button used to reach x=72 and steal clicks on the capacitor slot's bottom
		// edge (y 30..33 of its 17..32 hit-region).
		this.addRenderableWidget(
			Button.builder(Component.translatable("gui.bubbleshield.name"), button ->
				this.minecraft.gui.setScreen(new ShieldNameScreen(this, this.menu.pos()))
			).bounds(this.leftPos + 8, this.topPos + 30, 44, 13).build()
		);
	}

	private void toggleActive() {
		ClientPlayNetworking.send(new ShieldPayloads.SetActiveC2S(this.menu.pos(), !this.menu.isActive()));
	}

	private Component activateLabel() {
		return Component.translatable(this.menu.isActive() ? "gui.bubbleshield.deactivate" : "gui.bubbleshield.activate");
	}

	/** Sends the toggled shape, echoing the current (server-synced) diameter/effect/mode/cycle/beam. */
	private void toggleShape() {
		int toggled = this.menu.shape() == ShieldShape.SPHERE.ordinal() ? ShieldShape.DOME.ordinal() : ShieldShape.SPHERE.ordinal();
		ClientPlayNetworking.send(new ShieldPayloads.SetSettingsC2S(
			this.menu.pos(), this.menu.diameter(), this.menu.effectId(), toggled, this.menu.mode(), this.menu.cycleEffect(), this.menu.beamStyle()));
	}

	private Component shapeLabel() {
		boolean dome = ShieldShape.byOrdinal(this.menu.shape()) == ShieldShape.DOME;
		return Component.translatable(dome ? "gui.bubbleshield.shape.dome" : "gui.bubbleshield.shape.sphere");
	}

	/** Sends the next mode in the DEFENSE -> PULSE -> ECO cycle, echoing everything else. */
	private void cycleMode() {
		int next = (this.menu.mode() + 1) % ShieldMode.values().length;
		ClientPlayNetworking.send(new ShieldPayloads.SetSettingsC2S(
			this.menu.pos(), this.menu.diameter(), this.menu.effectId(), this.menu.shape(), next, this.menu.cycleEffect(), this.menu.beamStyle()));
	}

	private Component modeLabel() {
		return Component.translatable(switch (ShieldMode.byOrdinal(this.menu.mode())) {
			case PULSE -> "gui.bubbleshield.mode.pulse";
			case ECO -> "gui.bubbleshield.mode.eco";
			default -> "gui.bubbleshield.mode.defense";
		});
	}

	/** Sends the next beam style in the NONE -> AUTO -> STORM -> PULSE -> HELIX -> PRISM cycle. */
	private void cycleBeam() {
		int next = (this.menu.beamStyle() + 1) % BeamStyle.values().length;
		ClientPlayNetworking.send(new ShieldPayloads.SetSettingsC2S(
			this.menu.pos(), this.menu.diameter(), this.menu.effectId(), this.menu.shape(), this.menu.mode(), this.menu.cycleEffect(), next));
	}

	private Component beamLabel() {
		return Component.translatable(switch (BeamStyle.byOrdinal(this.menu.beamStyle())) {
			case AUTO -> "gui.bubbleshield.beam.auto";
			case STORM -> "gui.bubbleshield.beam.storm";
			case PULSE -> "gui.bubbleshield.beam.pulse";
			case HELIX -> "gui.bubbleshield.beam.helix";
			case PRISM -> "gui.bubbleshield.beam.prism";
			default -> "gui.bubbleshield.beam.none";
		});
	}

	@Override
	protected void containerTick() {
		super.containerTick();
		// The active flag is server-authoritative and arrives via data slots.
		this.activateButton.setMessage(this.activateLabel());
		// ContainerData arrives after init(), so the slider must re-sync once the
		// real diameter shows up (and whenever the server changes it).
		this.diameterSlider.syncFromMenu();
		// Same story for the shape, mode and beam: the labels always reflect the synced server state.
		this.shapeButton.setMessage(this.shapeLabel());
		this.modeButton.setMessage(this.modeLabel());
		this.beamButton.setMessage(this.beamLabel());
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractBackground(graphics, mouseX, mouseY, a);
		graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, this.leftPos, this.topPos, 0.0F, 0.0F, this.imageWidth, this.imageHeight, 256, 256);
		// The shipped background texture has no square for the (newer) core slot at
		// (56, 53); draw a vanilla-style 18x18 slot frame with plain fills instead of
		// a new texture: dark top/left edge, light bottom/right edge, grey interior.
		int sx = this.leftPos + 55;
		int sy = this.topPos + 52;
		graphics.fill(sx, sy, sx + 18, sy + 18, 0xFF373737);
		graphics.fill(sx + 1, sy + 1, sx + 18, sy + 18, 0xFFFFFFFF);
		graphics.fill(sx + 1, sy + 1, sx + 17, sy + 17, 0xFF8B8B8B);
		// Same technique for the flux-capacitor slot at (56, 17), above the fuel slot.
		int cx = this.leftPos + 55;
		int cy = this.topPos + 16;
		graphics.fill(cx, cy, cx + 18, cy + 18, 0xFF373737);
		graphics.fill(cx + 1, cy + 1, cx + 18, cy + 18, 0xFFFFFFFF);
		graphics.fill(cx + 1, cy + 1, cx + 17, cy + 17, 0xFF8B8B8B);
	}

	/** Left-column labels must end before the device slot frames, which start at x = 55. */
	private static final int LABEL_MAX_WIDTH = 55 - 8 - 1;

	@Override
	protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		super.extractLabels(graphics, mouseX, mouseY);
		// The fuel row (y 20..28) sits beside the capacitor slot frame (x 55..72,
		// y 16..33), so a wide value would render under the slot. Degrade gracefully:
		// full "Fuel: Ns" label when it fits, the bare "Ns" value when the prefix
		// does not, and an elided value only for absurdly large numbers.
		String fuel = Component.translatable("gui.bubbleshield.fuel", this.menu.fuelSeconds()).getString();
		if (this.font.width(fuel) > LABEL_MAX_WIDTH) {
			fuel = this.menu.fuelSeconds() + "s";
		}
		if (this.font.width(fuel) > LABEL_MAX_WIDTH) {
			fuel = this.font.plainSubstrByWidth(fuel, LABEL_MAX_WIDTH - this.font.width("...")) + "...";
		}

		graphics.text(this.font, fuel, 8, 20, LABEL_COLOR, false);
		graphics.text(this.font, Component.translatable("gui.bubbleshield.tier", this.menu.tier()), 8, 44, LABEL_COLOR, false);
		graphics.text(this.font, Component.translatable("gui.bubbleshield.health", String.format("%.1f", this.menu.health())), 8, 56, LABEL_COLOR, false);
		graphics.text(this.font, Component.translatable("gui.bubbleshield.cooldown", this.menu.cooldownSeconds()), 8, 66, LABEL_COLOR, false);
	}

	/**
	 * Diameter slider [8..200]. Any user change (mouse drag OR keyboard arrows, both of
	 * which land in {@link #applyValue()}) marks the slider dirty; the SetSettingsC2S
	 * packet is flushed on mouse release, immediately for keyboard adjustments, and on
	 * focus loss as a fallback, so no adjustment path is ever dropped.
	 */
	private static class DiameterSlider extends AbstractSliderButton {
		private final BubbleShieldMenu menu;
		/** AbstractSliderButton keeps its dragging flag private, so track our own. */
		private boolean dragging;
		/** Set by applyValue(); cleared when the pending value has been sent to the server. */
		private boolean dirty;

		DiameterSlider(int x, int y, int width, int height, BubbleShieldMenu menu) {
			super(x, y, width, height, Component.empty(), toSliderValue(menu.diameter()));
			this.menu = menu;
			this.updateMessage();
		}

		private static double toSliderValue(int diameter) {
			return (Mth.clamp(diameter, MIN_DIAMETER, MAX_DIAMETER) - MIN_DIAMETER) / (double) (MAX_DIAMETER - MIN_DIAMETER);
		}

		private int diameter() {
			return MIN_DIAMETER + (int) Math.round(this.value * (MAX_DIAMETER - MIN_DIAMETER));
		}

		/**
		 * Re-syncs from the server-authoritative ContainerData value unless the user is
		 * mid-adjustment (dragging or an unflushed change). Comparing against the
		 * DISPLAYED value means a label left stale (e.g. a rejected request) snaps back
		 * once the server state disagrees with it.
		 */
		void syncFromMenu() {
			int synced = this.menu.diameter();
			if (!this.dragging && !this.dirty && synced != this.diameter()) {
				this.value = toSliderValue(synced);
				this.updateMessage();
			}
		}

		@Override
		protected void updateMessage() {
			this.setMessage(Component.translatable("gui.bubbleshield.diameter", this.diameter()));
		}

		@Override
		protected void applyValue() {
			// Called for both mouse-drag and keyboard-arrow changes. While dragging, the
			// flush waits for onRelease; keyboard adjustments flush immediately.
			this.dirty = true;
			if (!this.dragging) {
				this.flush();
			}
		}

		/** Sends the pending diameter to the server (keeping the synced effect/shape/mode/cycle/beam). */
		private void flush() {
			if (!this.dirty) {
				return;
			}

			this.dirty = false;
			// The current (server-synced) shape/mode/cycle/beam are echoed back so only the diameter changes.
			ClientPlayNetworking.send(new ShieldPayloads.SetSettingsC2S(
				this.menu.pos(), this.diameter(), this.menu.effectId(), this.menu.shape(), this.menu.mode(), this.menu.cycleEffect(), this.menu.beamStyle()));
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			this.dragging = true;
			super.onClick(event, doubleClick);
		}

		@Override
		public void onRelease(MouseButtonEvent event) {
			this.dragging = false;
			super.onRelease(event);
			this.flush();
		}

		@Override
		public void setFocused(boolean focused) {
			super.setFocused(focused);
			if (!focused) {
				// Fallback: never leave an adjustment unsent when focus moves away.
				this.dragging = false;
				this.flush();
			}
		}
	}
}

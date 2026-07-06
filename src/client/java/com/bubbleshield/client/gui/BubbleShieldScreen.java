package com.bubbleshield.client.gui;

import com.bubbleshield.BubbleShield;
import com.bubbleshield.menu.BubbleShieldMenu;
import com.bubbleshield.net.ShieldPayloads;
import com.bubbleshield.shield.ShieldShape;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
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

		this.shapeButton = this.addRenderableWidget(
			Button.builder(this.shapeLabel(), button -> this.toggleShape())
				.bounds(x, this.topPos + 40, width, 13)
				.build()
		);

		this.addRenderableWidget(
			Button.builder(Component.translatable("gui.bubbleshield.effects"), button ->
				this.minecraft.gui.setScreen(new EffectPickerScreen(this, this.menu.pos(), this.menu.diameter(), this.menu.effectId(), this.menu.shape()))
			).bounds(x, this.topPos + 54, width, 13).build()
		);

		this.addRenderableWidget(
			Button.builder(Component.translatable("gui.bubbleshield.whitelist"), button ->
				this.minecraft.gui.setScreen(new WhitelistScreen(this, this.menu.pos()))
			).bounds(x, this.topPos + 68, width, 13).build()
		);
	}

	private void toggleActive() {
		ClientPlayNetworking.send(new ShieldPayloads.SetActiveC2S(this.menu.pos(), !this.menu.isActive()));
	}

	private Component activateLabel() {
		return Component.translatable(this.menu.isActive() ? "gui.bubbleshield.deactivate" : "gui.bubbleshield.activate");
	}

	/** Sends the toggled shape, keeping the current (server-synced) diameter and effect. */
	private void toggleShape() {
		int toggled = this.menu.shape() == ShieldShape.SPHERE.ordinal() ? ShieldShape.DOME.ordinal() : ShieldShape.SPHERE.ordinal();
		ClientPlayNetworking.send(new ShieldPayloads.SetSettingsC2S(this.menu.pos(), this.menu.diameter(), this.menu.effectId(), toggled));
	}

	private Component shapeLabel() {
		boolean dome = ShieldShape.byOrdinal(this.menu.shape()) == ShieldShape.DOME;
		return Component.translatable(dome ? "gui.bubbleshield.shape.dome" : "gui.bubbleshield.shape.sphere");
	}

	@Override
	protected void containerTick() {
		super.containerTick();
		// The active flag is server-authoritative and arrives via data slots.
		this.activateButton.setMessage(this.activateLabel());
		// ContainerData arrives after init(), so the slider must re-sync once the
		// real diameter shows up (and whenever the server changes it).
		this.diameterSlider.syncFromMenu();
		// Same story for the shape: the label always reflects the synced server state.
		this.shapeButton.setMessage(this.shapeLabel());
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
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		super.extractLabels(graphics, mouseX, mouseY);
		graphics.text(this.font, Component.translatable("gui.bubbleshield.fuel", this.menu.fuelSeconds()), 8, 20, LABEL_COLOR, false);
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

		/** Sends the pending diameter to the server (keeping the synced effect and shape). */
		private void flush() {
			if (!this.dirty) {
				return;
			}

			this.dirty = false;
			// The current (server-synced) shape is echoed back so only the diameter changes.
			ClientPlayNetworking.send(new ShieldPayloads.SetSettingsC2S(this.menu.pos(), this.diameter(), this.menu.effectId(), this.menu.shape()));
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

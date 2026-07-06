package com.bubbleshield.client.gui;

import com.bubbleshield.BubbleShield;
import com.bubbleshield.menu.BubbleShieldMenu;
import com.bubbleshield.net.ShieldPayloads;

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

	public BubbleShieldScreen(BubbleShieldMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, 176, 166);
		this.inventoryLabelY = this.imageHeight - 94;
	}

	@Override
	protected void init() {
		super.init();

		int x = this.leftPos + 80;
		int width = 88;

		this.activateButton = this.addRenderableWidget(
			Button.builder(this.activateLabel(), button -> this.toggleActive())
				.bounds(x, this.topPos + 12, width, 14)
				.build()
		);

		this.diameterSlider = this.addRenderableWidget(new DiameterSlider(x, this.topPos + 28, width, 14, this.menu));

		this.addRenderableWidget(
			Button.builder(Component.translatable("gui.bubbleshield.effects"), button ->
				this.minecraft.gui.setScreen(new EffectPickerScreen(this, this.menu.pos(), this.menu.diameter(), this.menu.effectId(), this.menu.shape()))
			).bounds(x, this.topPos + 44, width, 14).build()
		);

		this.addRenderableWidget(
			Button.builder(Component.translatable("gui.bubbleshield.whitelist"), button ->
				this.minecraft.gui.setScreen(new WhitelistScreen(this, this.menu.pos()))
			).bounds(x, this.topPos + 60, width, 14).build()
		);
	}

	private void toggleActive() {
		ClientPlayNetworking.send(new ShieldPayloads.SetActiveC2S(this.menu.pos(), !this.menu.isActive()));
	}

	private Component activateLabel() {
		return Component.translatable(this.menu.isActive() ? "gui.bubbleshield.deactivate" : "gui.bubbleshield.activate");
	}

	@Override
	protected void containerTick() {
		super.containerTick();
		// The active flag is server-authoritative and arrives via data slots.
		this.activateButton.setMessage(this.activateLabel());
		// ContainerData arrives after init(), so the slider must re-sync once the
		// real diameter shows up (and whenever the server changes it).
		this.diameterSlider.syncFromMenu();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractBackground(graphics, mouseX, mouseY, a);
		graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, this.leftPos, this.topPos, 0.0F, 0.0F, this.imageWidth, this.imageHeight, 256, 256);
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		super.extractLabels(graphics, mouseX, mouseY);
		graphics.text(this.font, Component.translatable("gui.bubbleshield.fuel", this.menu.fuelSeconds()), 8, 20, LABEL_COLOR, false);
		graphics.text(this.font, Component.translatable("gui.bubbleshield.health", String.format("%.1f", this.menu.health())), 8, 56, LABEL_COLOR, false);
		graphics.text(this.font, Component.translatable("gui.bubbleshield.cooldown", this.menu.cooldownSeconds()), 8, 66, LABEL_COLOR, false);
	}

	/** Diameter slider [8..200]; sends the chosen value (keeping the current effect) on mouse release. */
	private static class DiameterSlider extends AbstractSliderButton {
		private final BubbleShieldMenu menu;
		/** AbstractSliderButton keeps its dragging flag private, so track our own. */
		private boolean dragging;
		private int lastSyncedDiameter;

		DiameterSlider(int x, int y, int width, int height, BubbleShieldMenu menu) {
			super(x, y, width, height, Component.empty(), toSliderValue(menu.diameter()));
			this.menu = menu;
			this.lastSyncedDiameter = menu.diameter();
			this.updateMessage();
		}

		private static double toSliderValue(int diameter) {
			return (Mth.clamp(diameter, MIN_DIAMETER, MAX_DIAMETER) - MIN_DIAMETER) / (double) (MAX_DIAMETER - MIN_DIAMETER);
		}

		private int diameter() {
			return MIN_DIAMETER + (int) Math.round(this.value * (MAX_DIAMETER - MIN_DIAMETER));
		}

		/** Re-syncs from the server-authoritative ContainerData value unless the user is dragging. */
		void syncFromMenu() {
			int synced = this.menu.diameter();
			if (!this.dragging && synced != this.lastSyncedDiameter) {
				this.lastSyncedDiameter = synced;
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
			// Only the on-screen label updates while dragging; the packet is sent on release.
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
			// The current (server-synced) shape is echoed back so only the diameter changes.
			ClientPlayNetworking.send(new ShieldPayloads.SetSettingsC2S(this.menu.pos(), this.diameter(), this.menu.effectId(), this.menu.shape()));
		}
	}
}

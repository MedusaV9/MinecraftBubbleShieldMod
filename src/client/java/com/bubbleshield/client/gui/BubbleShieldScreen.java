package com.bubbleshield.client.gui;

import java.util.Locale;

import com.bubbleshield.BubbleShield;
import com.bubbleshield.menu.BubbleShieldMenu;
import com.bubbleshield.net.ShieldPayloads;
import com.bubbleshield.registry.ModItems;
import com.bubbleshield.shield.BeamStyle;
import com.bubbleshield.shield.ShieldLogic;
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

import org.jspecify.annotations.Nullable;

/**
 * Furnace-like screen for the bubble shield projector. Uses the 26.2 extractor-based
 * GUI system: background/labels are emitted via {@code extractBackground}/{@code extractLabels}.
 */
public class BubbleShieldScreen extends AbstractContainerScreen<BubbleShieldMenu> {
	private static final Identifier TEXTURE = BubbleShield.id("textures/gui/bubble_shield.png");
	private static final int LABEL_COLOR = -12566464;
	/**
	 * E1: the threats row's red-ish accent (0xFFB02525). Color is never the only
	 * cue — the label text itself carries a "!" prefix while threats are present.
	 */
	private static final int THREAT_COLOR = 0xFFB02525;

	public static final int MIN_DIAMETER = 8;
	public static final int MAX_DIAMETER = 200;

	private Button activateButton;
	private DiameterSlider diameterSlider;
	private Button shapeButton;
	private Button modeButton;
	private Button beamButton;

	public BubbleShieldScreen(BubbleShieldMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, 176, 166);
		// E1: the vanilla "Inventory" label row (y = 72) is reclaimed for the power
		// readout stack — up to five 8px rows at y = 48..80 in the left column.
		this.inventoryLabelY = -1000;
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
		this.activateButton.setTooltip(this.activateTooltip());
		this.activateButton.active = this.activateButtonEnabled();

		this.diameterSlider = this.addRenderableWidget(new DiameterSlider(x, this.topPos + 26, width, 13, this.menu));

		// Shape and mode share the third row: 42px shape + 2px gap + 44px mode = 88px.
		// The 42px button ellipsizes the longer shape names, so the tooltip carries
		// the full context ("Shield shape") like the beam button does.
		this.shapeButton = this.addRenderableWidget(
			Button.builder(this.shapeLabel(), button -> this.toggleShape())
				.bounds(x, this.topPos + 40, 42, 13)
				.tooltip(Tooltip.create(Component.translatable("gui.bubbleshield.shape.tooltip")))
				.build()
		);

		this.modeButton = this.addRenderableWidget(
			Button.builder(this.modeLabel(), button -> this.cycleMode())
				.bounds(x + 44, this.topPos + 40, 44, 13)
				.build()
		);

		// Effects and name share the fourth row (46px effects + 2px gap + 40px name
		// = 88px): the name button moved here from the left column (8, 30), whose
		// spot now hosts the augment slot (frame drawn in extractBackground).
		this.addRenderableWidget(
			Button.builder(Component.translatable("gui.bubbleshield.effects"), button ->
				this.minecraft.gui.setScreen(new EffectPickerScreen(this, this.menu))
			).bounds(x, this.topPos + 54, 46, 13).build()
		);

		this.addRenderableWidget(
			Button.builder(Component.translatable("gui.bubbleshield.name"), button ->
				this.minecraft.gui.setScreen(new ShieldNameScreen(this, this.menu.pos()))
			).bounds(x + 48, this.topPos + 54, 40, 13).build()
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

	}

	private void toggleActive() {
		// During a break cooldown the "activate" request doubles as the A7 emergency
		// revive: the server accepts it when the sender owns the shield, the revive
		// window is open (fix 3a) and at least the tier-scaled reviveFuelCost is
		// stored (and simply ignores it otherwise), so the same payload serves both
		// button faces.
		ClientPlayNetworking.send(new ShieldPayloads.SetActiveC2S(this.menu.pos(), !this.menu.isActive()));
	}

	/** Fix 3b: the tier-scaled revive fee, computed from the synced tier slot. */
	private int reviveCost() {
		return ShieldLogic.reviveFuelCost(this.menu.tier());
	}

	/**
	 * A7: true while the server-side revive gates pass (the synced
	 * DATA_REVIVE_AVAILABLE slot: inactive, a running cooldown with &ge; 200 ticks
	 * remaining, no revive spent in this window — fix 3a) AND at least the
	 * tier-scaled fee is stored — exactly when the SetActiveC2S handler would
	 * accept an emergency revive, so the Activate button flips to its "Revive" face.
	 */
	private boolean reviveAffordable() {
		return !this.menu.isActive()
				&& this.menu.reviveAvailable()
				&& this.menu.fuelSeconds() >= this.reviveCost();
	}

	private Component activateLabel() {
		if (this.menu.isActive()) {
			return Component.translatable("gui.bubbleshield.deactivate");
		}

		// Fix 3b: the revive face carries the real, tier-scaled fee.
		return this.reviveAffordable()
				? Component.translatable("gui.bubbleshield.revive", this.reviveCost())
				: Component.translatable("gui.bubbleshield.activate");
	}

	/**
	 * E5: the Activate button is clickable exactly when the server would act on the
	 * SetActiveC2S request — always while active (deactivate), during a cooldown
	 * only when the revive is affordable, and otherwise only with fuel on board.
	 */
	private boolean activateButtonEnabled() {
		if (this.menu.isActive() || this.reviveAffordable()) {
			return true;
		}

		if (this.menu.cooldownSeconds() > 0) {
			return false;
		}

		return this.menu.fuelSeconds() > 0;
	}

	/**
	 * E5: one tooltip per button state — active: deactivate hint; cooling +
	 * affordable: the A7 revive tooltip; cooling + unaffordable (disabled):
	 * "Ready in m:ss"; no fuel (disabled): the refuel hint; ready: the
	 * "Max HP N · DR NN% · -X fuel/min" summary.
	 */
	private @Nullable Tooltip activateTooltip() {
		if (this.menu.isActive()) {
			return Tooltip.create(Component.translatable("gui.bubbleshield.deactivate.tooltip"));
		}

		if (this.reviveAffordable()) {
			return Tooltip.create(Component.translatable("gui.bubbleshield.revive.tooltip", this.reviveCost()));
		}

		if (this.menu.cooldownSeconds() > 0) {
			return Tooltip.create(Component.translatable("gui.bubbleshield.activate.tooltip.cooldown",
					ShieldLogic.formatMinutesSeconds(this.menu.cooldownSeconds())));
		}

		if (this.menu.fuelSeconds() <= 0) {
			return Tooltip.create(Component.translatable("gui.bubbleshield.activate.tooltip.no_fuel"));
		}

		return Tooltip.create(Component.translatable("gui.bubbleshield.activate.tooltip.ready",
				this.menu.maxHealth(), this.combinedDrPercent(), this.projectedDrainPerMinute()));
	}

	/** E1: true while reinforced plating sits in the augment slot (client-visible via the menu slot). */
	private boolean hasPlating() {
		return this.menu.slots.get(BubbleShieldMenu.AUGMENT_SLOT).getItem().is(ModItems.REINFORCED_PLATING);
	}

	/**
	 * E1: the combined tier x plating damage resistance percent, computed
	 * client-side from the synced tier and the visible augment slot through the
	 * SAME {@link ShieldLogic#combinedDr} the server damage pipeline uses.
	 */
	private int combinedDrPercent() {
		return Math.round(ShieldLogic.combinedDr(this.menu.tier(), this.hasPlating() ? ShieldLogic.PLATING_DR : 0.0F) * 100.0F);
	}

	/** Formats a fuel/HP-per-minute-x10 value as "X.X" (locale-stable, like the data slots). */
	private static String perMinute(int x10) {
		return String.format(Locale.ROOT, "%.1f", x10 / 10.0F);
	}

	/**
	 * E5 ready-summary tooltip: the drain the shield WOULD pay once activated. The
	 * synced drain slot reads 0 while inactive, so this projects the baseline rate
	 * client-side from the synced diameter/mode/capacitor via the same
	 * {@link ShieldLogic} rules the server slot uses.
	 */
	private String projectedDrainPerMinute() {
		int interval = ShieldLogic.drainIntervalTicks(this.menu.mode() == ShieldMode.ECO.ordinal(), this.menu.hasCapacitor());
		return perMinute(12000 * ShieldLogic.drainUnits(this.menu.diameter() / 2.0F) / interval);
	}

	/**
	 * Sends the next shape in the SPHERE -> DOME -> CYLINDER -> CUBE -> DIAMOND ->
	 * RING -> PYRAMID -> LENS -> HOURGLASS -> STAR cycle, echoing the current
	 * (server-synced) diameter/effect/mode/cycle/beam.
	 */
	private void toggleShape() {
		int next = (this.menu.shape() + 1) % ShieldShape.values().length;
		ClientPlayNetworking.send(new ShieldPayloads.SetSettingsC2S(
			this.menu.pos(), this.menu.diameter(), this.menu.effectId(), next, this.menu.mode(), this.menu.cycleEffect(), this.menu.beamStyle()));
	}

	private Component shapeLabel() {
		return Component.translatable(switch (ShieldShape.byOrdinal(this.menu.shape())) {
			case DOME -> "gui.bubbleshield.shape.dome";
			case CYLINDER -> "gui.bubbleshield.shape.cylinder";
			case CUBE -> "gui.bubbleshield.shape.cube";
			case DIAMOND -> "gui.bubbleshield.shape.diamond";
			case RING -> "gui.bubbleshield.shape.ring";
			case PYRAMID -> "gui.bubbleshield.shape.pyramid";
			case LENS -> "gui.bubbleshield.shape.lens";
			case HOURGLASS -> "gui.bubbleshield.shape.hourglass";
			case STAR -> "gui.bubbleshield.shape.star";
			default -> "gui.bubbleshield.shape.sphere";
		});
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

	/**
	 * Sends the next beam style in the NONE -> AUTO -> STORM -> PULSE -> HELIX ->
	 * PRISM -> VOID -> EMBER -> RUNIC -> FROST cycle.
	 */
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
			case VOID -> "gui.bubbleshield.beam.void";
			case EMBER -> "gui.bubbleshield.beam.ember";
			case RUNIC -> "gui.bubbleshield.beam.runic";
			case FROST -> "gui.bubbleshield.beam.frost";
			default -> "gui.bubbleshield.beam.none";
		});
	}

	@Override
	protected void containerTick() {
		super.containerTick();
		// The active flag is server-authoritative and arrives via data slots.
		this.activateButton.setMessage(this.activateLabel());
		this.activateButton.setTooltip(this.activateTooltip());
		// E5: the disabled (inactive) style tracks the server's accept conditions.
		this.activateButton.active = this.activateButtonEnabled();
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
		// And for the augment (defense module) slot at (9, 30) in the left column —
		// the spot the name button occupied before it moved to the right column.
		int ax = this.leftPos + 8;
		int ay = this.topPos + 29;
		graphics.fill(ax, ay, ax + 18, ay + 18, 0xFF373737);
		graphics.fill(ax + 1, ay + 1, ax + 18, ay + 18, 0xFFFFFFFF);
		graphics.fill(ax + 1, ay + 1, ax + 17, ay + 17, 0xFF8B8B8B);
	}

	/** Left-column labels must end before the device slot frames, which start at x = 55. */
	private static final int LABEL_MAX_WIDTH = 55 - 8 - 1;
	/** E1 readout stack: first row y, and the per-row advance (font height 8 + 0 gap). */
	private static final int READOUT_TOP = 48;
	private static final int READOUT_ROW_HEIGHT = 8;

	/**
	 * The left-column degrade pattern (shared by every readout row): the full
	 * localized label when it fits, the compact form when the prefix does not,
	 * and an elided compact form as the last resort.
	 */
	private String fitLabel(String full, String compact) {
		String text = full;
		if (this.font.width(text) > LABEL_MAX_WIDTH) {
			text = compact;
		}
		if (this.font.width(text) > LABEL_MAX_WIDTH) {
			text = this.font.plainSubstrByWidth(text, LABEL_MAX_WIDTH - this.font.width("...")) + "...";
		}

		return text;
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		super.extractLabels(graphics, mouseX, mouseY);
		// The fuel row (y 20..28) sits beside the capacitor slot frame (x 55..72,
		// y 16..33), so a wide value would render under the slot. Degrade gracefully:
		// full "Fuel: Ns" label when it fits, the bare "Ns" value when the prefix
		// does not, and an elided value only for absurdly large numbers.
		graphics.text(this.font,
				this.fitLabel(Component.translatable("gui.bubbleshield.fuel", this.menu.fuelSeconds()).getString(),
						this.menu.fuelSeconds() + "s"),
				8, 20, LABEL_COLOR, false);

		// E1 power readout: a dynamic stack of 8px rows from y = 48 (the augment
		// slot frame at (8, 29) reaches down to y = 46) into the reclaimed vanilla
		// inventory-label row. Conditional rows keep the stack at <= 5 rows in
		// practice: the cooldown row only exists while broken, when the regen/
		// drain/threat rows (active-only) are all absent, and vice versa.
		int y = READOUT_TOP;

		// Tier + combined DR on ONE row ("T2 · DR 58%"): the DR percent comes from
		// the same ShieldLogic.combinedDr the damage pipeline applies.
		int dr = this.combinedDrPercent();
		graphics.text(this.font,
				this.fitLabel(Component.translatable("gui.bubbleshield.tier_dr", this.menu.tier(), dr).getString(),
						"T" + this.menu.tier() + " " + dr + "%"),
				8, y, LABEL_COLOR, false);
		y += READOUT_ROW_HEIGHT;

		// Health arrives as permille + whole max HP (the old health*10 slot overflowed
		// above 3276.7 HP); display "HP: cur/max" with the usual degrade.
		String healthValue = this.menu.currentHealth() + "/" + this.menu.maxHealth();
		graphics.text(this.font,
				this.fitLabel(Component.translatable("gui.bubbleshield.health", healthValue).getString(), healthValue),
				8, y, LABEL_COLOR, false);
		y += READOUT_ROW_HEIGHT;

		// E5: the cooldown renders m:ss and only occupies a row while one is running.
		if (this.menu.cooldownSeconds() > 0) {
			String clock = ShieldLogic.formatMinutesSeconds(this.menu.cooldownSeconds());
			graphics.text(this.font,
					this.fitLabel(Component.translatable("gui.bubbleshield.cooldown", clock).getString(), clock),
					8, y, LABEL_COLOR, false);
			y += READOUT_ROW_HEIGHT;
		}

		// Regen: hidden when 0 (inactive, ECO, or a combat-gated tier 0).
		int regenX10 = this.menu.regenPerMinuteTimes10();
		if (regenX10 > 0) {
			graphics.text(this.font,
					this.fitLabel(Component.translatable("gui.bubbleshield.regen", perMinute(regenX10)).getString(),
							"+" + perMinute(regenX10) + "/min"),
					8, y, LABEL_COLOR, false);
			y += READOUT_ROW_HEIGHT;
		}

		// Drain: hidden while inactive (the slot reads 0 then).
		int drainX10 = this.menu.drainPerMinuteTimes10();
		if (this.menu.isActive() && drainX10 > 0) {
			graphics.text(this.font,
					this.fitLabel(Component.translatable("gui.bubbleshield.drain", perMinute(drainX10)).getString(),
							"-" + perMinute(drainX10) + "/min"),
					8, y, LABEL_COLOR, false);
			y += READOUT_ROW_HEIGHT;
		}

		// Threats: hidden at 0; the "!" prefix (baked into the lang string and the
		// compact form) carries the warning so the red tint is never the only cue.
		int threats = this.menu.threatCount();
		if (threats > 0) {
			graphics.text(this.font,
					this.fitLabel(Component.translatable("gui.bubbleshield.threats", threats).getString(),
							"! " + threats),
					8, y, THREAT_COLOR, false);
		}
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

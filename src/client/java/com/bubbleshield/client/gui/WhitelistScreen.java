package com.bubbleshield.client.gui;

import java.util.List;

import com.bubbleshield.client.ClientShieldManager;
import com.bubbleshield.net.ShieldPayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Whitelist editor for a shield projector: type a player name and add/remove it.
 * The current names come from the client shield replica kept in sync by {@code ShieldSyncS2C}.
 */
public class WhitelistScreen extends Screen {
	private static final int LIST_TOP = 92;
	private static final int LINE_HEIGHT = 10;
	private static final int MAX_VISIBLE_NAMES = 10;

	private final Screen parent;
	private final BlockPos pos;
	private EditBox nameField;

	public WhitelistScreen(Screen parent, BlockPos pos) {
		super(Component.translatable("gui.bubbleshield.whitelist"));
		this.parent = parent;
		this.pos = pos;
	}

	@Override
	protected void init() {
		int centerX = this.width / 2;

		this.nameField = new EditBox(this.font, centerX - 100, 40, 200, 20, Component.translatable("gui.bubbleshield.whitelist"));
		this.nameField.setMaxLength(16);
		this.addRenderableWidget(this.nameField);

		this.addRenderableWidget(
			Button.builder(Component.translatable("gui.bubbleshield.add"), button -> this.sendModify(true))
				.bounds(centerX - 100, 64, 98, 20)
				.build()
		);
		this.addRenderableWidget(
			Button.builder(Component.translatable("gui.bubbleshield.remove"), button -> this.sendModify(false))
				.bounds(centerX + 2, 64, 98, 20)
				.build()
		);
		this.addRenderableWidget(
			Button.builder(Component.translatable("gui.bubbleshield.back"), button -> this.onClose())
				.bounds(centerX - 75, this.height - 28, 150, 20)
				.build()
		);
	}

	private void sendModify(boolean add) {
		String name = this.nameField.getValue().trim();
		if (!name.isEmpty()) {
			ClientPlayNetworking.send(new ShieldPayloads.WhitelistModifyC2S(this.pos, name, add));
			this.nameField.setValue("");
		}
	}

	private List<String> whitelistNames() {
		ClientShieldManager.ClientShield shield = ClientShieldManager.shields().get(this.pos);
		return shield != null ? shield.whitelistNames() : List.of();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int centerX = this.width / 2;
		graphics.text(this.font, this.title, centerX - this.font.width(this.title) / 2, 16, -1, true);

		List<String> names = this.whitelistNames().stream().sorted().toList();
		int y = LIST_TOP;
		for (String name : names.subList(0, Math.min(names.size(), MAX_VISIBLE_NAMES))) {
			graphics.text(this.font, name, centerX - this.font.width(name) / 2, y, -1, true);
			y += LINE_HEIGHT;
		}

		if (names.size() > MAX_VISIBLE_NAMES) {
			String more = "+" + (names.size() - MAX_VISIBLE_NAMES);
			graphics.text(this.font, more, centerX - this.font.width(more) / 2, y, -1, true);
		}
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}
}

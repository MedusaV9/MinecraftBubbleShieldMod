package com.bubbleshield.client.fx;

import com.bubbleshield.BubbleShield;
import com.bubbleshield.client.ClientShieldManager;
import com.bubbleshield.client.mixin.GameRendererInvoker;
import com.bubbleshield.effect.EffectRegistry;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * Applies the per-effect full-screen post chain ({@code bubbleshield:effect_NN}) while the
 * local player stands inside an active shield, and clears it again on the way out.
 *
 * <p>Runs on {@code ClientTickEvents.END_CLIENT_TICK} and only touches the game renderer
 * when the camera entity is the player itself, so it never fights vanilla's entity post
 * effects (creeper/spider/enderman spectator shaders driven by
 * {@code GameRenderer#checkEntityPostEffect}).
 *
 * <p>{@code GameRenderer#currentPostEffect()} is the source of truth for what is applied:
 * static tracking alone goes stale when vanilla swaps the effect underneath us (e.g. a
 * spectator round trip calls {@code checkEntityPostEffect} which clears the slot), which
 * used to permanently disable the in-bubble effect. The effect is (re-)applied whenever
 * the desired id differs from the current one, and only cleared when the current effect
 * is ours, so a vanilla or other-mod effect is never clobbered.
 */
public final class ScreenEffectManager {
	private ScreenEffectManager() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(ScreenEffectManager::tick);
	}

	private static void tick(Minecraft mc) {
		if (mc.level == null || mc.player == null || mc.getCameraEntity() != mc.player) {
			// Never fight vanilla's camera-entity shaders (spectating a creeper/spider/enderman);
			// vanilla owns the post-effect slot until the camera returns to the player.
			return;
		}

		Identifier current = mc.gameRenderer.currentPostEffect();
		// Shape-aware containment shared with the HUD element.
		ClientShieldManager.ClientShield shield = ClientShieldManager.findSurroundingShield(mc);
		if (shield != null) {
			Identifier id = Identifier.fromNamespaceAndPath(BubbleShield.MOD_ID, EffectRegistry.get(shield.effectId()).screenEffectName());
			if (!id.equals(current)) {
				((GameRendererInvoker) mc.gameRenderer).bubbleshield$setPostEffect(id);
			}
		} else if (current != null && current.getNamespace().equals(BubbleShield.MOD_ID)) {
			// Only clear the slot when the applied effect is ours.
			mc.gameRenderer.clearPostEffect();
		}
	}
}

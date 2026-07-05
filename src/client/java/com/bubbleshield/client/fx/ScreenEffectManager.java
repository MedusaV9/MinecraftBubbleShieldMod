package com.bubbleshield.client.fx;

import com.bubbleshield.client.ClientShieldManager;
import com.bubbleshield.client.mixin.GameRendererInvoker;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.EffectRegistry;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * Applies the per-effect full-screen post chain ({@code bubbleshield:effect_NN}) while the
 * local player stands inside an active shield, and clears it again on the way out.
 *
 * <p>Runs on {@code ClientTickEvents.END_CLIENT_TICK} and only touches the game renderer
 * when the camera entity is the player itself, so it never fights vanilla's entity post
 * effects (creeper/spider/enderman spectator shaders driven by
 * {@code GameRenderer#checkEntityPostEffect}).
 */
public final class ScreenEffectManager {
	/** The post-effect id we applied, or {@code null} when we have not applied one. */
	private static Identifier appliedId;

	private ScreenEffectManager() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(ScreenEffectManager::tick);
	}

	private static void tick(Minecraft mc) {
		if (mc.level == null || mc.player == null || mc.getCameraEntity() != mc.player) {
			// Never fight vanilla's camera-entity shaders (spectating a creeper/spider/enderman).
			// If we had applied an effect, leave it: vanilla's checkEntityPostEffect already
			// replaced it, and we would clobber the creeper shader by clearing here.
			if (mc.level == null && appliedId != null) {
				appliedId = null;
			}

			return;
		}

		EffectDefinition def = findSurroundingShieldEffect(mc);
		if (def != null) {
			Identifier id = Identifier.fromNamespaceAndPath("bubbleshield", def.screenEffectName());
			if (!id.equals(appliedId)) {
				((GameRendererInvoker) mc.gameRenderer).bubbleshield$setPostEffect(id);
				appliedId = id;
			}
		} else if (appliedId != null) {
			mc.gameRenderer.clearPostEffect();
			appliedId = null;
		}
	}

	/** The effect of the first active shield whose bubble contains the player, or {@code null}. */
	private static EffectDefinition findSurroundingShieldEffect(Minecraft mc) {
		Vec3 playerPos = mc.player.position();
		for (ClientShieldManager.ClientShield shield : ClientShieldManager.shields().values()) {
			if (!shield.active()) {
				continue;
			}

			Vec3 center = Vec3.atCenterOf(shield.pos());
			if (playerPos.distanceTo(center) < shield.currentRadius()) {
				return EffectRegistry.get(shield.effectId());
			}
		}

		return null;
	}
}

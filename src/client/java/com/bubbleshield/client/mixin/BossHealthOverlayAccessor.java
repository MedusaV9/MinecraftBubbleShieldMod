package com.bubbleshield.client.mixin;

import java.util.Map;
import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;

/**
 * Exposes {@code BossHealthOverlay#events} (private, no public size/iteration accessor
 * in 26.2) so {@link com.bubbleshield.client.hud.ShieldHudElement} can place its tier
 * line directly below the vanilla boss-bar stack instead of overlapping it.
 */
@Mixin(BossHealthOverlay.class)
public interface BossHealthOverlayAccessor {
	@Accessor("events")
	Map<UUID, LerpingBossEvent> bubbleshield$events();
}

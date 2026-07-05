package com.bubbleshield.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;

/**
 * Exposes {@code GameRenderer#setPostEffect(Identifier)} (private in 26.2; the public
 * surface is only {@code checkEntityPostEffect}/{@code clearPostEffect}) so the shield
 * screen-effect layer can apply a custom post chain while the player stands inside a bubble.
 */
@Mixin(GameRenderer.class)
public interface GameRendererInvoker {
	@Invoker("setPostEffect")
	void bubbleshield$setPostEffect(Identifier id);
}

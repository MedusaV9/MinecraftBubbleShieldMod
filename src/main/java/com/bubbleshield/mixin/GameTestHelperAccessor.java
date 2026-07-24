package com.bubbleshield.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;

/**
 * Exposes {@code GameTestHelper#testInfo} (private, no public accessor in 26.2) so
 * {@link com.bubbleshield.gametest.MockPlayers} can register a {@code GameTestListener}
 * on the running test and sweep its mock players out of the PlayerList on EVERY test
 * outcome (passed, failed, timed out) — not just on the paths a try/finally can reach.
 */
@Mixin(GameTestHelper.class)
public interface GameTestHelperAccessor {
	@Accessor("testInfo")
	GameTestInfo bubbleshield$testInfo();
}

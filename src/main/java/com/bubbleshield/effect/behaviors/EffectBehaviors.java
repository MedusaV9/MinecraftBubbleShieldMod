package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.InsideEffectBehavior;

/**
 * Registers the ten built-in {@link InsideEffectBehavior} implementations.
 */
public final class EffectBehaviors {
	private EffectBehaviors() {
	}

	/** Idempotent: safe to call more than once (e.g. from tests and mod init). */
	public static void registerAll() {
		if (!InsideEffectBehavior.REGISTRY.isEmpty()) {
			return;
		}

		InsideEffectBehavior.register(ParticleDome.ID, new ParticleDome());
		InsideEffectBehavior.register(ParticleSpiral.ID, new ParticleSpiral());
		InsideEffectBehavior.register(RegenAura.ID, new RegenAura());
		InsideEffectBehavior.register(SpeedAura.ID, new SpeedAura());
		InsideEffectBehavior.register(SlowHostiles.ID, new SlowHostiles());
		InsideEffectBehavior.register(EmberRain.ID, new EmberRain());
		InsideEffectBehavior.register(Snowfall.ID, new Snowfall());
		InsideEffectBehavior.register(FireflySwarm.ID, new FireflySwarm());
		InsideEffectBehavior.register(HeartbeatPulse.ID, new HeartbeatPulse());
		InsideEffectBehavior.register(MistLayer.ID, new MistLayer());
	}
}

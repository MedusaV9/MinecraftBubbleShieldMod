package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.InsideEffectBehavior;

/**
 * Registers the 25 built-in {@link InsideEffectBehavior} implementations.
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
		InsideEffectBehavior.register(OrbitingShards.ID, new OrbitingShards());
		InsideEffectBehavior.register(RisingSouls.ID, new RisingSouls());
		InsideEffectBehavior.register(FallingPetals.ID, new FallingPetals());
		InsideEffectBehavior.register(BubbleVeil.ID, new BubbleVeil());
		InsideEffectBehavior.register(MusicPulse.ID, new MusicPulse());
		InsideEffectBehavior.register(StaticField.ID, new StaticField());
		InsideEffectBehavior.register(MeteorBurst.ID, new MeteorBurst());
		InsideEffectBehavior.register(SporeDrift.ID, new SporeDrift());
		InsideEffectBehavior.register(EnchantStream.ID, new EnchantStream());
		InsideEffectBehavior.register(HasteAura.ID, new HasteAura());
		InsideEffectBehavior.register(ResistAura.ID, new ResistAura());
		InsideEffectBehavior.register(NightGlowAura.ID, new NightGlowAura());
		InsideEffectBehavior.register(FireWard.ID, new FireWard());
		InsideEffectBehavior.register(FrostIntruders.ID, new FrostIntruders());
		InsideEffectBehavior.register(PurgePulse.ID, new PurgePulse());
	}
}

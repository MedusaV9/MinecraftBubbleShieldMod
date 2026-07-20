package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.InsideEffectBehavior;

/**
 * Registers the 50 built-in {@link InsideEffectBehavior} implementations
 * (35 legacy behaviors used by the current 105-effect catalogue plus 15 new
 * behaviors staged for the 350-effect milestone).
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
		InsideEffectBehavior.register(LeapAura.ID, new LeapAura());
		InsideEffectBehavior.register(TideAura.ID, new TideAura());
		InsideEffectBehavior.register(EmberGuard.ID, new EmberGuard());
		InsideEffectBehavior.register(LuckyCharm.ID, new LuckyCharm());
		InsideEffectBehavior.register(EchoPulse.ID, new EchoPulse());
		InsideEffectBehavior.register(PrismaticRays.ID, new PrismaticRays());
		InsideEffectBehavior.register(VoidTendrils.ID, new VoidTendrils());
		InsideEffectBehavior.register(HoneyDrip.ID, new HoneyDrip());
		InsideEffectBehavior.register(WaxGlow.ID, new WaxGlow());
		InsideEffectBehavior.register(StormCage.ID, new StormCage());
		InsideEffectBehavior.register(GravityWells.ID, new GravityWells());
		InsideEffectBehavior.register(AuroraRibbons.ID, new AuroraRibbons());
		InsideEffectBehavior.register(SandDevils.ID, new SandDevils());
		InsideEffectBehavior.register(GlassShards.ID, new GlassShards());
		InsideEffectBehavior.register(MothSwarm.ID, new MothSwarm());
		InsideEffectBehavior.register(RuneOrbit.ID, new RuneOrbit());
		InsideEffectBehavior.register(DripStalactite.ID, new DripStalactite());
		InsideEffectBehavior.register(GeyserVents.ID, new GeyserVents());
		InsideEffectBehavior.register(StaticOrbs.ID, new StaticOrbs());
		InsideEffectBehavior.register(ShadowVeil.ID, new ShadowVeil());
		InsideEffectBehavior.register(PrismBeams.ID, new PrismBeams());
		InsideEffectBehavior.register(PollenHaze.ID, new PollenHaze());
		InsideEffectBehavior.register(TidePools.ID, new TidePools());
		InsideEffectBehavior.register(EmberSpiral.ID, new EmberSpiral());
		InsideEffectBehavior.register(CometTails.ID, new CometTails());
	}
}

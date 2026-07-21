package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.InsideEffectBehavior;

/**
 * Registers the 120 built-in {@link InsideEffectBehavior} implementations, each
 * used by the 840-effect catalogue exactly 7 times (variants 0..6 -- an exact
 * 120 x 7 cover enforced by
 * {@link com.bubbleshield.effect.EffectRegistry#validate()}).
 */
public final class EffectBehaviors {
	private EffectBehaviors() {
	}

	/**
	 * Idempotent: safe to call more than once (e.g. from tests and mod init).
	 * All-or-nothing by construction -- the guard only skips when the registry is
	 * non-empty, and a single call always registers the full set, so a partial
	 * registry can never survive this method.
	 */
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

		// The 10 ghost/apparition behaviors, covered by the 420-milestone rows
		// 350..419 (variants 0..6 each).
		InsideEffectBehavior.register(VexWisps.ID, new VexWisps());
		InsideEffectBehavior.register(SoulProcession.ID, new SoulProcession());
		InsideEffectBehavior.register(PhantomFlock.ID, new PhantomFlock());
		InsideEffectBehavior.register(SonicGhosts.ID, new SonicGhosts());
		InsideEffectBehavior.register(EnderWatchers.ID, new EnderWatchers());
		InsideEffectBehavior.register(WanderingSpirits.ID, new WanderingSpirits());
		InsideEffectBehavior.register(GraveyardMist.ID, new GraveyardMist());
		InsideEffectBehavior.register(SpectralShoal.ID, new SpectralShoal());
		InsideEffectBehavior.register(WraithOrbs.ID, new WraithOrbs());
		InsideEffectBehavior.register(SeanceCircle.ID, new SeanceCircle());

		// The 60 behaviors added by the 840 milestone, covered by rows 420..839
		// (variants 0..6 each; row assignment order = this append order).
		InsideEffectBehavior.register(BansheeWails.ID, new BansheeWails());
		InsideEffectBehavior.register(GhostRiders.ID, new GhostRiders());
		InsideEffectBehavior.register(SpiritLanterns.ID, new SpiritLanterns());
		InsideEffectBehavior.register(HauntedPortraits.ID, new HauntedPortraits());
		InsideEffectBehavior.register(PoltergeistToss.ID, new PoltergeistToss());
		InsideEffectBehavior.register(WailingChoir.ID, new WailingChoir());
		InsideEffectBehavior.register(GraveHands.ID, new GraveHands());
		InsideEffectBehavior.register(EctoMistMaze.ID, new EctoMistMaze());
		InsideEffectBehavior.register(PhantomBells.ID, new PhantomBells());
		InsideEffectBehavior.register(SeanceTable.ID, new SeanceTable());
		InsideEffectBehavior.register(GhostWolves.ID, new GhostWolves());
		InsideEffectBehavior.register(SpectralStag.ID, new SpectralStag());
		InsideEffectBehavior.register(WispOwls.ID, new WispOwls());
		InsideEffectBehavior.register(BoneFish.ID, new BoneFish());
		InsideEffectBehavior.register(CarrionCrows.ID, new CarrionCrows());
		InsideEffectBehavior.register(StyxFerry.ID, new StyxFerry());
		InsideEffectBehavior.register(SoulWells.ID, new SoulWells());
		InsideEffectBehavior.register(ChainedSpecters.ID, new ChainedSpecters());
		InsideEffectBehavior.register(ReaperScythe.ID, new ReaperScythe());
		InsideEffectBehavior.register(PurgatoryQueue.ID, new PurgatoryQueue());
		InsideEffectBehavior.register(SpiritRain.ID, new SpiritRain());
		InsideEffectBehavior.register(EctoFogBanks.ID, new EctoFogBanks());
		InsideEffectBehavior.register(AuroraGhosts.ID, new AuroraGhosts());
		InsideEffectBehavior.register(StaticHaunt.ID, new StaticHaunt());
		InsideEffectBehavior.register(MoonbeamShafts.ID, new MoonbeamShafts());
		InsideEffectBehavior.register(CreeperEffigies.ID, new CreeperEffigies());
		InsideEffectBehavior.register(EndermanStalkers.ID, new EndermanStalkers());
		InsideEffectBehavior.register(SkeletonArmy.ID, new SkeletonArmy());
		InsideEffectBehavior.register(SlimeGhosts.ID, new SlimeGhosts());
		InsideEffectBehavior.register(DrownedProcession.ID, new DrownedProcession());
		InsideEffectBehavior.register(ConstellationWheel.ID, new ConstellationWheel());
		InsideEffectBehavior.register(CometOrrery.ID, new CometOrrery());
		InsideEffectBehavior.register(EclipseDisc.ID, new EclipseDisc());
		InsideEffectBehavior.register(MeteorShowerVeil.ID, new MeteorShowerVeil());
		InsideEffectBehavior.register(ZodiacBeams.ID, new ZodiacBeams());
		InsideEffectBehavior.register(DryadBloom.ID, new DryadBloom());
		InsideEffectBehavior.register(MushroomRingSprites.ID, new MushroomRingSprites());
		InsideEffectBehavior.register(PollenElementals.ID, new PollenElementals());
		InsideEffectBehavior.register(VineSerpents.ID, new VineSerpents());
		InsideEffectBehavior.register(SeasonsWheel.ID, new SeasonsWheel());
		InsideEffectBehavior.register(ClockworkGears.ID, new ClockworkGears());
		InsideEffectBehavior.register(RuneForge.ID, new RuneForge());
		InsideEffectBehavior.register(AlchemyCircles.ID, new AlchemyCircles());
		InsideEffectBehavior.register(MirrorMaze.ID, new MirrorMaze());
		InsideEffectBehavior.register(ArcaneTurbines.ID, new ArcaneTurbines());
		InsideEffectBehavior.register(AbyssalJellies.ID, new AbyssalJellies());
		InsideEffectBehavior.register(VoidRiftsInside.ID, new VoidRiftsInside());
		InsideEffectBehavior.register(LeviathanShadow.ID, new LeviathanShadow());
		InsideEffectBehavior.register(AnglerfishLures.ID, new AnglerfishLures());
		InsideEffectBehavior.register(SingularityHeart.ID, new SingularityHeart());
		InsideEffectBehavior.register(LanternFestival.ID, new LanternFestival());
		InsideEffectBehavior.register(FireworkRegatta.ID, new FireworkRegatta());
		InsideEffectBehavior.register(GhostMasquerade.ID, new GhostMasquerade());
		InsideEffectBehavior.register(DrumlineGolems.ID, new DrumlineGolems());
		InsideEffectBehavior.register(ChimeCurtains.ID, new ChimeCurtains());
		InsideEffectBehavior.register(SentinelTotems.ID, new SentinelTotems());
		InsideEffectBehavior.register(ValkyriePatrol.ID, new ValkyriePatrol());
		InsideEffectBehavior.register(ShieldMaidens.ID, new ShieldMaidens());
		InsideEffectBehavior.register(CerberusWatch.ID, new CerberusWatch());
		InsideEffectBehavior.register(GeniePlumes.ID, new GeniePlumes());
	}
}

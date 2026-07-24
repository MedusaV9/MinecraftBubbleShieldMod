package com.bubbleshield.block;

import com.bubbleshield.advancements.ModCriteria;
import com.bubbleshield.effect.EffectRegistry;
import com.bubbleshield.menu.BubbleShieldMenu;
import com.bubbleshield.net.ServerNet;
import com.bubbleshield.net.ShieldPayloads;
import com.bubbleshield.registry.ModBlockEntities;
import com.bubbleshield.registry.ModGameRules;
import com.bubbleshield.registry.ModItems;
import com.bubbleshield.registry.ModTicketTypes;
import com.bubbleshield.shield.BeamStyle;
import com.bubbleshield.shield.FuelMap;
import com.bubbleshield.shield.ShieldGeometry;
import com.bubbleshield.shield.ShieldLinking;
import com.bubbleshield.shield.ShieldLogic;
import com.bubbleshield.shield.ShieldMode;
import com.bubbleshield.shield.ShieldShape;
import com.bubbleshield.shield.ShieldState;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.mojang.serialization.Codec;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

public class BubbleShieldBlockEntity extends BlockEntity implements ExtendedMenuProvider<BlockPos> {
	/** An active linked shield redraws its resonance tether once per this many ticks. */
	public static final int LINK_TETHER_PERIOD_TICKS = 20;
	/** Particles per tether segment (interior interpolation points, endpoints excluded). */
	public static final int LINK_TETHER_PARTICLES = 8;

	private final ShieldState shieldState = new ShieldState();
	/** One-slot fuel inventory shown in the projector menu; consumed into fuel-seconds each tick. */
	private final SimpleContainer fuelContainer = this.deviceContainer();
	/** One-slot upgrade-core inventory; its content derives the shield tier (see {@link #tier()}). */
	private final SimpleContainer coreContainer = this.deviceContainer();
	/** One-slot flux-capacitor inventory; its content drives {@link #hasCapacitor()}. */
	private final SimpleContainer capacitorContainer = this.deviceContainer();
	/**
	 * One-slot augment (defense module) inventory; its content drives
	 * {@link #hasPlating()} / {@link #hasBlastWard()}. Single slot by design:
	 * exactly ONE module fits, so plating vs blast ward is an either/or choice.
	 */
	private final SimpleContainer augmentContainer = this.deviceContainer();
	/**
	 * Inputs applied by the last {@link #refreshMaxHealth()} pass (tier, target radius,
	 * strength percent); {@code lastTier = -1} forces a recompute on the first tick,
	 * including the first tick after load (see {@code loadAdditional}).
	 */
	private int lastTier = -1;
	private float lastTargetRadius = Float.NaN;
	private int lastStrengthPercent = -1;
	/** Last observed redstone level; persisted so chunk reloads do not fake an edge. */
	private boolean powered;
	/**
	 * Whether {@link #powered} reflects an actual observation (placement seed, NBT load
	 * or a neighbor update). A freshly placed projector seeds from the live neighbor
	 * signal so a pre-existing steady level is never misread as a rising edge.
	 */
	private boolean poweredInitialized;
	/**
	 * Set on NBT load: the first server tick caps the remaining break cooldown at
	 * the maximum possible break cooldown. ShieldState.load already forces
	 * cooldown_until &gt;= 0, but it has no game time, so an absurd future value
	 * edited into the NBT (which would lock the projector for in-game years) can
	 * only be bounded here, where the level clock is available.
	 */
	private boolean capLoadedCooldown;
	/**
	 * Live server-side snapshot for the menu; values in SECONDS (data slots sync
	 * 16-bit signed, so every case below clamps into [0, Short.MAX_VALUE]).
	 */
	private final ContainerData menuData = new ContainerData() {
		@Override
		public int get(int dataId) {
			ShieldState state = BubbleShieldBlockEntity.this.shieldState;
			return switch (dataId) {
				case BubbleShieldMenu.DATA_FUEL_SECONDS -> Mth.clamp(state.fuelSeconds, 0, Short.MAX_VALUE);
				// Permille of max health (0..1000): the old health*10 encoding
				// overflowed the 16-bit slot above 3276.7 HP.
				case BubbleShieldMenu.DATA_HEALTH_PERMILLE -> state.maxHealth > 0.0F
						? Mth.clamp(Math.round(1000.0F * state.health / state.maxHealth), 0, 1000)
						: 0;
				case BubbleShieldMenu.DATA_DIAMETER -> Mth.clamp(Math.round(state.targetRadius * 2.0F), 0, Short.MAX_VALUE);
				case BubbleShieldMenu.DATA_EFFECT_ID -> state.effectId;
				case BubbleShieldMenu.DATA_ACTIVE -> state.active ? 1 : 0;
				case BubbleShieldMenu.DATA_COOLDOWN_SECONDS -> (int) Math.min(Short.MAX_VALUE, BubbleShieldBlockEntity.this.cooldownTicksLeft() / ShieldLogic.TICKS_PER_FUEL_SECOND);
				case BubbleShieldMenu.DATA_TIER -> BubbleShieldBlockEntity.this.tier();
				case BubbleShieldMenu.DATA_SHAPE -> state.shape.ordinal();
				case BubbleShieldMenu.DATA_MODE -> state.mode.ordinal();
				case BubbleShieldMenu.DATA_CYCLE -> state.cycleEffect ? 1 : 0;
				case BubbleShieldMenu.DATA_CAPACITOR -> BubbleShieldBlockEntity.this.hasCapacitor() ? 1 : 0;
				case BubbleShieldMenu.DATA_BEAM -> state.beamStyle.ordinal();
				case BubbleShieldMenu.DATA_MAX_HEALTH -> Mth.clamp(Math.round(state.maxHealth), 0, Short.MAX_VALUE);
				case BubbleShieldMenu.DATA_REGEN_PER_MIN_X10 -> BubbleShieldBlockEntity.this.regenPerMinuteTimes10();
				case BubbleShieldMenu.DATA_DRAIN_PER_MIN_X10 -> BubbleShieldBlockEntity.this.drainPerMinuteTimes10();
				case BubbleShieldMenu.DATA_WHITELIST_COUNT -> Mth.clamp(state.whitelistNames.size(), 0, ShieldState.MAX_WHITELIST_SIZE);
				case BubbleShieldMenu.DATA_STRENGTH_PERCENT -> BubbleShieldBlockEntity.this.strengthPercent();
				// B6: the once-per-second threat census (0 while inactive).
				case BubbleShieldMenu.DATA_THREAT_COUNT -> Mth.clamp(BubbleShieldBlockEntity.this.threatCount, 0, Short.MAX_VALUE);
				default -> 0;
			};
		}

		@Override
		public void set(int dataId, int value) {
			// Server-authoritative; clients change settings via payloads, not data slots.
		}

		@Override
		public int getCount() {
			return BubbleShieldMenu.DATA_COUNT;
		}
	};

	/** Last replicated snapshot; a new sync payload is only broadcast when this changes. */
	private @Nullable ReplicatedState lastSentState;

	/**
	 * D7c: set by {@link #markUpdated} on every replicable mutation and drained by
	 * {@link #flushSync()} at the end of this shield's {@link #serverTick}, so no
	 * matter how many mutations land in one tick, at most ONE ShieldSyncS2C
	 * broadcast (plus one block-entity data update) goes out per shield per tick.
	 * Mutations arriving outside the tick (e.g. C2S handlers) flush on the next
	 * serverTick — at most one tick of added latency.
	 */
	private boolean syncDirty;

	/**
	 * D5: true while this projector holds a {@link ModTicketTypes#SHIELD_PROJECTOR}
	 * chunk ticket (armed on activation, re-armed each active tick, released on
	 * deactivate/break/removal). Transient runtime state, never persisted: the
	 * ticket type is non-persistent and times out on its own if every release path
	 * is missed (e.g. an unclean shutdown).
	 */
	private boolean chunkTicketArmed;

	/**
	 * D7b: per-tick memo for {@link #linkedShields}. {@code linkedCacheTime} is the
	 * game time the cached list was resolved at; a lookup on a later tick recomputes.
	 */
	private long linkedCacheTime = Long.MIN_VALUE;
	private @Nullable List<BubbleShieldBlockEntity> linkedCache;

	/**
	 * Boss bar shown to every player inside the active shield. Transient runtime state
	 * (like {@link #lastSentState}): never persisted, lazily created on the first
	 * active server tick and emptied on deactivation/removal.
	 */
	private @Nullable ServerBossEvent bossEvent;

	/**
	 * B6: the last threat census result (non-whitelisted players + hostile
	 * monsters within radius + 8; see {@link ShieldLogic#countThreats}), updated
	 * once per second by the active tick and exposed through
	 * {@code DATA_THREAT_COUNT}. Transient runtime state, never persisted; reset
	 * to 0 whenever the shield is not active. The 0-to-positive edge between two
	 * censuses is one of the two siege-alarm triggers.
	 */
	private int threatCount;

	/**
	 * B6: whether the last {@link #serverTick} observed an OPEN alarm window.
	 * Only used to refresh the comparator when the window expires — the alarm
	 * TRIGGER already refreshes through {@link #markUpdated}, but the expiry is
	 * pure clock passage that no mutation path would otherwise notice.
	 */
	private boolean lastAlarmed;

	public BubbleShieldBlockEntity(BlockPos worldPosition, BlockState blockState) {
		super(ModBlockEntities.BUBBLE_SHIELD_PROJECTOR, worldPosition, blockState);
	}

	/**
	 * A one-slot device inventory (fuel/core/capacitor) that marks this block entity
	 * dirty on every content change. {@link SimpleContainer#setChanged()} is a no-op
	 * in 26.2, so without this hook a menu-driven insert/remove would never flag the
	 * chunk unsaved while the projector is otherwise idle — a capacitor could vanish
	 * on reload or be duplicated from a stale save. {@link BlockEntity#setChanged()}
	 * no-ops while {@code level == null} (i.e. during load), so
	 * {@code fromItemList}'s {@code clearContent} stays safe.
	 */
	private SimpleContainer deviceContainer() {
		return new SimpleContainer(1) {
			@Override
			public void setChanged() {
				super.setChanged();
				BubbleShieldBlockEntity.this.setChanged();
			}
		};
	}

	public ShieldState getShieldState() {
		return this.shieldState;
	}

	public SimpleContainer getFuelContainer() {
		return this.fuelContainer;
	}

	public SimpleContainer getCoreContainer() {
		return this.coreContainer;
	}

	public SimpleContainer getCapacitorContainer() {
		return this.capacitorContainer;
	}

	public SimpleContainer getAugmentContainer() {
		return this.augmentContainer;
	}

	/**
	 * @return true while a flux capacitor sits in the capacitor slot: the active shield's
	 * passive drain halves and regeneration pulses no longer burn the extra fuel-second
	 * (see {@link ShieldLogic#serverTick}).
	 */
	public boolean hasCapacitor() {
		return this.capacitorContainer.getItem(0).is(ModItems.FLUX_CAPACITOR);
	}

	/**
	 * @return true while reinforced plating sits in the augment slot: every shield
	 * hit gains {@link ShieldLogic#PLATING_DR} extra damage resistance, stacking
	 * multiplicatively with the tier DR under the 70% combined cap
	 * (see {@link ShieldLogic#appliedDamage}).
	 */
	public boolean hasPlating() {
		return this.augmentContainer.getItem(0).is(ModItems.REINFORCED_PLATING);
	}

	/**
	 * @return true while a blast ward sits in the augment slot: intercepted EXPLOSIVE
	 * projectiles (fireballs, wither skulls, wind charges) deal 60% less shield
	 * damage, applied to the RAW damage before the DR pipeline
	 * (see {@link ShieldLogic#blastWardedDamage}).
	 */
	public boolean hasBlastWard() {
		return this.augmentContainer.getItem(0).is(ModItems.BLAST_WARD);
	}

	/** The plating damage resistance fed into {@link ShieldLogic#appliedDamage}: 0.30 when socketed, else 0. */
	public float platingDr() {
		return this.hasPlating() ? ShieldLogic.PLATING_DR : 0.0F;
	}

	/**
	 * @return the shield tier derived from the upgrade-core slot: 0 without a core,
	 * 1 with a resonant core, 2 with a prismatic core, 3 with an aegis core.
	 */
	public int tier() {
		ItemStack core = this.coreContainer.getItem(0);
		if (core.is(ModItems.AEGIS_CORE)) {
			return 3;
		}

		if (core.is(ModItems.PRISMATIC_CORE)) {
			return 2;
		}

		return core.is(ModItems.RESONANT_CORE) ? 1 : 0;
	}

	/**
	 * The {@code bubbleshield:strength} gamerule as a percent, clamped into
	 * [{@link ModGameRules#MIN_STRENGTH_PERCENT}, {@link ModGameRules#MAX_STRENGTH_PERCENT}]
	 * on read (defense in depth; the rule's own range already enforces it). Falls back
	 * to the default off-server (client menus never query this authoritatively).
	 */
	public int strengthPercent() {
		if (this.level instanceof ServerLevel serverLevel) {
			return Mth.clamp(serverLevel.getGameRules().get(ModGameRules.STRENGTH),
					ModGameRules.MIN_STRENGTH_PERCENT, ModGameRules.MAX_STRENGTH_PERCENT);
		}

		return ModGameRules.DEFAULT_STRENGTH_PERCENT;
	}

	public ContainerData getMenuData() {
		return this.menuData;
	}

	/** @return the last redstone level observed by {@link #setNeighborPowered}. */
	public boolean isPowered() {
		return this.powered;
	}

	private long cooldownTicksLeft() {
		long gameTime = this.level != null ? this.level.getGameTime() : 0L;
		return Math.max(0L, this.shieldState.cooldownUntil - gameTime);
	}

	/**
	 * Current regeneration rate for the menu's {@code DATA_REGEN_PER_MIN_X10} slot:
	 * HP per minute x10 from the tier regen table (one {@link ShieldLogic#regenPerPulse}
	 * pulse per {@link ShieldLogic#REGEN_PERIOD_TICKS}, x3 out of combat for tiers 1-3),
	 * 0 while inactive, in ECO mode (which suppresses regen) or for a tier-0 shield in
	 * combat (whose regen is combat-gated away entirely). The linked x1.25 bonus is
	 * intentionally not counted: this is the shield's own baseline rate.
	 */
	private int regenPerMinuteTimes10() {
		ShieldState state = this.shieldState;
		int tier = this.tier();
		if (!state.active || state.mode == ShieldMode.ECO) {
			return 0;
		}

		long gameTime = this.level != null ? this.level.getGameTime() : 0L;
		boolean inCombat = ShieldLogic.inCombat(state, gameTime);
		if (tier == 0 && inCombat) {
			return 0;
		}

		float perPulse = ShieldLogic.regenPerPulse(tier);
		if (tier >= 1 && !inCombat) {
			perPulse *= ShieldLogic.OUT_OF_COMBAT_REGEN_MULTIPLIER;
		}

		int pulsesPerMinute = 1200 / ShieldLogic.REGEN_PERIOD_TICKS;
		return Mth.clamp(Math.round(perPulse * pulsesPerMinute * 10.0F), 0, Short.MAX_VALUE);
	}

	/**
	 * Current passive fuel drain for the menu's {@code DATA_DRAIN_PER_MIN_X10} slot:
	 * fuel-seconds per minute x10 — {@link ShieldLogic#drainUnits} (diameter-scaled,
	 * 1..4) per {@link ShieldLogic#drainIntervalTicks} interval (including the
	 * ECO/capacitor modifiers) — 0 while inactive. Regen/pulse surcharges are
	 * intentionally not counted: this is the steady baseline rate.
	 */
	private int drainPerMinuteTimes10() {
		ShieldState state = this.shieldState;
		if (!state.active) {
			return 0;
		}

		// drainUnits fuel-seconds per interval ticks -> units * (1200 / interval) per minute, x10.
		int interval = ShieldLogic.drainIntervalTicks(state.mode == ShieldMode.ECO, this.hasCapacitor());
		return Mth.clamp(12000 * ShieldLogic.drainUnits(state.targetRadius) / interval, 0, Short.MAX_VALUE);
	}

	public float currentRadius() {
		return ShieldLogic.currentRadius(this.shieldState);
	}

	/**
	 * Runs one server tick of shield logic. Invoked by the block ticker on the server only.
	 */
	public void serverTick() {
		// Covers placements that bypass setPlacedBy (e.g. /setblock, structures).
		if (!this.poweredInitialized && this.level != null) {
			this.seedPowered(this.level.hasNeighborSignal(this.worldPosition));
		}

		this.consumeFuelSlot();
		this.refreshMaxHealth();
		if (this.level instanceof ServerLevel serverLevel) {
			// Load hardening: cap a (possibly tampered) remaining cooldown at the
			// maximum possible break cooldown; breakCooldownTicks(0) is the longest
			// in the by-tier table (higher tiers only shorten it).
			if (this.capLoadedCooldown) {
				this.capLoadedCooldown = false;
				long maxCooldownUntil = serverLevel.getGameTime() + ShieldLogic.breakCooldownTicks(0);
				if (this.shieldState.cooldownUntil > maxCooldownUntil) {
					this.shieldState.cooldownUntil = maxCooldownUntil;
					this.markUpdated();
				}

				// B6: same hardening for alarm_until — a tampered far-future value
				// would otherwise pin the comparator at 15 (and the boss-bar suffix
				// on) indefinitely.
				long maxAlarmUntil = serverLevel.getGameTime() + ShieldLogic.ALARM_WINDOW_TICKS;
				if (this.shieldState.alarmUntilGameTime > maxAlarmUntil) {
					this.shieldState.alarmUntilGameTime = maxAlarmUntil;
					this.markUpdated();
				}
			}

			// ShieldLogic flips state.active directly on fuel-out and break-by-damage;
			// snapshot the flag so those paths stay sculk-audible like setActive(false).
			boolean wasActive = this.shieldState.active;
			if (ShieldLogic.serverTick(serverLevel, this.worldPosition, this.shieldState, this.tier(), this.hasCapacitor(), this)) {
				this.markUpdated();
			}

			if (wasActive && !this.shieldState.active) {
				serverLevel.gameEvent(GameEvent.BLOCK_DEACTIVATE, this.worldPosition, GameEvent.Context.of(this.getBlockState()));
			}

			// B6: the census only runs while active; a stale count must not linger
			// in the menu's threat slot after any deactivation path.
			if (!this.shieldState.active) {
				this.threatCount = 0;
			}

			// B6: refresh the comparator when the alarm window EXPIRES. The trigger
			// side already refreshes through markUpdated; the expiry is pure clock
			// passage that no mutation path would otherwise notice, leaving a stale
			// 15 on the comparator until the next unrelated update.
			boolean alarmed = this.shieldState.isAlarmed(serverLevel.getGameTime());
			if (alarmed != this.lastAlarmed) {
				this.lastAlarmed = alarmed;
				serverLevel.updateNeighbourForOutputSignal(this.worldPosition, this.getBlockState().getBlock());
			}

			// Runs even when the shield logic reported no change: deactivations from any
			// path (fuel-out, break, GUI, redstone) must empty the boss bar next tick.
			this.updateBossBar(serverLevel);

			if (this.shieldState.active && serverLevel.getGameTime() % LINK_TETHER_PERIOD_TICKS == 0L) {
				this.sendLinkTethers(serverLevel);
			}

			// D5: keep the projector chunk ticking while active (release when not).
			this.updateChunkTicket(serverLevel);
			// D7c: at most one sync broadcast per shield per tick.
			this.flushSync();
		}
	}

	/**
	 * D7b: the shields resonance-linked to this one (including this one; see
	 * {@link ShieldLinking#findLinked}), resolved at most ONCE per server tick and
	 * memoized for the rest of that tick. All same-tick users — the projectile damage
	 * split (which used to re-resolve PER INTERCEPTED PROJECTILE), the linked regen
	 * bonus and the tether renderer — share the one resolution. Mid-tick staleness
	 * (e.g. a partner shrinking below overlap during the same tick's volley) is
	 * accepted by design; the next tick recomputes.
	 */
	public List<BubbleShieldBlockEntity> linkedShields(ServerLevel level) {
		long gameTime = level.getGameTime();
		if (this.linkedCache == null || this.linkedCacheTime != gameTime) {
			this.linkedCacheTime = gameTime;
			this.linkedCache = ShieldLinking.findLinked(this, ServerNet.loadedShields(level));
		}

		return this.linkedCache;
	}

	/** D5: chunk-ticket radius; level 33 - 1 = 32 keeps the projector chunk itself block-ticking. */
	private static final int CHUNK_TICKET_RADIUS = 1;

	/**
	 * D5: arms/releases the projector's chunk ticket to match the active flag. While
	 * active, the ticket is re-added every tick — {@code TicketStorage.addTicket}
	 * dedups on (type, level) and RESETS the remaining timeout, so the re-arm keeps
	 * the ticket alive indefinitely while the {@link ModTicketTypes#SHIELD_PROJECTOR}
	 * timeout still self-releases it if every explicit release path is missed. Kept
	 * radius-1/block-ticking: that is exactly what keeps this block entity's own
	 * ticker (and with it the whole shield enforcement, e.g. a diameter-200 bubble's
	 * far edge) running when no player is near the projector chunk.
	 */
	private void updateChunkTicket(ServerLevel level) {
		ChunkPos chunkPos = ChunkPos.containing(this.worldPosition);
		if (this.shieldState.active) {
			level.getChunkSource().addTicketWithRadius(ModTicketTypes.SHIELD_PROJECTOR, chunkPos, CHUNK_TICKET_RADIUS);
			this.chunkTicketArmed = true;
		} else if (this.chunkTicketArmed) {
			this.chunkTicketArmed = false;
			level.getChunkSource().removeTicketWithRadius(ModTicketTypes.SHIELD_PROJECTOR, chunkPos, CHUNK_TICKET_RADIUS);
		}
	}

	/**
	 * Emits the resonance-link tether: for each linked partner (same owner, active,
	 * overlapping spheres — see {@link ShieldLinking#findLinked}), a line of
	 * {@link ParticleTypes#END_ROD} particles interpolated along the center-to-center
	 * segment. Both linked projectors tick this, so the tether pulses from either end.
	 */
	private void sendLinkTethers(ServerLevel level) {
		List<BubbleShieldBlockEntity> linked = this.linkedShields(level);
		if (linked.size() <= 1) {
			return;
		}

		Vec3 from = Vec3.atCenterOf(this.worldPosition);
		for (BubbleShieldBlockEntity partner : linked) {
			if (partner == this) {
				continue;
			}

			Vec3 to = Vec3.atCenterOf(partner.getBlockPos());
			for (int i = 1; i <= LINK_TETHER_PARTICLES; i++) {
				// Interior sample points only: the endpoints sit inside the projectors.
				Vec3 point = from.lerp(to, i / (double) (LINK_TETHER_PARTICLES + 1));
				// overrideLimiter=true lifts the 32-block send limit; linked projectors
				// can easily be farther apart than that on large bubbles.
				level.sendParticles(ParticleTypes.END_ROD, true, false, point.x, point.y, point.z, 1, 0.0, 0.0, 0.0, 0.0);
			}
		}
	}

	/**
	 * Keeps the shield's boss bar in sync while active: progress mirrors the health
	 * fraction, the name is the owner-set custom name (or the effect name when unset),
	 * and membership is exactly the players standing inside the bubble
	 * ({@link ShieldGeometry#isInside}, so a dome's open underside does not count).
	 * While inactive any previously tracked players are removed.
	 */
	private void updateBossBar(ServerLevel level) {
		if (!this.shieldState.active) {
			if (this.bossEvent != null) {
				this.bossEvent.removeAllPlayers();
			}

			return;
		}

		ShieldState state = this.shieldState;
		// The owner's recolor also drives the boss bar bucket; -1 keeps the authored primary.
		int barArgb = state.colorOverride != ShieldState.NO_COLOR_OVERRIDE
				? state.colorOverride
				: EffectRegistry.get(state.effectId).argbPrimary();
		BossEvent.BossBarColor color = bossBarColor(barArgb);
		ServerBossEvent event = this.bossEvent;
		if (event == null) {
			this.bossEvent = event = new ServerBossEvent(UUID.randomUUID(), this.bossBarName(), color, BossEvent.BossBarOverlay.PROGRESS);
		}

		// ServerBossEvent's setters only broadcast on an actual change.
		event.setName(this.bossBarName());
		event.setColor(color);
		event.setProgress(state.maxHealth > 0.0F ? Mth.clamp(state.health / state.maxHealth, 0.0F, 1.0F) : 0.0F);

		Vec3 center = Vec3.atCenterOf(this.worldPosition);
		double radius = this.currentRadius();

		// Membership diff: drop tracked players that left the bubble (or the level),
		// then add players that entered. addPlayer/removePlayer are no-op-safe, but
		// diffing avoids re-send packets entirely.
		for (ServerPlayer tracked : List.copyOf(event.getPlayers())) {
			if (tracked.isRemoved() || tracked.level() != level
					|| !ShieldGeometry.isInside(state.shape, center, radius, tracked.position())) {
				event.removePlayer(tracked);
			}
		}

		for (ServerPlayer player : level.players()) {
			if (ShieldGeometry.isInside(state.shape, center, radius, player.position()) && !event.getPlayers().contains(player)) {
				event.addPlayer(player);
			}
		}
	}

	/**
	 * The boss bar display name: the owner-set custom name, or the effect name when
	 * unset. While the B6 siege-alarm window is open, the localized UNDER ATTACK
	 * suffix is appended; {@code updateBossBar} recomputes this every tick and
	 * {@link ServerBossEvent#setName} only broadcasts when the computed component
	 * actually differs (WP2 coalescing pattern), so the suffix appearing/reverting
	 * costs exactly one packet each.
	 */
	private Component bossBarName() {
		String customName = this.shieldState.customName;
		Component base = customName.isEmpty()
				? Component.translatable(EffectRegistry.get(this.shieldState.effectId).nameKey())
				: Component.literal(customName);
		long gameTime = this.level != null ? this.level.getGameTime() : 0L;
		return this.shieldState.isAlarmed(gameTime)
				? Component.empty().append(base).append(Component.translatable("gui.bubbleshield.bossbar.alarm"))
				: base;
	}

	/**
	 * Maps an effect's primary ARGB color to the closest vanilla boss bar color by hue;
	 * near-grey colors (low channel spread) map to WHITE.
	 */
	static BossEvent.BossBarColor bossBarColor(int argb) {
		int r = (argb >> 16) & 0xFF;
		int g = (argb >> 8) & 0xFF;
		int b = argb & 0xFF;
		int max = Math.max(r, Math.max(g, b));
		int min = Math.min(r, Math.min(g, b));
		if (max - min < 25) {
			return BossEvent.BossBarColor.WHITE;
		}

		float delta = max - min;
		float hue;
		if (max == r) {
			hue = ((g - b) / delta + 6.0F) % 6.0F;
		} else if (max == g) {
			hue = (b - r) / delta + 2.0F;
		} else {
			hue = (r - g) / delta + 4.0F;
		}

		hue *= 60.0F; // degrees, 0..360
		if (hue < 25.0F || hue >= 330.0F) {
			return BossEvent.BossBarColor.RED;
		}
		if (hue < 70.0F) {
			return BossEvent.BossBarColor.YELLOW;
		}
		if (hue < 165.0F) {
			return BossEvent.BossBarColor.GREEN;
		}
		if (hue < 260.0F) {
			return BossEvent.BossBarColor.BLUE;
		}
		if (hue < 300.0F) {
			return BossEvent.BossBarColor.PURPLE;
		}

		return BossEvent.BossBarColor.PINK;
	}

	/**
	 * The shield's boss bar, or null when no active tick has run yet. Exposed for
	 * gametests; production code should not mutate it directly.
	 */
	public @Nullable ServerBossEvent getBossEvent() {
		return this.bossEvent;
	}

	/** B6: the last threat-census result (0 while inactive); backs {@code DATA_THREAT_COUNT}. */
	public int threatCount() {
		return this.threatCount;
	}

	/** B6: stores a fresh threat-census result; called by {@link ShieldLogic#serverTick} once per second. */
	public void setThreatCount(int threats) {
		this.threatCount = threats;
	}

	/**
	 * Recomputes the shield's max health ({@link ShieldLogic#maxHealthFor}: tier,
	 * bubble diameter and the {@code bubbleshield:strength} gamerule) whenever any of
	 * those inputs changed — including the first tick after load, where the recompute
	 * also overrides whatever (possibly tampered or legacy) {@code max_health} the NBT
	 * carried. The health FRACTION is always preserved across the recompute (clamped
	 * into [0, 1] against the previous max first, so a huge loaded {@code health} maps
	 * to full, not beyond), keeping the radius fraction stable through core swaps,
	 * resizes and strength changes.
	 */
	private void refreshMaxHealth() {
		int tier = this.tier();
		float targetRadius = this.shieldState.targetRadius;
		int strengthPercent = this.strengthPercent();
		if (tier == this.lastTier && targetRadius == this.lastTargetRadius && strengthPercent == this.lastStrengthPercent) {
			return;
		}

		this.lastTier = tier;
		this.lastTargetRadius = targetRadius;
		this.lastStrengthPercent = strengthPercent;
		float newMaxHealth = ShieldLogic.maxHealthFor(tier, targetRadius, strengthPercent);
		float oldMaxHealth = this.shieldState.maxHealth;
		if (newMaxHealth != oldMaxHealth) {
			float fraction = oldMaxHealth > 0.0F ? Math.clamp(this.shieldState.health / oldMaxHealth, 0.0F, 1.0F) : 1.0F;
			this.shieldState.maxHealth = newMaxHealth;
			this.shieldState.health = fraction * newMaxHealth;
			this.markUpdated();
		}
	}

	/**
	 * Converts any fuel items sitting in the menu's fuel slot into stored fuel-seconds,
	 * leaving crafting remainders (e.g. empty buckets) behind.
	 */
	private void consumeFuelSlot() {
		ItemStack stack = this.fuelContainer.getItem(0);
		int seconds = FuelMap.fuelSeconds(stack);
		if (seconds <= 0) {
			return;
		}

		ItemStackTemplate remainder = stack.getItem().getCraftingRemainder();
		this.fuelContainer.setItem(0, remainder != null ? remainder.withCount(stack.getCount()).create() : ItemStack.EMPTY);
		this.addFuelSeconds(seconds);
	}

	/**
	 * Adds fuel from the given stack (all items in it) if the item is a valid shield fuel.
	 *
	 * @return true if the stack was accepted as fuel.
	 */
	public boolean addFuel(ItemStack stack) {
		int seconds = FuelMap.fuelSeconds(stack);
		if (seconds <= 0) {
			return false;
		}

		this.addFuelSeconds(seconds);
		return true;
	}

	public void addFuelSeconds(int seconds) {
		if (seconds <= 0) {
			return;
		}

		this.shieldState.fuelSeconds += seconds;
		this.markUpdated();
	}

	/**
	 * Attempts to activate the shield. Requires fuel and an elapsed cooldown.
	 *
	 * @return true if the shield is active after this call.
	 */
	public boolean tryActivate() {
		return this.tryActivate(null);
	}

	/**
	 * Attempts to activate the shield, crediting {@code initiator} (when given) with the
	 * {@code bubbleshield:shield_activated} advancement criterion on success.
	 *
	 * @param initiator the player who requested the activation, or null for redstone/tests.
	 * @return true if the shield is active after this call.
	 */
	public boolean tryActivate(@Nullable ServerPlayer initiator) {
		if (this.level == null || !ShieldLogic.canActivate(this.shieldState, this.level.getGameTime())) {
			return false;
		}

		if (!this.shieldState.active) {
			this.shieldState.active = true;
			this.markUpdated();
			// Only a REAL inactive->active transition is a sculk-audible activation;
			// re-activating an already-active shield never reaches this branch.
			this.level.gameEvent(GameEvent.BLOCK_ACTIVATE, this.worldPosition, GameEvent.Context.of(this.getBlockState()));
			// Expel non-whitelisted players already standing inside the freshly raised
			// barrier — and, in DEFENSE mode, hostile monsters as well (A5;
			// expelBlockedMonsters is a no-op for PULSE/ECO).
			if (this.level instanceof ServerLevel serverLevel) {
				ShieldLogic.expelBlockedPlayers(serverLevel, this.worldPosition, this.shieldState);
				ShieldLogic.expelBlockedMonsters(serverLevel, this.worldPosition, this.shieldState);
				// D5: arm the chunk ticket right on activation (re-armed each tick).
				this.updateChunkTicket(serverLevel);
			}

			// Only a real inactive->active transition counts as an activation; a no-op
			// re-activation of an already-active shield must not award the criterion.
			if (initiator != null) {
				ModCriteria.SHIELD_ACTIVATED.trigger(initiator, Math.round(this.shieldState.targetRadius * 2.0F), this.shieldState.effectId);
			}
		}

		return true;
	}

	/**
	 * A7 emergency revive: skips a RUNNING break cooldown for
	 * {@link ShieldLogic#REVIVE_FUEL_COST} stored fuel-seconds, reactivating the
	 * shield at {@link ShieldLogic#REVIVE_HEALTH_FRACTION} of its max health.
	 * Only applies when a plain {@link #tryActivate} would fail SOLELY because of
	 * the cooldown (fuel is present — and at least the revive cost) and the
	 * initiator passes the same ownership rule as every other mutating request
	 * ({@link ServerNet#isOwner}: an ownerless projector is claimed by the first
	 * interacting player). Called from the SetActiveC2S handler after a failed
	 * tryActivate; redstone edges never revive (no initiating player).
	 *
	 * @return true if the shield was revived (it is active afterwards).
	 */
	public boolean tryEmergencyRevive(ServerPlayer initiator) {
		if (!(this.level instanceof ServerLevel serverLevel)) {
			return false;
		}

		ShieldState state = this.shieldState;
		long gameTime = serverLevel.getGameTime();
		// The ONLY canActivate failure the revive may bypass is the cooldown:
		// an active shield has nothing to revive, an elapsed cooldown needs no
		// revive, and the fuel gate is strictly TIGHTENED (>= 400, not > 0).
		if (state.active || gameTime >= state.cooldownUntil || state.fuelSeconds < ShieldLogic.REVIVE_FUEL_COST) {
			return false;
		}

		if (!ServerNet.isOwner(initiator, this)) {
			return false;
		}

		// Clear the cooldown, then run the REAL activation path (game event, expel,
		// chunk ticket, shield_activated criterion) before charging the fee: with
		// exactly 400 fuel the fee would zero the tank and canActivate would refuse.
		state.cooldownUntil = gameTime;
		// The cooldown is gone; the pending "shield ready again" ping is moot.
		state.readyAnnounced = true;
		if (!this.tryActivate(initiator)) {
			this.markUpdated();
			return false;
		}

		state.fuelSeconds -= ShieldLogic.REVIVE_FUEL_COST;
		state.health = ShieldLogic.REVIVE_HEALTH_FRACTION * state.maxHealth;
		serverLevel.playSound(null, this.worldPosition, SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.BLOCKS, 1.0F, 0.8F);
		this.markUpdated();
		return true;
	}

	/**
	 * C3 patch kit application (the block routes a patch-kit {@code useItemOn}
	 * here BEFORE the menu-open fallback). Owner/whitelisted only — the same
	 * subjects the barrier admits ({@link ShieldLogic#shouldBlock}), with an
	 * ownerless projector claimed by the first interacting player like every
	 * other mutating path. Two effects:
	 * <ul>
	 * <li>ACTIVE shield: restore {@link ShieldLogic#PATCH_KIT_HEAL} HP, capped at
	 * max health; the kit is only consumed when at least 1 HP was actually healed.</li>
	 * <li>Broken (cooling-down) shield: cut the remaining cooldown by 20% of the
	 * tier's FULL cooldown ({@link ShieldLogic#patchKitCooldownReduction}), never
	 * below 1 remaining tick; the kit is only consumed when it reduced something.</li>
	 * </ul>
	 *
	 * @return true if the kit took effect (one item was consumed from {@code stack});
	 * false leaves the stack untouched and lets the interaction fall through.
	 */
	public boolean applyPatchKit(Player player, ItemStack stack) {
		if (!(this.level instanceof ServerLevel serverLevel) || !stack.is(ModItems.PATCH_KIT)) {
			return false;
		}

		ShieldState state = this.shieldState;
		UUID uuid = player.getUUID();
		boolean allowed = !ShieldLogic.shouldBlock(state, player.getGameProfile().name(), uuid, uuid.equals(state.ownerUuid));
		if (!allowed && player instanceof ServerPlayer serverPlayer) {
			// Mirrors the settings paths: the first interacting player claims an
			// ownerless projector (isOwner only ever claims when ownerUuid == null).
			allowed = ServerNet.isOwner(serverPlayer, this);
		}

		if (!allowed) {
			return false;
		}

		long gameTime = serverLevel.getGameTime();
		if (state.active) {
			float healed = Math.min(ShieldLogic.PATCH_KIT_HEAL, state.maxHealth - state.health);
			if (healed < 1.0F) {
				return false;
			}

			state.health += healed;
		} else if (gameTime < state.cooldownUntil) {
			long remaining = state.cooldownUntil - gameTime;
			long reduced = Math.max(1L, remaining - ShieldLogic.patchKitCooldownReduction(this.tier()));
			if (reduced >= remaining) {
				return false;
			}

			state.cooldownUntil = gameTime + reduced;
		} else {
			// Inactive without a cooldown: nothing to patch.
			return false;
		}

		stack.shrink(1);
		serverLevel.playSound(null, this.worldPosition, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1.0F, 1.2F);
		Vec3 center = Vec3.atCenterOf(this.worldPosition);
		serverLevel.sendParticles(ParticleTypes.HAPPY_VILLAGER, true, false,
				center.x, center.y + 0.8, center.z, 8, 0.4, 0.4, 0.4, 0.0);
		this.markUpdated();
		return true;
	}

	/**
	 * Records the given player as the shield's owner and whitelists them.
	 * Called on placement and when the first player interacts with an ownerless shield.
	 */
	public void setOwner(Player player) {
		this.shieldState.ownerUuid = player.getUUID();
		this.shieldState.whitelistUuids.add(player.getUUID());
		addNameIfAbsent(this.shieldState.whitelistNames, player.getGameProfile().name());
		this.shieldState.rememberWhitelistUuid(player.getGameProfile().name(), player.getUUID());
		this.markUpdated();
	}

	/**
	 * Applies RAW damage to the shield through the single damage pipeline
	 * ({@link ShieldLogic#appliedDamage}: this shield's own tier DR, its own plating
	 * DR when reinforced plating is socketed, and the B3 last-stand halving
	 * evaluated against the health BEFORE the hit), breaking it (deactivate +
	 * tier-scaled cooldown) when health is depleted. Linked-split shares also
	 * arrive here raw: the split happens first, then each receiving shield
	 * discounts its share by its own DR. A break routes through the ONE shared
	 * break routine ({@link ShieldLogic#onShieldBreak}: break sound, criterion,
	 * B5 nova) like the interception path. This direct path deliberately never
	 * fires the B6 siege alarm — only real projectile interceptions and the
	 * threat count's 0-to-positive edge do — so command/test/linked-share damage
	 * keeps the comparator purely health-based.
	 */
	public void applyShieldDamage(float amount) {
		long gameTime = this.level != null ? this.level.getGameTime() : 0L;
		boolean wasActive = this.shieldState.active;
		int tier = this.tier();
		// B5: the nova targets the PRE-break radius; snapshot before the hit.
		double preBreakRadius = ShieldLogic.currentRadius(this.shieldState);
		float applied = ShieldLogic.appliedDamage(amount, tier, this.platingDr(), ShieldLogic.isLastStand(this.shieldState));
		boolean broke = ShieldLogic.applyDamage(this.shieldState, applied, gameTime, ShieldLogic.breakCooldownTicks(tier));
		if (broke && this.level instanceof ServerLevel serverLevel) {
			ShieldLogic.onShieldBreak(serverLevel, this.worldPosition, this.shieldState, preBreakRadius);
			// A break here is a real active->inactive transition and must stay
			// sculk-audible: this path covers linked-partner damage applied from
			// ANOTHER shield's tick, which this shield's own serverTick snapshot
			// cannot see (the flag is already false by the time it ticks).
			if (wasActive) {
				serverLevel.gameEvent(GameEvent.BLOCK_DEACTIVATE, this.worldPosition, GameEvent.Context.of(this.getBlockState()));
			}

			// D5: a break deactivates; release the chunk ticket immediately.
			this.updateChunkTicket(serverLevel);
		}

		this.markUpdated();
	}

	/**
	 * Applies validated settings from the client: diameter (converted to radius),
	 * effect id, shield shape, mode and beam style (by ordinal, clamped), and the
	 * effect-cycle toggle.
	 */
	public void setSettings(int diameter, int effectId, int shapeOrdinal, int modeOrdinal, boolean cycleEffect, int beamStyleOrdinal) {
		this.shieldState.targetRadius = diameter / 2.0F;
		this.shieldState.effectId = effectId;
		this.shieldState.shape = ShieldShape.byOrdinal(shapeOrdinal);
		this.shieldState.mode = ShieldMode.byOrdinal(modeOrdinal);
		this.shieldState.cycleEffect = cycleEffect;
		this.shieldState.beamStyle = BeamStyle.byOrdinal(beamStyleOrdinal);
		this.markUpdated();
	}

	/**
	 * Seeds the powered flag from the current neighbor signal WITHOUT acting on it.
	 * Called at placement (and on the first tick as a fallback) so a projector placed
	 * next to an already-powered block does not misread the next unrelated neighbor
	 * update as a rising edge.
	 */
	public void seedPowered(boolean powered) {
		this.poweredInitialized = true;
		this.powered = powered;
	}

	/**
	 * Records the redstone level seen by the block and acts on edges only: a rising edge
	 * tries to activate the shield (subject to fuel/cooldown), a falling edge deactivates
	 * it. Steady levels never fight the GUI toggle.
	 */
	public void setNeighborPowered(boolean powered) {
		this.poweredInitialized = true;
		if (powered == this.powered) {
			return;
		}

		this.powered = powered;
		if (powered) {
			this.tryActivate();
		} else {
			this.setActive(false);
		}

		this.setChanged();
	}

	/**
	 * Activates (subject to fuel/cooldown) or deactivates the shield.
	 */
	public void setActive(boolean active) {
		if (active) {
			this.tryActivate();
		} else if (this.shieldState.active) {
			this.shieldState.active = false;
			// Only a real active->inactive transition is a sculk-audible deactivation.
			if (this.level != null) {
				this.level.gameEvent(GameEvent.BLOCK_DEACTIVATE, this.worldPosition, GameEvent.Context.of(this.getBlockState()));
			}

			// Empty the boss bar immediately instead of waiting for the next tick.
			if (this.bossEvent != null) {
				this.bossEvent.removeAllPlayers();
			}

			// D5: release the chunk ticket immediately instead of on the next tick.
			if (this.level instanceof ServerLevel serverLevel) {
				this.updateChunkTicket(serverLevel);
			}

			this.markUpdated();
		}
	}

	/**
	 * Sets the owner-chosen shield display name (already sanitized by
	 * {@code ServerNet.sanitizeShieldName}); an empty string clears it, falling back
	 * to the effect name on the boss bar and HUD.
	 */
	public void setCustomName(String name) {
		if (!this.shieldState.customName.equals(name)) {
			this.shieldState.customName = name;
			this.markUpdated();
		}
	}

	/**
	 * Sets the owner-picked color override (already validated by
	 * {@code ServerNet.isValidColorOverride}): an opaque ARGB recolor, or
	 * {@link ShieldState#NO_COLOR_OVERRIDE} to reset to the effect's authored palette.
	 */
	public void setColorOverride(int argb) {
		if (this.shieldState.colorOverride != argb) {
			this.shieldState.colorOverride = argb;
			this.markUpdated();
		}
	}

	/**
	 * Adds a player name to the whitelist (case-insensitively deduplicated), also
	 * recording the UUID if the player is online.
	 */
	public void whitelistAdd(MinecraftServer server, String name) {
		this.whitelistAdd(server, name, null);
	}

	/**
	 * Adds a player name to the whitelist (case-insensitively deduplicated), also
	 * recording the UUID if the player is online. When {@code actor} is given, they
	 * are credited with the {@code bubbleshield:player_whitelisted} criterion.
	 */
	public void whitelistAdd(MinecraftServer server, String name, @Nullable ServerPlayer actor) {
		String trimmed = name.trim();
		if (trimmed.isEmpty()) {
			return;
		}

		boolean added = addNameIfAbsent(this.shieldState.whitelistNames, trimmed);
		ServerPlayer online = server.getPlayerList().getPlayerByName(trimmed);
		if (online != null) {
			this.shieldState.whitelistUuids.add(online.getUUID());
			this.shieldState.rememberWhitelistUuid(trimmed, online.getUUID());
		}

		// Only genuinely new entries count, and whitelisting yourself is not an achievement.
		if (added && actor != null && !trimmed.equalsIgnoreCase(actor.getGameProfile().name())) {
			ModCriteria.PLAYER_WHITELISTED.trigger(actor);
		}

		this.markUpdated();
	}

	/**
	 * Removes a name from the whitelist and revokes the matching UUID, resolving the
	 * name strictly locally: via the online player list and the shield's own stored
	 * name-to-UUID associations. The server's name-to-id cache is deliberately never
	 * consulted, because a cache miss there performs a blocking remote lookup plus a
	 * usercache disk write on the server thread (spammable via WhitelistModifyC2S).
	 */
	public void whitelistRemove(MinecraftServer server, String name) {
		String trimmed = name.trim();
		this.shieldState.whitelistNames.removeIf(existing -> existing.equalsIgnoreCase(trimmed));

		ServerPlayer online = server.getPlayerList().getPlayerByName(trimmed);
		if (online != null) {
			this.shieldState.whitelistUuids.remove(online.getUUID());
		}

		UUID stored = this.shieldState.forgetWhitelistUuid(trimmed);
		if (stored != null) {
			this.shieldState.whitelistUuids.remove(stored);
		}

		this.markUpdated();
	}

	/**
	 * Adds {@code name} unless an equal name (ignoring case) is already present.
	 *
	 * @return true if the name was actually added.
	 */
	private static boolean addNameIfAbsent(Set<String> names, String name) {
		for (String existing : names) {
			if (existing.equalsIgnoreCase(name)) {
				return false;
			}
		}

		names.add(name);
		return true;
	}

	/**
	 * Marks the shield dirty for persistence, refreshes comparators, and flags the
	 * state for client replication. The persistence mark and the comparator refresh
	 * stay immediate and unconditional (fuelSeconds is not part of the replicated
	 * snapshot, yet comparators read it while the shield is inactive); the network
	 * broadcast is COALESCED (D7c): however many mutations mark in one tick, the
	 * next {@link #flushSync()} — end of this shield's serverTick — sends at most
	 * one snapshot, and only when a replicated field actually changed.
	 */
	public void markUpdated() {
		// setChanged() also calls level.updateNeighbourForOutputSignal in 26.2, but the
		// comparator refresh is kept explicit so it never silently regresses.
		this.setChanged();
		if (!(this.level instanceof ServerLevel)) {
			return;
		}

		BlockState state = this.getBlockState();
		this.level.updateNeighbourForOutputSignal(this.worldPosition, state.getBlock());
		this.syncDirty = true;
	}

	/**
	 * D7c: flushes a pending {@link #markUpdated} into (at most) one client
	 * replication — the block-entity data update plus the ShieldSyncS2C broadcast —
	 * still gated on the replicated snapshot actually differing from the last one
	 * sent. Called once at the end of {@link #serverTick}.
	 */
	private void flushSync() {
		if (!this.syncDirty || !(this.level instanceof ServerLevel)) {
			return;
		}

		this.syncDirty = false;
		ReplicatedState snapshot = this.buildReplicatedState();
		if (snapshot.equals(this.lastSentState)) {
			return;
		}

		this.lastSentState = snapshot;
		BlockState state = this.getBlockState();
		this.level.sendBlockUpdated(this.worldPosition, state, state, Block.UPDATE_CLIENTS);
		ServerNet.syncShield(this);
	}

	/** The client-visible fields mirrored by {@code ShieldSyncS2C}; used to skip redundant broadcasts. */
	private record ReplicatedState(
		ShieldPayloads.ShieldVisual visual,
		Set<UUID> whitelistUuids,
		Set<String> whitelistNames,
		int cooldownSeconds,
		@Nullable UUID ownerUuid,
		String customName
	) {
	}

	private ReplicatedState buildReplicatedState() {
		ShieldState state = this.shieldState;
		return new ReplicatedState(
			new ShieldPayloads.ShieldVisual(
				state.active,
				state.effectId,
				state.targetRadius,
				ShieldLogic.currentRadius(state),
				state.maxHealth > 0.0F ? state.health / state.maxHealth : 0.0F,
				state.maxHealth,
				this.tier(),
				state.shape.ordinal(),
				state.colorOverride,
				state.beamStyle.ordinal()
			),
			Set.copyOf(state.whitelistUuids),
			Set.copyOf(state.whitelistNames),
			(int) (this.cooldownTicksLeft() / ShieldLogic.TICKS_PER_FUEL_SECOND),
			state.ownerUuid,
			state.customName
		);
	}

	@Override
	public void setLevel(Level level) {
		super.setLevel(level);
		if (level instanceof ServerLevel) {
			ServerNet.trackShield(this);
		}
	}

	@Override
	public void clearRemoved() {
		super.clearRemoved();
		if (this.level instanceof ServerLevel) {
			ServerNet.trackShield(this);
		}
	}

	@Override
	public void setRemoved() {
		if (this.level instanceof ServerLevel serverLevel) {
			ServerNet.untrackShield(this);
			ServerNet.broadcastRemove(this);

			// D5: never leak the chunk ticket past this block entity's removal
			// (block broken, chunk discarded, ...). The ticket type's timeout would
			// eventually drop it anyway; this releases it deterministically.
			if (this.chunkTicketArmed) {
				this.chunkTicketArmed = false;
				serverLevel.getChunkSource().removeTicketWithRadius(
						ModTicketTypes.SHIELD_PROJECTOR, ChunkPos.containing(this.worldPosition), CHUNK_TICKET_RADIUS);
			}
		}

		if (this.bossEvent != null) {
			this.bossEvent.removeAllPlayers();
		}

		super.setRemoved();
	}

	/**
	 * Drops the fuel slot contents when the block is destroyed. This is the 26.2 removal
	 * hook vanilla container block entities use ({@code Containers.dropContents} runs here
	 * because the block entity is already detached by the time the block's
	 * {@code affectNeighborsAfterRemoval} fires).
	 */
	@Override
	public void preRemoveSideEffects(BlockPos pos, BlockState state) {
		super.preRemoveSideEffects(pos, state);
		if (this.level != null) {
			Containers.dropContents(this.level, pos, this.fuelContainer);
			Containers.dropContents(this.level, pos, this.coreContainer);
			Containers.dropContents(this.level, pos, this.capacitorContainer);
			Containers.dropContents(this.level, pos, this.augmentContainer);
		}
	}

	// --- ExtendedMenuProvider<BlockPos> ---

	@Override
	public BlockPos getScreenOpeningData(ServerPlayer player) {
		return this.worldPosition;
	}

	@Override
	public Component getDisplayName() {
		return Component.translatable("gui.bubbleshield.title");
	}

	@Override
	public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
		return new BubbleShieldMenu(containerId, inventory, this);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		this.shieldState.save(output);
		output.putBoolean("powered", this.powered);
		this.fuelContainer.storeAsItemList(output.list("fuel_items", ItemStack.CODEC));
		this.coreContainer.storeAsItemList(output.list("core_items", ItemStack.CODEC));
		this.capacitorContainer.storeAsItemList(output.list("capacitor_items", ItemStack.CODEC));
		this.augmentContainer.storeAsItemList(output.list("augment_items", ItemStack.CODEC));
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.shieldState.load(input);
		// Presence-gated: only a save that actually recorded the level counts as an
		// observation. A legacy (pre-"powered") save next to a steady redstone source
		// must re-seed from the live signal on the first tick (see serverTick), or the
		// next unrelated neighbor update would be misread as a rising edge.
		Optional<Boolean> savedPowered = input.read("powered", Codec.BOOL);
		this.powered = savedPowered.orElse(false);
		this.poweredInitialized = savedPowered.isPresent();
		this.fuelContainer.fromItemList(input.listOrEmpty("fuel_items", ItemStack.CODEC));
		this.coreContainer.fromItemList(input.listOrEmpty("core_items", ItemStack.CODEC));
		this.capacitorContainer.fromItemList(input.listOrEmpty("capacitor_items", ItemStack.CODEC));
		this.augmentContainer.fromItemList(input.listOrEmpty("augment_items", ItemStack.CODEC));
		// Force the max-health recompute on the next tick (covers cores/diameters/
		// strength edited while unloaded AND maps a legacy save's health fraction
		// onto the current max-health model).
		this.lastTier = -1;
		// Cap the remaining cooldown on the first tick (needs the level clock).
		this.capLoadedCooldown = true;
	}

	@Override
	public ClientboundBlockEntityDataPacket getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return this.saveCustomOnly(registries);
	}
}

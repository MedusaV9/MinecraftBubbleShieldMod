package com.bubbleshield.block;

import com.bubbleshield.advancements.ModCriteria;
import com.bubbleshield.effect.EffectRegistry;
import com.bubbleshield.menu.BubbleShieldMenu;
import com.bubbleshield.net.ServerNet;
import com.bubbleshield.net.ShieldPayloads;
import com.bubbleshield.registry.ModBlockEntities;
import com.bubbleshield.registry.ModItems;
import com.bubbleshield.shield.FuelMap;
import com.bubbleshield.shield.ShieldGeometry;
import com.bubbleshield.shield.ShieldLogic;
import com.bubbleshield.shield.ShieldMode;
import com.bubbleshield.shield.ShieldShape;
import com.bubbleshield.shield.ShieldState;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

public class BubbleShieldBlockEntity extends BlockEntity implements ExtendedMenuProvider<BlockPos> {
	private final ShieldState shieldState = new ShieldState();
	/** One-slot fuel inventory shown in the projector menu; consumed into fuel-seconds each tick. */
	private final SimpleContainer fuelContainer = new SimpleContainer(1);
	/** One-slot upgrade-core inventory; its content derives the shield tier (see {@link #tier()}). */
	private final SimpleContainer coreContainer = new SimpleContainer(1);
	/** Tier applied by the last {@link #refreshTier()} pass; -1 forces a refresh on the first tick. */
	private int lastTier = -1;
	/** Last observed redstone level; persisted so chunk reloads do not fake an edge. */
	private boolean powered;
	/**
	 * Whether {@link #powered} reflects an actual observation (placement seed, NBT load
	 * or a neighbor update). A freshly placed projector seeds from the live neighbor
	 * signal so a pre-existing steady level is never misread as a rising edge.
	 */
	private boolean poweredInitialized;
	/** Live server-side snapshot for the menu; values in SECONDS (data slots sync 16-bit signed). */
	private final ContainerData menuData = new ContainerData() {
		@Override
		public int get(int dataId) {
			ShieldState state = BubbleShieldBlockEntity.this.shieldState;
			return switch (dataId) {
				case BubbleShieldMenu.DATA_FUEL_SECONDS -> Math.min(state.fuelSeconds, Short.MAX_VALUE);
				case BubbleShieldMenu.DATA_HEALTH_TIMES_10 -> Math.round(state.health * 10.0F);
				case BubbleShieldMenu.DATA_DIAMETER -> Math.round(state.targetRadius * 2.0F);
				case BubbleShieldMenu.DATA_EFFECT_ID -> state.effectId;
				case BubbleShieldMenu.DATA_ACTIVE -> state.active ? 1 : 0;
				case BubbleShieldMenu.DATA_COOLDOWN_SECONDS -> (int) Math.min(Short.MAX_VALUE, BubbleShieldBlockEntity.this.cooldownTicksLeft() / ShieldLogic.TICKS_PER_FUEL_SECOND);
				case BubbleShieldMenu.DATA_TIER -> BubbleShieldBlockEntity.this.tier();
				case BubbleShieldMenu.DATA_SHAPE -> state.shape.ordinal();
				case BubbleShieldMenu.DATA_MODE -> state.mode.ordinal();
				case BubbleShieldMenu.DATA_CYCLE -> state.cycleEffect ? 1 : 0;
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
	 * Boss bar shown to every player inside the active shield. Transient runtime state
	 * (like {@link #lastSentState}): never persisted, lazily created on the first
	 * active server tick and emptied on deactivation/removal.
	 */
	private @Nullable ServerBossEvent bossEvent;

	public BubbleShieldBlockEntity(BlockPos worldPosition, BlockState blockState) {
		super(ModBlockEntities.BUBBLE_SHIELD_PROJECTOR, worldPosition, blockState);
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

	/**
	 * @return the shield tier derived from the upgrade-core slot: 0 without a core,
	 * 1 with a resonant core, 2 with a prismatic core.
	 */
	public int tier() {
		ItemStack core = this.coreContainer.getItem(0);
		if (core.is(ModItems.PRISMATIC_CORE)) {
			return 2;
		}

		return core.is(ModItems.RESONANT_CORE) ? 1 : 0;
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
		this.refreshTier();
		if (this.level instanceof ServerLevel serverLevel) {
			if (ShieldLogic.serverTick(serverLevel, this.worldPosition, this.shieldState, this.tier())) {
				this.markUpdated();
			}

			// Runs even when the shield logic reported no change: deactivations from any
			// path (fuel-out, break, GUI, redstone) must empty the boss bar next tick.
			this.updateBossBar(serverLevel);
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

	/** The boss bar display name: the owner-set custom name, or the effect name when unset. */
	private Component bossBarName() {
		String customName = this.shieldState.customName;
		return customName.isEmpty()
				? Component.translatable(EffectRegistry.get(this.shieldState.effectId).nameKey())
				: Component.literal(customName);
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

	/**
	 * Applies the tier derived from the core slot to the shield's max health whenever the
	 * slot content changed (including the first tick after load): {@code maxHealth = 100 * (1 + tier)}.
	 * When max health rises (core inserted) the health FRACTION is preserved, so an
	 * active shield keeps its current radius instead of instantly shrinking; when it
	 * drops (core removed) current health is clamped down as before.
	 */
	private void refreshTier() {
		int tier = this.tier();
		if (tier == this.lastTier) {
			return;
		}

		this.lastTier = tier;
		float newMaxHealth = ShieldState.DEFAULT_MAX_HEALTH * (1 + tier);
		float oldMaxHealth = this.shieldState.maxHealth;
		if (newMaxHealth > oldMaxHealth && oldMaxHealth > 0.0F) {
			this.shieldState.health = this.shieldState.health / oldMaxHealth * newMaxHealth;
		}

		this.shieldState.maxHealth = newMaxHealth;
		this.shieldState.health = Math.min(this.shieldState.health, newMaxHealth);
		this.markUpdated();
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
			// Expel non-whitelisted players already standing inside the freshly raised barrier.
			if (this.level instanceof ServerLevel serverLevel) {
				ShieldLogic.expelBlockedPlayers(serverLevel, this.worldPosition, this.shieldState);
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
	 * Applies damage to the shield, breaking it (deactivate + tier-scaled cooldown) when
	 * health is depleted.
	 */
	public void applyShieldDamage(float amount) {
		long gameTime = this.level != null ? this.level.getGameTime() : 0L;
		boolean broke = ShieldLogic.applyDamage(this.shieldState, amount, gameTime, ShieldLogic.breakCooldownTicks(this.tier()));
		if (broke && this.level instanceof ServerLevel serverLevel) {
			serverLevel.playSound(null, this.worldPosition, SoundEvents.SHIELD_BREAK.value(), SoundSource.BLOCKS, 1.0F, 1.0F);
			ModCriteria.fireShieldBroken(serverLevel, this.shieldState.ownerUuid);
		}

		this.markUpdated();
	}

	/**
	 * Applies validated settings from the client: diameter (converted to radius),
	 * effect id, shield shape and mode (by ordinal), and the effect-cycle toggle.
	 */
	public void setSettings(int diameter, int effectId, int shapeOrdinal, int modeOrdinal, boolean cycleEffect) {
		this.shieldState.targetRadius = diameter / 2.0F;
		this.shieldState.effectId = effectId;
		this.shieldState.shape = ShieldShape.byOrdinal(shapeOrdinal);
		this.shieldState.mode = ShieldMode.byOrdinal(modeOrdinal);
		this.shieldState.cycleEffect = cycleEffect;
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
			// Empty the boss bar immediately instead of waiting for the next tick.
			if (this.bossEvent != null) {
				this.bossEvent.removeAllPlayers();
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
	 * Marks the shield dirty for persistence, refreshes comparators, and, if any
	 * replicated field changed since the last broadcast, replicates the new state to
	 * clients. Only the NETWORK broadcast is gated on the snapshot: fuelSeconds is not
	 * part of the replicated snapshot, yet comparators read it while the shield is
	 * inactive, so the comparator refresh must run unconditionally.
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

		ReplicatedState snapshot = this.buildReplicatedState();
		if (snapshot.equals(this.lastSentState)) {
			return;
		}

		this.lastSentState = snapshot;
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
				this.tier(),
				state.shape.ordinal(),
				state.colorOverride
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
		if (this.level instanceof ServerLevel) {
			ServerNet.untrackShield(this);
			ServerNet.broadcastRemove(this);
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
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.shieldState.load(input);
		this.powered = input.getBooleanOr("powered", false);
		// The persisted level counts as an observation; do not re-seed on the first tick.
		this.poweredInitialized = true;
		this.fuelContainer.fromItemList(input.listOrEmpty("fuel_items", ItemStack.CODEC));
		this.coreContainer.fromItemList(input.listOrEmpty("core_items", ItemStack.CODEC));
		// Re-derive tier-dependent fields on the next tick (covers cores edited while unloaded).
		this.lastTier = -1;
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

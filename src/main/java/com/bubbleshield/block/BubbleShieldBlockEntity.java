package com.bubbleshield.block;

import com.bubbleshield.menu.BubbleShieldMenu;
import com.bubbleshield.net.ServerNet;
import com.bubbleshield.net.ShieldPayloads;
import com.bubbleshield.registry.ModBlockEntities;
import com.bubbleshield.registry.ModItems;
import com.bubbleshield.shield.FuelMap;
import com.bubbleshield.shield.ShieldLogic;
import com.bubbleshield.shield.ShieldState;

import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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

import org.jspecify.annotations.Nullable;

public class BubbleShieldBlockEntity extends BlockEntity implements ExtendedMenuProvider<BlockPos> {
	private final ShieldState shieldState = new ShieldState();
	/** One-slot fuel inventory shown in the projector menu; consumed into fuel-seconds each tick. */
	private final SimpleContainer fuelContainer = new SimpleContainer(1);
	/** One-slot upgrade-core inventory; its content derives the shield tier (see {@link #tier()}). */
	private final SimpleContainer coreContainer = new SimpleContainer(1);
	/** Tier applied by the last {@link #refreshTier()} pass; -1 forces a refresh on the first tick. */
	private int lastTier = -1;
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
		this.consumeFuelSlot();
		this.refreshTier();
		if (this.level instanceof ServerLevel serverLevel && ShieldLogic.serverTick(serverLevel, this.worldPosition, this.shieldState, this.tier())) {
			this.markUpdated();
		}
	}

	/**
	 * Applies the tier derived from the core slot to the shield's max health whenever the
	 * slot content changed (including the first tick after load): {@code maxHealth = 100 * (1 + tier)},
	 * clamping current health down when a core is removed.
	 */
	private void refreshTier() {
		int tier = this.tier();
		if (tier == this.lastTier) {
			return;
		}

		this.lastTier = tier;
		this.shieldState.maxHealth = ShieldState.DEFAULT_MAX_HEALTH * (1 + tier);
		this.shieldState.health = Math.min(this.shieldState.health, this.shieldState.maxHealth);
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
		}

		this.markUpdated();
	}

	/**
	 * Applies validated settings from the client: diameter (converted to radius) and effect id.
	 */
	public void setSettings(int diameter, int effectId) {
		this.shieldState.targetRadius = diameter / 2.0F;
		this.shieldState.effectId = effectId;
		this.markUpdated();
	}

	/**
	 * Activates (subject to fuel/cooldown) or deactivates the shield.
	 */
	public void setActive(boolean active) {
		if (active) {
			this.tryActivate();
		} else if (this.shieldState.active) {
			this.shieldState.active = false;
			this.markUpdated();
		}
	}

	/**
	 * Adds a player name to the whitelist (case-insensitively deduplicated), also
	 * recording the UUID if the player is online.
	 */
	public void whitelistAdd(MinecraftServer server, String name) {
		String trimmed = name.trim();
		if (trimmed.isEmpty()) {
			return;
		}

		addNameIfAbsent(this.shieldState.whitelistNames, trimmed);
		ServerPlayer online = server.getPlayerList().getPlayerByName(trimmed);
		if (online != null) {
			this.shieldState.whitelistUuids.add(online.getUUID());
		}

		this.markUpdated();
	}

	/**
	 * Removes a name from the whitelist and revokes the matching UUID(s), resolving the
	 * name via the online player list and the server's name-to-id cache.
	 */
	public void whitelistRemove(MinecraftServer server, String name) {
		String trimmed = name.trim();
		this.shieldState.whitelistNames.removeIf(existing -> existing.equalsIgnoreCase(trimmed));

		ServerPlayer online = server.getPlayerList().getPlayerByName(trimmed);
		if (online != null) {
			this.shieldState.whitelistUuids.remove(online.getUUID());
		}

		server.services().nameToIdCache().get(trimmed).ifPresent(nameAndId -> this.shieldState.whitelistUuids.remove(nameAndId.id()));
		this.markUpdated();
	}

	/** Adds {@code name} unless an equal name (ignoring case) is already present. */
	private static void addNameIfAbsent(Set<String> names, String name) {
		for (String existing : names) {
			if (existing.equalsIgnoreCase(name)) {
				return;
			}
		}

		names.add(name);
	}

	/**
	 * Marks the shield dirty for persistence and, if any replicated field changed since
	 * the last broadcast, replicates the new state to clients.
	 */
	public void markUpdated() {
		this.setChanged();
		if (!(this.level instanceof ServerLevel)) {
			return;
		}

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
		@Nullable UUID ownerUuid
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
				this.tier()
			),
			Set.copyOf(state.whitelistUuids),
			Set.copyOf(state.whitelistNames),
			(int) (this.cooldownTicksLeft() / ShieldLogic.TICKS_PER_FUEL_SECOND),
			state.ownerUuid
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
		this.fuelContainer.storeAsItemList(output.list("fuel_items", ItemStack.CODEC));
		this.coreContainer.storeAsItemList(output.list("core_items", ItemStack.CODEC));
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.shieldState.load(input);
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

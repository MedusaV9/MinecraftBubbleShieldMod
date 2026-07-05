package com.bubbleshield.block;

import com.bubbleshield.net.ServerNet;
import com.bubbleshield.registry.ModBlockEntities;
import com.bubbleshield.shield.FuelMap;
import com.bubbleshield.shield.ShieldLogic;
import com.bubbleshield.shield.ShieldState;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public class BubbleShieldBlockEntity extends BlockEntity {
	private final ShieldState shieldState = new ShieldState();

	public BubbleShieldBlockEntity(BlockPos worldPosition, BlockState blockState) {
		super(ModBlockEntities.BUBBLE_SHIELD_PROJECTOR, worldPosition, blockState);
	}

	public ShieldState getShieldState() {
		return this.shieldState;
	}

	public float currentRadius() {
		return ShieldLogic.currentRadius(this.shieldState);
	}

	/**
	 * Runs one server tick of shield logic. Invoked by the block ticker on the server only.
	 */
	public void serverTick() {
		if (this.level instanceof ServerLevel serverLevel && ShieldLogic.serverTick(serverLevel, this.worldPosition, this.shieldState)) {
			this.markUpdated();
		}
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
		}

		return true;
	}

	/**
	 * Applies damage to the shield, breaking it (deactivate + cooldown) when health is depleted.
	 */
	public void applyShieldDamage(float amount) {
		long gameTime = this.level != null ? this.level.getGameTime() : 0L;
		boolean broke = ShieldLogic.applyDamage(this.shieldState, amount, gameTime);
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
	 * Adds a player name to the whitelist, also recording the UUID if the player is online.
	 */
	public void whitelistAdd(MinecraftServer server, String name) {
		this.shieldState.whitelistNames.add(name);
		ServerPlayer online = server.getPlayerList().getPlayerByName(name);
		if (online != null) {
			this.shieldState.whitelistUuids.add(online.getUUID());
		}

		this.markUpdated();
	}

	public void whitelistRemove(String name) {
		this.shieldState.whitelistNames.removeIf(existing -> existing.equalsIgnoreCase(name));
		this.markUpdated();
	}

	private void markUpdated() {
		this.setChanged();
		if (this.level != null) {
			BlockState state = this.getBlockState();
			this.level.sendBlockUpdated(this.worldPosition, state, state, Block.UPDATE_NEIGHBORS | Block.UPDATE_CLIENTS);
		}

		if (this.level instanceof ServerLevel) {
			ServerNet.syncShield(this);
		}
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

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		this.shieldState.save(output);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.shieldState.load(input);
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

package com.bubbleshield.block;

import com.bubbleshield.registry.ModBlockEntities;
import com.bubbleshield.shield.ShieldState;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
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

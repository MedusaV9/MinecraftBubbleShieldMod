package com.bubbleshield.block;

import com.bubbleshield.registry.ModBlockEntities;
import com.bubbleshield.registry.ModItems;
import com.bubbleshield.shield.ShieldState;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;

import org.jspecify.annotations.Nullable;

public class BubbleShieldBlock extends BaseEntityBlock {
	public static final MapCodec<BubbleShieldBlock> CODEC = simpleCodec(BubbleShieldBlock::new);

	public BubbleShieldBlock(BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos worldPosition, BlockState blockState) {
		return new BubbleShieldBlockEntity(worldPosition, blockState);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack itemStack) {
		if (!level.isClientSide() && level.getBlockEntity(pos) instanceof BubbleShieldBlockEntity blockEntity) {
			// Seed the powered flag from the pre-existing signal WITHOUT acting, so the
			// next unrelated neighbor update is not misread as a rising edge.
			blockEntity.seedPowered(level.hasNeighborSignal(pos));
			if (placer instanceof Player player) {
				blockEntity.setOwner(player);
			}
		}
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
		if (!level.isClientSide() && level.getBlockEntity(pos) instanceof BubbleShieldBlockEntity blockEntity) {
			player.openMenu(blockEntity);
		}

		return InteractionResult.SUCCESS;
	}

	/**
	 * C3 patch kit: a held patch kit takes precedence over the menu-open fallback —
	 * but ONLY when it actually takes effect (heal an active shield / shorten a break
	 * cooldown, owner/whitelisted only; see
	 * {@link BubbleShieldBlockEntity#applyPatchKit}). A no-op kit (full health,
	 * no cooldown, or an unauthorized user) returns {@code TRY_WITH_EMPTY_HAND} so
	 * the interaction falls through to {@link #useWithoutItem} and the menu opens
	 * exactly as it does for every other held item. Sneak-use keeps the vanilla
	 * convention: block interaction is skipped entirely while sneaking with an item,
	 * so a sneaking player places blocks/uses the item rather than patching.
	 */
	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
		if (stack.is(ModItems.PATCH_KIT)) {
			if (level.isClientSide()) {
				// Optimistic swing; the server below is authoritative (and falls back
				// to opening the menu itself when the kit turns out to be a no-op).
				return InteractionResult.SUCCESS;
			}

			if (level.getBlockEntity(pos) instanceof BubbleShieldBlockEntity blockEntity && blockEntity.applyPatchKit(player, stack)) {
				return InteractionResult.SUCCESS;
			}

			return InteractionResult.TRY_WITH_EMPTY_HAND;
		}

		return super.useItemOn(stack, state, level, pos, player, hand, hitResult);
	}

	@Override
	public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState blockState, BlockEntityType<T> type) {
		if (level.isClientSide()) {
			return null;
		}

		return createTickerHelper(type, ModBlockEntities.BUBBLE_SHIELD_PROJECTOR, (lvl, pos, st, be) -> be.serverTick());
	}

	/**
	 * Redstone control: activation on a rising edge, deactivation on a falling edge.
	 * The block entity keeps the persisted powered flag and performs the edge detection,
	 * so GUI toggling in between stays independent of a steady redstone level.
	 */
	@Override
	protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, @Nullable Orientation orientation, boolean movedByPiston) {
		if (!level.isClientSide() && level.getBlockEntity(pos) instanceof BubbleShieldBlockEntity blockEntity) {
			blockEntity.setNeighborPowered(level.hasNeighborSignal(pos));
		}
	}

	@Override
	protected boolean hasAnalogOutputSignal(BlockState state) {
		return true;
	}

	/**
	 * Comparator output: while active, the shield's health fraction on a 1..15 scale;
	 * while inactive, the stored fuel (1 signal step per 200 fuel-seconds, capped at 15).
	 */
	@Override
	protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
		if (!(level.getBlockEntity(pos) instanceof BubbleShieldBlockEntity blockEntity)) {
			return 0;
		}

		ShieldState shield = blockEntity.getShieldState();
		if (shield.active) {
			return Math.max(1, Math.round(15.0F * shield.health / shield.maxHealth));
		}

		return Math.min(15, shield.fuelSeconds / 200);
	}
}

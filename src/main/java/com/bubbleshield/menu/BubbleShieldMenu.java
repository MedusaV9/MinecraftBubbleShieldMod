package com.bubbleshield.menu;

import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.registry.ModMenus;
import com.bubbleshield.shield.FuelMap;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import org.jspecify.annotations.Nullable;

/**
 * Furnace-like menu for the bubble shield projector: one fuel slot plus the
 * player inventory, and a six-value {@link ContainerData} snapshot of the shield.
 *
 * <p>All time values are synced in SECONDS (not ticks) because container data
 * slots are replicated as 16-bit signed values.
 */
public class BubbleShieldMenu extends AbstractContainerMenu {
	public static final int DATA_FUEL_SECONDS = 0;
	public static final int DATA_HEALTH_TIMES_10 = 1;
	public static final int DATA_DIAMETER = 2;
	public static final int DATA_EFFECT_ID = 3;
	public static final int DATA_ACTIVE = 4;
	public static final int DATA_COOLDOWN_SECONDS = 5;
	public static final int DATA_COUNT = 6;

	public static final int FUEL_SLOT = 0;
	private static final int INV_SLOT_START = 1;
	private static final int INV_SLOT_END = 28;
	private static final int HOTBAR_SLOT_START = 28;
	private static final int HOTBAR_SLOT_END = 37;

	private final Container container;
	private final ContainerData data;
	private final BlockPos pos;
	/** The projector this menu is attached to; null on the client. */
	private final @Nullable BubbleShieldBlockEntity blockEntity;

	/** Client-side constructor, invoked by the ExtendedMenuType with the synced BlockPos. */
	public BubbleShieldMenu(int containerId, Inventory inventory, BlockPos pos) {
		this(containerId, inventory, pos, new SimpleContainer(1), new SimpleContainerData(DATA_COUNT), null);
	}

	/** Server-side constructor. */
	public BubbleShieldMenu(int containerId, Inventory inventory, BubbleShieldBlockEntity blockEntity) {
		this(containerId, inventory, blockEntity.getBlockPos(), blockEntity.getFuelContainer(), blockEntity.getMenuData(), blockEntity);
	}

	private BubbleShieldMenu(
		int containerId,
		Inventory inventory,
		BlockPos pos,
		Container container,
		ContainerData data,
		@Nullable BubbleShieldBlockEntity blockEntity
	) {
		super(ModMenus.BUBBLE_SHIELD, containerId);
		checkContainerSize(container, 1);
		checkContainerDataCount(data, DATA_COUNT);
		this.container = container;
		this.data = data;
		this.pos = pos;
		this.blockEntity = blockEntity;

		this.addSlot(new Slot(container, 0, 56, 35) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return FuelMap.fuelSeconds(stack.getItem()) > 0;
			}
		});
		this.addStandardInventorySlots(inventory, 8, 84);
		this.addDataSlots(data);
	}

	public BlockPos pos() {
		return this.pos;
	}

	public int fuelSeconds() {
		return this.data.get(DATA_FUEL_SECONDS);
	}

	public float health() {
		return this.data.get(DATA_HEALTH_TIMES_10) / 10.0F;
	}

	public int diameter() {
		return this.data.get(DATA_DIAMETER);
	}

	public int effectId() {
		return this.data.get(DATA_EFFECT_ID);
	}

	public boolean isActive() {
		return this.data.get(DATA_ACTIVE) != 0;
	}

	public int cooldownSeconds() {
		return this.data.get(DATA_COOLDOWN_SECONDS);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slotIndex) {
		ItemStack clicked = ItemStack.EMPTY;
		Slot slot = this.slots.get(slotIndex);
		if (slot != null && slot.hasItem()) {
			ItemStack stack = slot.getItem();
			clicked = stack.copy();
			if (slotIndex == FUEL_SLOT) {
				if (!this.moveItemStackTo(stack, INV_SLOT_START, HOTBAR_SLOT_END, true)) {
					return ItemStack.EMPTY;
				}
			} else if (this.slots.get(FUEL_SLOT).mayPlace(stack)) {
				if (!this.moveItemStackTo(stack, FUEL_SLOT, FUEL_SLOT + 1, false)) {
					return ItemStack.EMPTY;
				}
			} else if (slotIndex >= INV_SLOT_START && slotIndex < INV_SLOT_END) {
				if (!this.moveItemStackTo(stack, HOTBAR_SLOT_START, HOTBAR_SLOT_END, false)) {
					return ItemStack.EMPTY;
				}
			} else if (slotIndex >= HOTBAR_SLOT_START && slotIndex < HOTBAR_SLOT_END && !this.moveItemStackTo(stack, INV_SLOT_START, INV_SLOT_END, false)) {
				return ItemStack.EMPTY;
			}

			if (stack.isEmpty()) {
				slot.setByPlayer(ItemStack.EMPTY);
			} else {
				slot.setChanged();
			}

			if (stack.getCount() == clicked.getCount()) {
				return ItemStack.EMPTY;
			}

			slot.onTake(player, stack);
		}

		return clicked;
	}

	@Override
	public boolean stillValid(Player player) {
		// The block entity is only present on the server; the client menu is never queried authoritatively.
		return this.blockEntity == null || Container.stillValidBlockEntity(this.blockEntity, player);
	}
}

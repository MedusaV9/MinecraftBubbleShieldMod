package com.bubbleshield.menu;

import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.registry.ModItems;
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
 * Furnace-like menu for the bubble shield projector: one fuel slot, one upgrade-core
 * slot, the player inventory, and a {@link ContainerData} snapshot of the shield.
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
	public static final int DATA_TIER = 6;
	public static final int DATA_SHAPE = 7;
	public static final int DATA_MODE = 8;
	public static final int DATA_CYCLE = 9;
	public static final int DATA_CAPACITOR = 10;
	public static final int DATA_BEAM = 11;
	public static final int DATA_COUNT = 12;

	public static final int FUEL_SLOT = 0;
	public static final int CORE_SLOT = 1;
	public static final int CAPACITOR_SLOT = 2;
	private static final int INV_SLOT_START = 3;
	private static final int INV_SLOT_END = 30;
	private static final int HOTBAR_SLOT_START = 30;
	private static final int HOTBAR_SLOT_END = 39;

	private final Container fuelContainer;
	private final Container coreContainer;
	private final Container capacitorContainer;
	private final ContainerData data;
	private final BlockPos pos;
	/** The projector this menu is attached to; null on the client. */
	private final @Nullable BubbleShieldBlockEntity blockEntity;

	/** Client-side constructor, invoked by the ExtendedMenuType with the synced BlockPos. */
	public BubbleShieldMenu(int containerId, Inventory inventory, BlockPos pos) {
		this(containerId, inventory, pos, new SimpleContainer(1), new SimpleContainer(1), new SimpleContainer(1), new SimpleContainerData(DATA_COUNT), null);
	}

	/** Server-side constructor. */
	public BubbleShieldMenu(int containerId, Inventory inventory, BubbleShieldBlockEntity blockEntity) {
		this(containerId, inventory, blockEntity.getBlockPos(), blockEntity.getFuelContainer(), blockEntity.getCoreContainer(), blockEntity.getCapacitorContainer(), blockEntity.getMenuData(), blockEntity);
	}

	private BubbleShieldMenu(
		int containerId,
		Inventory inventory,
		BlockPos pos,
		Container fuelContainer,
		Container coreContainer,
		Container capacitorContainer,
		ContainerData data,
		@Nullable BubbleShieldBlockEntity blockEntity
	) {
		super(ModMenus.BUBBLE_SHIELD, containerId);
		checkContainerSize(fuelContainer, 1);
		checkContainerSize(coreContainer, 1);
		checkContainerSize(capacitorContainer, 1);
		checkContainerDataCount(data, DATA_COUNT);
		this.fuelContainer = fuelContainer;
		this.coreContainer = coreContainer;
		this.capacitorContainer = capacitorContainer;
		this.data = data;
		this.pos = pos;
		this.blockEntity = blockEntity;

		this.addSlot(new Slot(fuelContainer, 0, 56, 35) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return FuelMap.fuelSeconds(stack.getItem()) > 0;
			}
		});
		this.addSlot(new Slot(coreContainer, 0, 56, 53) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return isCore(stack);
			}
		});
		this.addSlot(new Slot(capacitorContainer, 0, 56, 17) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return stack.is(ModItems.FLUX_CAPACITOR);
			}
		});
		this.addStandardInventorySlots(inventory, 8, 84);
		this.addDataSlots(data);
	}

	/** @return true for the two upgrade-core items accepted by the core slot. */
	private static boolean isCore(ItemStack stack) {
		return stack.is(ModItems.RESONANT_CORE) || stack.is(ModItems.PRISMATIC_CORE);
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

	public int tier() {
		return this.data.get(DATA_TIER);
	}

	/** @return the synced {@link com.bubbleshield.shield.ShieldShape} ordinal. */
	public int shape() {
		return this.data.get(DATA_SHAPE);
	}

	/** @return the synced {@link com.bubbleshield.shield.ShieldMode} ordinal. */
	public int mode() {
		return this.data.get(DATA_MODE);
	}

	/** @return the synced effect-cycle toggle. */
	public boolean cycleEffect() {
		return this.data.get(DATA_CYCLE) != 0;
	}

	/** @return the synced flux-capacitor flag (a capacitor sits in the capacitor slot). */
	public boolean hasCapacitor() {
		return this.data.get(DATA_CAPACITOR) != 0;
	}

	/** @return the synced {@link com.bubbleshield.shield.BeamStyle} ordinal (16-bit safe: max ordinal 9). */
	public int beamStyle() {
		return this.data.get(DATA_BEAM);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slotIndex) {
		ItemStack clicked = ItemStack.EMPTY;
		Slot slot = this.slots.get(slotIndex);
		if (slot != null && slot.hasItem()) {
			ItemStack stack = slot.getItem();
			clicked = stack.copy();
			if (slotIndex == FUEL_SLOT || slotIndex == CORE_SLOT || slotIndex == CAPACITOR_SLOT) {
				if (!this.moveItemStackTo(stack, INV_SLOT_START, HOTBAR_SLOT_END, true)) {
					return ItemStack.EMPTY;
				}
			} else if (this.slots.get(FUEL_SLOT).mayPlace(stack)) {
				if (!this.moveItemStackTo(stack, FUEL_SLOT, FUEL_SLOT + 1, false)) {
					return ItemStack.EMPTY;
				}
			} else if (isCore(stack)) {
				if (!this.moveItemStackTo(stack, CORE_SLOT, CORE_SLOT + 1, false)) {
					return ItemStack.EMPTY;
				}
			} else if (stack.is(ModItems.FLUX_CAPACITOR)) {
				if (!this.moveItemStackTo(stack, CAPACITOR_SLOT, CAPACITOR_SLOT + 1, false)) {
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

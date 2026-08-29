package com.arxyt.customnpcstaczcompat;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.ItemHandlerHelper;
import noppes.npcs.entity.EntityNPCInterface;

/** Exposes CNPC projectile/drop slots to TaCZ's normal ammunition lookup. */
public final class NpcAmmoItemHandler implements IItemHandlerModifiable {
    private static final int SLOT_COUNT = 9;
    private final EntityNPCInterface npc;

    public NpcAmmoItemHandler(EntityNPCInterface npc) { this.npc = npc; }

    private static int inventorySlot(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) throw new IllegalArgumentException("Invalid NPC ammo slot " + slot);
        return slot == 0 ? 5 : slot + 6;
    }

    @Override public int getSlots() { return SLOT_COUNT; }
    @Override public ItemStack getStackInSlot(int slot) { return npc.inventory.getItem(inventorySlot(slot)); }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack current = getStackInSlot(slot);
        if (!current.isEmpty() && !ItemHandlerHelper.canItemStacksStack(current, stack)) return stack;
        int accepted = Math.min(Math.min(getSlotLimit(slot), stack.getMaxStackSize()) - current.getCount(), stack.getCount());
        if (accepted <= 0) return stack;
        if (!simulate) {
            ItemStack replacement = current.isEmpty() ? stack.copy() : current.copy();
            replacement.setCount(current.getCount() + accepted);
            setStackInSlot(slot, replacement);
        }
        ItemStack remainder = stack.copy();
        remainder.shrink(accepted);
        return remainder;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        ItemStack current = getStackInSlot(slot);
        if (current.isEmpty() || amount <= 0) return ItemStack.EMPTY;
        ItemStack result = current.copy();
        result.setCount(Math.min(amount, current.getCount()));
        if (!simulate) {
            ItemStack replacement = current.copy();
            replacement.shrink(result.getCount());
            setStackInSlot(slot, replacement);
        }
        return result;
    }

    @Override public int getSlotLimit(int slot) { inventorySlot(slot); return 64; }
    @Override public boolean isItemValid(int slot, ItemStack stack) { inventorySlot(slot); return true; }
    @Override public void setStackInSlot(int slot, ItemStack stack) {
        npc.inventory.setItem(inventorySlot(slot), stack.copy());
        npc.updateClient = true;
    }
}

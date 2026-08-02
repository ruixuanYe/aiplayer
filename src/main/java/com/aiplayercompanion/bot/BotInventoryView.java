package com.aiplayercompanion.bot;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;

public class BotInventoryView implements Inventory {
    private static final int VIEW_SIZE = 54;

    private final AIPlayerBot bot;

    public BotInventoryView(AIPlayerBot bot) {
        this.bot = bot;
    }

    @Override
    public int size() {
        return VIEW_SIZE;
    }

    @Override
    public boolean isEmpty() {
        return bot.getInventory().isEmpty();
    }

    @Override
    public ItemStack getStack(int slot) {
        return isBotSlot(slot) ? bot.getInventory().getStack(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeStack(int slot, int amount) {
        return isBotSlot(slot) ? bot.getInventory().removeStack(slot, amount) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeStack(int slot) {
        return isBotSlot(slot) ? bot.getInventory().removeStack(slot) : ItemStack.EMPTY;
    }

    @Override
    public void setStack(int slot, ItemStack stack) {
        if (isBotSlot(slot)) {
            bot.getInventory().setStack(slot, stack);
        }
    }

    @Override
    public void markDirty() {
        bot.getInventory().markDirty();
    }

    @Override
    public boolean canPlayerUse(PlayerEntity player) {
        return player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer
                && bot.getOwnerUuid().equals(serverPlayer.getUuid())
                && bot.isAlive()
                && !bot.isRemoved();
    }

    @Override
    public void clear() {
        bot.getInventory().clear();
    }

    private boolean isBotSlot(int slot) {
        return slot >= 0 && slot < bot.getInventory().size();
    }
}

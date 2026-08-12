package com.yourserver.chestlogger.gui;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;

public class ChestLogMenu extends ChestMenu {
    private final ChestLogGui gui;

    public ChestLogMenu(int syncId, Inventory playerInventory, Container container, ChestLogGui gui) {
        super(MenuType.GENERIC_9x6, syncId, playerInventory, container, 6);
        this.gui = gui;
    }

    public ChestLogGui getGui() {
        return gui;
    }
}

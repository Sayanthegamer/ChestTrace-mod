package com.yourserver.chestlogger.gui;

import com.yourserver.chestlogger.ChestLoggerMod;
import com.yourserver.chestlogger.logging.ChestLogEvent;
import com.yourserver.chestlogger.logging.ChestLogReader;
import com.yourserver.chestlogger.rollback.ChestLogRollback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class ChestLogGui implements MenuProvider {
    private final BlockPos chestPos;
    private final List<ChestLogEvent> rawEvents;
    private List<ChestLogAggregator.AggregatedEntry> aggregatedEntries;
    private int page = 0;
    private boolean aggregatedMode = true;

    private static final int PAGE_SIZE = 45;

    public ChestLogGui(BlockPos chestPos, List<ChestLogEvent> rawEvents) {
        this.chestPos = chestPos;
        this.rawEvents = rawEvents != null ? rawEvents : Collections.emptyList();
        recalculate();
    }

    private void recalculate() {
        this.aggregatedEntries = ChestLogAggregator.aggregate(rawEvents, 300_000L); // 5 minute window
    }

    public static void open(ServerPlayer player, BlockPos pos) {
        long packedPos = pos.asLong();
        Path logDir = ChestLoggerMod.logDirectory();
        player.sendSystemMessage(Component.literal("§7[ChestLogger] Loading history GUI for " + pos.toShortString() + "..."));

        CompletableFuture.supplyAsync(() -> ChestLogReader.queryAll(logDir, ChestLoggerMod.writer(), packedPos))
            .thenAccept(query -> {
                try {
                    MinecraftServer server = getServer(player);
                    if (server != null) {
                        server.execute(() -> {
                            try {
                                ChestLogGui gui = new ChestLogGui(pos, query.events());
                                player.openMenu(gui);
                            } catch (Throwable t) {
                                ChestLoggerMod.LOGGER.error("Failed to populate/open ChestLogGui", t);
                                player.sendSystemMessage(Component.literal("§c[ChestLogger] Error opening GUI: " + t.getMessage()));
                            }
                        });
                    }
                } catch (Throwable t) {
                    ChestLoggerMod.LOGGER.error("Failed to schedule openMenu task on server thread", t);
                }
            });
    }

    private static MinecraftServer getServer(ServerPlayer player) {
        if (player == null) return null;
        try {
            return player.serverLevel().getServer();
        } catch (Throwable t1) {
            try {
                return (MinecraftServer) player.getClass().getMethod("getServer").invoke(player);
            } catch (Throwable t2) {
                try {
                    return (MinecraftServer) player.getClass().getField("server").get(player);
                } catch (Throwable t3) {
                    return null;
                }
            }
        }
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Chest History: " + chestPos.toShortString());
    }

    @Override
    public AbstractContainerMenu createMenu(int syncId, Inventory playerInventory, Player player) {
        ChestMenu menu = ChestMenu.sixRows(syncId, playerInventory);
        populateMenu(menu, player);
        return menu;
    }

    private void populateMenu(ChestMenu menu, Player player) {
        // Clear slots
        for (int i = 0; i < 54; i++) {
            menu.getContainer().setItem(i, ItemStack.EMPTY);
        }

        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        if (aggregatedMode) {
            int totalPages = Math.max(1, (int) Math.ceil((double) aggregatedEntries.size() / PAGE_SIZE));
            page = Math.max(0, Math.min(page, totalPages - 1));

            int start = page * PAGE_SIZE;
            int end = Math.min(start + PAGE_SIZE, aggregatedEntries.size());

            for (int i = start; i < end; i++) {
                ChestLogAggregator.AggregatedEntry entry = aggregatedEntries.get(i);
                int slotIndex = i - start;

                Item item = getItemFromIdentifier(ResourceLocation.tryParse(entry.itemId()));
                if (item == null) item = Items.BARRIER;

                int displayCount = Math.min(64, Math.max(1, Math.abs(entry.netCountDiff())));
                ItemStack icon = new ItemStack(item, displayCount);

                String prefix = entry.netCountDiff() > 0 ? "§a+" : "§c";
                String actionStr = entry.netCountDiff() > 0 ? "§aAdded / Restored" : "§cRemoved";

                Component customName = Component.literal(prefix + Math.abs(entry.netCountDiff()) + "x " + getItemDisplayName(item));

                List<Component> lore = new ArrayList<>();
                lore.add(Component.literal("§7Action: " + actionStr));
                lore.add(Component.literal("§7Player: §f" + (entry.playerId() != null ? entry.playerId().toString().substring(0, 8) : "unknown")));
                lore.add(Component.literal("§7Latest: §f" + df.format(new Date(entry.latestTimestamp()))));
                lore.add(Component.literal("§7Events Merged: §f" + entry.eventCount()));

                try {
                    icon.set(DataComponents.CUSTOM_NAME, customName);
                    icon.set(DataComponents.LORE, new ItemLore(lore));
                } catch (Throwable t) {
                    icon.setHoverName(customName);
                }

                menu.getContainer().setItem(slotIndex, icon);
            }
        } else {
            // Raw mode
            List<ChestLogEvent> nonAdmin = rawEvents.stream().filter(e -> !e.isAdminEvent()).toList();
            int totalPages = Math.max(1, (int) Math.ceil((double) nonAdmin.size() / PAGE_SIZE));
            page = Math.max(0, Math.min(page, totalPages - 1));

            int start = page * PAGE_SIZE;
            int end = Math.min(start + PAGE_SIZE, nonAdmin.size());

            for (int i = start; i < end; i++) {
                ChestLogEvent e = nonAdmin.get(i);
                int slotIndex = i - start;

                Item item = getItemFromIdentifier(ResourceLocation.tryParse(e.itemId));
                if (item == null) item = Items.BARRIER;

                int displayCount = Math.min(64, Math.max(1, Math.abs(e.countDiff)));
                ItemStack icon = new ItemStack(item, displayCount);

                String prefix = e.countDiff > 0 ? "§a+" : "§c";
                String actionStr = e.countDiff > 0 ? "§aAdded" : "§cRemoved";

                Component customName = Component.literal(prefix + Math.abs(e.countDiff) + "x " + getItemDisplayName(item));

                List<Component> lore = new ArrayList<>();
                lore.add(Component.literal("§7Action: " + actionStr));
                lore.add(Component.literal("§7Player: §f" + (e.playerId != null ? e.playerId.toString().substring(0, 8) : "unknown")));
                lore.add(Component.literal("§7Time: §f" + df.format(new Date(e.timestampMillis))));
                lore.add(Component.literal("§8Tx ID: #" + e.transactionId));

                try {
                    icon.set(DataComponents.CUSTOM_NAME, customName);
                    icon.set(DataComponents.LORE, new ItemLore(lore));
                } catch (Throwable t) {
                    icon.setHoverName(customName);
                }

                menu.getContainer().setItem(slotIndex, icon);
            }
        }

        // --- Bottom Control Bar (Slots 45 to 53) ---
        // Slot 45: Previous Page
        ItemStack prevBtn = new ItemStack(Items.PAPER);
        try { prevBtn.set(DataComponents.CUSTOM_NAME, Component.literal("§e[ Previous Page ]")); } catch (Throwable t) { prevBtn.setHoverName(Component.literal("§e[ Previous Page ]")); }
        menu.getContainer().setItem(45, prevBtn);

        // Slot 48: Mode Toggle
        ItemStack modeBtn = new ItemStack(Items.BOOK);
        try {
            modeBtn.set(DataComponents.CUSTOM_NAME, Component.literal("§b[ Mode: " + (aggregatedMode ? "Aggregated Net" : "Raw Events") + " ]"));
            modeBtn.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Click to toggle between aggregated summary and raw click logs."))));
        } catch (Throwable t) {
            modeBtn.setHoverName(Component.literal("§b[ Mode: " + (aggregatedMode ? "Aggregated Net" : "Raw Events") + " ]"));
        }
        menu.getContainer().setItem(48, modeBtn);

        // Slot 49: Rollback Button
        ItemStack rollbackBtn = new ItemStack(Items.ANVIL);
        try {
            rollbackBtn.set(DataComponents.CUSTOM_NAME, Component.literal("§c[ Rollback Chest (5m) ]"));
            rollbackBtn.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Click to execute an instant 5-minute rollback on this chest."))));
        } catch (Throwable t) {
            rollbackBtn.setHoverName(Component.literal("§c[ Rollback Chest (5m) ]"));
        }
        menu.getContainer().setItem(49, rollbackBtn);

        // Slot 53: Next Page
        ItemStack nextBtn = new ItemStack(Items.PAPER);
        try { nextBtn.set(DataComponents.CUSTOM_NAME, Component.literal("§e[ Next Page ]")); } catch (Throwable t) { nextBtn.setHoverName(Component.literal("§e[ Next Page ]")); }
        menu.getContainer().setItem(53, nextBtn);
    }

    public boolean handleControlClick(int slotIndex, Player player, ChestMenu menu) {
        if (slotIndex < 45) {
            return true; // Cancel movement of history items
        }

        if (slotIndex == 45) {
            // Previous Page
            if (page > 0) {
                page--;
                populateMenu(menu, player);
            }
            return true;
        }

        if (slotIndex == 48) {
            // Toggle Mode
            aggregatedMode = !aggregatedMode;
            page = 0;
            populateMenu(menu, player);
            return true;
        }

        if (slotIndex == 49) {
            // Rollback 5m
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.closeContainer();
                serverPlayer.sendSystemMessage(Component.literal("§7[ChestLogger] Executing GUI 5m rollback for " + chestPos.toShortString() + "..."));
                long cutoff = System.currentTimeMillis() - 300_000L;
                ChestLogRollback.Result res = ChestLogRollback.rollback(serverPlayer.serverLevel(), chestPos, rawEvents, cutoff);
                if (res.success()) {
                    serverPlayer.sendSystemMessage(Component.literal(String.format("§a[ChestLogger] GUI Rollback committed: %d restored, %d removed (%d txs).", res.restoredItems(), res.removedItems(), res.transactionCount())));
                } else {
                    serverPlayer.sendSystemMessage(Component.literal("§c[ChestLogger] Rollback Failed: " + res.errorMessage()));
                }
            }
            return true;
        }

        if (slotIndex == 53) {
            // Next Page
            int total = aggregatedMode ? aggregatedEntries.size() : rawEvents.size();
            int maxPages = Math.max(1, (int) Math.ceil((double) total / PAGE_SIZE));
            if (page < maxPages - 1) {
                page++;
                populateMenu(menu, player);
            }
            return true;
        }

        return true;
    }

    private Item getItemFromIdentifier(ResourceLocation id) {
        if (id == null) return Items.AIR;
        try {
            return BuiltInRegistries.ITEM.get(id).map(net.minecraft.core.Holder::value).orElse(Items.AIR);
        } catch (Throwable t) {
            try {
                for (java.lang.reflect.Method m : BuiltInRegistries.ITEM.getClass().getMethods()) {
                    if (m.getName().equals("get") || m.getName().equals("getValue") || m.getName().equals("getOptional")) {
                        if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == ResourceLocation.class) {
                            Object res = m.invoke(BuiltInRegistries.ITEM, id);
                            if (res instanceof Item item) return item;
                            if (res instanceof java.util.Optional opt) {
                                Object inner = opt.orElse(null);
                                if (inner instanceof net.minecraft.core.Holder h) return (Item) h.value();
                                if (inner instanceof Item item) return item;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return Items.AIR;
    }

    private String getItemDisplayName(Item item) {
        if (item == null || item == Items.AIR) return "Air";
        String key = item.getDescriptionId();
        int lastDot = key.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < key.length() - 1) {
            String rawName = key.substring(lastDot + 1).replace('_', ' ');
            return Character.toUpperCase(rawName.charAt(0)) + rawName.substring(1);
        }
        return key;
    }
}

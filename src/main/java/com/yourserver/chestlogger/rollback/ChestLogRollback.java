package com.yourserver.chestlogger.rollback;

import com.yourserver.chestlogger.ChestLoggerMod;
import com.yourserver.chestlogger.logging.ChestLogEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.*;

public final class ChestLogRollback {
    private ChestLogRollback() {}

    public record Result(
        boolean success,
        String errorMessage,
        int restoredItems,
        int removedItems,
        int droppedItems,
        int transactionCount
    ) {
        public static Result failure(String msg) {
            return new Result(false, msg, 0, 0, 0, 0);
        }
    }

    private static String getItemIdentifier(Item item) {
        if (item == null) return "minecraft:air";
        try {
            Object loc = BuiltInRegistries.ITEM.getKey(item);
            if (loc != null) return loc.toString();
        } catch (Throwable t) {
            try {
                for (java.lang.reflect.Method m : BuiltInRegistries.ITEM.getClass().getMethods()) {
                    if (m.getName().equals("getKey") && m.getParameterCount() == 1) {
                        Object res = m.invoke(BuiltInRegistries.ITEM, item);
                        if (res != null) return res.toString();
                    }
                }
            } catch (Throwable ignored) {}
        }
        return item.toString();
    }

    private static Item getItemFromIdentifier(String itemIdStr) {
        if (itemIdStr == null || itemIdStr.isEmpty()) return null;
        try {
            ResourceLocation id = ResourceLocation.tryParse(itemIdStr);
            if (id != null) {
                Item item = BuiltInRegistries.ITEM.get(id).map(net.minecraft.core.Holder::value).orElse(null);
                if (item != null && item != Items.AIR) return item;
            }
        } catch (Throwable ignored) {}

        try {
            for (Item item : BuiltInRegistries.ITEM) {
                Object key = BuiltInRegistries.ITEM.getKey(item);
                if (key != null && itemIdStr.equals(key.toString())) {
                    return item;
                }
            }
        } catch (Throwable ignored) {}

        return null;
    }

    public static Result rollback(ServerLevel world, BlockPos pos, List<ChestLogEvent> events, long cutoffMillis) {
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (!(blockEntity instanceof Container inventory)) {
            return Result.failure("Target block at " + pos.toShortString() + " is not a valid inventory container.");
        }

        // --- PHASE 1: Calculate Net Inverse Differences ---
        Map<Long, Map<String, Integer>> inverseByTransaction = new LinkedHashMap<>();
        int eventCount = 0;

        for (ChestLogEvent event : events) {
            if (event.timestampMillis < cutoffMillis) continue;
            if (event.isAdminEvent()) continue; // Ignore past admin rollbacks

            Map<String, Integer> txMap = inverseByTransaction.computeIfAbsent(
                event.transactionId, k -> new LinkedHashMap<>()
            );

            // Invert the log diff: if original event was -X (removed), we add +X back.
            // If original event was +X (added), we subtract -X.
            int inverseDiff = -event.countDiff;
            txMap.merge(event.itemId, inverseDiff, Integer::sum);

            eventCount++;
        }

        inverseByTransaction.values().forEach(map -> map.entrySet().removeIf(e -> e.getValue() == 0));

        Map<String, Integer> totalInverse = new LinkedHashMap<>();
        for (Map<String, Integer> txMap : inverseByTransaction.values()) {
            for (Map.Entry<String, Integer> entry : txMap.entrySet()) {
                totalInverse.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }

        // --- PHASE 2: Snapshot Live Backup & Virtual Preflight Verification ---
        int invSize = inventory.getContainerSize();
        ItemStack[] originalLiveSlots = new ItemStack[invSize];
        ItemStack[] virtualSlots = new ItemStack[invSize];
        Map<String, Integer> availableCounts = new HashMap<>();

        for (int i = 0; i < invSize; i++) {
            ItemStack stack = inventory.getItem(i);
            originalLiveSlots[i] = stack.copy(); 
            virtualSlots[i] = stack.copy();
            if (!stack.isEmpty()) {
                String itemId = getItemIdentifier(stack.getItem());
                availableCounts.merge(itemId, stack.getCount(), Integer::sum);
            }
        }

        Map<Item, Integer> toRestore = new LinkedHashMap<>();
        Map<Item, Integer> toRemove = new LinkedHashMap<>();

        for (Map.Entry<String, Integer> entry : totalInverse.entrySet()) {
            int netInverse = entry.getValue();
            if (netInverse == 0) continue;

            Item item = getItemFromIdentifier(entry.getKey());
            if (item == null) return Result.failure("Item missing from Registry: " + entry.getKey());

            if (netInverse < 0) {
                int requiredToRemove = Math.abs(netInverse);
                int availableInChest = availableCounts.getOrDefault(entry.getKey(), 0);
                if (availableInChest < requiredToRemove) {
                    return Result.failure(String.format("Preflight Failed: Cannot remove %d x %s (Only %d present)", requiredToRemove, entry.getKey(), availableInChest));
                }
                toRemove.put(item, requiredToRemove);
            } else {
                toRestore.put(item, netInverse);
            }
        }

        // --- PHASE 3: Virtual Slot Simulation ---
        // 3a. Remove items virtually
        for (Map.Entry<Item, Integer> entry : toRemove.entrySet()) {
            Item item = entry.getKey();
            int amountRemaining = entry.getValue();
            for (int i = 0; i < invSize && amountRemaining > 0; i++) {
                ItemStack stack = virtualSlots[i];
                if (stack.is(item)) {
                    int toTake = Math.min(stack.getCount(), amountRemaining);
                    stack.shrink(toTake);
                    amountRemaining -= toTake;
                }
            }
            if (amountRemaining > 0) {
                return Result.failure("Preflight Failed: Virtual slot removal mismatch for " + item);
            }
        }

        // 3b. Add items virtually
        int restoredCount = 0;
        int droppedCount = 0;

        for (Map.Entry<Item, Integer> entry : toRestore.entrySet()) {
            Item item = entry.getKey();
            int amountToAdd = entry.getValue();
            restoredCount += amountToAdd;

            // Fill existing partial stacks first
            for (int i = 0; i < invSize && amountToAdd > 0; i++) {
                ItemStack stack = virtualSlots[i];
                if (stack.is(item) && stack.getCount() < stack.getMaxStackSize()) {
                    int space = stack.getMaxStackSize() - stack.getCount();
                    int toAdd = Math.min(space, amountToAdd);
                    stack.grow(toAdd);
                    amountToAdd -= toAdd;
                }
            }

            // Fill empty slots next
            for (int i = 0; i < invSize && amountToAdd > 0; i++) {
                ItemStack stack = virtualSlots[i];
                if (stack.isEmpty()) {
                    int toAdd = Math.min(item.getDefaultMaxStackSize(), amountToAdd);
                    virtualSlots[i] = new ItemStack(item, toAdd);
                    amountToAdd -= toAdd;
                }
            }

            // Excess items overflow onto ground
            if (amountToAdd > 0) {
                droppedCount += amountToAdd;
            }
        }

        int removedCount = toRemove.values().stream().mapToInt(Integer::intValue).sum();

        // --- PHASE 4: Transactional Commit to Live Inventory ---
        try {
            for (int i = 0; i < invSize; i++) {
                inventory.setItem(i, virtualSlots[i]);
            }
            inventory.setChanged();

            // Spawn overflow items in world safely
            if (droppedCount > 0) {
                double dropX = pos.getX() + 0.5;
                double dropY = pos.getY() + 1.0;
                double dropZ = pos.getZ() + 0.5;
                for (Map.Entry<Item, Integer> entry : toRestore.entrySet()) {
                    Item item = entry.getKey();
                    int overflow = entry.getValue();
                    while (overflow > 0) {
                        int stackSize = Math.min(item.getDefaultMaxStackSize(), overflow);
                        ItemStack dropStack = new ItemStack(item, stackSize);
                        ItemEntity entity = new ItemEntity(world, dropX, dropY, dropZ, dropStack);
                        entity.setDefaultPickUpDelay();
                        world.addFreshEntity(entity);
                        overflow -= stackSize;
                    }
                }
            }

            return new Result(
                true,
                null,
                restoredCount,
                removedCount,
                droppedCount,
                inverseByTransaction.size()
            );
        } catch (Throwable commitError) {
            // ROLLBACK / REVERT ALL MUTATIONS TO SNAPSHOT
            try {
                for (int i = 0; i < invSize; i++) {
                    inventory.setItem(i, originalLiveSlots[i]);
                }
                inventory.setChanged();
            } catch (Throwable rollbackError) {
                ChestLoggerMod.LOGGER.error("CRITICAL: Failed to revert live snapshot after commit error!", rollbackError);
            }
            return Result.failure("Transactional Commit Exception: " + commitError.getMessage());
        }
    }
}

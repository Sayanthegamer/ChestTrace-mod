package com.yourserver.chestlogger.rollback;

import com.yourserver.chestlogger.logging.ChestLogEvent;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public final class ChestLogRollback {

    private static final Logger LOGGER = LoggerFactory.getLogger("ChestLoggerRollback");

    private ChestLogRollback() {}

    public record Result(
            boolean success,
            String errorMessage,
            int transactionCount,
            int eventCount,
            int restoredItems,
            int removedItems,
            int droppedItems
    ) {
        public static Result failure(String errorMessage) {
            return new Result(false, errorMessage, 0, 0, 0, 0, 0);
        }

        public static Result success(int transactionCount, int eventCount, int restoredItems, int removedItems, int droppedItems) {
            return new Result(true, null, transactionCount, eventCount, restoredItems, removedItems, droppedItems);
        }
    }

    public static Result rollback(
            ServerWorld world,
            BlockPos targetPos,
            List<ChestLogEvent> events,
            long cutoffMillis
    ) {
        BlockEntity blockEntity = world.getBlockEntity(targetPos);
        if (!(blockEntity instanceof Inventory inventory)) {
            return Result.failure("Target block is not a valid container inventory at " + targetPos.toShortString());
        }

        // --- PHASE 1: Group events by Tx ID & aggregate net inverse deltas ---
        Map<Long, Map<String, Integer>> inverseByTransaction = new LinkedHashMap<>();
        int eventCount = 0;

        for (ChestLogEvent event : events) {
            if (event.packedBlockPos != targetPos.asLong() || event.timestampMillis < cutoffMillis || event.isAdminEvent()) {
                continue;
            }

            inverseByTransaction
                    .computeIfAbsent(event.transactionId, k -> new LinkedHashMap<>())
                    .merge(event.itemId, -(int) event.countDiff, Integer::sum);

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
        int invSize = inventory.size();
        ItemStack[] originalLiveSlots = new ItemStack[invSize];
        ItemStack[] virtualSlots = new ItemStack[invSize];
        Map<String, Integer> availableCounts = new HashMap<>();

        for (int i = 0; i < invSize; i++) {
            ItemStack stack = inventory.getStack(i);
            originalLiveSlots[i] = stack.copy(); 
            virtualSlots[i] = stack.copy();
            if (!stack.isEmpty()) {
                String itemId = Registries.ITEM.getId(stack.getItem()).toString();
                availableCounts.merge(itemId, stack.getCount(), Integer::sum);
            }
        }

        Map<Item, Integer> toRestore = new LinkedHashMap<>();
        Map<Item, Integer> toRemove = new LinkedHashMap<>();

        for (Map.Entry<String, Integer> entry : totalInverse.entrySet()) {
            int netInverse = entry.getValue();
            if (netInverse == 0) continue;

            Identifier id = Identifier.tryParse(entry.getKey());
            if (id == null) return Result.failure("Unresolvable Item Identifier: " + entry.getKey());
            Item item = Registries.ITEM.get(id);
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

        // --- PHASE 3: Virtual Simulation Workspace ---
        int restored = 0;
        int removed = 0;
        int dropped = 0;
        List<ItemStack> pendingWorldDrops = new ArrayList<>();

        for (Map.Entry<Item, Integer> entry : toRemove.entrySet()) {
            Item item = entry.getKey();
            int remainingToRemove = entry.getValue();

            for (int i = 0; i < invSize && remainingToRemove > 0; i++) {
                ItemStack stack = virtualSlots[i];
                if (stack.isEmpty() || stack.getItem() != item) continue;

                int take = Math.min(stack.getCount(), remainingToRemove);
                stack.decrement(take);
                if (stack.isEmpty()) virtualSlots[i] = ItemStack.EMPTY;
                remainingToRemove -= take;
                removed += take;
            }
        }

        for (Map.Entry<Item, Integer> entry : toRestore.entrySet()) {
            Item item = entry.getKey();
            int remainingToRestore = entry.getValue();

            for (int i = 0; i < invSize && remainingToRestore > 0; i++) {
                ItemStack stack = virtualSlots[i];
                if (stack.isEmpty() || stack.getItem() != item) continue;

                int max = Math.min(stack.getMaxCount(), inventory.getMaxCountPerStack());
                int freeSpace = max - stack.getCount();
                if (freeSpace <= 0) continue;

                int insert = Math.min(freeSpace, remainingToRestore);
                stack.increment(insert);
                remainingToRestore -= insert;
                restored += insert;
            }

            for (int i = 0; i < invSize && remainingToRestore > 0; i++) {
                if (!virtualSlots[i].isEmpty()) continue;

                int insert = Math.min(item.getMaxCount(), remainingToRestore);
                virtualSlots[i] = new ItemStack(item, insert);
                remainingToRestore -= insert;
                restored += insert;
            }

            while (remainingToRestore > 0) {
                int dropCount = Math.min(item.getMaxCount(), remainingToRestore);
                pendingWorldDrops.add(new ItemStack(item, dropCount));
                remainingToRestore -= dropCount;
                dropped += dropCount;
            }
        }

        // --- PHASE 4: COMPENSATING COMMIT WITH DOUBLE-FAULT RECOVERY ---
        List<ItemEntity> spawnedEntities = new ArrayList<>();
        try {
            for (int i = 0; i < invSize; i++) {
                inventory.setStack(i, virtualSlots[i]);
            }
            inventory.markDirty();

            for (ItemStack dropStack : pendingWorldDrops) {
                ItemEntity entity = new ItemEntity(world,
                        targetPos.getX() + 0.5,
                        targetPos.getY() + 1.0,
                        targetPos.getZ() + 0.5,
                        dropStack);
                if (world.spawnEntity(entity)) {
                    spawnedEntities.add(entity);
                } else {
                    throw new IllegalStateException("Engine rejected ItemEntity spawn at " + targetPos.toShortString());
                }
            }
        } catch (Throwable commitError) {
            LOGGER.error("ChestLogger: commit failed; initiating best-effort compensation", commitError);
            boolean revertSucceeded = true;
            for (int i = 0; i < invSize; i++) {
                try { inventory.setStack(i, originalLiveSlots[i]); }
                catch (Throwable doubleFault) {
                    revertSucceeded = false;
                    LOGGER.error("ChestLogger: DOUBLE FAULT reverting slot " + i, doubleFault);
                }
            }
            try { inventory.markDirty(); } catch (Throwable ignored) {}
            for (ItemEntity entity : spawnedEntities) {
                try { entity.discard(); } catch (Throwable ignored) {}
            }
            if (!revertSucceeded) {
                return Result.failure("CRITICAL PANIC: commit failed and inventory compensation failed at " + targetPos.toShortString() + ". Manual admin inspection required.");
            }
            String reason = commitError.getMessage() == null ? commitError.getClass().getSimpleName() : commitError.getMessage();
            return Result.failure("Rollback failed mid-commit. Best-effort inventory compensation and entity cleanup completed: " + reason);
        }

        return Result.success(inverseByTransaction.size(), eventCount, restored, removed, dropped);
    }
}

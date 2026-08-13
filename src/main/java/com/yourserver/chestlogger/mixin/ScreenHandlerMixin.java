package com.yourserver.chestlogger.mixin;

import com.yourserver.chestlogger.ChestLoggerMod;
import com.yourserver.chestlogger.gui.ChestLogGui;
import com.yourserver.chestlogger.gui.ChestLogMenu;
import com.yourserver.chestlogger.logging.ChestLogEvent;
import com.yourserver.chestlogger.logging.ChestLogWriter;
import com.yourserver.chestlogger.logging.TransactionIdGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(AbstractContainerMenu.class)
public abstract class ScreenHandlerMixin {

    private static final ThreadLocal<PendingClickSnapshot> PENDING_SNAPSHOT = new ThreadLocal<>();

    private record SlotState(int slotIndex, ItemStack copy) {}

    private record PendingClickSnapshot(
        Container container,
        BlockPos pos,
        Player player,
        byte flags,
        List<SlotState> beforeStates
    ) {}

    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void onSlotClickIntercept(int slotIndex, int button, ClickType clickType, Player player, CallbackInfo ci) {
        AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;

        // --- GUI Click Protection & Control Handling for ChestLogMenu ---
        if (menu instanceof ChestLogMenu chestLogMenu) {
            if (player instanceof ServerPlayer serverPlayer) {
                ChestLogGui gui = chestLogMenu.getGui();
                if (gui != null) {
                    gui.handleControlClick(slotIndex, serverPlayer, chestLogMenu);
                }
            }
            ci.cancel();
            return;
        }

        ChestLogWriter writer = ChestLoggerMod.writer();
        if (writer == null || writer.isDisabled()) return;

        Container chestContainer = getChestContainer(menu);
        if (chestContainer == null) return;

        BlockPos pos = getBlockPosFromContainer(chestContainer);
        if (pos == null) return;

        byte flags = 0;
        if (clickType == ClickType.QUICK_MOVE) {
            flags |= ChestLogEvent.Flags.SHIFT_CLICK;
        } else if (clickType == ClickType.QUICK_CRAFT || clickType == ClickType.CLONE) {
            flags |= ChestLogEvent.Flags.DRAG;
        }

        List<SlotState> beforeStates = new ArrayList<>();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot != null && slot.container == chestContainer) {
                beforeStates.add(new SlotState(i, slot.getItem().copy()));
            }
        }

        if (!beforeStates.isEmpty()) {
            PENDING_SNAPSHOT.set(new PendingClickSnapshot(chestContainer, pos, player, flags, beforeStates));
        }
    }

    @Inject(method = "clicked", at = @At("TAIL"))
    private void onSlotClickTail(int slotIndex, int button, ClickType clickType, Player player, CallbackInfo ci) {
        PendingClickSnapshot snapshot = PENDING_SNAPSHOT.get();
        PENDING_SNAPSHOT.remove();

        if (snapshot == null) return;

        ChestLogWriter writer = ChestLoggerMod.writer();
        if (writer == null || writer.isDisabled()) return;

        AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
        long timestamp = System.currentTimeMillis();
        long txId = TransactionIdGenerator.next();
        long packedPos = snapshot.pos.asLong();

        for (SlotState before : snapshot.beforeStates) {
            if (before.slotIndex >= menu.slots.size()) continue;
            Slot slot = menu.slots.get(before.slotIndex);
            if (slot == null || slot.container != snapshot.container) continue;

            ItemStack oldStack = before.copy;
            ItemStack newStack = slot.getItem();

            if (oldStack.isEmpty() && newStack.isEmpty()) continue;

            if (oldStack.isEmpty() && !newStack.isEmpty()) {
                String itemId = getItemIdentifier(newStack.getItem());
                int countDiff = newStack.getCount();
                ChestLoggerMod.LOGGER.info("[ChestLogger] Slot click: +{} x {} at {}", countDiff, itemId, snapshot.pos.toShortString());
                writer.enqueue(new ChestLogEvent(timestamp, txId, player.getUUID(), packedPos, itemId, countDiff, snapshot.flags));
            } else if (!oldStack.isEmpty() && newStack.isEmpty()) {
                String itemId = getItemIdentifier(oldStack.getItem());
                int countDiff = -oldStack.getCount();
                ChestLoggerMod.LOGGER.info("[ChestLogger] Slot click: {} x {} at {}", countDiff, itemId, snapshot.pos.toShortString());
                writer.enqueue(new ChestLogEvent(timestamp, txId, player.getUUID(), packedPos, itemId, countDiff, snapshot.flags));
            } else if (ItemStack.isSameItemSameComponents(oldStack, newStack)) {
                int diff = newStack.getCount() - oldStack.getCount();
                if (diff != 0) {
                    String itemId = getItemIdentifier(newStack.getItem());
                    ChestLoggerMod.LOGGER.info("[ChestLogger] Slot click: {}{} x {} at {}", diff > 0 ? "+" : "", diff, itemId, snapshot.pos.toShortString());
                    writer.enqueue(new ChestLogEvent(timestamp, txId, player.getUUID(), packedPos, itemId, diff, snapshot.flags));
                }
            } else {
                String oldItemId = getItemIdentifier(oldStack.getItem());
                ChestLoggerMod.LOGGER.info("[ChestLogger] Slot click swap remove: -{} x {} at {}", oldStack.getCount(), oldItemId, snapshot.pos.toShortString());
                writer.enqueue(new ChestLogEvent(timestamp, txId, player.getUUID(), packedPos, oldItemId, -oldStack.getCount(), snapshot.flags));

                String newItemId = getItemIdentifier(newStack.getItem());
                ChestLoggerMod.LOGGER.info("[ChestLogger] Slot click swap add: +{} x {} at {}", newStack.getCount(), newItemId, snapshot.pos.toShortString());
                writer.enqueue(new ChestLogEvent(timestamp, txId, player.getUUID(), packedPos, newItemId, newStack.getCount(), snapshot.flags));
            }
        }
    }

    private Container getChestContainer(AbstractContainerMenu menu) {
        if (menu instanceof ChestMenu chestMenu) {
            return chestMenu.getContainer();
        }
        for (Slot slot : menu.slots) {
            if (slot != null && slot.container != null) {
                if (slot.container instanceof BlockEntity || slot.container instanceof CompoundContainer) {
                    return slot.container;
                }
            }
        }
        return null;
    }

    private String getItemIdentifier(Item item) {
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

    private BlockPos getBlockPosFromContainer(Container container) {
        if (container instanceof BlockEntity be) {
            return be.getBlockPos();
        }
        if (container instanceof CompoundContainer cc) {
            try {
                for (java.lang.reflect.Field f : CompoundContainer.class.getDeclaredFields()) {
                    if (Container.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object subContainer = f.get(cc);
                        if (subContainer instanceof BlockEntity be) {
                            return be.getBlockPos();
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }
}

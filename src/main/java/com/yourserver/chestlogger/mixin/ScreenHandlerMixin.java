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

@Mixin(AbstractContainerMenu.class)
public abstract class ScreenHandlerMixin {

    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void onSlotClickIntercept(int slotIndex, int button, @Coerce Object clickType, Player player, CallbackInfo ci) {
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
        if (slotIndex < 0) return;
        if (slotIndex >= menu.slots.size()) return;

        Slot slot = menu.slots.get(slotIndex);
        if (slot == null) return;

        Container container = slot.container;
        BlockPos pos = getBlockPosFromContainer(container);

        // Fallback: If clicked slot is player inventory (e.g. shift-clicking into chest), resolve position from ChestMenu container
        if (pos == null && menu instanceof ChestMenu chestMenu) {
            pos = getBlockPosFromContainer(chestMenu.getContainer());
        }

        if (pos == null) return;

        ItemStack slotStack = slot.getItem();
        ItemStack carriedStack = menu.getCarried();

        String itemId = null;
        int countDiff = 0;
        byte flags = 0;

        String clickStr = clickType != null ? clickType.name() : "";
        if (clickStr.contains("QUICK_MOVE")) {
            flags |= ChestLogEvent.Flags.SHIFT_CLICK;
        } else if (clickStr.contains("QUICK_CRAFT") || clickStr.contains("DRAG")) {
            flags |= ChestLogEvent.Flags.DRAG;
        }

        // Determine if items are being removed from or added to the chest
        if (!slotStack.isEmpty()) {
            // Taking from chest slot
            itemId = getItemIdentifier(slotStack.getItem());
            countDiff = -slotStack.getCount();
        } else if (carriedStack != null && !carriedStack.isEmpty()) {
            // Placing into empty chest slot
            itemId = getItemIdentifier(carriedStack.getItem());
            countDiff = carriedStack.getCount();
        }

        if (itemId == null || countDiff == 0) return;

        long packedPos = pos.asLong();
        long timestamp = System.currentTimeMillis();
        long txId = TransactionIdGenerator.next();

        writer.enqueue(new ChestLogEvent(
            timestamp,
            txId,
            player.getUUID(),
            packedPos,
            itemId,
            countDiff,
            flags
        ));
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

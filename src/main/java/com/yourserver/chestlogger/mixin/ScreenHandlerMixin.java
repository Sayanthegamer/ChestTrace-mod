package com.yourserver.chestlogger.mixin;

import com.yourserver.chestlogger.ChestLoggerMod;
import com.yourserver.chestlogger.logging.ChestLogEvent;
import com.yourserver.chestlogger.logging.ChestLogWriter;
import com.yourserver.chestlogger.logging.TransactionIdGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
public abstract class ScreenHandlerMixin {

    @Inject(method = "doClick", at = @At("HEAD"))
    private void onSlotClickIntercept(int slotIndex, int button, @Coerce Object clickType, Player player, CallbackInfo ci) {
        ChestLogWriter writer = ChestLoggerMod.writer();
        if (writer == null || writer.isDisabled()) return;
        if (slotIndex < 0) return;

        AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
        if (slotIndex >= menu.slots.size()) return;

        Slot slot = menu.slots.get(slotIndex);
        if (slot == null) return;

        Container container = slot.container;
        BlockPos pos = getBlockPosFromContainer(container);
        if (pos == null) return;

        ItemStack slotStack = slot.getItem();
        ItemStack carriedStack = menu.getCarried();

        String itemId = null;
        int countDiff = 0;
        byte flags = 0;

        String clickStr = clickType != null ? clickType.toString() : "";
        if (clickStr.contains("QUICK_MOVE")) {
            flags |= ChestLogEvent.Flags.SHIFT_CLICK;
        } else if (clickStr.contains("QUICK_CRAFT") || clickStr.contains("DRAG")) {
            flags |= ChestLogEvent.Flags.DRAG;
        }

        // Determine if items are being removed from or added to the chest
        if (!slotStack.isEmpty()) {
            // Taking from chest slot
            itemId = BuiltInRegistries.ITEM.getKey(slotStack.getItem()).toString();
            countDiff = -slotStack.getCount();
        } else if (carriedStack != null && !carriedStack.isEmpty()) {
            // Placing into empty chest slot
            itemId = BuiltInRegistries.ITEM.getKey(carriedStack.getItem()).toString();
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

    private BlockPos getBlockPosFromContainer(Container container) {
        if (container instanceof BlockEntity be) {
            return be.getBlockPos();
        }
        if (container instanceof CompoundContainer cc) {
            if (cc.container1 instanceof BlockEntity be1) {
                return be1.getBlockPos();
            }
            if (cc.container2 instanceof BlockEntity be2) {
                return be2.getBlockPos();
            }
        }
        return null;
    }
}

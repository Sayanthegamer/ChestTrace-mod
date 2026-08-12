package com.yourserver.chestlogger.mixin;

import com.yourserver.chestlogger.ChestLoggerMod;
import com.yourserver.chestlogger.logging.ChestLogEvent;
import com.yourserver.chestlogger.logging.ChestLogWriter;
import com.yourserver.chestlogger.logging.TransactionIdGenerator;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ScreenHandler.class)
public abstract class ScreenHandlerMixin {

    @Inject(method = "onSlotClick", at = @At("HEAD"))
    private void onSlotClickIntercept(int slotIndex, int button, SlotActionType actionType, PlayerEntity player, CallbackInfo ci) {
        ChestLogWriter writer = ChestLoggerMod.writer();
        if (writer == null || writer.isDisabled()) return;
        if (slotIndex < 0) return;

        ScreenHandler handler = (ScreenHandler) (Object) this;
        if (slotIndex >= handler.slots.size()) return;

        Slot slot = handler.slots.get(slotIndex);
        if (slot == null || !(slot.inventory instanceof BlockEntity blockEntity)) return;

        ItemStack stack = slot.getStack();
        if (stack.isEmpty()) return;

        byte flags = 0;
        if (actionType == SlotActionType.QUICK_MOVE) {
            flags |= ChestLogEvent.Flags.SHIFT_CLICK;
        } else if (actionType == SlotActionType.QUICK_CRAFT) {
            flags |= ChestLogEvent.Flags.DRAG;
        }

        String itemId = Registries.ITEM.getId(stack.getItem()).toString();
        long packedPos = blockEntity.getPos().asLong();
        long timestamp = System.currentTimeMillis();
        long txId = TransactionIdGenerator.next();

        // Enqueue event 100% lock-free
        writer.enqueue(new ChestLogEvent(
            timestamp,
            txId,
            player.getUuid(),
            packedPos,
            itemId,
            -stack.getCount(),
            flags
        ));
    }
}

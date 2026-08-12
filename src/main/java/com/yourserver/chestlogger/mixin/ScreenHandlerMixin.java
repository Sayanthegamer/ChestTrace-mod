package com.yourserver.chestlogger.mixin;

import com.yourserver.chestlogger.ChestLoggerMod;
import com.yourserver.chestlogger.logging.ChestLogEvent;
import com.yourserver.chestlogger.logging.ChestLogWriter;
import com.yourserver.chestlogger.logging.TransactionIdGenerator;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
public abstract class ScreenHandlerMixin {

    @Inject(method = "doClick", at = @At("HEAD"))
    private void onSlotClickIntercept(int slotIndex, int button, ContainerInput clickType, Player player, CallbackInfo ci) {
        ChestLogWriter writer = ChestLoggerMod.writer();
        if (writer == null || writer.isDisabled()) return;
        if (slotIndex < 0) return;

        AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
        if (slotIndex >= menu.slots.size()) return;

        Slot slot = menu.slots.get(slotIndex);
        if (slot == null || !(slot.container instanceof BlockEntity blockEntity)) return;

        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) return;

        byte flags = 0;
        String clickStr = clickType != null ? clickType.toString() : "";
        if (clickStr.contains("QUICK_MOVE")) {
            flags |= ChestLogEvent.Flags.SHIFT_CLICK;
        } else if (clickStr.contains("QUICK_CRAFT") || clickStr.contains("DRAG")) {
            flags |= ChestLogEvent.Flags.DRAG;
        }

        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        long packedPos = blockEntity.getBlockPos().asLong();
        long timestamp = System.currentTimeMillis();
        long txId = TransactionIdGenerator.next();

        // Enqueue event 100% lock-free
        writer.enqueue(new ChestLogEvent(
            timestamp,
            txId,
            player.getUUID(),
            packedPos,
            itemId,
            -stack.getCount(),
            flags
        ));
    }
}

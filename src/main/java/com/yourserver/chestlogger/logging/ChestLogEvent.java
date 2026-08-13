package com.yourserver.chestlogger.logging;

import net.minecraft.core.component.DataComponentPatch;

import java.util.UUID;

public final class ChestLogEvent {
    public final long timestampMillis;
    public final long transactionId;
    public final UUID playerId;
    public final long packedBlockPos;
    public final String itemId;
    public final short countDiff;
    public final byte flags;
    public final DataComponentPatch componentPatch;
    private byte[] componentsNbt;

    public static final class Flags {
        public static final byte SHIFT_CLICK     = 0b00000001;
        public static final byte DRAG            = 0b00000010;
        public static final byte ADMIN_ROLLBACK  = 0b00000100;
        public static final byte HAS_COMPONENTS  = 0b01000000;
    }

    public ChestLogEvent(long timestampMillis, long transactionId, UUID playerId,
                         long packedBlockPos, String itemId, int countDiff, byte flags) {
        this(timestampMillis, transactionId, playerId, packedBlockPos, itemId, countDiff, flags, DataComponentPatch.EMPTY);
    }

    public ChestLogEvent(long timestampMillis, long transactionId, UUID playerId,
                         long packedBlockPos, String itemId, int countDiff, byte flags,
                         DataComponentPatch componentPatch) {
        this.timestampMillis = timestampMillis;
        this.transactionId = transactionId;
        this.playerId = playerId;
        this.packedBlockPos = packedBlockPos;
        this.itemId = itemId;
        this.countDiff = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, countDiff));
        this.flags = flags;
        this.componentPatch = componentPatch != null ? componentPatch : DataComponentPatch.EMPTY;
    }

    public boolean isAdminEvent() {
        return (flags & Flags.ADMIN_ROLLBACK) != 0;
    }

    public boolean hasComponentsNbt() {
        return (flags & Flags.HAS_COMPONENTS) != 0 || (componentsNbt != null && componentsNbt.length > 0);
    }

    public byte[] getComponentsNbt() {
        return componentsNbt;
    }

    public void setComponentsNbt(byte[] componentsNbt) {
        this.componentsNbt = componentsNbt;
    }

    public static ChestLogEvent adminRollback(long timestamp, UUID adminId, long packedBlockPos, int transactionCount) {
        long txId = TransactionIdGenerator.next();
        return new ChestLogEvent(
            timestamp,
            txId,
            adminId,
            packedBlockPos,
            "chestlogger:admin_rollback",
            transactionCount,
            Flags.ADMIN_ROLLBACK,
            DataComponentPatch.EMPTY
        );
    }
}

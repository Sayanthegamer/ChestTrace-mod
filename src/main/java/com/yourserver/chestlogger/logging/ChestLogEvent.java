package com.yourserver.chestlogger.logging;

import java.util.UUID;

public final class ChestLogEvent {
    public final long timestampMillis;
    public final long transactionId;
    public final UUID playerId;
    public final long packedBlockPos;
    public final String itemId;
    public final short countDiff;
    public final byte flags;

    public ChestLogEvent(long timestampMillis, long transactionId, UUID playerId,
                         long packedBlockPos, String itemId, int countDiff, byte flags) {
        this.timestampMillis = timestampMillis;
        this.transactionId = transactionId;
        this.playerId = playerId;
        this.packedBlockPos = packedBlockPos;
        this.itemId = itemId;
        this.countDiff = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, countDiff));
        this.flags = flags;
    }

    public boolean isAdminEvent() {
        return (flags & Flags.ADMIN_ROLLBACK) != 0;
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
            Flags.ADMIN_ROLLBACK
        );
    }

    public static final class Flags {
        public static final byte SHIFT_CLICK     = 0b0001;
        public static final byte DRAG            = 0b0010;
        public static final byte ADMIN_ROLLBACK  = 0b0100;
    }
}

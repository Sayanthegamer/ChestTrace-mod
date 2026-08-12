package com.yourserver.chestlogger.logging;

import java.util.concurrent.atomic.AtomicInteger;

public final class TransactionIdGenerator {
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private TransactionIdGenerator() {}
    public static long next() {
        long timestamp = System.currentTimeMillis();
        int seq = SEQUENCE.getAndIncrement() & 0xFFFFF;
        return (timestamp << 20) | seq;
    }
}

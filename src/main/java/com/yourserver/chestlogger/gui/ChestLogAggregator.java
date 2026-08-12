package com.yourserver.chestlogger.gui;

import com.yourserver.chestlogger.logging.ChestLogEvent;

import java.util.*;

public final class ChestLogAggregator {
    private ChestLogAggregator() {}

    public record AggregatedEntry(
        UUID playerId,
        String itemId,
        int netCountDiff,
        long latestTimestamp,
        long earliestTimestamp,
        int eventCount
    ) {}

    /**
     * Aggregates raw chest log events within a time threshold (e.g. 5 minutes) by (playerId, itemId).
     * Filters out zero-sum net differences (+5 then -5 = 0).
     */
    public static List<AggregatedEntry> aggregate(List<ChestLogEvent> rawEvents, long windowMillis) {
        if (rawEvents == null || rawEvents.isEmpty()) return Collections.emptyList();

        List<ChestLogEvent> filtered = new ArrayList<>();
        for (ChestLogEvent e : rawEvents) {
            if (!e.isAdminEvent()) {
                filtered.add(e);
            }
        }
        filtered.sort(Comparator.comparingLong((ChestLogEvent e) -> e.timestampMillis).reversed());

        Map<String, AggregatedEntryBuilder> map = new LinkedHashMap<>();

        for (ChestLogEvent e : filtered) {
            String pIdStr = e.playerId != null ? e.playerId.toString() : "unknown";
            String key = pIdStr + ":" + e.itemId;

            AggregatedEntryBuilder builder = map.get(key);
            if (builder == null || Math.abs(builder.latestTimestamp - e.timestampMillis) > windowMillis) {
                key = key + ":" + e.timestampMillis;
                builder = new AggregatedEntryBuilder(e.playerId, e.itemId, e.timestampMillis);
                map.put(key, builder);
            }
            builder.add(e.countDiff, e.timestampMillis);
        }

        List<AggregatedEntry> result = new ArrayList<>();
        for (AggregatedEntryBuilder b : map.values()) {
            if (b.netDiff != 0) {
                result.add(b.build());
            }
        }

        result.sort(Comparator.comparingLong(AggregatedEntry::latestTimestamp).reversed());
        return result;
    }

    private static class AggregatedEntryBuilder {
        final UUID playerId;
        final String itemId;
        long latestTimestamp;
        long earliestTimestamp;
        int netDiff = 0;
        int count = 0;

        AggregatedEntryBuilder(UUID playerId, String itemId, long initialTimestamp) {
            this.playerId = playerId;
            this.itemId = itemId;
            this.latestTimestamp = initialTimestamp;
            this.earliestTimestamp = initialTimestamp;
        }

        void add(int diff, long timestamp) {
            this.netDiff += diff;
            this.count++;
            if (timestamp > this.latestTimestamp) this.latestTimestamp = timestamp;
            if (timestamp < this.earliestTimestamp) this.earliestTimestamp = timestamp;
        }

        AggregatedEntry build() {
            return new AggregatedEntry(playerId, itemId, netDiff, latestTimestamp, earliestTimestamp, count);
        }
    }
}

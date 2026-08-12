package com.yourserver.chestlogger.logging;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LogDictionary {

    private final Map<UUID, Short> playerIds = new ConcurrentHashMap<>();
    private final Map<String, Short> itemIds = new ConcurrentHashMap<>();
    
    private final Map<Short, UUID> playerByShort = new ConcurrentHashMap<>();
    private final Map<Short, String> itemByShort = new ConcurrentHashMap<>();

    private short nextPlayerId = 0;
    private short nextItemId = 0;

    public synchronized short playerShort(UUID player) {
        Short existing = playerIds.get(player);
        if (existing != null) return existing;

        short id = nextPlayerId++;
        playerIds.put(player, id);
        playerByShort.put(id, player);
        return id;
    }

    public synchronized short itemShort(String itemId) {
        Short existing = itemIds.get(itemId);
        if (existing != null) return existing;

        short id = nextItemId++;
        itemIds.put(itemId, id);
        itemByShort.put(id, itemId);
        return id;
    }

    public UUID getPlayer(short shortId) {
        return playerByShort.get(shortId);
    }

    public String getItem(short shortId) {
        return itemByShort.get(shortId);
    }

    public synchronized void rehydrate(ReverseDictionary reverseDict) {
        for (Map.Entry<Short, UUID> entry : reverseDict.getPlayers().entrySet()) {
            playerIds.put(entry.getValue(), entry.getKey());
            playerByShort.put(entry.getKey(), entry.getValue());
            if (entry.getKey() >= nextPlayerId) {
                nextPlayerId = (short) (entry.getKey() + 1);
            }
        }
        for (Map.Entry<Short, String> entry : reverseDict.getItems().entrySet()) {
            itemIds.put(entry.getValue(), entry.getKey());
            itemByShort.put(entry.getKey(), entry.getValue());
            if (entry.getKey() >= nextItemId) {
                nextItemId = (short) (entry.getKey() + 1);
            }
        }
    }

    public synchronized byte[] serializeHeader() {
        int size = 4;
        for (UUID u : playerIds.keySet()) size += 2 + 16;
        for (String s : itemIds.keySet()) size += 2 + 2 + s.getBytes(StandardCharsets.UTF_8).length;

        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putShort((short) playerIds.size());
        for (Map.Entry<UUID, Short> e : playerIds.entrySet()) {
            buf.putShort(e.getValue());
            buf.putLong(e.getKey().getMostSignificantBits());
            buf.putLong(e.getKey().getLeastSignificantBits());
        }
        buf.putShort((short) itemIds.size());
        for (Map.Entry<String, Short> e : itemIds.entrySet()) {
            byte[] strBytes = e.getKey().getBytes(StandardCharsets.UTF_8);
            buf.putShort(e.getValue());
            buf.putShort((short) strBytes.length);
            buf.put(strBytes);
        }
        buf.flip();
        byte[] out = new byte[buf.remaining()];
        buf.get(out);
        return out;
    }
}

package com.yourserver.chestlogger.logging;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ReverseDictionary {
    private final Map<Short, UUID> players = new HashMap<>();
    private final Map<Short, String> items = new HashMap<>();

    public static ReverseDictionary deserialize(ByteBuffer buf) {
        ReverseDictionary dict = new ReverseDictionary();

        short playerCount = buf.getShort();
        for (int i = 0; i < playerCount; i++) {
            short id = buf.getShort();
            long msb = buf.getLong();
            long lsb = buf.getLong();
            dict.players.put(id, new UUID(msb, lsb));
        }

        short itemCount = buf.getShort();
        for (int i = 0; i < itemCount; i++) {
            short id = buf.getShort();
            short strLen = buf.getShort();
            byte[] strBytes = new byte[strLen];
            buf.get(strBytes);
            dict.items.put(id, new String(strBytes, StandardCharsets.UTF_8));
        }

        return dict;
    }

    public UUID getPlayer(short id) { return players.get(id); }
    public String getItem(short id) { return items.get(id); }
    public Map<Short, UUID> getPlayers() { return Collections.unmodifiableMap(players); }
    public Map<Short, String> getItems() { return Collections.unmodifiableMap(items); }
}

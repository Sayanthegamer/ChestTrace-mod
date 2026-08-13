package com.yourserver.chestlogger.logging;

import com.yourserver.chestlogger.ChestLoggerMod;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class ChestLogReader {

    private static final LZ4FastDecompressor DECOMPRESSOR = LZ4Factory.fastestInstance().fastDecompressor();

    public record QueryResult(
        List<ChestLogEvent> events,
        boolean isComplete,
        List<Path> failedSegments
    ) {}

    public static QueryResult queryAll(Path logDir, ChestLogWriter writer, long targetBlockPos) {
        if (writer == null || writer.isDisabled()) {
            return new QueryResult(Collections.emptyList(), false, logDir != null ? List.of(logDir) : Collections.emptyList());
        }

        List<ChestLogEvent> combined = new ArrayList<>();
        List<Path> failedSegments = new ArrayList<>();
        boolean isComplete = true;

        combined.addAll(writer.queryLiveSegment(targetBlockPos));

        String activeFileName = writer.getCurrentSegmentDate() != null
                ? writer.getCurrentSegmentDate() + ".chlog"
                : "";

        if (logDir != null && Files.exists(logDir)) {
            try (var stream = Files.list(logDir)) {
                List<Path> files = stream.filter(p -> p.toString().endsWith(".chlog"))
                      .filter(p -> !p.getFileName().toString().equals(activeFileName))
                      .toList();

                for (Path path : files) {
                    try {
                        combined.addAll(readEventsFromDisk(path, targetBlockPos));
                    } catch (Throwable e) {
                        ChestLoggerMod.LOGGER.error("Failed to read log segment file: " + path, e);
                        isComplete = false;
                        failedSegments.add(path);
                    }
                }
            } catch (Throwable e) {
                ChestLoggerMod.LOGGER.error("Failed to list log directory: " + logDir, e);
                isComplete = false;
            }
        }

        combined.sort(Comparator.comparingLong(e -> e.timestampMillis));
        return new QueryResult(combined, isComplete, failedSegments);
    }

    public static List<ChestLogEvent> readEventsFromDisk(Path logFile, long targetBlockPos) throws IOException {
        List<ChestLogEvent> results = new ArrayList<>();

        try (FileChannel channel = FileChannel.open(logFile, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            if (fileSize < 12) return results;

            ByteBuffer footerBuf = ByteBuffer.allocate(8);
            channel.position(fileSize - 8);
            readFully(channel, footerBuf);
            footerBuf.flip();
            long trailerOffset = footerBuf.getLong();

            channel.position(trailerOffset);
            ByteBuffer lenBuf = ByteBuffer.allocate(4);
            readFully(channel, lenBuf);
            lenBuf.flip();
            int dictLen = lenBuf.getInt();

            ByteBuffer dictBuf = ByteBuffer.allocate(dictLen);
            readFully(channel, dictBuf);
            dictBuf.flip();
            ReverseDictionary dict = ReverseDictionary.deserialize(dictBuf);

            channel.position(0);
            while (channel.position() < trailerOffset) {
                ByteBuffer frameHeader = ByteBuffer.allocate(8);
                if (channel.read(frameHeader) < 8) break;
                frameHeader.flip();

                int uncompressedLen = frameHeader.getInt();
                int compressedLen = frameHeader.getInt();

                ByteBuffer compressedBuf = ByteBuffer.allocate(compressedLen);
                readFully(channel, compressedBuf);
                compressedBuf.flip();

                byte[] decompressedBytes = new byte[uncompressedLen];
                DECOMPRESSOR.decompress(compressedBuf.array(), 0, decompressedBytes, 0, uncompressedLen);

                ByteBuffer raw = ByteBuffer.wrap(decompressedBytes);
                long baseTimestamp = raw.getLong();

                while (raw.hasRemaining()) {
                    long txId = readVarLong(raw);
                    long delta = readVarLong(raw);
                    short playerShort = raw.getShort();
                    long blockPos = raw.getLong();
                    short itemShort = raw.getShort();
                    short countDiff = raw.getShort();
                    byte flags = raw.get();

                    if (blockPos == targetBlockPos) {
                        UUID player = dict.getPlayer(playerShort);
                        String item = dict.getItem(itemShort);
                        long timestamp = baseTimestamp + delta;

                        results.add(new ChestLogEvent(timestamp, txId, player, blockPos, item, countDiff, flags));
                    }
                }
            }
        }
        return results;
    }

    private static void readFully(FileChannel channel, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            if (channel.read(buf) == -1) throw new IOException("Unexpected EOF while reading frame");
        }
    }

    private static long readVarLong(ByteBuffer buf) {
        long value = 0;
        int shift = 0;
        byte b;
        while (((b = buf.get()) & 0x80) != 0) {
            value |= (long) (b & 0x7F) << shift;
            shift += 7;
            if (shift > 63) throw new IllegalArgumentException("VarLong overflow");
        }
        return value | ((long) (b & 0x7F) << shift);
    }
}

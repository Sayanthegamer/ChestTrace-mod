package com.yourserver.chestlogger.logging;

import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public final class ChestLogWriter {

    private static final Logger LOGGER = Logger.getLogger("chestlogger");
    private static final LZ4FastDecompressor DECOMPRESSOR = LZ4Factory.fastestInstance().fastDecompressor();

    private final int flushEventThreshold;
    private final long flushIntervalMillis;
    private final int retentionDays;
    private final Path logDirectory;

    private final ConcurrentLinkedQueue<ChestLogEvent> queue = new ConcurrentLinkedQueue<>();
    private final Object flushLock = new Object();
    private final List<ChestLogEvent> pendingBatch = new ArrayList<>();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong droppedEventCount = new AtomicLong(0);
    private volatile boolean disabled = false;

    private final LZ4Compressor compressor = LZ4Factory.fastestInstance().highCompressor();

    private Thread workerThread;
    private FileChannel currentChannel;
    private LogDictionary currentDictionary;
    private LocalDate currentSegmentDate;
    private volatile long bytesWrittenInSegment = 0;

    public record LiveSnapshot(
        List<ChestLogEvent> memoryEvents,
        long fileReadLimit,
        LocalDate segmentDate,
        LogDictionary dictionary
    ) {}

    public ChestLogWriter(Path logDirectory, int flushEventThreshold, long flushIntervalMillis, int retentionDays) {
        this.logDirectory = logDirectory;
        this.flushEventThreshold = flushEventThreshold;
        this.flushIntervalMillis = flushIntervalMillis;
        this.retentionDays = retentionDays;
    }

    public boolean isDisabled() { return disabled; }
    public long getDroppedEventCount() { return droppedEventCount.get(); }

    public void enqueue(ChestLogEvent event) {
        if (disabled) {
            long dropped = droppedEventCount.incrementAndGet();
            if (dropped % 100 == 1) {
                LOGGER.severe("ChestLogger: Logger circuit breaker is ACTIVE! Dropped " + dropped + " events due to disk failure.");
            }
            return;
        }
        queue.offer(event);
    }

    public void start() {
        if (!Files.exists(logDirectory)) {
            try { Files.createDirectories(logDirectory); } catch (IOException e) {
                throw new RuntimeException("Cannot create log directory", e);
            }
        }
        pruneOldSegments();
        running.set(true);
        workerThread = new Thread(this::runLoop, "ChestLogger-Writer");
        workerThread.setDaemon(false);
        workerThread.start();
    }

    private void runLoop() {
        long lastFlush = System.currentTimeMillis();
        List<ChestLogEvent> workBatch = new ArrayList<>(flushEventThreshold);

        while ((running.get() || !queue.isEmpty()) && !disabled) {
            boolean intervalElapsed = System.currentTimeMillis() - lastFlush >= flushIntervalMillis;
            boolean thresholdHit = queue.size() >= flushEventThreshold;

            if (!queue.isEmpty() && (thresholdHit || intervalElapsed || !running.get())) {
                workBatch.clear();
                synchronized (flushLock) {
                    ChestLogEvent e;
                    while ((e = queue.poll()) != null) {
                        workBatch.add(e);
                        if (workBatch.size() >= flushEventThreshold) break;
                    }
                    pendingBatch.addAll(workBatch);
                }

                if (!workBatch.isEmpty()) {
                    boolean success = false;
                    int bytesWritten = 0;
                    long startPosition = -1;
                    try {
                        ensureSegmentForToday();
                        startPosition = currentChannel.position();
                        bytesWritten = writeBatchToDisk(workBatch);
                        success = true;
                    } catch (IOException ex) {
                        LOGGER.severe("ChestLogger: write failed: " + ex.getMessage());
                        if (currentChannel != null && startPosition >= 0) {
                            try {
                                currentChannel.truncate(startPosition);
                                currentChannel.position(startPosition);
                                bytesWrittenInSegment = startPosition;
                            } catch (IOException truncateEx) {
                                disabled = true;
                                LOGGER.severe("ChestLogger: CRITICAL: truncation failed; disabling writer: " + truncateEx.getMessage());
                            }
                        } else {
                            disabled = true;
                        }
                    } finally {
                        synchronized (flushLock) {
                            if (success) {
                                bytesWrittenInSegment += bytesWritten;
                            } else if (!disabled) {
                                for (int i = workBatch.size() - 1; i >= 0; i--) queue.offer(workBatch.get(i));
                            } else {
                                long dropped = droppedEventCount.addAndGet(workBatch.size());
                                LOGGER.severe("ChestLogger: circuit breaker active; batch dropped from memory: " + workBatch.size() + " events, total dropped=" + dropped);
                                queue.clear();
                            }
                            pendingBatch.clear();
                        }
                    }
                    lastFlush = System.currentTimeMillis();
                }
            } else {
                try { Thread.sleep(50); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
        }

        if (!disabled && !queue.isEmpty()) {
            workBatch.clear();
            synchronized (flushLock) {
                ChestLogEvent e;
                while ((e = queue.poll()) != null) workBatch.add(e);
                pendingBatch.addAll(workBatch);
            }
            if (!workBatch.isEmpty()) {
                boolean success = false;
                long startPosition = -1;
                try {
                    ensureSegmentForToday();
                    startPosition = currentChannel.position();
                    int bytesWritten = writeBatchToDisk(workBatch);
                    bytesWrittenInSegment += bytesWritten;
                    success = true;
                } catch (IOException ex) {
                    LOGGER.severe("ChestLogger: final drain failed: " + ex.getMessage());
                    if (currentChannel != null && startPosition >= 0) {
                        try { currentChannel.truncate(startPosition); currentChannel.position(startPosition); bytesWrittenInSegment = startPosition; }
                        catch (IOException truncateEx) { disabled = true; LOGGER.severe("ChestLogger: CRITICAL: final truncation failed: " + truncateEx.getMessage()); }
                    } else disabled = true;
                    if (!disabled) for (int i = workBatch.size() - 1; i >= 0; i--) queue.offer(workBatch.get(i));
                    else droppedEventCount.addAndGet(workBatch.size());
                } finally {
                    synchronized (flushLock) { pendingBatch.clear(); }
                }
            }
        }
        closeSegment();
    }

    private int writeBatchToDisk(List<ChestLogEvent> batch) throws IOException {
        int estimatedCapacity = 16 + (batch.size() * 35);
        ByteBuffer raw = ByteBuffer.allocate(estimatedCapacity);

        long baseTimestamp = batch.get(0).timestampMillis;
        raw.putLong(baseTimestamp);

        for (ChestLogEvent e : batch) {
            long delta = e.timestampMillis - baseTimestamp;
            putVarLong(raw, e.transactionId);
            putVarLong(raw, delta);
            raw.putShort(currentDictionary.playerShort(e.playerId));
            raw.putLong(e.packedBlockPos);
            raw.putShort(currentDictionary.itemShort(e.itemId));
            raw.putShort(e.countDiff);
            raw.put(e.flags);
        }

        byte[] rawBytes = new byte[raw.position()];
        raw.flip();
        raw.get(rawBytes);

        byte[] compressed = new byte[compressor.maxCompressedLength(rawBytes.length)];
        int compressedLen = compressor.compress(rawBytes, 0, rawBytes.length, compressed, 0);

        ByteBuffer frame = ByteBuffer.allocate(8 + compressedLen);
        frame.putInt(rawBytes.length);
        frame.putInt(compressedLen);
        frame.put(compressed, 0, compressedLen);
        frame.flip();

        return writeFully(currentChannel, frame);
    }

    private static int writeFully(FileChannel channel, ByteBuffer buf) throws IOException {
        int totalWritten = 0;
        while (buf.hasRemaining()) {
            int written = channel.write(buf);
            if (written < 0) throw new IOException("Unexpected Channel EOF during write");
            totalWritten += written;
        }
        return totalWritten;
    }

    private static void putVarLong(ByteBuffer buf, long value) {
        while ((value & ~0x7FL) != 0) {
            buf.put((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        buf.put((byte) (value & 0x7F));
    }

    private void ensureSegmentForToday() throws IOException {
        LocalDate today = LocalDate.now();
        if (currentChannel != null && today.equals(currentSegmentDate)) return;

        closeSegment();

        currentSegmentDate = today;
        currentDictionary = new LogDictionary();

        Path segmentPath = logDirectory.resolve(today + ".chlog");
        boolean fileExists = Files.exists(segmentPath);

        currentChannel = FileChannel.open(segmentPath,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);

        if (fileExists && currentChannel.size() >= 12) {
            long fileSize = currentChannel.size();

            ByteBuffer footerBuf = ByteBuffer.allocate(8);
            currentChannel.position(fileSize - 8);
            readFully(currentChannel, footerBuf);
            footerBuf.flip();
            long trailerOffset = footerBuf.getLong();
            if (trailerOffset < 0 || trailerOffset > fileSize - 8) {
                throw new IOException("Invalid trailer offset: " + trailerOffset);
            }

            currentChannel.position(trailerOffset);
            ByteBuffer lenBuf = ByteBuffer.allocate(4);
            readFully(currentChannel, lenBuf);
            lenBuf.flip();
            int dictLen = lenBuf.getInt();
            if (dictLen < 0 || dictLen > fileSize - trailerOffset - 4 - 8) {
                throw new IOException("Invalid dictionary length: " + dictLen);
            }

            ByteBuffer dictBuf = ByteBuffer.allocate(dictLen);
            readFully(currentChannel, dictBuf);
            dictBuf.flip();

            ReverseDictionary existingDict = ReverseDictionary.deserialize(dictBuf);
            currentDictionary.rehydrate(existingDict);

            currentChannel.truncate(trailerOffset);
            currentChannel.position(trailerOffset);
            bytesWrittenInSegment = trailerOffset;
        } else {
            bytesWrittenInSegment = currentChannel.size();
            currentChannel.position(bytesWrittenInSegment);
        }
    }

    private static void readFully(FileChannel channel, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            if (channel.read(buf) == -1) throw new IOException("Unexpected EOF during read");
        }
    }

    private void closeSegment() {
        if (currentChannel == null) return;
        try {
            long trailerOffset = bytesWrittenInSegment;

            byte[] dictBytes = currentDictionary.serializeHeader();
            ByteBuffer trailerBuf = ByteBuffer.allocate(dictBytes.length + 4);
            trailerBuf.putInt(dictBytes.length);
            trailerBuf.put(dictBytes);
            trailerBuf.flip();
            writeFully(currentChannel, trailerBuf);

            ByteBuffer footerBuf = ByteBuffer.allocate(8);
            footerBuf.putLong(trailerOffset);
            footerBuf.flip();
            writeFully(currentChannel, footerBuf);

            currentChannel.force(true);
            currentChannel.close();
        } catch (IOException e) {
            LOGGER.severe("ChestLogger: error closing segment: " + e.getMessage());
        } finally {
            currentChannel = null;
        }
    }

    public LiveSnapshot captureSnapshot(long targetBlockPos) {
        synchronized (flushLock) {
            List<ChestLogEvent> memoryEvents = new ArrayList<>();
            for (ChestLogEvent event : queue) {
                if (event.packedBlockPos == targetBlockPos) memoryEvents.add(event);
            }
            for (ChestLogEvent event : pendingBatch) {
                if (event.packedBlockPos == targetBlockPos) memoryEvents.add(event);
            }
            return new LiveSnapshot(
                memoryEvents,
                bytesWrittenInSegment,
                currentSegmentDate,
                currentDictionary
            );
        }
    }

    public List<ChestLogEvent> queryLiveSegment(long targetBlockPos) {
        LiveSnapshot snapshot = captureSnapshot(targetBlockPos);
        List<ChestLogEvent> results = new ArrayList<>(snapshot.memoryEvents());

        if (snapshot.segmentDate() == null || snapshot.dictionary() == null) return results;
        Path todayPath = logDirectory.resolve(snapshot.segmentDate() + ".chlog");
        if (!Files.exists(todayPath) || snapshot.fileReadLimit() <= 0) return results;

        try (FileChannel channel = FileChannel.open(todayPath, StandardOpenOption.READ)) {
            channel.position(0);

            while (channel.position() < snapshot.fileReadLimit()) {
                ByteBuffer frameHeader = ByteBuffer.allocate(8);
                if (channel.read(frameHeader) < 8) break;
                frameHeader.flip();

                int uncompressedLen = frameHeader.getInt();
                int compressedLen = frameHeader.getInt();

                if (channel.position() + compressedLen > snapshot.fileReadLimit()) break;

                ByteBuffer compressedBuf = ByteBuffer.allocate(compressedLen);
                channel.read(compressedBuf);
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
                        UUID player = snapshot.dictionary().getPlayer(playerShort);
                        String item = snapshot.dictionary().getItem(itemShort);
                        long timestamp = baseTimestamp + delta;

                        if (player != null && item != null) {
                            results.add(new ChestLogEvent(timestamp, txId, player, blockPos, item, countDiff, flags));
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warning("ChestLogger: Error reading live segment file: " + e.getMessage());
        }

        return results;
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

    public void shutdownAndFlush() {
        running.set(false);
        try {
            if (workerThread != null) workerThread.join(30_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void pruneOldSegments() {
        try (var stream = Files.list(logDirectory)) {
            LocalDate cutoff = LocalDate.now().minusDays(retentionDays);
            stream.filter(p -> p.toString().endsWith(".chlog")).forEach(p -> {
                String name = p.getFileName().toString().replace(".chlog", "");
                try {
                    LocalDate segDate = LocalDate.parse(name);
                    if (segDate.isBefore(cutoff)) {
                        Files.deleteIfExists(p);
                    }
                } catch (Exception ignored) {}
            });
        } catch (IOException e) {
            LOGGER.warning("ChestLogger: retention prune failed: " + e.getMessage());
        }
    }

    public LogDictionary getCurrentDictionary() { return currentDictionary; }
    public LocalDate getCurrentSegmentDate() { return currentSegmentDate; }
}

package me.cortex.voxy.client.worldgen;

import java.util.concurrent.atomic.AtomicLong;

public final class NetworkState {
    private static boolean serverConnected;
    private static final AtomicLong chunksReceived = new AtomicLong();
    private static final AtomicLong bytesReceived = new AtomicLong();
    private static volatile long totalToSync = 0;
    private static double receiveRate;
    private static double bandwidthRate;
    private static long lastUpdateTime;
    private static long lastChunkCount;
    private static long lastByteCount;

    private NetworkState() {}

    public static void setServerConnected(boolean connected) {
        serverConnected = connected;
        if (!connected) {
            chunksReceived.set(0);
            bytesReceived.set(0);
            totalToSync = 0;
            receiveRate = 0;
            bandwidthRate = 0;
            lastUpdateTime = 0;
            lastChunkCount = 0;
            lastByteCount = 0;
            ServerProgressState.reset();
        }
    }

    public static void setTotalToSync(long total) { totalToSync = total; }
    public static long getTotalToSync() { return totalToSync; }

    public static boolean isServerConnected() {
        return serverConnected;
    }

    public static void incrementReceived(long bytes) {
        chunksReceived.incrementAndGet();
        bytesReceived.addAndGet(bytes);
    }

    public static void tick() {
        long now = System.currentTimeMillis();
        if (lastUpdateTime == 0) {
            lastUpdateTime = now;
            lastChunkCount = chunksReceived.get();
            lastByteCount = bytesReceived.get();
            return;
        }

        long delta = now - lastUpdateTime;
        if (delta >= 1000) {
            long currentChunkCount = chunksReceived.get();
            long currentByteCount = bytesReceived.get();
            double seconds = delta / 1000.0;
            receiveRate = (currentChunkCount - lastChunkCount) / seconds;
            bandwidthRate = (currentByteCount - lastByteCount) / seconds;
            lastChunkCount = currentChunkCount;
            lastByteCount = currentByteCount;
            lastUpdateTime = now;
        }
    }

    public static double getReceiveRate() {
        return receiveRate;
    }

    public static double getBandwidthRate() {
        return bandwidthRate;
    }

    public static long getChunksReceived() {
        return chunksReceived.get();
    }

    public static long getBytesReceived() {
        return bytesReceived.get();
    }
}

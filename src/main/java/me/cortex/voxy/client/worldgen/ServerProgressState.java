package me.cortex.voxy.client.worldgen;

import me.cortex.voxy.server.worldgen.ChunkGenerationManager;

import java.util.concurrent.atomic.AtomicLong;

public final class ServerProgressState {
    private static volatile long lastUpdateTime;
    private static final AtomicLong totalTarget = new AtomicLong();
    private static final AtomicLong totalRemaining = new AtomicLong();
    private static volatile double chunksPerSecond;
    private static volatile int activeTaskCount;
    private static volatile ChunkGenerationManager.PregenMode mode;
    private static volatile boolean paused;

    private ServerProgressState() {}

    public static boolean isActive() {
        return System.currentTimeMillis() - lastUpdateTime < 5000L;
    }

    public static void update(long target, long remaining, double cps, int tasks, byte modeByte, boolean isPaused) {
        totalTarget.set(target);
        totalRemaining.set(remaining);
        chunksPerSecond = cps;
        activeTaskCount = tasks;
        var values = ChunkGenerationManager.PregenMode.values();
        mode = (modeByte >= 0 && modeByte < values.length) ? values[modeByte] : ChunkGenerationManager.PregenMode.NONE;
        paused = isPaused;
        lastUpdateTime = System.currentTimeMillis();
    }

    public static void reset() {
        lastUpdateTime = 0;
        totalTarget.set(0);
        totalRemaining.set(0);
        chunksPerSecond = 0;
        activeTaskCount = 0;
        mode = ChunkGenerationManager.PregenMode.NONE;
        paused = false;
    }

    public static long getTotalTarget() { return totalTarget.get(); }
    public static long getTotalRemaining() { return totalRemaining.get(); }
    public static double getChunksPerSecond() { return chunksPerSecond; }
    public static int getActiveTaskCount() { return activeTaskCount; }
    public static ChunkGenerationManager.PregenMode getMode() { return mode; }
    public static boolean isPaused() { return paused; }
}

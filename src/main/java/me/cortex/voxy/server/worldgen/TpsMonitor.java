package me.cortex.voxy.server.worldgen;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public class TpsMonitor {
    private final long[] recentTickTimes = new long[20];
    private int tickTimeIndex = 0;
    private long lastTickNanos = 0;
    private final AtomicBoolean throttled = new AtomicBoolean(false);
    
    // standard for high performance: 18 tps (55.5ms)
    // aggressively pause if server truly struggles
    private static final double MSPT_THRESHOLD = 1000.0 / 18.0;

    public void tick() {
        long now = System.nanoTime();
        long delta = 0;
        if (lastTickNanos > 0) {
            delta = now - lastTickNanos;
            recentTickTimes[tickTimeIndex] = delta;
            tickTimeIndex = (tickTimeIndex + 1) % recentTickTimes.length;
        }
        lastTickNanos = now;

        long totalTickTime = 0;
        int count = 0;
        for (long tickNanos : recentTickTimes) {
            if (tickNanos > 0) {
                totalTickTime += tickNanos;
                count++;
            }
        }

        float mspt = 0.0f;
        if (count > 0) {
            mspt = (float) (totalTickTime / count) / 1_000_000.0f;
        }

        if (mspt > MSPT_THRESHOLD) {
            throttled.set(true);
        } else {
            throttled.set(false);
        }
    }

    public void reset() {
        lastTickNanos = 0;
        tickTimeIndex = 0;
        Arrays.fill(recentTickTimes, 0);
        throttled.set(false);
    }

    public boolean isThrottled() {
        return throttled.get();
    }
}

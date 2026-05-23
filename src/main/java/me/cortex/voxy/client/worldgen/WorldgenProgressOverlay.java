package me.cortex.voxy.client.worldgen;

import me.cortex.voxy.server.worldgen.ChunkGenerationManager;
import me.cortex.voxy.server.worldgen.GenerationStats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.Locale;

/**
 * Top-center HUD overlay showing LOD pre-generation progress.
 *
 * Visible only while a task is actively running or paused (DYNAMIC / REGION mode),
 * or while the client is actively receiving LOD data from a server.
 * Disappears immediately when nothing is running.
 */
public final class WorldgenProgressOverlay {

    private static final int PANEL_W = 280;
    private static final int ROW_TITLE = 4;
    private static final int ROW_BAR   = 14;
    private static final int BAR_H     = 7;
    private static final int ROW_STATS = 25;
    private static final int PANEL_H   = 37;

    private static boolean progressVisible = true;
    /** Server LOD sync panel; disabled by default, enable via /voxy overlay sync enabled. */
    private static boolean syncVisible = false;
    /** Memory-pressure dot inside the progress overlay; enabled by default. */
    private static boolean memoryPressureVisible = true;
    /** Server pregen progress panel; disabled by default, enable via /voxy overlay progress server_progress enabled. */
    private static boolean serverProgressVisible = false;

    public static boolean isProgressVisible() { return progressVisible; }
    public static void setProgressVisible(boolean v) { progressVisible = v; }

    public static boolean isSyncVisible() { return syncVisible; }
    public static void setSyncVisible(boolean v) { syncVisible = v; }

    public static boolean isMemoryPressureVisible() { return memoryPressureVisible; }
    public static void setMemoryPressureVisible(boolean v) { memoryPressureVisible = v; }

    public static boolean isServerProgressVisible() { return serverProgressVisible; }
    public static void setServerProgressVisible(boolean v) { serverProgressVisible = v; }

    // State tracking across frames
    private static boolean wasRunning = false;
    private static long peakRemaining = 0;
    private static long networkPeakReceived = 0;
    private static float animPhase = 0f;

    private WorldgenProgressOverlay() {}

    /** Called by /voxy pregen stop and start to reset the progress bar baseline. */
    public static void resetPeak() {
        peakRemaining = 0;
    }

    // -------------------------------------------------------------------------

    public static void render(GuiGraphics gfx, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;

        ChunkGenerationManager mgr = ChunkGenerationManager.getInstance();
        boolean localRunning = mgr.isRunning();
        ChunkGenerationManager.PregenMode mode = mgr.getPregenMode();
        boolean localActive = localRunning && mode != ChunkGenerationManager.PregenMode.NONE;
        boolean serverActive = ServerProgressState.isActive();
        boolean networkActive = NetworkState.isServerConnected();

        // Reset peaks when a new local run begins
        if (localActive && !wasRunning) {
            peakRemaining = 0;
        }
        // Reset network peak when disconnecting from a server
        if (!networkActive) {
            networkPeakReceived = 0;
        }
        wasRunning = localActive;

        if (!localActive && !serverActive && !networkActive) return; // nothing running

        if (localActive) {
            if (progressVisible) {
                renderLocal(gfx, mc.font, mgr, mode, partialTick);
            }
        } else if (serverActive) {
            if (progressVisible && serverProgressVisible) {
                renderServerProgress(gfx, mc.font, partialTick);
            }
        } else {
            if (syncVisible) {
                renderNetwork(gfx, mc.font, partialTick);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Local worldgen modes

    private static void renderLocal(GuiGraphics gfx, Font font, ChunkGenerationManager mgr,
                                    ChunkGenerationManager.PregenMode mode, float partialTick) {
        GenerationStats stats = mgr.getStats();
        long remaining = mgr.getTotalRemaining();
        boolean paused = mgr.isUserPaused();

        if (remaining > peakRemaining) peakRemaining = remaining;

        if (remaining <= 0 && !paused) return; // not yet started
        double progress = peakRemaining > 0 ? 1.0 - ((double) remaining / peakRemaining) : 0.0;

        long total      = mgr.getTotalTarget();
        long completed  = Math.max(0, total - remaining);
        double cps      = paused ? 0 : stats.getChunksPerSecond();
        int tasks       = paused ? 0 : mgr.getActiveTaskCount();

        boolean isRegion = mode == ChunkGenerationManager.PregenMode.REGION;

        String titleStr;
        if (paused) {
            titleStr = isRegion ? "Voxy LOD Region Pre-gen \u23f8 Paused" : "Voxy LOD Pre-generation \u23f8 Paused";
        } else {
            titleStr = isRegion ? "Voxy LOD Region Pre-generation" : "Voxy LOD Pre-generation";
        }
        String pctStr   = String.format(Locale.ROOT, "%.1f%%", progress * 100.0);
        String statsStr = paused
                ? String.format(Locale.ROOT, "%,d / %,d chunks  \u00b7  paused  \u00b7  /voxy pregen resume",
                        completed, total)
                : String.format(Locale.ROOT, "%,d / %,d chunks  \u00b7  %.1f c/s  \u00b7  %d tasks",
                        completed, total, cps, tasks);

        int titleColor = paused ? 0xFFFFCC66 : (isRegion ? 0xFFCCFFCC : 0xFFCCEEFF);
        int pctColor   = paused ? 0xFFFF9900 : (isRegion ? 0xFF00FF88 : 0xFF00FFCC);

        int sw   = gfx.guiWidth();
        int panX = sw / 2 - PANEL_W / 2;
        int panY = 12;

        drawBackground(gfx, panX, panY, paused, isRegion);
        drawTitleRow(gfx, font, panX, panY, titleStr, pctStr, titleColor, pctColor);
        drawBar(gfx, panX, panY, progress, paused, isRegion, partialTick);
        drawStats(gfx, font, panX, panY, statsStr);
        drawMemoryPressureDot(gfx, panX, panY);
    }

    // -------------------------------------------------------------------------
    // Remote network mode

    private static void renderNetwork(GuiGraphics gfx, Font font, float partialTick) {
        double cps    = NetworkState.getReceiveRate();
        long received = NetworkState.getChunksReceived();
        long total    = NetworkState.getTotalToSync();
        double kbps   = NetworkState.getBandwidthRate() / 1024.0;

        // Hide once all expected chunks have arrived
        if (total > 0 && received >= total) return;

        // Use a running peak so progress never goes backwards if total updates
        if (received > networkPeakReceived) networkPeakReceived = received;

        boolean determinate = total > 0;
        // Clamp progress so bar never exceeds 100 % (total can be a slight undercount)
        double progress = determinate ? Math.min(1.0, (double) received / total) : 0.0;

        String titleStr = "Voxy LOD Sync";
        String pctStr   = determinate
                ? String.format(Locale.ROOT, "%.1f%%", progress * 100.0)
                : null;
        String statsStr = determinate
                ? String.format(Locale.ROOT, "%,d / %,d chunks  \u00b7  %.1f c/s  \u00b7  %.1f KB/s",
                        received, total, cps, kbps)
                : String.format(Locale.ROOT, "%,d chunks received  \u00b7  %.1f c/s  \u00b7  %.1f KB/s",
                        received, cps, kbps);

        int sw   = gfx.guiWidth();
        int panX = sw / 2 - PANEL_W / 2;
        int panY = 12;

        drawBackground(gfx, panX, panY, false, false);
        drawTitleRow(gfx, font, panX, panY, titleStr, pctStr, 0xFFCCEEFF, 0xFF00FFCC);
        if (determinate) {
            drawBar(gfx, panX, panY, progress, false, false, partialTick);
        } else {
            drawAnimatedBar(gfx, panX, panY, partialTick);
        }
        drawStats(gfx, font, panX, panY, statsStr);
    }

    // -------------------------------------------------------------------------
    // Remote server pregen mode

    private static void renderServerProgress(GuiGraphics gfx, Font font, float partialTick) {
        long remaining = ServerProgressState.getTotalRemaining();
        long total     = ServerProgressState.getTotalTarget();
        boolean paused = ServerProgressState.isPaused();
        double cps     = paused ? 0 : ServerProgressState.getChunksPerSecond();
        int tasks      = paused ? 0 : ServerProgressState.getActiveTaskCount();

        if (remaining <= 0 && !paused) return;

        long completed = Math.max(0, total - remaining);
        double progress = total > 0 ? 1.0 - ((double) remaining / total) : 0.0;

        boolean isRegion = ServerProgressState.getMode() == ChunkGenerationManager.PregenMode.REGION;

        String titleStr;
        if (paused) {
            titleStr = isRegion ? "Voxy Server Region Pre-gen \u23f8 Paused" : "Voxy Server Pre-generation \u23f8 Paused";
        } else {
            titleStr = isRegion ? "Voxy Server Region Pre-generation" : "Voxy Server Pre-generation";
        }
        String pctStr   = String.format(Locale.ROOT, "%.1f%%", progress * 100.0);
        String statsStr = paused
                ? String.format(Locale.ROOT, "%,d / %,d chunks  \u00b7  paused  \u00b7  /voxy pregen resume",
                        completed, total)
                : String.format(Locale.ROOT, "%,d / %,d chunks  \u00b7  %.1f c/s  \u00b7  %d tasks",
                        completed, total, cps, tasks);

        int titleColor = paused ? 0xFFFFCC66 : (isRegion ? 0xFFCCFFCC : 0xFFCCEEFF);
        int pctColor   = paused ? 0xFFFF9900 : (isRegion ? 0xFF00FF88 : 0xFF00FFCC);

        int sw   = gfx.guiWidth();
        int panX = sw / 2 - PANEL_W / 2;
        int panY = 12;

        drawBackground(gfx, panX, panY, paused, isRegion);
        drawTitleRow(gfx, font, panX, panY, titleStr, pctStr, titleColor, pctColor);
        drawBar(gfx, panX, panY, progress, paused, isRegion, partialTick);
        drawStats(gfx, font, panX, panY, statsStr);
        drawMemoryPressureDot(gfx, panX, panY);
    }

    // -------------------------------------------------------------------------
    // Drawing primitives

    private static void drawBackground(GuiGraphics gfx, int x, int y, boolean paused, boolean region) {
        final int alpha = 0xC0;
        gfx.fill(x, y, x + PANEL_W, y + PANEL_H, (alpha << 24) | 0x060912);
        int accentRGB = paused ? 0xFF9900 : region ? 0x00EE66 : 0x00BBFF;
        gfx.fill(x, y, x + PANEL_W, y + 2, (alpha << 24) | accentRGB);
        int borderRGB = paused ? 0x664400 : region ? 0x005533 : 0x004466;
        int border = (alpha << 24) | borderRGB;
        gfx.fill(x,               y + 2,           x + 1,           y + PANEL_H, border);
        gfx.fill(x + PANEL_W - 1, y + 2,           x + PANEL_W,     y + PANEL_H, border);
        gfx.fill(x + 1,           y + PANEL_H - 1, x + PANEL_W - 1, y + PANEL_H, border);
    }

    private static void drawTitleRow(GuiGraphics gfx, Font font,
                                     int x, int y, String title, String right,
                                     int titleColor, int rightColor) {
        int ty = y + ROW_TITLE;
        gfx.drawString(font, title, x + 6, ty, titleColor, false);
        if (right != null) {
            int rw = font.width(right);
            gfx.drawString(font, right, x + PANEL_W - 6 - rw, ty, rightColor, false);
        }
    }

    private static void drawBar(GuiGraphics gfx, int x, int y, double progress,
                                 boolean paused, boolean region, float partialTick) {
        int bx = x + 4;
        int by = y + ROW_BAR;
        int bw = PANEL_W - 8;

        gfx.fill(bx, by, bx + bw, by + BAR_H, 0xFF0A0A14);
        gfx.fill(bx, by, bx + bw, by + 1, 0xFF1A1A2A);

        int fillW = (int)(bw * Math.max(0.0, Math.min(1.0, progress)));
        if (fillW <= 0) return;

        int topColor = paused ? 0xFFBB6600 : region ? 0xFF00CC66 : 0xFF0099FF;
        int botColor = paused ? 0xFF663300 : region ? 0xFF005533 : 0xFF004499;
        gfx.fillGradient(bx, by, bx + fillW, by + BAR_H, topColor, botColor);
        gfx.fillGradient(bx, by, bx + fillW, by + 2, 0x55FFFFFF, 0x00FFFFFF);

        if (!paused && fillW < bw) {
            int edgeColor = region ? 0xA000FF88 : 0xA000EEFF;
            gfx.fill(bx + fillW - 1, by, bx + fillW, by + BAR_H, edgeColor);
        }
    }

    private static void drawAnimatedBar(GuiGraphics gfx, int x, int y, float partialTick) {
        animPhase = (animPhase + partialTick * 0.018f) % 1f;

        int bx = x + 4;
        int by = y + ROW_BAR;
        int bw = PANEL_W - 8;

        gfx.fill(bx, by, bx + bw, by + BAR_H, 0xFF0A0A14);
        gfx.fill(bx, by, bx + bw, by + 1, 0xFF1A1A2A);

        int pulseW = bw / 3;
        int offset = (int)((bw + pulseW) * animPhase) - pulseW;
        int cs = Math.max(bx, bx + offset);
        int ce = Math.min(bx + bw, bx + offset + pulseW);
        if (cs < ce) {
            gfx.fillGradient(cs, by, ce, by + BAR_H, 0xFF005599, 0xFF003366);
            gfx.fillGradient(cs, by, ce, by + 2, 0x660099EE, 0x00006699);
        }
    }

    private static void drawStats(GuiGraphics gfx, Font font, int x, int y, String statsStr) {
        int sw = font.width(statsStr);
        int cx = x + PANEL_W / 2;
        gfx.drawString(font, statsStr, cx - sw / 2, y + ROW_STATS, 0xFF7A8899, false);
    }

    // -------------------------------------------------------------------------
    // Memory-pressure dot

    /** Returns the ARGB color for the memory-pressure dot, or 0 if the dot should not be drawn. */
    private static int memoryPressureDotColor() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();

        // Heap not yet expanded much — plenty of headroom for GC to grow
        if (totalMemory < maxMemory * 9L / 10L) {
            long used = totalMemory - freeMemory;
            double ratio = (double) used / totalMemory;
            if (ratio < 0.70) return 0xFF00FF00; // green
            if (ratio < 0.85) return 0xFFFFFF00; // yellow
            return 0xFFFF0000;                 // red
        }

        // Heap is expanded; look at committed-space pressure
        long used = totalMemory - freeMemory;
        double ratio = (double) used / totalMemory;
        if (ratio < 0.75) return 0xFF00FF00; // green
        if (ratio < 0.88) return 0xFFFFFF00; // yellow
        return 0xFFFF0000;                 // red
    }

    private static void drawMemoryPressureDot(GuiGraphics gfx, int x, int y) {
        if (!memoryPressureVisible) return;
        int color = memoryPressureDotColor();
        if (color == 0) return;
        int size = 5;
        int dx = x + PANEL_W - 10 - size;
        int dy = y + ROW_STATS + 1;
        gfx.fill(dx, dy, dx + size, dy + size, color);
    }

}


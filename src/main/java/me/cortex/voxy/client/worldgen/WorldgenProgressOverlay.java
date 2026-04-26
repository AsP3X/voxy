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
 * Shown while a local worldgen worker is active or while the client receives
 * LOD data from a worldgen-capable server. Lingers for a few seconds after
 * completion to show a "Done" state, then fades.
 */
public final class WorldgenProgressOverlay {

    private static final int PANEL_W = 264;
    // Row Y offsets relative to panel top
    private static final int ROW_TITLE = 4;   // title text top-left
    private static final int ROW_BAR   = 14;  // progress bar top
    private static final int BAR_H     = 7;
    private static final int ROW_STATS = 25;  // stats text top
    private static final int PANEL_H   = 37;  // total height

    private static final long LINGER_MS = 6_000;

    private static boolean visible = true;

    public static boolean isVisible() { return visible; }
    public static void setVisible(boolean v) { visible = v; }

    // State tracking across frames
    private static boolean wasRunning = false;
    private static long peakRemaining = 0;
    private static long completedTimestamp = -1;
    // animated offset for indeterminate bar (network mode)
    private static float animPhase = 0f;

    private WorldgenProgressOverlay() {}

    // -------------------------------------------------------------------------

    public static void render(GuiGraphics gfx, float partialTick) {
        if (!visible) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;

        ChunkGenerationManager mgr = ChunkGenerationManager.getInstance();
        boolean localRunning  = mgr.isRunning();
        boolean userPaused    = localRunning && mgr.isUserPaused();
        boolean networkActive = NetworkState.isServerConnected();

        // Detect new server session — reset peak so the bar starts fresh
        if (localRunning && !wasRunning) {
            peakRemaining = 0;
            completedTimestamp = -1;
        }
        wasRunning = localRunning;

        // Determine display mode
        boolean showLocal   = localRunning;
        boolean showNetwork = networkActive && !localRunning; // prefer local stats when both active

        if (!showLocal && !showNetwork) {
            // Nothing active — check linger
            if (completedTimestamp < 0) return;
            if (System.currentTimeMillis() - completedTimestamp > LINGER_MS) {
                completedTimestamp = -1;
                return;
            }
            // Still lingering: render complete state
            renderComplete(gfx, mc.font, mgr.getStats());
            return;
        }

        if (showLocal) {
            renderLocal(gfx, mc.font, mgr, partialTick);
        } else {
            renderNetwork(gfx, mc.font, partialTick);
        }
    }

    // -------------------------------------------------------------------------
    // Stopped / paused state

    // -------------------------------------------------------------------------
    // Local worldgen mode

    private static void renderLocal(GuiGraphics gfx, Font font, ChunkGenerationManager mgr, float partialTick) {
        GenerationStats stats = mgr.getStats();
        int remaining = mgr.getTotalRemaining();

        if (remaining > peakRemaining) peakRemaining = remaining;

        boolean done    = remaining <= 0 && peakRemaining > 0;
        boolean stopped = mgr.isUserPaused();

        if (done && !stopped) {
            if (completedTimestamp < 0) completedTimestamp = System.currentTimeMillis();
            renderComplete(gfx, font, stats);
            return;
        }
        if (remaining <= 0 && !stopped) return; // not yet started

        completedTimestamp = -1;
        double progress = peakRemaining > 0 ? 1.0 - ((double) remaining / peakRemaining) : 0.0;

        long completed = stats.getCompleted() + stats.getSkipped();
        long total     = completed + remaining;
        double cps     = stopped ? 0 : stats.getChunksPerSecond();
        int tasks      = stopped ? 0 : mgr.getActiveTaskCount();

        String titleStr = stopped ? "Voxy LOD Pre-generation \u23f8 Stopped" : "Voxy LOD Pre-generation";
        String pctStr   = String.format(Locale.ROOT, "%.1f%%", progress * 100.0);
        String statsStr = stopped
                ? String.format(Locale.ROOT, "%,d / %,d chunks  \u00b7  paused  \u00b7  /voxy pregen start", completed, total)
                : String.format(Locale.ROOT, "%,d / %,d chunks  \u00b7  %.1f c/s  \u00b7  %d tasks", completed, total, cps, tasks);

        int titleColor = stopped ? 0xFFFFCC66 : 0xFFCCEEFF;
        int pctColor   = stopped ? 0xFFFF9900 : 0xFF00FFCC;

        int sw   = gfx.guiWidth();
        int panX = sw / 2 - PANEL_W / 2;
        int panY = 12;

        drawBackground(gfx, panX, panY, false, stopped);
        drawTitleRow(gfx, font, panX, panY, titleStr, pctStr, titleColor, pctColor);
        drawBar(gfx, panX, panY, progress, false, stopped, partialTick);
        drawStats(gfx, font, panX, panY, statsStr);
    }

    // -------------------------------------------------------------------------
    // Remote network mode (LOD sync from server)

    private static void renderNetwork(GuiGraphics gfx, Font font, float partialTick) {
        double cps      = NetworkState.getReceiveRate();
        long received   = NetworkState.getChunksReceived();
        double kbps     = NetworkState.getBandwidthRate() / 1024.0;

        String titleStr = "Voxy LOD Sync";
        String statsStr = String.format(Locale.ROOT,
                "%,d received  \u00b7  %.1f c/s  \u00b7  %.1f KB/s",
                received, cps, kbps);

        int sw   = gfx.guiWidth();
        int panX = sw / 2 - PANEL_W / 2;
        int panY = 12;

        drawBackground(gfx, panX, panY, false, false);
        drawTitleRow(gfx, font, panX, panY, titleStr, null, 0xFFCCEEFF, 0);
        drawAnimatedBar(gfx, panX, panY, partialTick);
        drawStats(gfx, font, panX, panY, statsStr);
    }

    // -------------------------------------------------------------------------
    // Complete / linger state

    private static void renderComplete(GuiGraphics gfx, Font font, GenerationStats stats) {
        long processed = stats.getCompleted() + stats.getSkipped();
        String titleStr = "Voxy Pre-generation Complete";
        String statsStr = String.format(Locale.ROOT, "%,d chunks processed", processed);

        // fade out in last 1.5 s
        long age = completedTimestamp >= 0 ? System.currentTimeMillis() - completedTimestamp : 0;
        float alpha = 1f;
        if (age > LINGER_MS - 1500) {
            alpha = 1f - ((age - (LINGER_MS - 1500)) / 1500f);
            alpha = Math.max(0f, Math.min(1f, alpha));
        }

        int sw   = gfx.guiWidth();
        int panX = sw / 2 - PANEL_W / 2;
        int panY = 12;

        int a = (int)(alpha * 255) & 0xFF;
        drawBackgroundAlpha(gfx, panX, panY, true, false, a);
        if (a > 10) {
            drawTitleRow(gfx, font, panX, panY, titleStr, "Done!", applyAlpha(0xFF66FFAA, a), applyAlpha(0xFF00FF88, a));
            drawBar(gfx, panX, panY, 1.0, true, false, 0);
            drawStats(gfx, font, panX, panY, statsStr);
        }
    }

    // -------------------------------------------------------------------------
    // Drawing primitives

    private static void drawBackground(GuiGraphics gfx, int x, int y, boolean done, boolean stopped) {
        drawBackgroundAlpha(gfx, x, y, done, stopped, 0xC0);
    }

    private static void drawBackgroundAlpha(GuiGraphics gfx, int x, int y, boolean done, boolean stopped, int alpha) {
        // Dark panel
        gfx.fill(x, y, x + PANEL_W, y + PANEL_H, (alpha << 24) | 0x060912);
        // Top 2-px accent line
        int accentRGB = done ? 0x00FF88 : stopped ? 0xFF9900 : 0x00BBFF;
        gfx.fill(x, y, x + PANEL_W, y + 2, (alpha << 24) | accentRGB);
        // Side + bottom borders
        int borderRGB = done ? 0x006644 : stopped ? 0x664400 : 0x004466;
        int border = (alpha << 24) | borderRGB;
        gfx.fill(x,               y + 2,           x + 1,            y + PANEL_H, border); // left
        gfx.fill(x + PANEL_W - 1, y + 2,           x + PANEL_W,      y + PANEL_H, border); // right
        gfx.fill(x + 1,           y + PANEL_H - 1, x + PANEL_W - 1,  y + PANEL_H, border); // bottom
    }

    private static void drawTitleRow(GuiGraphics gfx, Font font,
                                     int x, int y,
                                     String title, String right,
                                     int titleColor, int rightColor) {
        int ty = y + ROW_TITLE;
        gfx.drawString(font, title, x + 6, ty, titleColor, false);
        if (right != null) {
            int rw = font.width(right);
            gfx.drawString(font, right, x + PANEL_W - 6 - rw, ty, rightColor, false);
        }
    }

    private static void drawBar(GuiGraphics gfx, int x, int y, double progress, boolean done, boolean stopped, float partialTick) {
        int bx = x + 4;
        int by = y + ROW_BAR;
        int bw = PANEL_W - 8;

        // Bar background
        gfx.fill(bx, by, bx + bw, by + BAR_H, 0xFF0A0A14);
        gfx.fill(bx, by, bx + bw, by + 1, 0xFF1A1A2A); // subtle top reflection on bg

        int fillW = (int)(bw * Math.max(0.0, Math.min(1.0, progress)));
        if (fillW <= 0) return;

        // Main bar fill — vertical gradient for depth
        int topColor = done ? 0xFF00EE88 : stopped ? 0xFFBB6600 : 0xFF0099FF;
        int botColor = done ? 0xFF007744 : stopped ? 0xFF663300 : 0xFF004499;
        gfx.fillGradient(bx, by, bx + fillW, by + BAR_H, topColor, botColor);

        // Shine on top 2px
        gfx.fillGradient(bx, by, bx + fillW, by + 2, 0x55FFFFFF, 0x00FFFFFF);

        // Bright leading edge (not on completed or stopped bar)
        if (!done && !stopped && fillW < bw) {
            int edgeColor = 0xA000EEFF;
            gfx.fill(bx + fillW - 1, by, bx + fillW, by + BAR_H, edgeColor);
        }
    }

    private static void drawAnimatedBar(GuiGraphics gfx, int x, int y, float partialTick) {
        animPhase = (animPhase + partialTick * 0.018f) % 1f;

        int bx = x + 4;
        int by = y + ROW_BAR;
        int bw = PANEL_W - 8;

        // Trough
        gfx.fill(bx, by, bx + bw, by + BAR_H, 0xFF0A0A14);
        gfx.fill(bx, by, bx + bw, by + 1, 0xFF1A1A2A);

        // Sliding pulse — 1/3 bar width wide
        int pulseW = bw / 3;
        int offset = (int)((bw + pulseW) * animPhase) - pulseW;

        // Clamp to bar bounds
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

    private static int applyAlpha(int argb, int alpha) {
        return (argb & 0x00FFFFFF) | (alpha << 24);
    }
}

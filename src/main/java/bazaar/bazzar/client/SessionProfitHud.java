package bazaar.bazzar.client;

import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;

final class SessionProfitHud {
    private static final int BAR_HEIGHT = 18;
    private static final String CHIP_LABEL = "Session";
    private static final String RESET_LABEL = "reset";
    private static final long SAMPLE_INTERVAL_MS = 1000L;
    private static final long MIN_RATE_ELAPSED_MS = 60_000L;
    private static final double SUSPICIOUS_JUMP_FLOOR = 1_000_000d;
    private static final int BASELINE_CONFIRM_SAMPLES = 5;
    private static final int PANEL_BG = 0xDB0C0E12;
    private static final int PANEL_EDGE = 0x14FFFFFF;
    private static final int DIVIDER = 0xFF2A3039;
    private static final int MUTED_TEXT = 0xFFB4BCC8;
    private static final int VALUE_TEXT = 0xFFE6E9EE;
    private static final int RESET_TEXT = 0xFF6B7480;
    private static final int GAIN_TEXT = 0xFF86EFAC;

    private static int hudX = 6;
    private static int hudY = 140;
    private static float scale = 1.0f;
    private static int barWidth = 200;
    private static int resetX = 170;
    private static boolean dragging = false;
    private static boolean dragMoved = false;
    private static int dragOffsetX = 0;
    private static int dragOffsetY = 0;
    private static boolean dirty = false;

    private static long sessionStartMs = 0L;
    private static long lastSampleMs = 0L;
    private static double lastShards = -1d;
    private static double pendingBaseline = -1d;
    private static int pendingSamples = 0;
    private static double earnedShards = 0d;
    private static long blocksBroken = 0L;
    private static ClientLevel trackedLevel = null;

    private SessionProfitHud() {
    }

    static boolean isEnabled() {
        BazzarConfig.State state = BazzarConfig.load();
        return state != null && state.profitHudEnabled;
    }

    static void setEnabled(boolean value) {
        BazzarConfig.State state = BazzarConfig.load();
        if (state == null) {
            return;
        }
        state.profitHudEnabled = value;
        BazzarConfig.save(state);
    }

    static void applyConfig(BazzarConfig.State state) {
        if (state == null) {
            return;
        }
        hudX = state.profitHudX;
        hudY = state.profitHudY;
        scale = Math.max(0.65f, Math.min(2.5f, state.profitHudScale));
        dirty = false;
    }

    static void applyConfigToState(BazzarConfig.State state) {
        if (state == null) {
            return;
        }
        state.profitHudX = hudX;
        state.profitHudY = hudY;
        state.profitHudScale = scale;
    }

    static void onMouseScroll(double mouseX, double mouseY, double verticalAmount) {
        if (!shouldHandleInput() || verticalAmount == 0.0d) {
            return;
        }
        if (!isHovering((int) mouseX, (int) mouseY)) {
            return;
        }
        float step = (float) (Math.signum(verticalAmount) * 0.05f);
        float clamped = Math.max(0.65f, Math.min(2.5f, scale + step));
        if (Math.abs(clamped - scale) > 0.0001f) {
            scale = clamped;
            dirty = true;
        }
    }

    static boolean consumeDirty() {
        if (!dirty) {
            return false;
        }
        dirty = false;
        return true;
    }

    static void resetSession() {
        sessionStartMs = 0L;
        lastShards = -1d;
        pendingBaseline = -1d;
        pendingSamples = 0;
        earnedShards = 0d;
        blocksBroken = 0L;
    }

    static void onBlockBroken() {
        blocksBroken++;
        if (sessionStartMs == 0L) {
            sessionStartMs = System.currentTimeMillis();
        }
    }

    static void tick(Minecraft client) {
        if (client == null || client.level == null || client.player == null) {
            lastShards = -1d;
            return;
        }
        if (client.level != trackedLevel) {
            trackedLevel = client.level;
            lastShards = -1d;
        }
        long now = System.currentTimeMillis();
        if (now - lastSampleMs < SAMPLE_INTERVAL_MS) {
            return;
        }
        lastSampleMs = now;
        List<ScoreboardReader.Line> lines = ScoreboardReader.readSidebar(client);
        if (lines.isEmpty()) {
            return;
        }
        double shards = -1d;
        for (ScoreboardReader.Line line : lines) {
            String text = line.normalizedText();
            if (text.contains("shard")) {
                shards = SbText.amountAfterLabel(text, "shard");
                break;
            }
        }
        if (shards < 0d) {
            return;
        }
        PlayerStats.onShardsSeen(now);
        if (sessionStartMs == 0L) {
            sessionStartMs = now;
        }
        if (lastShards < 0d) {
            lastShards = shards;
            pendingBaseline = -1d;
            pendingSamples = 0;
            return;
        }
        double delta = shards - lastShards;
        boolean suspicious = delta < 0d || delta > Math.max(SUSPICIOUS_JUMP_FLOOR, lastShards * 0.5d);
        if (!suspicious) {
            earnedShards += delta;
            PlayerStats.addShards(delta);
            lastShards = shards;
            pendingBaseline = -1d;
            pendingSamples = 0;
            return;
        }
        if (pendingBaseline >= 0d && Math.abs(shards - pendingBaseline) <= Math.max(pendingBaseline * 0.05d, 10_000d)) {
            pendingBaseline = shards;
            pendingSamples++;
            if (pendingSamples >= BASELINE_CONFIRM_SAMPLES) {
                lastShards = shards;
                pendingBaseline = -1d;
                pendingSamples = 0;
            }
            return;
        }
        pendingBaseline = shards;
        pendingSamples = 1;
    }

    static void render(GuiGraphics graphics, int mouseX, int mouseY) {
        HudBoxes.hide("profit");
        Minecraft client = Minecraft.getInstance();
        if (!isEnabled() || client == null || client.player == null || client.level == null) {
            return;
        }
        Font font = client.font;
        long now = System.currentTimeMillis();
        long elapsed = sessionStartMs > 0L ? now - sessionStartMs : 0L;
        String shardsText = "+" + SbText.formatCompact(earnedShards) + " shards";
        String rateText = elapsed >= MIN_RATE_ELAPSED_MS
                ? SbText.formatCompact(earnedShards / (elapsed / 3_600_000d)) + "/h"
                : "";
        String blocksText = "+" + SbText.formatCompact(blocksBroken) + " blocks";
        String timeText = formatElapsed(elapsed);

        int chipW = font.width(CHIP_LABEL) + 12;
        int shardsX = 3 + chipW + 6;
        int rateX = shardsX + font.width(shardsText) + 4;
        int firstDividerX = rateText.isEmpty() ? rateX + 2 : rateX + font.width(rateText) + 6;
        int blocksX = firstDividerX + 7;
        int secondDividerX = blocksX + font.width(blocksText) + 6;
        int timeX = secondDividerX + 7;
        resetX = timeX + font.width(timeText) + 8;
        barWidth = resetX + font.width(RESET_LABEL) + 8;

        int scaledW = Math.round(barWidth * scale);
        int scaledH = Math.round(BAR_HEIGHT * scale);
        hudX = clamp(hudX, 2, Math.max(2, graphics.guiWidth() - scaledW - 2));
        hudY = HudBoxes.place("profit", hudX, clamp(hudY, 2, Math.max(2, graphics.guiHeight() - scaledH - 2)),
                scaledW, scaledH, graphics.guiHeight());

        BazzarTheme theme = BazzarTheme.current();
        graphics.pose().pushMatrix();
        graphics.pose().translate(hudX, hudY);
        graphics.pose().scale(scale, scale);
        BazzarUi.fillRoundedRect(graphics, -1, -1, barWidth + 2, BAR_HEIGHT + 2, (BAR_HEIGHT + 2) / 2, PANEL_EDGE);
        BazzarUi.fillRoundedRect(graphics, 0, 0, barWidth, BAR_HEIGHT, BAR_HEIGHT / 2, PANEL_BG);
        BazzarUi.fillRoundedRect(graphics, 3, 3, chipW, BAR_HEIGHT - 6, (BAR_HEIGHT - 6) / 2, theme.accent());
        graphics.drawString(font, CHIP_LABEL, 9, 5, theme.readableOnAccent(), false);
        graphics.drawString(font, shardsText, shardsX, 5, GAIN_TEXT, false);
        if (!rateText.isEmpty()) {
            graphics.drawString(font, rateText, rateX, 5, theme.light(), false);
        }
        graphics.fill(firstDividerX, 4, firstDividerX + 1, BAR_HEIGHT - 4, DIVIDER);
        graphics.drawString(font, blocksText, blocksX, 5, VALUE_TEXT, false);
        graphics.fill(secondDividerX, 4, secondDividerX + 1, BAR_HEIGHT - 4, DIVIDER);
        graphics.drawString(font, timeText, timeX, 5, MUTED_TEXT, false);
        graphics.drawString(font, RESET_LABEL, resetX, 5, RESET_TEXT, false);
        graphics.pose().popMatrix();
    }

    static String gainLabel(double earned, long elapsedMs) {
        String total = "+" + SbText.formatCompact(earned);
        if (elapsedMs < MIN_RATE_ELAPSED_MS) {
            return total;
        }
        double perHour = earned / (elapsedMs / 3_600_000d);
        return total + " (" + SbText.formatCompact(perHour) + "/h)";
    }

    static String formatElapsed(long elapsedMs) {
        long totalMinutes = elapsedMs / 60_000L;
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%dh %02dm", hours, minutes);
        }
        return minutes + "m";
    }

    static void onMouseClick(Screen screen, MouseButtonEvent event) {
        if (!shouldHandleInput()) {
            return;
        }
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        if (!isHovering(mouseX, mouseY)) {
            return;
        }
        int localX = Math.round((mouseX - hudX) / scale);
        int localY = Math.round((mouseY - hudY) / scale);
        if (localX >= resetX - 3 && localX <= barWidth && localY >= 0 && localY <= BAR_HEIGHT) {
            resetSession();
            return;
        }
        if (event.button() == 1) {
            resetSession();
            return;
        }
        dragging = true;
        dragMoved = false;
        dragOffsetX = mouseX - hudX;
        dragOffsetY = mouseY - hudY;
    }

    static void onMouseRelease(Screen screen, MouseButtonEvent event) {
        if (!dragging) {
            return;
        }
        dragging = false;
        if (dragMoved) {
            dirty = true;
        }
        dragMoved = false;
    }

    static void onMouseDrag(Screen screen, MouseButtonEvent event, double deltaX, double deltaY) {
        if (!dragging) {
            return;
        }
        dragMoved = true;
        int[] pos = HudBoxes.drag("profit", hudX, hudY, (int) event.x() - dragOffsetX, (int) event.y() - dragOffsetY);
        hudX = pos[0];
        hudY = pos[1];
    }

    private static boolean shouldHandleInput() {
        Minecraft client = Minecraft.getInstance();
        return isEnabled() && client != null && client.player != null && client.level != null;
    }

    private static boolean isHovering(int mouseX, int mouseY) {
        int scaledW = Math.round(barWidth * scale);
        int scaledH = Math.round(BAR_HEIGHT * scale);
        return mouseX >= hudX && mouseX <= hudX + scaledW && mouseY >= hudY && mouseY <= hudY + scaledH;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

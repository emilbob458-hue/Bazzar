package com.shady.mod.client;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;

final class ShadySummonerTimerHud {
    private static final Pattern CROP_PATTERN =
            Pattern.compile("(?i)he\\s+knows\\s+something\\s+about\\s+(.+?)\\.{3}\\s*$");

    private static final String SUMMONER_DISAPPEARED = "the shady summoner has disappeared!";
    private static final String BAZAAR_AFFECTED = "the bazaar was affected by his presence";
    private static final long TIMER_DURATION_MS = 30L * 60L * 1000L;

    private static final int HUD_BG = 0xA6111720;
    private static final int HUD_BORDER = 0xFF4D738D;
    private static final int HUD_TEXT = 0xFFE7F2FF;
    private static final int HUD_TEXT_MUTED = 0xFFC1D2DE;
    private static final int HUD_TEXT_ACTIVE = 0xFF7EF59C;
    private static final int WIDTH = 188;
    private static final int HEIGHT = 44;

    private static String currentCrop = "";
    private static long timerEndsAtMs = 0L;

    private static int hudX = 8;
    private static int hudY = 8;
    private static boolean dragging = false;
    private static int dragOffsetX = 0;
    private static int dragOffsetY = 0;
    private static boolean dirty = false;

    private ShadySummonerTimerHud() {
    }

    static void onChatLine(String rawLine) {
        if (rawLine == null || rawLine.isBlank()) {
            return;
        }
        String line = rawLine.trim();
        String lower = line.toLowerCase(Locale.ROOT);

        Matcher cropMatcher = CROP_PATTERN.matcher(line);
        if (cropMatcher.find()) {
            String extractedCrop = normalizeCropName(cropMatcher.group(1));
            if (!extractedCrop.isBlank()) {
                currentCrop = extractedCrop;
            }
            return;
        }

        if (lower.contains(SUMMONER_DISAPPEARED) || lower.contains(BAZAAR_AFFECTED)) {
            timerEndsAtMs = System.currentTimeMillis() + TIMER_DURATION_MS;
        }
    }

    static void render(GuiGraphics graphics, int screenWidth, int screenHeight, int mouseX, int mouseY) {
        if (currentCrop.isBlank() && timerEndsAtMs <= 0L) {
            return;
        }

        long now = System.currentTimeMillis();
        long remainingMs = Math.max(0L, timerEndsAtMs - now);
        if (timerEndsAtMs > 0L && remainingMs <= 0L) {
            timerEndsAtMs = 0L;
        }

        int x = clamp(hudX, 2, Math.max(2, screenWidth - WIDTH - 2));
        int y = clamp(hudY, 2, Math.max(2, screenHeight - HEIGHT - 2));
        hudX = x;
        hudY = y;

        graphics.fill(x, y, x + WIDTH, y + HEIGHT, HUD_BG);
        graphics.fill(x, y, x + WIDTH, y + 1, HUD_BORDER);
        graphics.fill(x, y + HEIGHT - 1, x + WIDTH, y + HEIGHT, HUD_BORDER);
        graphics.fill(x, y, x + 1, y + HEIGHT, HUD_BORDER);
        graphics.fill(x + WIDTH - 1, y, x + WIDTH, y + HEIGHT, HUD_BORDER);

        Minecraft client = Minecraft.getInstance();
        graphics.drawString(
                client.font,
                "Shady Summoner",
                x + 6,
                y + 6,
                HUD_TEXT,
                false
        );

        String cropText = currentCrop.isBlank() ? "Crop: unknown" : "Crop: " + currentCrop;
        graphics.drawString(
                client.font,
                cropText,
                x + 6,
                y + 18,
                HUD_TEXT_MUTED,
                false
        );

        String timerText;
        int timerColor;
        if (timerEndsAtMs > 0L) {
            timerText = "Spawn: " + formatDuration(remainingMs);
            timerColor = HUD_TEXT_ACTIVE;
        } else {
            timerText = "Spawn: ready";
            timerColor = HUD_TEXT_MUTED;
        }
        graphics.drawString(
                client.font,
                timerText,
                x + 6,
                y + 30,
                timerColor,
                false
        );
    }

    static void onMouseClick(Screen screen, MouseButtonEvent event) {
        if (currentCrop.isBlank() && timerEndsAtMs <= 0L) {
            return;
        }
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        if (isHovering(hudX, hudY, WIDTH, HEIGHT, mouseX, mouseY)) {
            dragging = true;
            dragOffsetX = mouseX - hudX;
            dragOffsetY = mouseY - hudY;
        }
    }

    static void onMouseRelease(Screen screen, MouseButtonEvent event) {
        if (dragging) {
            dragging = false;
            dirty = true;
        }
    }

    static void onMouseDrag(Screen screen, MouseButtonEvent event, double deltaX, double deltaY) {
        if (!dragging) {
            return;
        }
        hudX = (int) (event.x() - dragOffsetX);
        hudY = (int) (event.y() - dragOffsetY);
    }

    static void applyConfig(ShadyConfig.State state) {
        if (state == null) {
            return;
        }
        hudX = state.hudX;
        hudY = state.hudY;
        dirty = false;
    }

    static ShadyConfig.State captureConfig(ShadyConfig.State state) {
        if (state == null) {
            state = new ShadyConfig.State();
        }
        state.hudX = hudX;
        state.hudY = hudY;
        return state;
    }

    static boolean consumeDirty() {
        if (!dirty) {
            return false;
        }
        dirty = false;
        return true;
    }

    private static String normalizeCropName(String crop) {
        if (crop == null) {
            return "";
        }
        String value = crop.trim();
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        return value;
    }

    private static String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    private static boolean isHovering(int x, int y, int width, int height, int mouseX, int mouseY) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

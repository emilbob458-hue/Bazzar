package bazaar.bazzar.client;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;

final class QuestCommandHud {
    private static final int DEFAULT_X = 6;
    private static final int DEFAULT_Y = 6;
    private static final int BASE_WIDTH = 192;
    private static final int HEADER_HEIGHT = 13;
    private static final int ROW_HEIGHT = 12;
    private static final int FOOTER_HEIGHT = 9;
    private static final int MAX_ROWS = 6;
    private static final int MIN_ROWS_VISIBLE = 2;

    private static final int NAME_COLOR = 0xFFF1F3F5;
    private static final int VALUE_DONE_COLOR = 0xFF86EFAC;
    private static final int VALUE_MISSING_COLOR = 0xFFB4BCC8;
    private static final int MUTED_TEXT = 0xFFB4BCC8;

    private static boolean visible = true;
    private static float scale = 1.0f;
    private static int hudX = DEFAULT_X;
    private static int hudY = DEFAULT_Y;
    private static boolean dragging = false;
    private static boolean dragMoved = false;
    private static int dragOffsetX = 0;
    private static int dragOffsetY = 0;
    private static boolean dirty = false;
    private static BackgroundMode backgroundMode = BackgroundMode.DARK;

    private QuestCommandHud() {
    }

    static void render(GuiGraphics graphics, int mouseX, int mouseY) {
        HudBoxes.hide("quests");
        Minecraft client = Minecraft.getInstance();
        if (!visible || client == null || client.player == null || client.level == null) {
            return;
        }

        BazzarTheme theme = BazzarTheme.current();
        int rowCount = visibleRows();
        int rawHeight = panelHeight(rowCount);
        int scaledWidth = Math.round(BASE_WIDTH * scale);
        int scaledHeight = Math.round(rawHeight * scale);

        hudX = clamp(hudX, 0, Math.max(0, graphics.guiWidth() - scaledWidth));
        hudY = HudBoxes.place("quests", hudX, clamp(hudY, 0, Math.max(0, graphics.guiHeight() - scaledHeight)),
                scaledWidth, scaledHeight, graphics.guiHeight());

        graphics.pose().pushMatrix();
        graphics.pose().translate(hudX, hudY);
        graphics.pose().scale(scale, scale);

        drawScaledText(graphics, client, "PINNED QUESTS", 0, 3, theme.light(), 0.75f);

        List<GuildQuestScanner.QuestEntry> entries = GuildQuestScanner.getLastEntries();
        if (entries.isEmpty()) {
            graphics.drawString(client.font, "Press K to scan /gq quests.", 0, HEADER_HEIGHT, MUTED_TEXT, true);
        } else {
            int drawRows = Math.min(MAX_ROWS, entries.size());
            for (int i = 0; i < drawRows; i++) {
                GuildQuestScanner.QuestEntry entry = entries.get(i);
                int rowY = HEADER_HEIGHT + (i * ROW_HEIGHT);
                String leftValue = entry.leftValue();
                String rightValue = entry.rightValue();
                int valueColor;
                if ("N/A".equals(leftValue) || "N/A".equals(rightValue)) {
                    valueColor = VALUE_MISSING_COLOR;
                } else if (leftValue.equalsIgnoreCase(rightValue)) {
                    valueColor = VALUE_DONE_COLOR;
                } else {
                    valueColor = theme.light();
                }
                String value = leftValue + " / " + rightValue;
                int valueX = BASE_WIDTH - 2 - client.font.width(value);
                graphics.drawString(client.font, value, valueX, rowY, valueColor, true);

                String name = client.font.plainSubstrByWidth(entry.name(), valueX - 6);
                graphics.drawString(client.font, name, 0, rowY, NAME_COLOR, true);
            }
        }

        int footerY = HEADER_HEIGHT + (rowCount * ROW_HEIGHT) + 1;
        int secondsLeft = GuildQuestScanner.getSecondsUntilQuestRefresh();
        String footerText;
        if (secondsLeft < 0) {
            footerText = "refresh ?";
        } else if (secondsLeft == 0) {
            footerText = "new quests ready";
        } else {
            footerText = "refresh " + formatSeconds(secondsLeft);
        }
        drawScaledText(graphics, client, footerText, 0, footerY, MUTED_TEXT, 0.75f);

        graphics.pose().popMatrix();
    }

    private static void drawScaledText(GuiGraphics graphics, Minecraft client, String text, int x, int y, int color, float textScale) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(textScale, textScale);
        graphics.drawString(client.font, text, 0, 0, color, true);
        graphics.pose().popMatrix();
    }

    private static String formatSeconds(int totalSeconds) {
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m " + seconds + "s";
        }
        if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }

    private static int panelHeight(int rowCount) {
        return HEADER_HEIGHT + (rowCount * ROW_HEIGHT) + FOOTER_HEIGHT;
    }

    static void onMouseClick(Screen screen, MouseButtonEvent event) {
        if (!shouldHandleInput()) {
            return;
        }
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        int width = Math.round(BASE_WIDTH * scale);
        int height = Math.round(panelHeight(visibleRows()) * scale);
        if (!isHovering(hudX, hudY, width, height, mouseX, mouseY)) {
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
            dragMoved = false;
            return;
        }
        dragMoved = false;
        handleQuestClick(event);
    }

    private static void handleQuestClick(MouseButtonEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) {
            return;
        }
        List<GuildQuestScanner.QuestEntry> entries = GuildQuestScanner.getLastEntries();
        if (entries.isEmpty()) {
            return;
        }
        float localY = (float) ((event.y() - hudY) / scale);
        if (localY < HEADER_HEIGHT) {
            return;
        }
        int row = (int) ((localY - HEADER_HEIGHT) / ROW_HEIGHT);
        if (row < 0 || row >= Math.min(MAX_ROWS, entries.size())) {
            return;
        }
        String command = FarmLogic.questToFarmCommand(entries.get(row).name());
        if (command == null) {
            return;
        }
        CommandSender.send(client, command);
        McCompat.setScreen(client, null);
    }

    static void onMouseDrag(Screen screen, MouseButtonEvent event, double deltaX, double deltaY) {
        if (!dragging) {
            return;
        }
        dragMoved = true;
        int[] pos = HudBoxes.drag("quests", hudX, hudY, (int) event.x() - dragOffsetX, (int) event.y() - dragOffsetY);
        hudX = pos[0];
        hudY = pos[1];
    }

    static void onMouseScroll(double mouseX, double mouseY, double verticalAmount) {
        if (!shouldHandleInput() || verticalAmount == 0.0d) {
            return;
        }
        int width = Math.round(BASE_WIDTH * scale);
        int height = Math.round(panelHeight(visibleRows()) * scale);
        if (!isHovering(hudX, hudY, width, height, (int) mouseX, (int) mouseY)) {
            return;
        }
        float step = (float) (Math.signum(verticalAmount) * 0.05f);
        setScale(scale + step);
    }

    static void setVisible(boolean value) {
        if (visible == value) {
            return;
        }
        visible = value;
        dirty = true;
    }

    static boolean isVisible() {
        return visible;
    }

    static float getScale() {
        return scale;
    }

    static void setScale(float value) {
        float clamped = Math.max(0.65f, Math.min(2.5f, value));
        if (Math.abs(clamped - scale) < 0.0001f) {
            return;
        }
        scale = clamped;
        dirty = true;
    }

    static int getHudX() {
        return hudX;
    }

    static int getHudY() {
        return hudY;
    }

    static void resetPositionAndScale() {
        hudX = DEFAULT_X;
        hudY = DEFAULT_Y;
        scale = 1.0f;
        dirty = true;
    }

    static void applyConfig(BazzarConfig.State state) {
        if (state == null) {
            return;
        }
        visible = state.questHudVisible;
        hudX = state.questHudX;
        hudY = state.questHudY;
        scale = Math.max(0.65f, Math.min(2.5f, state.questHudScale));
        backgroundMode = BackgroundMode.fromId(state.questHudBackgroundMode);
        dirty = false;
    }

    static void applyConfigToState(BazzarConfig.State state) {
        if (state == null) {
            return;
        }
        state.questHudVisible = visible;
        state.questHudX = hudX;
        state.questHudY = hudY;
        state.questHudScale = scale;
        state.questHudBackgroundMode = backgroundMode.id();
    }

    static boolean consumeDirty() {
        if (!dirty) {
            return false;
        }
        dirty = false;
        return true;
    }

    static void cycleBackgroundMode() {
        backgroundMode = backgroundMode.next();
        dirty = true;
    }

    static String getBackgroundModeLabel() {
        return switch (backgroundMode) {
            case DARK -> "Dark";
            case CLEAR -> "Clear";
            case BLUE -> "Blue";
        };
    }

    private static int visibleRows() {
        List<GuildQuestScanner.QuestEntry> entries = GuildQuestScanner.getLastEntries();
        return Math.max(MIN_ROWS_VISIBLE, Math.min(MAX_ROWS, entries.size()));
    }

    private static boolean shouldHandleInput() {
        Minecraft client = Minecraft.getInstance();
        return visible && client != null && client.player != null && client.level != null;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean isHovering(int x, int y, int width, int height, int mouseX, int mouseY) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private enum BackgroundMode {
        DARK(0),
        CLEAR(1),
        BLUE(2);

        private final int id;

        BackgroundMode(int id) {
            this.id = id;
        }

        private int id() {
            return id;
        }

        private BackgroundMode next() {
            return switch (this) {
                case DARK -> CLEAR;
                case CLEAR -> BLUE;
                case BLUE -> DARK;
            };
        }

        private static BackgroundMode fromId(int id) {
            for (BackgroundMode value : values()) {
                if (value.id == id) {
                    return value;
                }
            }
            return DARK;
        }
    }
}

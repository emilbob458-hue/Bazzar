package bazaar.bazzar.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;

// the treasure of the crop you're farming: how many levels of it you have and the odds of the next one
final class TreasureHud {
    private static final int BASE_WIDTH = 160;
    private static final int HEADER_HEIGHT = 13;
    private static final int ROW_HEIGHT = 11;

    private static final int PANEL_BG = 0xE00C0E12;
    private static final int PANEL_EDGE = 0x1FFFFFFF;
    private static final int NAME_COLOR = 0xFFF1F3F5;
    private static final int VALUE_COLOR = 0xFF86EFAC;
    private static final int MUTED_TEXT = 0xFFB4BCC8;
    private static final int MISSING_COLOR = 0xFFFF6B6B;
    private static final int LUCK_COLOR = 0xFFFFD75A;

    private static boolean visible = true;
    private static float scale = 1.0f;
    private static int hudX = 6;
    private static int hudY = 260;
    private static boolean dragging = false;
    private static boolean dragMoved = false;
    private static int dragOffsetX = 0;
    private static int dragOffsetY = 0;
    private static boolean dirty = false;
    private static int height = HEADER_HEIGHT + ROW_HEIGHT * 3;

    private TreasureHud() {
    }

    static boolean toggle() {
        visible = !visible;
        dirty = true;
        return visible;
    }

    static boolean isVisible() {
        return visible;
    }

    // mouseX is -1 out in the world; inside a screen the HUD always draws so it can be dragged into place
    static void render(GuiGraphics graphics, int mouseX, int mouseY) {
        HudBoxes.hide("treasure");
        Minecraft client = Minecraft.getInstance();
        if (!visible || client == null || client.player == null || client.level == null) {
            return;
        }
        boolean inWorld = mouseX < 0;
        if (inWorld && (!TreasureTracker.farming() || BazzarConfig.load().treasureLuckOnly && !TreasureTracker.luckActive())) {
            return;
        }
        String crop = TreasureTracker.crop();
        String world = TreasureTracker.world();
        Treasures.Info info = TreasureTracker.current();
        height = HEADER_HEIGHT + ROW_HEIGHT * (crop == null ? 1 : 3);
        int scaledWidth = Math.round(BASE_WIDTH * scale);
        int scaledHeight = Math.round(height * scale);

        hudX = clamp(hudX, 0, Math.max(0, graphics.guiWidth() - scaledWidth));
        hudY = HudBoxes.place("treasure", hudX, clamp(hudY, 0, Math.max(0, graphics.guiHeight() - scaledHeight)),
                scaledWidth, scaledHeight, graphics.guiHeight());

        BazzarTheme theme = BazzarTheme.current();
        Font font = client.font;
        graphics.pose().pushMatrix();
        graphics.pose().translate(hudX, hudY);
        graphics.pose().scale(scale, scale);

        BazzarUi.fillRoundedRect(graphics, -3, -2, BASE_WIDTH + 6, height + 4, 3, PANEL_EDGE);
        BazzarUi.fillRoundedRect(graphics, -2, -1, BASE_WIDTH + 4, height + 2, 3, PANEL_BG);
        String header = "TREASURE" + (world == null ? "" : " · " + world.toUpperCase(java.util.Locale.ROOT));
        drawScaledText(graphics, font, header, 0, 2, theme.light(), 0.75f);
        if (TreasureTracker.luckActive()) {
            String luck = "LUCK";
            drawScaledText(graphics, font, luck, BASE_WIDTH - Math.round(font.width(luck) * 0.75f), 2, LUCK_COLOR, 0.75f);
        }

        int rowY = HEADER_HEIGHT;
        if (crop == null) {
            graphics.drawString(font, "Farm a crop to see its treasure", 0, rowY, MUTED_TEXT, false);
            graphics.pose().popMatrix();
            return;
        }
        String level = info == null ? (TreasureTracker.isLocked(world) ? "World locked"
                : TreasureTracker.isScanning() ? "Reading..." : "Not read yet")
                : !info.discovered ? "Undiscovered"
                : "Level " + info.level + (info.maxLevel > 0 ? "/" + info.maxLevel : "");
        int levelColor = info == null ? MUTED_TEXT : info.discovered ? VALUE_COLOR : MISSING_COLOR;
        int levelX = BASE_WIDTH - font.width(level);
        graphics.drawString(font, level, levelX, rowY, levelColor, false);
        graphics.drawString(font, font.plainSubstrByWidth(crop, levelX - 4), 0, rowY, NAME_COLOR, false);

        rowY += ROW_HEIGHT;
        drawRow(graphics, font, "Chance", info == null || info.chance.isEmpty() ? "?" : info.chance, rowY);
        rowY += ROW_HEIGHT;
        // a drop restarts the countdown, so until the next read there's nothing true to show
        drawRow(graphics, font, "Guaranteed in", info == null || info.guaranteed.isEmpty() ? "?" : info.guaranteed, rowY);
        graphics.pose().popMatrix();
    }

    private static void drawRow(GuiGraphics graphics, Font font, String label, String value, int rowY) {
        graphics.drawString(font, label, 0, rowY, MUTED_TEXT, false);
        graphics.drawString(font, value, BASE_WIDTH - font.width(value), rowY, NAME_COLOR, false);
    }

    private static void drawScaledText(GuiGraphics graphics, Font font, String text, int x, int y, int color, float textScale) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(textScale, textScale);
        graphics.drawString(font, text, 0, 0, color, false);
        graphics.pose().popMatrix();
    }

    static void onMouseClick(Screen screen, MouseButtonEvent event) {
        if (!visible || !isHovering((int) event.x(), (int) event.y())) {
            return;
        }
        dragging = true;
        dragMoved = false;
        dragOffsetX = (int) event.x() - hudX;
        dragOffsetY = (int) event.y() - hudY;
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
        int[] pos = HudBoxes.drag("treasure", hudX, hudY, (int) event.x() - dragOffsetX, (int) event.y() - dragOffsetY);
        hudX = pos[0];
        hudY = pos[1];
    }

    static void onMouseScroll(double mouseX, double mouseY, double verticalAmount) {
        if (!visible || verticalAmount == 0.0d || !isHovering((int) mouseX, (int) mouseY)) {
            return;
        }
        float next = scale + (float) (Math.signum(verticalAmount) * 0.05f);
        float clamped = Math.max(0.65f, Math.min(2.5f, next));
        if (Math.abs(clamped - scale) < 0.0001f) {
            return;
        }
        scale = clamped;
        dirty = true;
    }

    private static boolean isHovering(int mouseX, int mouseY) {
        int width = Math.round(BASE_WIDTH * scale);
        int scaledHeight = Math.round(height * scale);
        return mouseX >= hudX && mouseX <= hudX + width && mouseY >= hudY && mouseY <= hudY + scaledHeight;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static void applyConfig(BazzarConfig.State state) {
        if (state == null) {
            return;
        }
        visible = state.treasureHudVisible;
        hudX = state.treasureHudX;
        hudY = state.treasureHudY;
        scale = Math.max(0.65f, Math.min(2.5f, state.treasureHudScale));
        dirty = false;
    }

    static void applyConfigToState(BazzarConfig.State state) {
        if (state == null) {
            return;
        }
        state.treasureHudVisible = visible;
        state.treasureHudX = hudX;
        state.treasureHudY = hudY;
        state.treasureHudScale = scale;
    }

    static boolean consumeDirty() {
        if (!dirty) {
            return false;
        }
        dirty = false;
        return true;
    }
}

package bazaar.bazzar.client;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;

// who in the guild is actually doing the guild quests: every member's mod reports the kills and farms
// it counted for the current set of quests, so the list shows the workers and the ones just standing there
final class GuildWorkHud {
    private static final int BASE_WIDTH = 150;
    private static final int HEADER_HEIGHT = 13;
    private static final int ROW_HEIGHT = 11;
    private static final int FOOTER_HEIGHT = 9;
    private static final int MAX_ROWS = 8;

    private static final int PANEL_BG = 0xE00C0E12;
    private static final int PANEL_EDGE = 0x1FFFFFFF;
    private static final int BAR_BG = 0x33FFFFFF;
    private static final int NAME_COLOR = 0xFFF1F3F5;
    private static final int VALUE_COLOR = 0xFF86EFAC;
    private static final int MUTED_TEXT = 0xFFB4BCC8;
    private static final int IDLE_TEXT = 0xFF6F7784;

    private static boolean visible = false;
    private static float scale = 1.0f;
    private static int hudX = 6;
    private static int hudY = 140;
    private static boolean dragging = false;
    private static boolean dragMoved = false;
    private static int dragOffsetX = 0;
    private static int dragOffsetY = 0;
    private static boolean dirty = false;

    private GuildWorkHud() {
    }

    static boolean toggle() {
        visible = !visible;
        dirty = true;
        return visible;
    }

    static boolean isVisible() {
        return visible;
    }

    static void render(GuiGraphics graphics, int mouseX, int mouseY) {
        HudBoxes.hide("guildwork");
        Minecraft client = Minecraft.getInstance();
        if (!visible || client == null || client.player == null || client.level == null) {
            return;
        }
        List<GuildShare.Worker> workers = GuildShare.workers();
        String note = note(workers);
        int rows = note != null ? 1 : Math.min(MAX_ROWS, workers.size());
        int height = HEADER_HEIGHT + (rows * ROW_HEIGHT) + FOOTER_HEIGHT;
        int scaledWidth = Math.round(BASE_WIDTH * scale);
        int scaledHeight = Math.round(height * scale);

        hudX = clamp(hudX, 0, Math.max(0, graphics.guiWidth() - scaledWidth));
        hudY = HudBoxes.place("guildwork", hudX, clamp(hudY, 0, Math.max(0, graphics.guiHeight() - scaledHeight)),
                scaledWidth, scaledHeight, graphics.guiHeight());

        BazzarTheme theme = BazzarTheme.current();
        Font font = client.font;
        graphics.pose().pushMatrix();
        graphics.pose().translate(hudX, hudY);
        graphics.pose().scale(scale, scale);

        BazzarUi.fillRoundedRect(graphics, -3, -2, BASE_WIDTH + 6, height + 4, 3, PANEL_EDGE);
        BazzarUi.fillRoundedRect(graphics, -2, -1, BASE_WIDTH + 4, height + 2, 3, PANEL_BG);
        drawScaledText(graphics, font, "GUILD QUESTS · WHO'S ON IT", 0, 2, theme.light(), 0.75f);

        if (note != null) {
            graphics.drawString(font, note, 0, HEADER_HEIGHT, MUTED_TEXT, false);
            graphics.pose().popMatrix();
            return;
        }

        String self = client.player.getName().getString();
        double top = Math.max(1d, workers.get(0).done());
        for (int i = 0; i < rows; i++) {
            GuildShare.Worker worker = workers.get(i);
            int rowY = HEADER_HEIGHT + (i * ROW_HEIGHT);
            boolean working = worker.done() > 0d;
            // a bar as wide as their share of the busiest member's count, so the workers stand out at a glance
            if (working) {
                int barWidth = (int) Math.max(1d, (BASE_WIDTH - 1) * worker.done() / top);
                graphics.fill(0, rowY + 9, barWidth, rowY + 10, worker.name().equalsIgnoreCase(self)
                        ? theme.accent() : BAR_BG);
            }
            String value = working ? SbText.formatCompact(worker.done()) : worker.afk() ? "afk" : "nothing yet";
            int valueX = BASE_WIDTH - font.width(value);
            graphics.drawString(font, value, valueX, rowY, working ? VALUE_COLOR : IDLE_TEXT, false);
            String name = font.plainSubstrByWidth(worker.name(), valueX - 4);
            graphics.drawString(font, name, 0, rowY,
                    worker.name().equalsIgnoreCase(self) ? theme.accent() : working ? NAME_COLOR : IDLE_TEXT, false);
        }

        double total = 0d;
        int busy = 0;
        for (GuildShare.Worker worker : workers) {
            total += worker.done();
            if (worker.done() > 0d) {
                busy++;
            }
        }
        drawScaledText(graphics, font, busy + " of " + workers.size() + " working · " + SbText.formatCompact(total) + " counted",
                0, HEADER_HEIGHT + (rows * ROW_HEIGHT) + 1, MUTED_TEXT, 0.75f);
        graphics.pose().popMatrix();
    }

    // what to say instead of a list; null means there is a list to show
    private static String note(List<GuildShare.Worker> workers) {
        if (!GuildShare.isActive()) {
            return "Set /bazzarguild <name> first.";
        }
        return workers.isEmpty() ? "Waiting for guildmates..." : null;
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
        int[] pos = HudBoxes.drag("guildwork", hudX, hudY, (int) event.x() - dragOffsetX, (int) event.y() - dragOffsetY);
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
        int rows = Math.max(1, Math.min(MAX_ROWS, GuildShare.workers().size()));
        int height = Math.round((HEADER_HEIGHT + (rows * ROW_HEIGHT) + FOOTER_HEIGHT) * scale);
        return mouseX >= hudX && mouseX <= hudX + width && mouseY >= hudY && mouseY <= hudY + height;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static void applyConfig(BazzarConfig.State state) {
        if (state == null) {
            return;
        }
        visible = state.guildWorkHudVisible;
        hudX = state.guildWorkHudX;
        hudY = state.guildWorkHudY;
        scale = Math.max(0.65f, Math.min(2.5f, state.guildWorkHudScale));
        dirty = false;
    }

    static void applyConfigToState(BazzarConfig.State state) {
        if (state == null) {
            return;
        }
        state.guildWorkHudVisible = visible;
        state.guildWorkHudX = hudX;
        state.guildWorkHudY = hudY;
        state.guildWorkHudScale = scale;
    }

    static boolean consumeDirty() {
        if (!dirty) {
            return false;
        }
        dirty = false;
        return true;
    }
}

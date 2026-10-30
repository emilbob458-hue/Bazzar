package bazaar.bazzar.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;

// how many of each kind of mob you have killed, this session and all time. The one you are on now
// sits at the top so you can watch it climb, the rest are whatever you have killed most of today.
final class MobKillHud {
    private static final int BASE_WIDTH = 164;
    private static final int HEADER_HEIGHT = 13;
    private static final int ROW_HEIGHT = 11;
    private static final int FOOTER_HEIGHT = 9;
    private static final int MAX_ROWS = 8;

    private static final int PANEL_BG = 0xE00C0E12;
    private static final int PANEL_EDGE = 0x1FFFFFFF;
    private static final int NAME_COLOR = 0xFFF1F3F5;
    private static final int SESSION_COLOR = 0xFF86EFAC;
    private static final int TOTAL_COLOR = 0xFF7DD3FC;
    private static final int MUTED_TEXT = 0xFFB4BCC8;

    private static boolean visible = false;
    private static float scale = 1.0f;
    private static int hudX = 6;
    private static int hudY = 200;
    private static boolean dragging = false;
    private static boolean dragMoved = false;
    private static int dragOffsetX = 0;
    private static int dragOffsetY = 0;
    private static boolean dirty = false;

    private MobKillHud() {
    }

    static boolean toggle() {
        visible = !visible;
        dirty = true;
        return visible;
    }

    static boolean isVisible() {
        return visible;
    }

    // the mob you are on first, then whatever you have killed most of this session
    static List<MobKillTracker.Entry> rows(List<MobKillTracker.Entry> bySession, String currentName, int max) {
        List<MobKillTracker.Entry> picked = new ArrayList<>();
        if (bySession == null || max <= 0) {
            return picked;
        }
        String wanted = MobKillTracker.key(currentName);
        if (!wanted.isEmpty()) {
            for (MobKillTracker.Entry entry : bySession) {
                if (MobKillTracker.key(entry.name()).equals(wanted)) {
                    picked.add(entry);
                    break;
                }
            }
        }
        for (MobKillTracker.Entry entry : bySession) {
            if (picked.size() >= max) {
                break;
            }
            if (picked.isEmpty() || !MobKillTracker.key(picked.get(0).name()).equals(MobKillTracker.key(entry.name()))) {
                picked.add(entry);
            }
        }
        return picked;
    }

    static void render(GuiGraphics graphics, int mouseX, int mouseY) {
        HudBoxes.hide("mobkills");
        Minecraft client = Minecraft.getInstance();
        if (!visible || client == null || client.player == null || client.level == null) {
            return;
        }
        List<MobKillTracker.Entry> all = MobKillTracker.entries(false);
        String current = MobKillTracker.currentName();
        List<MobKillTracker.Entry> shown = rows(all, current, MAX_ROWS);
        boolean empty = shown.isEmpty();
        int rowCount = empty ? 1 : shown.size();
        int height = HEADER_HEIGHT + (rowCount * ROW_HEIGHT) + FOOTER_HEIGHT;
        int scaledWidth = Math.round(BASE_WIDTH * scale);
        int scaledHeight = Math.round(height * scale);

        hudX = clamp(hudX, 0, Math.max(0, graphics.guiWidth() - scaledWidth));
        hudY = HudBoxes.place("mobkills", hudX, clamp(hudY, 0, Math.max(0, graphics.guiHeight() - scaledHeight)),
                scaledWidth, scaledHeight, graphics.guiHeight());

        BazzarTheme theme = BazzarTheme.current();
        Font font = client.font;
        graphics.pose().pushMatrix();
        graphics.pose().translate(hudX, hudY);
        graphics.pose().scale(scale, scale);

        BazzarUi.fillRoundedRect(graphics, -3, -2, BASE_WIDTH + 6, height + 4, 3, PANEL_EDGE);
        BazzarUi.fillRoundedRect(graphics, -2, -1, BASE_WIDTH + 4, height + 2, 3, PANEL_BG);
        drawScaledText(graphics, font, "KILLS · SESSION / ALL TIME", 0, 2, theme.light(), 0.75f);

        if (empty) {
            graphics.drawString(font, "Hit a mob to count it", 0, HEADER_HEIGHT, MUTED_TEXT, false);
            graphics.pose().popMatrix();
            return;
        }

        String currentKey = MobKillTracker.key(current);
        long sessionTotal = 0L;
        for (MobKillTracker.Entry entry : all) {
            sessionTotal += entry.session();
        }

        for (int i = 0; i < shown.size(); i++) {
            MobKillTracker.Entry entry = shown.get(i);
            int rowY = HEADER_HEIGHT + (i * ROW_HEIGHT);
            boolean onThisOne = MobKillTracker.key(entry.name()).equals(currentKey);

            String total = SbText.formatCompact(entry.total());
            String session = SbText.formatCompact(entry.session());
            int totalX = BASE_WIDTH - font.width(total);
            int slashX = totalX - font.width(" / ");
            int sessionX = slashX - font.width(session);
            graphics.drawString(font, total, totalX, rowY, TOTAL_COLOR, false);
            graphics.drawString(font, " / ", slashX, rowY, MUTED_TEXT, false);
            graphics.drawString(font, session, sessionX, rowY, SESSION_COLOR, false);

            String name = font.plainSubstrByWidth(entry.name(), sessionX - 4);
            graphics.drawString(font, name, 0, rowY, onThisOne ? theme.accent() : NAME_COLOR, false);
        }

        drawScaledText(graphics, font, shown.size() < all.size()
                        ? all.size() + " kinds · " + SbText.formatCompact(sessionTotal) + " this session"
                        : SbText.formatCompact(sessionTotal) + " killed this session",
                0, HEADER_HEIGHT + (shown.size() * ROW_HEIGHT) + 1, MUTED_TEXT, 0.75f);
        graphics.pose().popMatrix();
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
        int[] pos = HudBoxes.drag("mobkills", hudX, hudY, (int) event.x() - dragOffsetX, (int) event.y() - dragOffsetY);
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
        int rows = Math.max(1, Math.min(MAX_ROWS, MobKillTracker.entries(false).size()));
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
        visible = state.killHudVisible;
        hudX = state.killHudX;
        hudY = state.killHudY;
        scale = Math.max(0.65f, Math.min(2.5f, state.killHudScale));
        dirty = false;
    }

    static void applyConfigToState(BazzarConfig.State state) {
        if (state == null) {
            return;
        }
        state.killHudVisible = visible;
        state.killHudX = hudX;
        state.killHudY = hudY;
        state.killHudScale = scale;
    }

    static boolean consumeDirty() {
        if (!dirty) {
            return false;
        }
        dirty = false;
        return true;
    }
}

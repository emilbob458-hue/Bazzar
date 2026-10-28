package bazaar.bazzar.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;

final class MobMilestoneHud {
    private static final int WIDTH = 176;
    private static final int PAD = 5;
    private static final int HEADER_HEIGHT = 16;
    private static final int ROW_HEIGHT = 15;
    private static final int FOOTER_HEIGHT = 10;
    private static final int MAX_ROWS = 8;
    private static final int PANEL_BG = 0xDB0C0E12;
    private static final int PANEL_EDGE = 0x14FFFFFF;
    private static final int TRACK = 0xFF2A3039;
    private static final int MUTED_TEXT = 0xFFB4BCC8;
    private static final int VALUE_TEXT = 0xFFE6E9EE;
    private static final int DONE_TEXT = 0xFF86EFAC;

    private static int hudX = 6;
    private static int hudY = 60;
    private static float scale = 1.0f;
    private static int height = HEADER_HEIGHT;
    private static boolean dragging = false;
    private static boolean dragMoved = false;
    private static int dragOffsetX = 0;
    private static int dragOffsetY = 0;
    private static boolean dirty = false;

    private MobMilestoneHud() {
    }

    static boolean isEnabled() {
        BazzarConfig.State state = BazzarConfig.load();
        return state != null && state.milestoneHudEnabled;
    }

    static void setEnabled(boolean value) {
        BazzarConfig.State state = BazzarConfig.load();
        state.milestoneHudEnabled = value;
        BazzarConfig.save(state);
    }

    static int getMode() {
        return BazzarConfig.load().milestoneMode;
    }

    static void setMode(int mode) {
        BazzarConfig.State state = BazzarConfig.load();
        state.milestoneMode = mode;
        BazzarConfig.save(state);
    }

    static String modeLabel(int mode) {
        return switch (mode) {
            case MobMilestones.MODE_SELECTED -> "Selected";
            case MobMilestones.MODE_CURRENT -> "Current";
            default -> "All";
        };
    }

    static boolean isSelected(String key) {
        List<String> selected = BazzarConfig.load().milestoneSelected;
        return selected != null && selected.contains(key);
    }

    static void toggleSelected(String key) {
        BazzarConfig.State state = BazzarConfig.load();
        if (state.milestoneSelected == null) {
            state.milestoneSelected = new ArrayList<>();
        }
        if (!state.milestoneSelected.remove(key)) {
            state.milestoneSelected.add(key);
        }
        BazzarConfig.save(state);
    }

    static void applyConfig(BazzarConfig.State state) {
        if (state == null) {
            return;
        }
        hudX = state.milestoneHudX;
        hudY = state.milestoneHudY;
        scale = Math.max(0.65f, Math.min(2.5f, state.milestoneHudScale));
        dirty = false;
    }

    static void applyConfigToState(BazzarConfig.State state) {
        if (state == null) {
            return;
        }
        state.milestoneHudX = hudX;
        state.milestoneHudY = hudY;
        state.milestoneHudScale = scale;
    }

    static boolean consumeDirty() {
        if (!dirty) {
            return false;
        }
        dirty = false;
        return true;
    }

    static void resetPositionAndScale() {
        hudX = 6;
        hudY = 60;
        scale = 1.0f;
        dirty = true;
    }

    static void render(GuiGraphics graphics, int mouseX, int mouseY) {
        HudBoxes.hide("milestones");
        Minecraft client = Minecraft.getInstance();
        if (!shouldShow(client)) {
            return;
        }
        Font font = client.font;
        BazzarConfig.State state = BazzarConfig.load();
        int mode = state.milestoneMode;

        List<MobMilestones.Entry> rows = new ArrayList<>();
        String emptyText = null;
        int doneCount = 0;
        List<MobMilestones.Entry> all = MobMilestones.entries();
        for (MobMilestones.Entry entry : all) {
            if (entry.done()) {
                doneCount++;
            }
        }
        if (mode == MobMilestones.MODE_CURRENT) {
            MobMilestones.Entry current = MobMilestones.currentMob();
            if (current != null) {
                rows.add(current);
            } else {
                emptyText = "Hit a mob to track it";
            }
        } else if (mode == MobMilestones.MODE_SELECTED) {
            if (state.milestoneSelected != null) {
                for (String key : state.milestoneSelected) {
                    MobMilestones.Entry entry = MobMilestones.get(key);
                    if (entry != null) {
                        rows.add(entry);
                    }
                }
            }
            if (rows.isEmpty()) {
                emptyText = "Pick mobs in Bazzar settings";
            }
        } else {
            for (MobMilestones.Entry entry : all) {
                if (!entry.done()) {
                    rows.add(entry);
                }
            }
            rows.sort(Comparator.comparingLong(MobMilestones.Entry::remaining));
            if (rows.isEmpty()) {
                emptyText = "Every mob is at 25k!";
            }
        }

        int shown = Math.min(MAX_ROWS, rows.size());
        int hidden = rows.size() - shown;
        int bodyRows = emptyText != null ? 1 : shown;
        height = HEADER_HEIGHT + bodyRows * ROW_HEIGHT + FOOTER_HEIGHT + 2;

        int scaledW = Math.round(WIDTH * scale);
        int scaledH = Math.round(height * scale);
        hudX = clamp(hudX, 2, Math.max(2, graphics.guiWidth() - scaledW - 2));
        hudY = HudBoxes.place("milestones", hudX, clamp(hudY, 2, Math.max(2, graphics.guiHeight() - scaledH - 2)),
                scaledW, scaledH, graphics.guiHeight());

        BazzarTheme theme = BazzarTheme.current();
        graphics.pose().pushMatrix();
        graphics.pose().translate(hudX, hudY);
        graphics.pose().scale(scale, scale);
        BazzarUi.fillRoundedRect(graphics, -1, -1, WIDTH + 2, height + 2, 7, PANEL_EDGE);
        BazzarUi.fillRoundedRect(graphics, 0, 0, WIDTH, height, 6, PANEL_BG);

        drawSmall(graphics, font, "MOB MILESTONES", PAD, PAD + 1, theme.light());
        String modeText = modeLabel(mode).toUpperCase(Locale.ROOT);
        drawSmall(graphics, font, modeText, WIDTH - PAD - Math.round(font.width(modeText) * 0.75f), PAD + 1, MUTED_TEXT);

        int barW = WIDTH - 2 * PAD;
        if (emptyText != null) {
            graphics.drawString(font, emptyText, PAD, HEADER_HEIGHT, MUTED_TEXT, false);
        } else {
            for (int i = 0; i < shown; i++) {
                MobMilestones.Entry entry = rows.get(i);
                int rowY = HEADER_HEIGHT + i * ROW_HEIGHT;
                boolean done = entry.done();
                String value = done ? "Done" : String.format(Locale.ROOT, "%,d left", entry.remaining());
                int valueX = WIDTH - PAD - font.width(value);
                graphics.drawString(font, value, valueX, rowY, done ? DONE_TEXT : theme.light(), false);
                String name = font.plainSubstrByWidth(entry.name, valueX - PAD - 4);
                graphics.drawString(font, name, PAD, rowY, VALUE_TEXT, false);

                float progress = done ? 1.0f : Math.min(1.0f, entry.slain / (float) MobMilestones.TARGET);
                int barY = rowY + 10;
                BazzarUi.fillRoundedRect(graphics, PAD, barY, barW, 2, 1, TRACK);
                int fillW = Math.round(barW * progress);
                if (fillW > 0) {
                    BazzarUi.fillRoundedRect(graphics, PAD, barY, fillW, 2, 1, done ? DONE_TEXT : theme.accent());
                }
            }
        }

        String footer = doneCount + "/" + all.size() + " done";
        if (hidden > 0) {
            footer += "  ·  +" + hidden + " more";
        }
        drawSmall(graphics, font, footer, PAD, height - FOOTER_HEIGHT, MUTED_TEXT);
        graphics.pose().popMatrix();
    }

    private static void drawSmall(GuiGraphics graphics, Font font, String text, int x, int y, int color) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(0.75f, 0.75f);
        graphics.drawString(font, text, 0, 0, color, false);
        graphics.pose().popMatrix();
    }

    static void onMouseClick(Screen screen, MouseButtonEvent event) {
        if (!shouldShow(Minecraft.getInstance())) {
            return;
        }
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        if (!isHovering(mouseX, mouseY)) {
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
        int[] pos = HudBoxes.drag("milestones", hudX, hudY, (int) event.x() - dragOffsetX, (int) event.y() - dragOffsetY);
        hudX = pos[0];
        hudY = pos[1];
    }

    static void onMouseScroll(double mouseX, double mouseY, double verticalAmount) {
        if (!shouldShow(Minecraft.getInstance()) || verticalAmount == 0.0d) {
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

    private static boolean shouldShow(Minecraft client) {
        return isEnabled() && MobMilestones.hasData()
                && client != null && client.player != null && client.level != null;
    }

    private static boolean isHovering(int mouseX, int mouseY) {
        int scaledW = Math.round(WIDTH * scale);
        int scaledH = Math.round(height * scale);
        return mouseX >= hudX && mouseX <= hudX + scaledW && mouseY >= hudY && mouseY <= hudY + scaledH;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

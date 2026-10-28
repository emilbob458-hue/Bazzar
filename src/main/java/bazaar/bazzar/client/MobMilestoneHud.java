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
    private static final int MIN_WIDTH = 90;
    private static final int PAD = 3;
    private static final int GAP = 4;
    private static final int TITLE_HEIGHT = 11;
    private static final int ROW_HEIGHT = 10;
    private static final int MAX_ROWS = 8;
    private static final int TITLE_BG = 0x8C000000;
    private static final int BODY_BG = 0x61000000;
    private static final int DIVIDER = 0x1FFFFFFF;
    private static final int MUTED_TEXT = 0xFFB4BCC8;
    private static final int VALUE_TEXT = 0xFFE6E9EE;
    private static final int DONE_TEXT = 0xFF86EFAC;

    private static int hudX = 6;
    private static int hudY = 60;
    private static float scale = 1.0f;
    private static int width = MIN_WIDTH;
    private static int height = TITLE_HEIGHT;
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
        String title = "Milestones";
        String doneText = doneCount + "/" + all.size();
        String moreText = hidden > 0 ? "+" + hidden + " more" : null;

        // size the strip to its widest line, like the vanilla sidebar
        int pctW = Math.max(font.width("100%"), font.width("Done"));
        int nameW = 0;
        int leftW = 0;
        for (int i = 0; i < shown; i++) {
            MobMilestones.Entry entry = rows.get(i);
            nameW = Math.max(nameW, font.width(entry.name));
            if (!entry.done()) {
                leftW = Math.max(leftW, font.width(shortCount(entry.remaining())));
            }
        }
        int contentW = emptyText != null ? font.width(emptyText) : nameW + GAP + leftW + GAP + pctW;
        contentW = Math.max(contentW, font.width("Done") + GAP + font.width(doneText));
        if (moreText != null) {
            contentW = Math.max(contentW, font.width(moreText));
        }
        width = Math.max(MIN_WIDTH, Math.max(font.width(title), contentW) + 2 * PAD);
        int bodyRows = (emptyText != null ? 1 : shown) + (moreText != null ? 1 : 0);
        int footerY = TITLE_HEIGHT + PAD + bodyRows * ROW_HEIGHT;
        height = footerY + 4 + ROW_HEIGHT - 2 + PAD;

        int scaledW = Math.round(width * scale);
        int scaledH = Math.round(height * scale);
        hudX = clamp(hudX, 2, Math.max(2, graphics.guiWidth() - scaledW - 2));
        hudY = HudBoxes.place("milestones", hudX, clamp(hudY, 2, Math.max(2, graphics.guiHeight() - scaledH - 2)),
                scaledW, scaledH, graphics.guiHeight());

        BazzarTheme theme = BazzarTheme.current();
        graphics.pose().pushMatrix();
        graphics.pose().translate(hudX, hudY);
        graphics.pose().scale(scale, scale);
        graphics.fill(0, 0, width, TITLE_HEIGHT, TITLE_BG);
        graphics.fill(0, TITLE_HEIGHT, width, height, BODY_BG);
        graphics.drawString(font, title, (width - font.width(title)) / 2, 2, theme.light(), true);

        int rowY = TITLE_HEIGHT + PAD;
        if (emptyText != null) {
            graphics.drawString(font, emptyText, PAD, rowY, MUTED_TEXT, true);
            rowY += ROW_HEIGHT;
        } else {
            for (int i = 0; i < shown; i++) {
                MobMilestones.Entry entry = rows.get(i);
                String pct;
                int pctColor;
                if (entry.done()) {
                    pct = "Done";
                    pctColor = DONE_TEXT;
                } else {
                    int percent = (int) (entry.slain * 100L / MobMilestones.TARGET);
                    pct = percent + "%";
                    pctColor = percent >= 90 ? DONE_TEXT : theme.light();
                    String left = shortCount(entry.remaining());
                    graphics.drawString(font, left, width - PAD - pctW - GAP - font.width(left), rowY, MUTED_TEXT, true);
                }
                graphics.drawString(font, pct, width - PAD - font.width(pct), rowY, pctColor, true);
                String name = font.plainSubstrByWidth(entry.name, width - 2 * PAD - pctW - leftW - 2 * GAP);
                graphics.drawString(font, name, PAD, rowY, VALUE_TEXT, true);
                rowY += ROW_HEIGHT;
            }
        }
        if (moreText != null) {
            graphics.drawString(font, moreText, PAD, rowY, MUTED_TEXT, true);
        }

        graphics.fill(PAD, footerY + 1, width - PAD, footerY + 2, DIVIDER);
        graphics.drawString(font, "Done", PAD, footerY + 4, MUTED_TEXT, true);
        graphics.drawString(font, doneText, width - PAD - font.width(doneText), footerY + 4, DONE_TEXT, true);
        graphics.pose().popMatrix();
    }

    private static String shortCount(long value) {
        if (value < 1000L) {
            return Long.toString(value);
        }
        return String.format(Locale.ROOT, "%.1fk", value / 1000.0d).replace(".0k", "k");
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
        int scaledW = Math.round(width * scale);
        int scaledH = Math.round(height * scale);
        return mouseX >= hudX && mouseX <= hudX + scaledW && mouseY >= hudY && mouseY <= hudY + scaledH;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

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

    static final int STYLE_COMPACT = 0;
    static final int STYLE_PANEL = 1;
    private static final int PANEL_WIDTH = 176;
    private static final int PANEL_PAD = 5;
    private static final int PANEL_HEADER_HEIGHT = 16;
    private static final int PANEL_ROW_HEIGHT = 15;
    private static final int PANEL_FOOTER_HEIGHT = 10;
    private static final int PANEL_BG = 0xDB0C0E12;
    private static final int PANEL_EDGE = 0x14FFFFFF;
    private static final int PANEL_TRACK = 0xFF2A3039;

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

    static int getStyle() {
        return BazzarConfig.load().milestoneHudStyle;
    }

    static void setStyle(int style) {
        BazzarConfig.State state = BazzarConfig.load();
        state.milestoneHudStyle = style;
        BazzarConfig.save(state);
    }

    static String styleLabel(int style) {
        return style == STYLE_PANEL ? "Panel" : "Compact";
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
            if (current == null) {
                emptyText = "Hit a mob to track it";
            } else if (current.done()) {
                emptyText = current.name + " is at 25k!";
            } else {
                rows.add(current);
            }
        } else if (mode == MobMilestones.MODE_SELECTED) {
            // finished mobs drop off the list, like completed guild quests
            boolean picked = false;
            if (state.milestoneSelected != null) {
                for (String key : state.milestoneSelected) {
                    MobMilestones.Entry entry = MobMilestones.get(key);
                    if (entry != null) {
                        picked = true;
                        if (!entry.done()) {
                            rows.add(entry);
                        }
                    }
                }
            }
            if (rows.isEmpty()) {
                emptyText = picked ? "Your picked mobs are all at 25k!" : "Pick mobs in Bazzar settings";
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
        if (state.milestoneHudStyle == STYLE_PANEL) {
            renderPanel(graphics, font, mode, rows, shown, hidden, emptyText, doneCount, all.size());
            return;
        }
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

    private static void renderPanel(GuiGraphics graphics, Font font, int mode, List<MobMilestones.Entry> rows,
            int shown, int hidden, String emptyText, int doneCount, int total) {
        int bodyRows = emptyText != null ? 1 : shown;
        width = PANEL_WIDTH;
        height = PANEL_HEADER_HEIGHT + bodyRows * PANEL_ROW_HEIGHT + PANEL_FOOTER_HEIGHT + 2;

        int scaledW = Math.round(width * scale);
        int scaledH = Math.round(height * scale);
        hudX = clamp(hudX, 2, Math.max(2, graphics.guiWidth() - scaledW - 2));
        hudY = HudBoxes.place("milestones", hudX, clamp(hudY, 2, Math.max(2, graphics.guiHeight() - scaledH - 2)),
                scaledW, scaledH, graphics.guiHeight());

        BazzarTheme theme = BazzarTheme.current();
        graphics.pose().pushMatrix();
        graphics.pose().translate(hudX, hudY);
        graphics.pose().scale(scale, scale);
        BazzarUi.fillRoundedRect(graphics, -1, -1, width + 2, height + 2, 7, PANEL_EDGE);
        BazzarUi.fillRoundedRect(graphics, 0, 0, width, height, 6, PANEL_BG);

        drawSmall(graphics, font, "MOB MILESTONES", PANEL_PAD, PANEL_PAD + 1, theme.light());
        String modeText = modeLabel(mode).toUpperCase(Locale.ROOT);
        drawSmall(graphics, font, modeText, width - PANEL_PAD - Math.round(font.width(modeText) * 0.75f), PANEL_PAD + 1, MUTED_TEXT);

        int barW = width - 2 * PANEL_PAD;
        if (emptyText != null) {
            graphics.drawString(font, emptyText, PANEL_PAD, PANEL_HEADER_HEIGHT, MUTED_TEXT, false);
        } else {
            for (int i = 0; i < shown; i++) {
                MobMilestones.Entry entry = rows.get(i);
                int rowY = PANEL_HEADER_HEIGHT + i * PANEL_ROW_HEIGHT;
                boolean done = entry.done();
                String value = done ? "Done" : String.format(Locale.ROOT, "%,d left", entry.remaining());
                int valueX = width - PANEL_PAD - font.width(value);
                graphics.drawString(font, value, valueX, rowY, done ? DONE_TEXT : theme.light(), false);
                String name = font.plainSubstrByWidth(entry.name, valueX - PANEL_PAD - 4);
                graphics.drawString(font, name, PANEL_PAD, rowY, VALUE_TEXT, false);

                float progress = done ? 1.0f : Math.min(1.0f, entry.slain / (float) MobMilestones.TARGET);
                int barY = rowY + 10;
                BazzarUi.fillRoundedRect(graphics, PANEL_PAD, barY, barW, 2, 1, PANEL_TRACK);
                int fillW = Math.round(barW * progress);
                if (fillW > 0) {
                    BazzarUi.fillRoundedRect(graphics, PANEL_PAD, barY, fillW, 2, 1, done ? DONE_TEXT : theme.accent());
                }
            }
        }

        String footer = doneCount + "/" + total + " done";
        if (hidden > 0) {
            footer += "  ·  +" + hidden + " more";
        }
        String eta = etaText(mode, rows, shown, true);
        int room = width - 2 * PANEL_PAD - 6;
        if (eta != null && (font.width(footer) + font.width(eta)) * 0.75f > room) {
            footer = doneCount + "/" + total + " done";
            if ((font.width(footer) + font.width(eta)) * 0.75f > room) {
                eta = etaText(mode, rows, shown, false);
            }
        }
        drawSmall(graphics, font, footer, PANEL_PAD, height - PANEL_FOOTER_HEIGHT, MUTED_TEXT);
        if (eta != null) {
            drawSmall(graphics, font, eta, width - PANEL_PAD - Math.round(font.width(eta) * 0.75f),
                    height - PANEL_FOOTER_HEIGHT, theme.light());
        }
        graphics.pose().popMatrix();
    }

    // time until the mob you're killing hits 25k, from the server's MPS
    private static String etaText(int mode, List<MobMilestones.Entry> rows, int shown, boolean withName) {
        MobMilestones.Entry current = MobMilestones.currentMob();
        double mps = MobMilestones.currentMobMps();
        if (current == null || current.done() || mps <= 0d || !rows.subList(0, shown).contains(current)) {
            return null;
        }
        long seconds = Math.round(current.remaining() / mps);
        String time;
        if (seconds < 60L) {
            time = "<1m";
        } else if (seconds < 3600L) {
            time = "~" + (seconds / 60L) + "m";
        } else {
            time = "~" + (seconds / 3600L) + "h " + (seconds % 3600L / 60L) + "m";
        }
        return (mode == MobMilestones.MODE_CURRENT || !withName ? "" : current.name + " ") + "25k in " + time;
    }

    private static void drawSmall(GuiGraphics graphics, Font font, String text, int x, int y, int color) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(0.75f, 0.75f);
        graphics.drawString(font, text, 0, 0, color, false);
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

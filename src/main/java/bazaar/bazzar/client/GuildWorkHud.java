package bazaar.bazzar.client;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;

final class GuildWorkHud {
    private static final int BASE_WIDTH = 170;
    private static final int HEADER_HEIGHT = 13;
    private static final int ROW_HEIGHT = 11;
    private static final int LABEL_HEIGHT = 10;
    private static final long RATE_WINDOW_MS = 60L * 60_000L;
    private static final long MIN_RATE_SPAN_MS = 10L * 60_000L;
    private static final long ACTIVE_MS = 30_000L;
    private static final Map<String, Tracked> TRACKED = new HashMap<>();

    private static final int PANEL_BG = 0xE00C0E12;
    private static final int PANEL_EDGE = 0x1FFFFFFF;
    private static final int NAME_COLOR = 0xFFF1F3F5;
    private static final int VALUE_COLOR = 0xFF86EFAC;
    private static final int MUTED_TEXT = 0xFFB4BCC8;

    private static boolean visible = false;
    private static float scale = 1.0f;
    private static int hudX = 6;
    private static int hudY = 140;
    private static boolean dragging = false;
    private static boolean dragMoved = false;
    private static int dragOffsetX = 0;
    private static int dragOffsetY = 0;
    private static boolean dirty = false;
    private static int height = HEADER_HEIGHT + ROW_HEIGHT;

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

    static void onWorkers(List<GuildShare.Worker> workers) {
        long now = System.currentTimeMillis();
        for (GuildShare.Worker worker : workers) {
            Tracked tracked = TRACKED.computeIfAbsent(worker.name().toLowerCase(Locale.ROOT), key -> new Tracked());
            long done = Math.round(worker.done());
            long gained = tracked.seen ? (done >= tracked.lastDone ? done - tracked.lastDone : done) : 0L;
            tracked.seen = true;
            tracked.lastDone = done;
            tracked.total += gained;
            tracked.lastSeenMs = now;
            tracked.samples.addLast(new long[]{now, tracked.total});
            while (tracked.samples.size() > 1 && now - tracked.samples.peekFirst()[0] > RATE_WINDOW_MS) {
                tracked.samples.pollFirst();
            }
            if (gained > 0L) {
                tracked.activeAtMs = now;
                String quest = busiestQuest(tracked.lastCounts, worker.counts());
                if (quest != null) {
                    tracked.questKey = quest;
                }
            }
            tracked.lastCounts = worker.counts();
        }
        TRACKED.values().removeIf(tracked -> now - tracked.lastSeenMs > RATE_WINDOW_MS);
    }

    private static String busiestQuest(Map<String, Long> before, Map<String, Long> after) {
        String best = null;
        long bestGain = 0L;
        for (Map.Entry<String, Long> count : after.entrySet()) {
            long gain = count.getValue() - before.getOrDefault(count.getKey(), 0L);
            if (gain > bestGain) {
                bestGain = gain;
                best = count.getKey();
            }
        }
        return best;
    }

    static void render(GuiGraphics graphics, int mouseX, int mouseY) {
        HudBoxes.hide("guildwork");
        Minecraft client = Minecraft.getInstance();
        if (!visible || client == null || client.player == null || client.level == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String note = note();
        GuildShare.Worker top = null;
        double topRate = 0d;
        GuildShare.Worker active = null;
        long activeAt = 0L;
        int activeCount = 0;
        if (note == null) {
            for (GuildShare.Worker worker : GuildShare.workers()) {
                Tracked tracked = TRACKED.get(worker.name().toLowerCase(Locale.ROOT));
                if (tracked == null) {
                    continue;
                }
                double rate = tracked.perHour(now);
                if (rate > topRate) {
                    topRate = rate;
                    top = worker;
                }
                if (now - tracked.activeAtMs <= ACTIVE_MS) {
                    activeCount++;
                    if (tracked.activeAtMs > activeAt) {
                        activeAt = tracked.activeAtMs;
                        active = worker;
                    }
                }
            }
        }
        height = note != null ? HEADER_HEIGHT + ROW_HEIGHT : HEADER_HEIGHT + ROW_HEIGHT + LABEL_HEIGHT + ROW_HEIGHT;
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
        drawScaledText(graphics, font, "GUILD QUESTS · MOST PER HOUR", 0, 2, theme.light(), 0.75f);

        if (note != null) {
            graphics.drawString(font, note, 0, HEADER_HEIGHT, MUTED_TEXT, false);
            graphics.pose().popMatrix();
            return;
        }

        String self = client.player.getName().getString();
        int rowY = HEADER_HEIGHT;
        if (top == null) {
            graphics.drawString(font, "Nobody has done any this hour", 0, rowY, MUTED_TEXT, false);
        } else {
            String value = SbText.formatCompact(topRate) + "/h";
            int valueX = BASE_WIDTH - font.width(value);
            graphics.drawString(font, value, valueX, rowY, VALUE_COLOR, false);
            String name = font.plainSubstrByWidth(top.name(), valueX - 4);
            graphics.drawString(font, name, 0, rowY,
                    top.name().equalsIgnoreCase(self) ? theme.accent() : NAME_COLOR, false);
        }

        drawScaledText(graphics, font, "ON A QUEST NOW", 0, HEADER_HEIGHT + ROW_HEIGHT + 2, theme.light(), 0.75f);
        rowY = HEADER_HEIGHT + ROW_HEIGHT + LABEL_HEIGHT;
        if (active == null) {
            graphics.drawString(font, "Nobody right now", 0, rowY, MUTED_TEXT, false);
        } else {
            Tracked tracked = TRACKED.get(active.name().toLowerCase(Locale.ROOT));
            String quest = tracked == null ? null : questName(tracked.questKey);
            String who = font.plainSubstrByWidth(activeCount > 1 ? active.name() + " +" + (activeCount - 1) : active.name(),
                    BASE_WIDTH);
            int questRoom = BASE_WIDTH - font.width(who) - 6;
            if (quest != null && questRoom > 20) {
                String shown = font.plainSubstrByWidth(quest, questRoom);
                graphics.drawString(font, shown, BASE_WIDTH - font.width(shown), rowY, MUTED_TEXT, false);
            }
            graphics.drawString(font, who, 0, rowY,
                    active.name().equalsIgnoreCase(self) ? theme.accent() : NAME_COLOR, false);
        }
        graphics.pose().popMatrix();
    }

    private static String questName(String poolKey) {
        if (poolKey == null) {
            return null;
        }
        int colon = poolKey.indexOf(':');
        String key = colon >= 0 ? poolKey.substring(0, colon) : poolKey;
        for (GuildQuestScanner.QuestEntry entry : GuildQuestScanner.getLastEntries()) {
            if (entry.name().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "").equals(key)) {
                return entry.name();
            }
        }
        return null;
    }

    private static String note() {
        if (!GuildShare.isActive()) {
            return GuildShare.isOff() ? "Sharing is off, /bazzarguild on" : "Reading your guild from /guild...";
        }
        return null;
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

    private static final class Tracked {
        private final ArrayDeque<long[]> samples = new ArrayDeque<>();
        private Map<String, Long> lastCounts = Map.of();
        private boolean seen = false;
        private long lastDone = 0L;
        private long total = 0L;
        private long lastSeenMs = 0L;
        private long activeAtMs = 0L;
        private String questKey = null;

        private double perHour(long now) {
            if (samples.isEmpty()) {
                return 0d;
            }
            long[] first = samples.peekFirst();
            long gained = total - first[1];
            if (gained <= 0L) {
                return 0d;
            }
            return gained * 3_600_000d / Math.max(MIN_RATE_SPAN_MS, now - first[0]);
        }
    }
}

package bazaar.bazzar.client;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

final class PayoutHistoryScreen extends Screen {
    private static final int PANEL_W = 320;
    private static final int TITLE_H = 28;
    private static final int ROW_H = 13;
    private static final int MUTED = 0xFF8E97A6;
    private static final int GOLD = 0xFFFFD75A;
    private static final int GREEN = 0xFF86EFAC;
    private static final int RED = 0xFFF87171;
    private static final int YELLOW = 0xFFFDE047;
    private static final long QUEUE_STALE_MS = 60_000L;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM");

    private final Screen parent;
    private final long openedAtMs = System.currentTimeMillis();
    private List<PayoutLog.Entry> entries;
    private int left;
    private int top;
    private int panelW;
    private int panelH;
    private int scroll;

    PayoutHistoryScreen(Screen parent) {
        super(Component.literal("Payouts"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        entries = PayoutLog.newestFirst();
        panelW = Math.min(PANEL_W, width - 8);
        panelH = Math.min(260, height - 8);
        left = (width - panelW) / 2;
        top = (height - panelH) / 2;
    }

    private int listTop() {
        return top + TITLE_H + 38;
    }

    private int visibleRows() {
        return Math.max(1, (top + panelH - 26 - listTop()) / ROW_H);
    }

    private ButtonBounds clearButton() {
        return new ButtonBounds(left + panelW - 58, top + panelH - 20, 50, 14);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        BazzarTheme theme = BazzarTheme.current();
        float open = BazzarUi.screenAnim(openedAtMs, -1L);
        graphics.fill(0, 0, width, height, (int) (0xAA * open) << 24);
        BazzarUi.drawRoundedGlowBorder(graphics, left, top, panelW, panelH, 8);

        Component title = BazzarUi.gradientText("Payouts", theme.light() & 0xFFFFFF, theme.rgb, true);
        graphics.drawCenteredString(font, title, left + panelW / 2, top + 10, 0xFFFFFFFF);
        graphics.fill(left + 8, top + TITLE_H - 3, left + panelW - 8, top + TITLE_H - 2, theme.accentDim());

        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        double cobbleToday = 0.0d;
        double shardToday = 0.0d;
        double gemToday = 0.0d;
        int countToday = 0;
        double altShards = 0.0d;
        double altGems = 0.0d;
        double altCobble = 0.0d;
        int unpaidCount = 0;
        double unpaidTotal = 0.0d;
        long nowMs = System.currentTimeMillis();
        for (PayoutLog.Entry entry : entries) {
            if (!entry.alt && isUnpaid(entry, nowMs)) {
                unpaidCount++;
                unpaidTotal += entry.paid;
            }
            if (!Instant.ofEpochMilli(entry.timeMs).atZone(zone).toLocalDate().equals(today)) {
                continue;
            }
            if (entry.alt) {
                switch (entry.kind) {
                    case "shard" -> altShards += entry.received;
                    case "gem" -> altGems += entry.received;
                    default -> altCobble += entry.received;
                }
                continue;
            }
            if (isUnpaid(entry, nowMs)) {
                continue;
            }
            countToday++;
            switch (entry.kind) {
                case "shard" -> shardToday += entry.paid;
                case "gem" -> gemToday += entry.paid;
                default -> cobbleToday += entry.paid;
            }
        }
        String summary = "Today: " + countToday + " payouts, " + SbText.formatCompact(cobbleToday) + " for cobble, "
                + SbText.formatCompact(shardToday) + " for shards, " + SbText.formatCompact(gemToday) + " for gems";
        graphics.drawString(font, font.plainSubstrByWidth(summary, panelW - 20), left + 10, top + TITLE_H + 2, MUTED, false);
        String altSummary = "From alts today: " + SbText.formatCompact(altShards) + " shards, "
                + SbText.formatCompact(altGems) + " gems, " + SbText.formatCompact(altCobble) + " cobble";
        graphics.drawString(font, font.plainSubstrByWidth(altSummary, panelW - 20), left + 10, top + TITLE_H + 12, MUTED, false);
        if (unpaidCount > 0) {
            String unpaidSummary = unpaidCount + (unpaidCount == 1 ? " player" : " players") + " still need paying ("
                    + SbText.formatCompact(unpaidTotal) + " total)";
            graphics.drawString(font, font.plainSubstrByWidth(unpaidSummary, panelW - 20), left + 10, top + TITLE_H + 22, RED, false);
        }

        if (entries.isEmpty()) {
            graphics.drawCenteredString(font, "No payouts yet.", left + panelW / 2, listTop() + 20, MUTED);
        }
        int rows = visibleRows();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, entries.size() - rows)));
        for (int i = 0; i < rows && scroll + i < entries.size(); i++) {
            PayoutLog.Entry entry = entries.get(scroll + i);
            int y = listTop() + i * ROW_H;
            ZonedDateTime when = Instant.ofEpochMilli(entry.timeMs).atZone(zone);
            String time = when.toLocalDate().equals(today) ? when.format(TIME) : when.format(DAY);
            String paid;
            int paidColor;
            if (entry.alt) {
                paid = "from alt";
                paidColor = GREEN;
            } else if (isUnpaid(entry, nowMs)) {
                paid = "UNPAID " + SbText.formatCompact(entry.paid);
                paidColor = RED;
            } else if ("queued".equals(entry.status)) {
                paid = "sending " + SbText.formatCompact(entry.paid);
                paidColor = MUTED;
            } else if ("unconfirmed".equals(entry.status)) {
                paid = SbText.formatCompact(entry.paid) + "?";
                paidColor = YELLOW;
            } else {
                paid = SbText.formatCompact(entry.paid);
                paidColor = GOLD;
            }
            int paidX = left + panelW - 10 - font.width(paid);
            graphics.drawString(font, time, left + 10, y, MUTED, false);
            String unit = entry.received == 1.0d ? entry.kind : entry.kind + "s";
            String detail = entry.alt
                    ? entry.sender + "  +" + SbText.formatCompact(entry.received) + " " + unit
                    : entry.sender + "  " + SbText.formatCompact(entry.received) + " " + entry.kind;
            if (isUnpaid(entry, nowMs)) {
                detail += "  " + (entry.note != null ? entry.note : "not sent");
            }
            graphics.drawString(font, font.plainSubstrByWidth(detail, paidX - left - 52), left + 46, y,
                    isUnpaid(entry, nowMs) ? RED : 0xFFFFFFFF, false);
            graphics.drawString(font, paid, paidX, y, paidColor, false);
        }

        ButtonBounds clear = clearButton();
        boolean hovered = isInside(mouseX, mouseY, clear);
        BazzarUi.fillPill(graphics, clear.x(), clear.y(), clear.w(), clear.h(), hovered ? theme.soft(0x66) : theme.soft(0x33));
        graphics.drawCenteredString(font, "Clear", clear.x() + clear.w() / 2, clear.y() + 3, 0xFFFFFFFF);
        if (entries.size() > rows) {
            String range = (scroll + 1) + " to " + Math.min(entries.size(), scroll + rows) + " of " + entries.size();
            graphics.drawString(font, range, left + 10, clear.y() + 3, MUTED, false);
        }
        super.render(graphics, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
        if (event.button() == 0 && isInside((int) event.x(), (int) event.y(), clearButton()) && !entries.isEmpty()) {
            PayoutLog.clear();
            entries = PayoutLog.newestFirst();
            scroll = 0;
            return true;
        }
        return super.mouseClicked(event, bl);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, scroll - (int) Math.signum(scrollY) * 3);
        return true;
    }

    @Override
    public void onClose() {
        McCompat.setScreen(Minecraft.getInstance(), parent);
    }

    private static boolean isUnpaid(PayoutLog.Entry entry, long nowMs) {
        if ("unpaid".equals(entry.status)) {
            return true;
        }
        return "queued".equals(entry.status) && nowMs - entry.timeMs > QUEUE_STALE_MS;
    }

    private boolean isInside(int mouseX, int mouseY, ButtonBounds bounds) {
        return mouseX >= bounds.x() && mouseX < bounds.x() + bounds.w() && mouseY >= bounds.y() && mouseY < bounds.y() + bounds.h();
    }

    private record ButtonBounds(int x, int y, int w, int h) {}
}

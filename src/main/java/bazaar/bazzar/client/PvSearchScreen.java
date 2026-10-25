package bazaar.bazzar.client;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

final class PvSearchScreen extends Screen {
    private static final int PANEL_W = 340;
    private static final int ROW_H = 14;
    private static final int LIST_TOP_OFFSET = 56;
    private static final int PANEL_BG_TOP = 0xCC000000;
    private static final int PANEL_BG_BOTTOM = 0x88000000;
    private static final int MUTED_TEXT = 0xCC8A97A6;
    private static final int ROW_HOVER_BG = 0x22FFFFFF;

    private final long openedAtMs = System.currentTimeMillis();
    private long closingSinceMs = -1L;
    private EditBox searchBox;
    private List<PvIndex.SearchHit> hits = List.of();
    private int scroll;
    private int left;
    private int top;
    private int panelH;

    PvSearchScreen() {
        super(Component.literal("PV Search"));
    }

    @Override
    protected void init() {
        panelH = Math.min(260, Math.max(160, height - 8));
        left = Math.max(4, (width - PANEL_W) / 2);
        top = Math.max(4, (height - panelH) / 2);
        String previous = searchBox != null ? searchBox.getValue() : "";
        clearWidgets();
        searchBox = new EditBox(font, left + 14, top + 30, PANEL_W - 28, 16, Component.literal("Search"));
        searchBox.setMaxLength(60);
        searchBox.setValue(previous);
        searchBox.setHint(Component.literal("search items...").withStyle(ChatFormatting.DARK_GRAY));
        searchBox.setResponder(value -> refresh());
        addRenderableWidget(searchBox);
        setInitialFocus(searchBox);
        refresh();
    }

    private String account() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) {
            return "";
        }
        return ShardPayController.normalizeName(client.player.getName().getString());
    }

    private void refresh() {
        hits = PvIndex.search(account(), searchBox == null ? "" : searchBox.getValue());
        scroll = 0;
    }

    private int visibleRows() {
        return Math.max(1, (panelH - LIST_TOP_OFFSET - 26) / ROW_H);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        float open = BazzarUi.screenAnim(openedAtMs, closingSinceMs);
        graphics.fill(0, 0, width, height, (int) (0xAA * open) << 24);

        BazzarUi.fillRoundedGradient(graphics, left, top, PANEL_W, panelH, 8, PANEL_BG_TOP, PANEL_BG_BOTTOM);
        BazzarUi.drawRoundedGlowBorder(graphics, left, top, PANEL_W, panelH, 8);

        BazzarTheme theme = BazzarTheme.current();
        graphics.drawString(font, "PV Search", left + 14, top + 12, theme.accent(), false);
        String pageInfo = PvIndex.pageCount(account()) + " pages indexed";
        graphics.drawString(font, pageInfo, left + PANEL_W - 14 - font.width(pageInfo), top + 12, MUTED_TEXT, false);

        int listTop = top + LIST_TOP_OFFSET;
        int rows = visibleRows();
        if (hits.isEmpty()) {
            String empty = PvIndex.pageCount(account()) == 0
                    ? "Open your /pv pages once to index them."
                    : "No matches.";
            graphics.drawString(font, empty, left + 14, listTop + 4, MUTED_TEXT, false);
        } else {
            int end = Math.min(hits.size(), scroll + rows);
            for (int i = scroll; i < end; i++) {
                PvIndex.SearchHit hit = hits.get(i);
                int rowY = listTop + (i - scroll) * ROW_H;
                boolean hovered = mouseX >= left + 8 && mouseX <= left + PANEL_W - 8
                        && mouseY >= rowY - 2 && mouseY < rowY + ROW_H - 2;
                if (hovered) {
                    graphics.fill(left + 8, rowY - 2, left + PANEL_W - 8, rowY + ROW_H - 2, ROW_HOVER_BG);
                }
                String pageLabel = "/pv " + hit.pageKey();
                graphics.drawString(font, pageLabel, left + 14, rowY, theme.accent(), false);
                String countLabel = "x" + SbText.formatCompact(hit.count());
                int countW = font.width(countLabel);
                graphics.drawString(font, countLabel, left + PANEL_W - 14 - countW, rowY, MUTED_TEXT, false);
                int nameX = left + 14 + font.width("/pv 88 ");
                String name = font.plainSubstrByWidth(hit.name(), PANEL_W - 28 - font.width("/pv 88 ") - countW - 6);
                graphics.drawString(font, name, nameX, rowY, 0xFFEFEFEF, false);
            }
            if (hits.size() > rows) {
                String more = (scroll + 1) + " to " + end + " of " + hits.size();
                graphics.drawString(font, more, left + PANEL_W - 14 - font.width(more), top + panelH - 16, MUTED_TEXT, false);
            }
        }
        graphics.drawString(font, "Click a row to open that /pv", left + 14, top + panelH - 16, MUTED_TEXT, false);
        super.render(graphics, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        int listTop = top + LIST_TOP_OFFSET;
        int rows = visibleRows();
        if (mouseX >= left + 8 && mouseX <= left + PANEL_W - 8 && mouseY >= listTop - 2) {
            int index = scroll + (mouseY - listTop + 2) / ROW_H;
            if (index >= scroll && index < Math.min(hits.size(), scroll + rows)) {
                PvIndex.SearchHit hit = hits.get(index);
                if (hit.pageKey().chars().allMatch(Character::isDigit)) {
                    Minecraft client = Minecraft.getInstance();
                    PvScanner.setHighlight(hit.name());
                    CommandSender.send(client, "pv " + hit.pageKey());
                    client.setScreen(null);
                    return true;
                }
            }
        }
        return super.mouseClicked(event, bl);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxScroll = Math.max(0, hits.size() - visibleRows());
        scroll = Math.max(0, Math.min(scroll - (int) Math.signum(scrollY) * 3, maxScroll));
        return true;
    }

    @Override
    public void onClose() {
        if (closingSinceMs < 0L) {
            closingSinceMs = System.currentTimeMillis();
        }
        super.onClose();
    }
}

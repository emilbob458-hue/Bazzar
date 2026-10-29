package bazaar.bazzar.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

// the Celestial tab of the Bazzar menu: a seller table, your own listing, and everyone's boost timers
final class CelestialPanel {
    private static final long REFRESH_MS = 10_000L;
    private static final long BOOST_SYNC_MS = 60_000L;
    private static final int ROW_H = 13;
    private static final int BOOST_ROW_H = 12;
    private static final int SALE_ROW_H = 11;

    private static final int TEXT = 0xFFF1F3F5;
    private static final int MUTED = 0xFF8E97A6;
    private static final int GREY = 0xFF5F6773;
    private static final int DIM = 0xFF3A414B;
    private static final int HDR = 0xFF8B95A3;
    private static final int TAB_INACTIVE = 0x998A97A6;
    private static final int GREEN = 0xFF86EFAC;
    private static final int RED = 0xFFF87171;
    private static final int RED_DIM = 0xB3F87171;
    private static final int GOLD = 0xFFFFD75A;
    private static final int AMBER = 0xFFF5B54A;
    private static final int AMBER_DIM = 0xFFE0A458;
    private static final int LIME = 0xFFBEF264;
    private static final int OFF_DOT = 0xFF4B5563;
    private static final int ROW = 0x1AFFFFFF;
    private static final int ROW_ALT = 0x0DFFFFFF;
    private static final int SEP = 0x22FFFFFF;
    private static final int PILL_BG = 0xFF14171C;
    private static final int FIELD = 0xFF0B0D11;
    private static final int FIELD_EDGE = 0xFF2A303A;

    private static final String WARNING = "Clicking a price, or pressing the buy key while the market HUD is on, "
            + "immediately pays that seller the amount shown. The seller's mod then uses their Celestial Boost "
            + "for you automatically.";

    enum View {
        MARKET("Market"),
        MY_LISTING("My Listing"),
        BOOSTS("Boosts");

        final String label;

        View(String label) {
            this.label = label;
        }
    }

    View view = View.MARKET;
    private final Font font = Minecraft.getInstance().font;
    private final EditBox[] priceBoxes = new EditBox[CelestialMarket.CURRENCIES.length];
    private int cx;
    private int cy;
    private int w;
    private int h;
    private int panelLeft;
    private int panelTop;
    private int panelW;
    private int panelH;
    private int scroll;
    private int salesScroll;
    private int readyScroll;
    private int coolingScroll;
    private long lastRefreshMs;
    private long lastBoostSyncMs;
    private boolean warning;
    private boolean warningDone;
    private boolean dontShowAgain;
    private String pickerOwner;
    private List<String> tooltip;

    void init(Consumer<EditBox> addWidget) {
        Map<String, Double> prices = BazzarConfig.load().cbPrices;
        for (int i = 0; i < priceBoxes.length; i++) {
            String currency = CelestialMarket.CURRENCIES[i];
            EditBox box = new EditBox(font, 0, 0, 80, 9, Component.literal(currency));
            box.setBordered(false);
            box.setMaxLength(16);
            Double price = prices == null ? null : prices.get(currency);
            box.setValue(price != null && price > 0d ? SbText.formatCompact(price) : "");
            box.setHint(Component.literal("not accepted").withStyle(ChatFormatting.DARK_GRAY));
            box.visible = false;
            box.active = false;
            priceBoxes[i] = box;
            addWidget.accept(box);
        }
    }

    // called when the tab is shown or the view changes, so the pay warning comes up before anything can be bought
    void armWarning() {
        warning = view != View.BOOSTS && !warningDone && !BazzarConfig.load().cbWarningHidden;
    }

    void show(View which) {
        view = which;
        pickerOwner = null;
        scroll = 0;
        salesScroll = 0;
        readyScroll = 0;
        coolingScroll = 0;
        armWarning();
        refresh();
    }

    boolean modal() {
        return warning || pickerOwner != null;
    }

    void setVisible(boolean shown) {
        boolean show = shown && view == View.MY_LISTING && !warning;
        for (EditBox box : priceBoxes) {
            if (box != null) {
                box.visible = show;
                box.active = show;
            }
        }
    }

    void refresh() {
        lastRefreshMs = System.currentTimeMillis();
        CelestialMarket.refresh(Minecraft.getInstance());
        if (view == View.BOOSTS && lastRefreshMs - lastBoostSyncMs >= BOOST_SYNC_MS) {
            lastBoostSyncMs = lastRefreshMs;
            CelestialTracker.sync(null);
        }
    }

    void tick() {
        if (System.currentTimeMillis() - lastRefreshMs >= REFRESH_MS) {
            refresh();
        }
    }

    String headerInfo() {
        Minecraft client = Minecraft.getInstance();
        return switch (view) {
            case MARKET -> {
                if (!CelestialMarket.loaded()) {
                    yield "";
                }
                int ready = 0;
                for (CelestialMarket.Listing listing : CelestialMarket.listings()) {
                    if (listing.ready()) {
                        ready++;
                    }
                }
                yield ready + " ready of " + CelestialMarket.listings().size();
            }
            case MY_LISTING -> CelestialMarket.sales().isEmpty() ? "" : CelestialMarket.sales().size() + " sold";
            case BOOSTS -> {
                long now = System.currentTimeMillis();
                int ready = 0;
                for (CelestialTracker.Entry entry : CelestialTracker.entries(client)) {
                    if (entry.ready(now) && entry.online()) {
                        ready++;
                    }
                }
                yield ready + " ready now";
            }
        };
    }

    // ---- drawing ----

    void render(GuiGraphics graphics, int cx, int cy, int w, int h, int left, int top, int panelW, int panelH,
            int mouseX, int mouseY) {
        this.cx = cx;
        this.cy = cy;
        this.w = w;
        this.h = h;
        this.panelLeft = left;
        this.panelTop = top;
        this.panelW = panelW;
        this.panelH = panelH;
        tooltip = null;
        if (pickerOwner != null && pickerListing() == null) {
            pickerOwner = null;
        }
        if (modal()) {
            mouseX = -1;
            mouseY = -1;
        }
        drawSegments(graphics, mouseX, mouseY);
        switch (view) {
            case MARKET -> drawMarket(graphics, mouseX, mouseY);
            case MY_LISTING -> drawMyListing(graphics, mouseX, mouseY);
            case BOOSTS -> drawBoosts(graphics, mouseX, mouseY);
        }
        drawMessage(graphics, mouseX, mouseY);
    }

    // drawn after the menu's widgets, so the warning and tooltips sit on top of the price boxes
    void renderOverlay(GuiGraphics graphics, int mouseX, int mouseY) {
        if (warning) {
            drawWarning(graphics, mouseX, mouseY);
            return;
        }
        if (pickerOwner != null) {
            tooltip = null;
            drawPicker(graphics, mouseX, mouseY);
        }
        if (tooltip != null && !tooltip.isEmpty()) {
            drawTooltip(graphics, tooltip, mouseX, mouseY);
        }
    }

    private int segW(View which) {
        return Math.max(font.width(which.label) + 16, 48);
    }

    private int trackW() {
        int total = 4;
        for (View which : View.values()) {
            total += segW(which);
        }
        return total;
    }

    private Bounds segment(View which) {
        int x = cx + 2;
        for (View other : View.values()) {
            if (other == which) {
                break;
            }
            x += segW(other);
        }
        return new Bounds(x, cy + 2, segW(which), 12);
    }

    private void drawSegments(GuiGraphics graphics, int mouseX, int mouseY) {
        BazzarTheme theme = BazzarTheme.current();
        BazzarUi.fillPill(graphics, cx - 1, cy - 1, trackW() + 2, 18, 0x2EFFFFFF);
        BazzarUi.fillPill(graphics, cx, cy, trackW(), 16, PILL_BG);
        for (View which : View.values()) {
            Bounds seg = segment(which);
            boolean selected = which == view;
            boolean hovered = seg.contains(mouseX, mouseY);
            int color = TAB_INACTIVE;
            if (selected) {
                BazzarUi.fillPill(graphics, seg.x(), seg.y(), seg.w(), seg.h(), theme.accent());
                color = theme.readableOnAccent();
            } else if (hovered) {
                BazzarUi.fillPill(graphics, seg.x(), seg.y(), seg.w(), seg.h(), theme.buttonBgHover());
                color = 0xFFFFFFFF;
            }
            graphics.drawString(font, which.label, seg.x() + (seg.w() - font.width(which.label)) / 2, seg.y() + 2, color, false);
        }
    }

    // ---- market ----

    private int priceColW() {
        return w >= 370 ? 48 : w >= 300 ? 40 : 36;
    }

    private int priceStart() {
        return w - 4 * priceColW();
    }

    private int colL(int index) {
        return priceStart() + index * priceColW();
    }

    private int colTextR(int index) {
        return colL(index) + priceColW() - 5;
    }

    private int statusRight() {
        return priceStart() - 6;
    }

    private int nameMax() {
        return priceStart() - 44 - 18;
    }

    private int marketListTop() {
        return 49;
    }

    private int poolY() {
        return h - messageSpace() - 16;
    }

    private int marketRows() {
        return Math.max(1, (poolY() - 4 - marketListTop()) / ROW_H);
    }

    private Bounds cooldownToggle() {
        return new Bounds(cx + w - 25, cy + 2, 24, 12);
    }

    private Bounds cooldownLabel() {
        String label = cooldownText();
        return new Bounds(cx + w - 31 - font.width(label), cy + 2, font.width(label), 12);
    }

    private String cooldownText() {
        long left = CelestialMarket.cooldownLeftMs();
        return left > 0L ? "Wait " + ((left + 999L) / 1000L) + "s" : "Buy delay";
    }

    private Bounds headerCell(int index) {
        return new Bounds(cx + colL(index), cy + 22, priceColW(), 11);
    }

    private Bounds priceCell(int row, int index) {
        int y = cy + marketListTop() + row * ROW_H;
        return new Bounds(cx + colL(index) + 2, y + 1, priceColW() - 3, 11);
    }

    private Bounds requestButton() {
        return new Bounds(cx + w - 60, cy + poolY(), 60, 16);
    }

    private int quickIndex() {
        String quick = BazzarConfig.load().cbQuickCurrency;
        for (int i = 0; i < CelestialMarket.CURRENCIES.length; i++) {
            if (CelestialMarket.CURRENCIES[i].equals(quick)) {
                return i;
            }
        }
        return 0;
    }

    private boolean buyable(Minecraft client, CelestialMarket.Listing listing, String currency, double price) {
        return listing.ready() && !CelestialMarket.isOwnListing(client, listing) && !CelestialMarket.waitingForPayment()
                && !CelestialMarket.coolingDown() && CelestialMarket.canAfford(client, currency, price);
    }

    private void drawMarket(GuiGraphics graphics, int mouseX, int mouseY) {
        Minecraft client = Minecraft.getInstance();
        BazzarTheme theme = BazzarTheme.current();
        BazzarConfig.State state = BazzarConfig.load();
        List<CelestialMarket.Listing> listings = CelestialMarket.listings();
        int rows = marketRows();
        scroll = Math.max(0, Math.min(scroll, listings.size() - rows));
        int shown = Math.min(rows, listings.size() - scroll);
        int pc = priceColW();
        int quick = quickIndex();
        int listTop = marketListTop();

        // buy delay toggle in the toolbar
        boolean cooling = CelestialMarket.cooldownLeftMs() > 0L;
        Bounds toggle = cooldownToggle();
        Bounds label = cooldownLabel();
        boolean toggleHover = toggle.contains(mouseX, mouseY) || label.contains(mouseX, mouseY);
        graphics.drawString(font, cooldownText(), label.x(), cy + 4, cooling ? AMBER : MUTED, false);
        drawToggle(graphics, toggle, state.cbBuyCooldown, toggleHover);
        if (toggleHover) {
            tooltip = List.of("Wait 5s between buys so a", "double click can't pay twice.");
        }

        // quick-buy column tint, under everything else
        int tintBottom = cy + listTop + Math.max(0, shown) * ROW_H - 1;
        graphics.fill(cx + colL(quick), cy + 22, cx + colL(quick) + pc - 1, Math.max(cy + 46, tintBottom), theme.soft(0x12));

        // column headers
        drawSmall(graphics, "SELLER", cx + 6, cy + 24, HDR);
        drawSmall(graphics, "STATUS", cx + statusRight() - smallWidth("STATUS"), cy + 24, HDR);
        for (int i = 0; i < CelestialMarket.CURRENCIES.length; i++) {
            String currency = CelestialMarket.CURRENCIES[i];
            Bounds cell = headerCell(i);
            boolean hovered = cell.contains(mouseX, mouseY);
            if (hovered) {
                BazzarUi.fillRoundedRect(graphics, cell.x() + 1, cell.y(), cell.w() - 2, 10, 3, theme.buttonBgHover());
                tooltip = List.of("Quick-buy currency for the HUD", "and buy key. Click to use " + currency + ".");
            }
            String text = currency.toUpperCase(Locale.ROOT);
            int labelX = cx + colTextR(i) - smallWidth(text);
            drawSmall(graphics, text, labelX, cy + 24, i == quick ? theme.light() : HDR);
            CurrencyIcons.draw(graphics, font, currency, labelX - 8, cy + 23, 0.75f);
        }
        graphics.fill(cx, cy + 32, cx + w, cy + 33, theme.accentDim());
        graphics.fill(cx + colL(quick) + 3, cy + 31, cx + colL(quick) + pc - 3, cy + 33, theme.accent());

        // wallet row lined up with the price columns
        graphics.fill(cx, cy + 34, cx + w, cy + 46, theme.soft(0x16));
        drawSmall(graphics, "YOU HAVE", cx + 6, cy + 37, MUTED);
        for (int i = 0; i < CelestialMarket.CURRENCIES.length; i++) {
            double amount = CelestialMarket.balance(client, CelestialMarket.CURRENCIES[i]);
            String text = amount < 0d ? "?" : fitPrice(amount, pc - 6);
            int x = cx + colTextR(i) - font.width(text);
            graphics.drawString(font, text, x, cy + 36, amount < 0d ? GREY : 0xFFE6E9EE, false);
            if (amount < 0d && inside(mouseX, mouseY, cx + colL(i), cy + 34, pc, 12)) {
                tooltip = List.of("Not shown on your sidebar");
            }
        }
        graphics.fill(cx, cy + 47, cx + w, cy + 48, SEP);

        if (!CelestialMarket.loaded()) {
            graphics.drawCenteredString(font, "Loading...", cx + w / 2, cy + listTop + 20, MUTED);
        } else if (listings.isEmpty()) {
            graphics.drawCenteredString(font, fitWords("Nobody is selling a Celestial Boost yet.", w - 8), cx + w / 2,
                    cy + listTop + 20, MUTED);
        }

        CelestialMarket.Listing cheapest = CelestialMarket.cheapest(client, CelestialMarket.CURRENCIES[quick]);
        for (int i = 0; i < shown; i++) {
            CelestialMarket.Listing listing = listings.get(scroll + i);
            int y = cy + listTop + i * ROW_H;
            boolean own = CelestialMarket.isOwnListing(client, listing);
            boolean rowHover = inside(mouseX, mouseY, cx, y, w, 12);
            graphics.fill(cx, y, cx + w, y + 12, rowHover ? theme.soft(0x1C) : (i % 2 == 0 ? ROW : ROW_ALT));
            int stripe = own ? theme.accent() : listing.ready() ? GREEN : listing.online() ? AMBER : OFF_DOT;
            graphics.fill(cx, y, cx + 2, y + 12, stripe);

            String name = fitName(listing.owner(), nameMax());
            int nameColor = own ? theme.light() : (!listing.online() && !listing.ready()) ? GREY : TEXT;
            graphics.drawString(font, name, cx + 6, y + 3, nameColor, false);
            int ax = cx + 6 + font.width(name) + 4;
            int nameEnd = cx + 6 + nameMax();
            boolean viaHidden = false;
            boolean viaShown = !listing.backend().equalsIgnoreCase(listing.owner());
            if (own && ax + 20 <= nameEnd) {
                BazzarUi.fillPill(graphics, ax, y + 2, 20, 8, theme.soft(0x55));
                drawSmall(graphics, "YOU", ax + 4, y + 3, theme.light());
            } else if (viaShown && ax + font.width("via " + listing.backend()) <= nameEnd) {
                graphics.drawString(font, "via " + listing.backend(), ax, y + 3, GREY, false);
            } else if (viaShown) {
                graphics.drawString(font, "*", ax - 2, y + 3, GREY, false);
                viaHidden = true;
            }

            String status;
            int statusColor;
            if (listing.ready()) {
                status = listing.stock() >= 10 ? String.valueOf(listing.stock()) : listing.stock() + " ready";
                statusColor = GREEN;
            } else if (!listing.online()) {
                status = "offline";
                statusColor = GREY;
            } else {
                long left = listing.readyAtLocal() - System.currentTimeMillis();
                status = countdown(listing.readyAtLocal());
                statusColor = left < 15L * 60_000L ? LIME : AMBER;
            }
            graphics.drawString(font, status, cx + statusRight() - font.width(status), y + 3, statusColor, false);

            if (rowHover && listing.ready() && !own) {
                tooltip = List.of("Click to buy from " + listing.owner(), "and pick how you want to pay");
            }
            if (inside(mouseX, mouseY, cx, y, priceStart(), 12)) {
                List<String> lines = new ArrayList<>();
                lines.add(listing.owner());
                lines.add(listing.ready() ? listing.stock() + " ready to boost you"
                        : !listing.online() ? "Offline" : "Ready in " + countdown(listing.readyAtLocal()));
                if (viaShown || viaHidden) {
                    lines.add("Delivered by " + listing.backend());
                }
                List<String> accepts = new ArrayList<>();
                for (String currency : CelestialMarket.CURRENCIES) {
                    if (listing.price(currency) != null) {
                        accepts.add(currency);
                    }
                }
                if (accepts.size() < CelestialMarket.CURRENCIES.length) {
                    lines.add("Accepts " + (accepts.isEmpty() ? "nothing" : String.join(", ", accepts)));
                }
                tooltip = lines;
            }

            for (int c = 0; c < CelestialMarket.CURRENCIES.length; c++) {
                String currency = CelestialMarket.CURRENCIES[c];
                Double price = listing.price(currency);
                Bounds cell = priceCell(i, c);
                int textR = cx + colTextR(c);
                if (price == null) {
                    graphics.drawString(font, "-", textR - font.width("-"), y + 3, DIM, false);
                    continue;
                }
                String text = fitPrice(price, pc - 6);
                boolean hovered = cell.contains(mouseX, mouseY);
                int color;
                if (!listing.ready()) {
                    color = GREY;
                } else if (own) {
                    color = (0xAA << 24) | (theme.light() & 0xFFFFFF);
                } else if (!CelestialMarket.canAfford(client, currency, price)) {
                    color = RED_DIM;
                    if (hovered) {
                        double have = CelestialMarket.balance(client, currency);
                        tooltip = List.of("Need " + SbText.formatCompact(price) + " " + currency + ",",
                                "you have " + (have < 0d ? "?" : SbText.formatCompact(have)));
                    }
                } else if (CelestialMarket.waitingForPayment() || CelestialMarket.coolingDown()) {
                    color = MUTED;
                    if (hovered) {
                        tooltip = List.of(CelestialMarket.waitingForPayment() ? "Waiting for your last buy" : "Wait for the buy delay");
                    }
                } else {
                    color = GREEN;
                }
                boolean isCheapest = c == quick && listing == cheapest;
                if (isCheapest) {
                    BazzarUi.fillRoundedRect(graphics, cell.x(), cell.y(), cell.w(), cell.h(), 3, 0x2EFFD75A);
                    graphics.fill(cell.x() + 2, y + 11, cell.x() + cell.w() - 2, y + 12, 0x99FFD75A);
                    color = GOLD;
                }
                if (hovered && isCheapest) {
                    tooltip = List.of("Cheapest in " + currency + ",", "the HUD and buy key use this one");
                }
                graphics.drawString(font, text, textR - font.width(text), y + 3, color, false);
            }
        }
        drawScrollbar(graphics, cy + listTop, rows * ROW_H - 1, scroll, rows, listings.size());

        // free pool bar
        int pool = CelestialMarket.poolReady();
        int py = cy + poolY();
        graphics.fill(cx, py - 2, cx + w, py - 1, SEP);
        BazzarUi.fillCircleSmooth(graphics, cx + 5, py + 8, 2.5f, pool > 0 ? GREEN : OFF_DOT);
        graphics.drawString(font, "Free pool", cx + 12, py + 4, MUTED, false);
        String poolValue = pool > 0 ? pool + " ready" : "empty";
        int valueX = cx + 12 + font.width("Free pool ") + 2;
        graphics.drawString(font, poolValue, valueX, py + 4, pool > 0 ? GREEN : GREY, false);
        Bounds request = requestButton();
        drawPill(graphics, request, "Request", mouseX, mouseY, false, pool > 0);
        if (request.contains(mouseX, mouseY)) {
            tooltip = List.of("Ask the free pool for a boost", "(same as /requestcelestial)");
        }
        if (listings.size() > rows) {
            String range = (scroll + 1) + "-" + Math.min(listings.size(), scroll + rows) + " of " + listings.size();
            int rangeX = request.x() - 8 - font.width(range);
            if (rangeX > valueX + font.width(poolValue) + 10) {
                graphics.drawString(font, range, rangeX, py + 4, GREY, false);
            }
        }
    }

    // ---- my listing ----

    private static final int ACTION_W = 96;
    private static final int CURRENCY_COL_W = 58;

    private int gridRight() {
        return w - ACTION_W - 8;
    }

    private int fieldW() {
        return Math.min(90, gridRight() - CURRENCY_COL_W - 60);
    }

    private int cardH() {
        return 26 + (statusLine2().size() - 1) * 10;
    }

    private int gridTop() {
        return 22 + cardH() + 6;
    }

    private int priceRowY(int index) {
        return gridTop() + 13 + index * 18;
    }

    private Bounds actionButton(int index) {
        return new Bounds(cx + w - ACTION_W, cy + priceRowY(index), ACTION_W, 16);
    }

    private Bounds poolToggle() {
        Bounds row = actionButton(2);
        return new Bounds(cx + w - 28, row.y() + 2, 24, 12);
    }

    private int salesTop() {
        return priceRowY(3) + 16 + 6;
    }

    private int salesRows() {
        return Math.max(1, (h - messageSpace() - (salesTop() + 13)) / SALE_ROW_H);
    }

    private CelestialMarket.Listing ownListing() {
        Minecraft client = Minecraft.getInstance();
        CelestialMarket.Listing mine = null;
        for (CelestialMarket.Listing listing : CelestialMarket.listings()) {
            if (CelestialMarket.isOwnListing(client, listing)) {
                mine = listing;
            }
        }
        return mine;
    }

    private List<String> statusLine2() {
        Minecraft client = Minecraft.getInstance();
        String group = CelestialMarket.groupName(client);
        String self = CelestialMarket.selfName(client);
        boolean alt = group != null && self != null && !group.equalsIgnoreCase(self);
        CelestialMarket.Listing mine = ownListing();
        String text;
        if (alt) {
            text = "Alt of " + group + ", its prices apply";
        } else if (BazzarConfig.load().cbSellEnabled && mine != null) {
            text = "Listed as " + mine.owner();
        } else if (BazzarConfig.load().cbSellEnabled) {
            text = "Your listing goes up in a moment.";
        } else {
            text = "Fill in prices, then press List it.";
        }
        return wrap(text, Math.max(40, w - 16));
    }

    private void drawMyListing(GuiGraphics graphics, int mouseX, int mouseY) {
        Minecraft client = Minecraft.getInstance();
        BazzarTheme theme = BazzarTheme.current();
        BazzarConfig.State state = BazzarConfig.load();
        CelestialMarket.Listing mine = ownListing();

        // state badge in the toolbar
        String badge;
        int badgeColor;
        int badgeBg;
        if (!state.cbSellEnabled) {
            badge = "NOT LISTED";
            badgeColor = MUTED;
            badgeBg = 0x14FFFFFF;
        } else if (mine == null) {
            badge = "SYNCING";
            badgeColor = AMBER;
            badgeBg = 0x26F5B54A;
        } else {
            badge = "LISTED";
            badgeColor = GREEN;
            badgeBg = 0x2686EFAC;
        }
        int badgeW = smallWidth(badge) + 12;
        int badgeX = cx + w - badgeW;
        BazzarUi.fillPill(graphics, badgeX, cy + 3, badgeW, 10, badgeBg);
        BazzarUi.fillCircleSmooth(graphics, badgeX + 5, cy + 8, 1.5f, badgeColor);
        drawSmall(graphics, badge, badgeX + 8, cy + 5, badgeColor);

        // status card
        String line1;
        int stateColor;
        if (!state.cbSellEnabled) {
            line1 = "Not listed";
            stateColor = MUTED;
        } else if (mine == null) {
            line1 = "Syncing with the market...";
            stateColor = MUTED;
        } else if (mine.ready()) {
            line1 = mine.stock() + " boost" + (mine.stock() == 1 ? "" : "s") + " ready to sell";
            stateColor = GREEN;
        } else {
            line1 = "Next boost in " + countdown(mine.readyAtLocal());
            stateColor = AMBER;
        }
        int cardH = cardH();
        BazzarUi.fillRoundedRect(graphics, cx, cy + 22, w, cardH, 4, 0x12FFFFFF);
        graphics.fill(cx, cy + 23, cx + 2, cy + 22 + cardH - 1, stateColor);
        BazzarUi.fillCircleSmooth(graphics, cx + 9, cy + 29, 2.5f, stateColor);
        graphics.drawString(font, fitWords(line1, w - 20), cx + 15, cy + 25, stateColor, false);
        List<String> line2 = statusLine2();
        for (int i = 0; i < line2.size(); i++) {
            graphics.drawString(font, line2.get(i), cx + 8, cy + 36 + i * 10, MUTED, false);
        }

        // price grid
        int gy = cy + gridTop();
        int gridR = gridRight();
        drawSmall(graphics, "CURRENCY", cx + 6, gy + 1, HDR);
        drawSmall(graphics, "YOUR PRICE", cx + CURRENCY_COL_W + 1, gy + 1, HDR);
        drawSmall(graphics, "LOWEST ASK", cx + gridR - 4 - smallWidth("LOWEST ASK"), gy + 1, HDR);
        graphics.fill(cx, gy + 9, cx + gridR, gy + 10, theme.accentDim());
        int fieldW = fieldW();
        for (int i = 0; i < priceBoxes.length; i++) {
            String currency = CelestialMarket.CURRENCIES[i];
            int y = cy + priceRowY(i);
            CurrencyIcons.draw(graphics, font, currency, cx + 4, y + 4, 1f);
            graphics.drawString(font, currency, cx + 15, y + 4, TEXT, false);
            EditBox box = priceBoxes[i];
            boolean focused = box.isFocused();
            BazzarUi.fillRoundedRect(graphics, cx + CURRENCY_COL_W, y, fieldW, 16, 3, focused ? theme.accent() : FIELD_EDGE);
            BazzarUi.fillRoundedRect(graphics, cx + CURRENCY_COL_W + 1, y + 1, fieldW - 2, 14, 2, FIELD);
            box.setX(cx + CURRENCY_COL_W + 5);
            box.setY(y + 4);
            box.setWidth(fieldW - 10);

            CelestialMarket.Listing best = CelestialMarket.cheapest(client, currency);
            int askR = cx + gridR - 4;
            if (best == null) {
                graphics.drawString(font, "-", askR - font.width("-"), y + 4, DIM, false);
                continue;
            }
            double ask = best.price(currency);
            String askText = fitPrice(ask, 40);
            Double yours = ShardPayoutController.parseCompactNumber(box.getValue().trim());
            int askColor;
            String tip;
            if (yours == null || yours <= 0d) {
                askColor = MUTED;
                tip = "Cheapest seller: " + best.owner();
            } else if (ask < yours) {
                askColor = RED;
                tip = best.owner() + " sells for less";
            } else {
                askColor = GREEN;
                tip = "You're the cheapest";
            }
            int askX = askR - font.width(askText);
            graphics.drawString(font, askText, askX, y + 4, askColor, false);
            if (inside(mouseX, mouseY, askX - 2, y, font.width(askText) + 4, 16)) {
                tooltip = List.of(tip);
            }
        }

        // action column
        drawPrimaryPill(graphics, actionButton(0), state.cbSellEnabled ? "Save prices" : "List it", mouseX, mouseY);
        Bounds delete = actionButton(1);
        boolean deleteOn = state.cbSellEnabled;
        boolean deleteHover = deleteOn && delete.contains(mouseX, mouseY);
        BazzarUi.fillPill(graphics, delete.x(), delete.y(), delete.w(), delete.h(),
                !deleteOn ? 0xFF1E1B1D : deleteHover ? 0xFF502828 : 0xFF3B1E1E);
        graphics.drawCenteredString(font, "Delete listing", delete.x() + delete.w() / 2, delete.y() + 4, deleteOn ? 0xFFFCA5A5 : GREY);
        Bounds poolRow = actionButton(2);
        BazzarUi.fillRoundedRect(graphics, poolRow.x(), poolRow.y(), poolRow.w(), poolRow.h(), 8, 0x0FFFFFFF);
        graphics.drawString(font, "Free pool", poolRow.x() + 6, poolRow.y() + 4, TEXT, false);
        boolean poolHover = poolRow.contains(mouseX, mouseY);
        drawToggle(graphics, poolToggle(), state.cbPoolEnabled, poolHover);
        if (poolHover) {
            tooltip = List.of("Give your boost away free to", "people who /requestcelestial");
        }

        // bought by
        int sy = cy + salesTop();
        List<CelestialMarket.Sale> sales = CelestialMarket.sales();
        String self = CelestialMarket.selfName(client);
        drawSmall(graphics, "BOUGHT BY", cx + 6, sy + 2, GOLD);
        String count = String.valueOf(sales.size());
        int countX = cx + 6 + smallWidth("BOUGHT BY") + 4;
        BazzarUi.fillPill(graphics, countX, sy, font.width(count) + 7, 10, 0x2EFFD75A);
        graphics.drawString(font, count, countX + 4, sy + 1, GOLD, false);
        int numR = w - 82;
        drawSmall(graphics, "PRICE", cx + numR - smallWidth("PRICE"), sy + 2, HDR);
        drawSmall(graphics, "WHEN", cx + w - 4 - smallWidth("WHEN"), sy + 2, HDR);
        graphics.fill(cx, sy + 10, cx + w, sy + 11, theme.accentDim());
        int listTop = sy + 13;
        int rows = salesRows();
        salesScroll = Math.max(0, Math.min(salesScroll, sales.size() - rows));
        if (sales.isEmpty()) {
            graphics.drawString(font, "Nobody has bought your boost yet.", cx + 6, listTop + 2, GREY, false);
        }
        for (int i = 0; i < rows && salesScroll + i < sales.size(); i++) {
            CelestialMarket.Sale sale = sales.get(salesScroll + i);
            int y = listTop + i * SALE_ROW_H;
            graphics.fill(cx, y, cx + w, y + 10, i % 2 == 0 ? ROW : ROW_ALT);
            String buyer = fitName(sale.buyer(), numR - 54);
            graphics.drawString(font, buyer, cx + 6, y + 2, TEXT, false);
            boolean via = self != null && !sale.seller().equalsIgnoreCase(self);
            int ax = cx + 6 + font.width(buyer) + 4;
            if (via && ax + font.width("via " + sale.seller()) <= cx + numR - 48) {
                graphics.drawString(font, "via " + sale.seller(), ax, y + 2, GREY, false);
            } else if (via) {
                graphics.drawString(font, "*", ax - 2, y + 2, GREY, false);
            }
            if (inside(mouseX, mouseY, cx, y, numR - 40, 10)) {
                tooltip = via ? List.of(sale.buyer(), "Delivered by " + sale.seller()) : List.of(sale.buyer());
            }
            String price = SbText.formatCompact(sale.price());
            graphics.drawString(font, price, cx + numR - font.width(price), y + 2, GOLD, false);
            String unit = CelestialMarket.currencyOf(sale.currency());
            CurrencyIcons.draw(graphics, font, unit == null ? sale.currency() : unit, cx + w - 78, y + 1, 1f);
            String ago = ago(sale.atLocal());
            graphics.drawString(font, ago, cx + w - 4 - font.width(ago), y + 2, MUTED, false);
        }
        drawScrollbar(graphics, listTop, rows * SALE_ROW_H - 1, salesScroll, rows, sales.size());
    }

    // ---- boosts ----

    private int boostColW() {
        return (w - 10) / 2;
    }

    private int boostListTop() {
        return 35;
    }

    private int boostNoteY() {
        return h - messageSpace() - 10;
    }

    private int boostRows() {
        return Math.max(1, (boostNoteY() - 4 - boostListTop()) / BOOST_ROW_H);
    }

    private void drawBoosts(GuiGraphics graphics, int mouseX, int mouseY) {
        long now = System.currentTimeMillis();
        String self = CelestialMarket.selfName(Minecraft.getInstance());
        List<CelestialTracker.Entry> ready = new ArrayList<>();
        List<CelestialTracker.Entry> cooling = new ArrayList<>();
        int readyOffline = 0;
        for (CelestialTracker.Entry entry : CelestialTracker.entries(Minecraft.getInstance())) {
            if (!entry.ready(now)) {
                cooling.add(entry);
            } else if (entry.online()) {
                ready.add(entry);
            } else {
                readyOffline++;
            }
        }

        // legend in the toolbar
        String offline = "offline";
        int legendX = cx + w - font.width(offline);
        graphics.drawString(font, offline, legendX, cy + 4, MUTED, false);
        BazzarUi.fillCircleSmooth(graphics, legendX - 5, cy + 7.5f, 2f, OFF_DOT);
        legendX -= 10 + font.width("online");
        graphics.drawString(font, "online", legendX, cy + 4, MUTED, false);
        BazzarUi.fillCircleSmooth(graphics, legendX - 5, cy + 7.5f, 2f, GREEN);

        int colW = boostColW();
        int rx = colW + 10;
        int listBottom = boostNoteY() - 4;
        drawSmall(graphics, "READY NOW", cx + 4, cy + 24, GREEN);
        drawCountPill(graphics, cx + 4 + smallWidth("READY NOW") + 4, cy + 22, ready.size(), GREEN, 0x2E86EFAC);
        drawSmall(graphics, "ON COOLDOWN", cx + rx + 4, cy + 24, GOLD);
        drawCountPill(graphics, cx + rx + 4 + smallWidth("ON COOLDOWN") + 4, cy + 22, cooling.size(), GOLD, 0x2EFFD75A);
        if (rx + 4 + smallWidth("ON COOLDOWN") + 30 + smallWidth("READY IN") < w - 4) {
            drawSmall(graphics, "READY IN", cx + w - 4 - smallWidth("READY IN"), cy + 24, HDR);
        }
        graphics.fill(cx, cy + 32, cx + colW, cy + 33, 0x8086EFAC);
        graphics.fill(cx + rx, cy + 32, cx + w, cy + 33, 0x80FFD75A);
        graphics.fill(cx + colW + 5, cy + 22, cx + colW + 6, cy + listBottom, SEP);

        int rows = boostRows();

        // the ready list ends with one grey line for everyone who is ready but offline
        int readyItems = ready.size() + (readyOffline > 0 ? 1 : 0);
        readyScroll = Math.max(0, Math.min(readyScroll, readyItems - rows));
        int readyW = readyItems > rows ? colW - 3 : colW;
        if (readyItems == 0) {
            graphics.drawString(font, "Nobody online is ready", cx + 4, cy + boostListTop() + 2, GREY, false);
        }
        for (int i = 0; i < rows && readyScroll + i < readyItems; i++) {
            int index = readyScroll + i;
            int y = cy + boostListTop() + i * BOOST_ROW_H;
            graphics.fill(cx, y, cx + readyW, y + 11, i % 2 == 0 ? ROW : ROW_ALT);
            if (i == rows - 1 && index < readyItems - 1) {
                graphics.drawString(font, "▾ " + (readyItems - index) + " more, scroll", cx + 11, y + 2, MUTED, false);
                continue;
            }
            if (index >= ready.size()) {
                BazzarUi.fillCircleSmooth(graphics, cx + 5, y + 5.5f, 2f, OFF_DOT);
                graphics.drawString(font, fitWords("+" + readyOffline + " ready, offline", readyW - 14), cx + 11, y + 2, GREY, false);
                continue;
            }
            CelestialTracker.Entry entry = ready.get(index);
            boolean you = self != null && entry.name().equalsIgnoreCase(self);
            BazzarUi.fillCircleSmooth(graphics, cx + 5, y + 5.5f, 2f, GREEN);
            String name = fitName(entry.name(), readyW - 14);
            graphics.drawString(font, name, cx + 11, y + 2, you ? BazzarTheme.current().light() : TEXT, false);
            if (!name.equals(entry.name()) && inside(mouseX, mouseY, cx, y, readyW, 11)) {
                tooltip = List.of(entry.name(), "Ready now");
            }
        }
        drawColumnScrollbar(graphics, cx + colW - 2, cy + boostListTop(), rows * BOOST_ROW_H - 1, readyScroll, rows, readyItems);

        int coolW = w - rx;
        coolingScroll = Math.max(0, Math.min(coolingScroll, cooling.size() - rows));
        int usableW = cooling.size() > rows ? coolW - 3 : coolW;
        if (cooling.isEmpty()) {
            graphics.drawString(font, "Nobody is on cooldown", cx + rx + 4, cy + boostListTop() + 2, GREY, false);
        }
        for (int i = 0; i < rows && coolingScroll + i < cooling.size(); i++) {
            int index = coolingScroll + i;
            int x = cx + rx;
            int y = cy + boostListTop() + i * BOOST_ROW_H;
            graphics.fill(x, y, x + usableW, y + 11, i % 2 == 0 ? ROW : ROW_ALT);
            if (i == rows - 1 && index < cooling.size() - 1) {
                graphics.drawString(font, "▾ " + (cooling.size() - index) + " more, scroll", x + 11, y + 2, MUTED, false);
                continue;
            }
            CelestialTracker.Entry entry = cooling.get(index);
            long left = entry.readyAtMs() - now;
            String time = CelestialTracker.formatLeft(left);
            int timeColor = left <= 15L * 60_000L ? LIME : left <= 60L * 60_000L ? GOLD : AMBER_DIM;
            int timeX = x + usableW - 4 - font.width(time);
            BazzarUi.fillCircleSmooth(graphics, x + 5, y + 5.5f, 2f, entry.online() ? GREEN : OFF_DOT);
            String name = fitName(entry.name(), timeX - 6 - (x + 11));
            graphics.drawString(font, name, x + 11, y + 2, entry.online() ? TEXT : MUTED, false);
            graphics.drawString(font, time, timeX, y + 2, timeColor, false);
            float elapsed = Math.max(0f, Math.min(1f, 1f - (float) left / CelestialTracker.COOLDOWN_MS));
            graphics.fill(x, y + 11, x + Math.round(usableW * elapsed), y + 12, 0x66F5B54A);
            if (inside(mouseX, mouseY, x, y, usableW, 11)) {
                tooltip = entry.online() ? List.of(entry.name(), "Ready in " + time)
                        : List.of(entry.name(), "Ready in " + time, "Offline");
            }
        }
        drawColumnScrollbar(graphics, cx + w - 2, cy + boostListTop(), rows * BOOST_ROW_H - 1, coolingScroll, rows, cooling.size());

        String note = "Timers are shared by everyone using Bazzar.";
        if (font.width(note) > w) {
            note = "Timers are shared by all Bazzar users.";
        }
        if (font.width(note) <= w) {
            graphics.drawString(font, note, cx, cy + boostNoteY(), GREY, false);
        }
    }

    private void drawCountPill(GuiGraphics graphics, int x, int y, int count, int color, int bg) {
        String text = String.valueOf(count);
        BazzarUi.fillPill(graphics, x, y, font.width(text) + 7, 10, bg);
        graphics.drawString(font, text, x + 4, y + 1, color, false);
    }

    // ---- message strip ----

    private List<String> messageLines() {
        String message = CelestialMarket.lastMessage();
        if (message == null || message.isEmpty()) {
            return List.of();
        }
        int maxW = w - 12;
        List<String> lines = wrap(message, maxW);
        if (lines.size() <= 2) {
            return lines;
        }
        String rest = String.join(" ", lines.subList(1, lines.size()));
        return List.of(lines.get(0), fitWords(rest, maxW));
    }

    private int messageSpace() {
        int count = messageLines().size();
        return count == 0 ? 0 : 4 + count * 10 + 4;
    }

    private void drawMessage(GuiGraphics graphics, int mouseX, int mouseY) {
        List<String> lines = messageLines();
        if (lines.isEmpty()) {
            return;
        }
        String message = CelestialMarket.lastMessage();
        int msgH = 4 + lines.size() * 10;
        int y = cy + h - msgH;
        int bar;
        int color;
        if (message.startsWith("You need") || message.contains("didn't") || message.contains("Couldn't")
                || message.startsWith("Another")) {
            bar = RED;
            color = 0xFFFCA5A5;
        } else if (message.startsWith("Sold") || message.startsWith("Bought") || message.startsWith("Paid")) {
            bar = GREEN;
            color = 0xFFBBF7D0;
        } else {
            bar = BazzarTheme.current().accent();
            color = 0xFFD7DCE3;
        }
        BazzarUi.fillRoundedRect(graphics, cx, y, w, msgH, 3, 0x14FFFFFF);
        graphics.fill(cx, y + 1, cx + 2, y + msgH - 1, bar);
        for (int i = 0; i < lines.size(); i++) {
            graphics.drawString(font, lines.get(i), cx + 6, y + 3 + i * 10, color, false);
        }
        if (inside(mouseX, mouseY, cx, y, w, msgH) && lines.get(lines.size() - 1).endsWith("..")) {
            tooltip = wrap(message, 220);
        }
    }

    // ---- warning ----

    private int warningW() {
        return Math.min(264, w - 24);
    }

    private List<String> warningLines() {
        return wrap(WARNING, warningW() - 24);
    }

    private int warningH() {
        return 86 + warningLines().size() * 10;
    }

    private int warningX() {
        return cx + (w - warningW()) / 2;
    }

    private int warningY() {
        return Math.max(panelTop + 4, cy + (h - warningH()) / 2);
    }

    private Bounds checkbox() {
        return new Bounds(warningX() + 12, warningY() + warningH() - 44, 150, 10);
    }

    private Bounds understandButton() {
        return new Bounds(warningX() + warningW() - 12 - 56 - 6 - 84, warningY() + warningH() - 26, 84, 16);
    }

    private Bounds cancelButton() {
        return new Bounds(warningX() + warningW() - 12 - 56, warningY() + warningH() - 26, 56, 16);
    }

    private void drawWarning(GuiGraphics graphics, int mouseX, int mouseY) {
        BazzarTheme theme = BazzarTheme.current();
        BazzarUi.fillRoundedRect(graphics, panelLeft, panelTop, panelW, panelH, 8, 0xB8000000);
        int x = warningX();
        int y = warningY();
        int ww = warningW();
        int wh = warningH();
        BazzarUi.fillRoundedRect(graphics, x - 1, y - 1, ww + 2, wh + 2, 7, 0x99FFD75A);
        BazzarUi.fillRoundedGradient(graphics, x, y, ww, wh, 6, 0xFF1C1912, 0xFF12161D);
        BazzarUi.fillCircleSmooth(graphics, x + 18, y + 17, 6f, GOLD);
        graphics.drawString(font, "!", x + 17, y + 13, 0xFF1C1912, false);
        graphics.drawString(font, "Before you buy", x + 30, y + 13, GOLD, false);
        graphics.fill(x + 12, y + 28, x + ww - 12, y + 29, 0x33FFD75A);
        int lineY = y + 34;
        for (String line : warningLines()) {
            graphics.drawString(font, line, x + 12, lineY, TEXT, false);
            lineY += 10;
        }
        Bounds box = checkbox();
        BazzarUi.fillRoundedRect(graphics, box.x(), box.y(), 10, 10, 2, dontShowAgain ? theme.accent() : MUTED);
        BazzarUi.fillRoundedRect(graphics, box.x() + 1, box.y() + 1, 8, 8, 2, dontShowAgain ? theme.accent() : 0xFF12161D);
        if (dontShowAgain) {
            graphics.drawString(font, "✔", box.x() + 1, box.y() + 1, theme.readableOnAccent(), false);
        }
        graphics.drawString(font, "Don't show this again", box.x() + 15, box.y() + 1, MUTED, false);
        drawPrimaryPill(graphics, understandButton(), "I understand", mouseX, mouseY);
        drawPill(graphics, cancelButton(), "Cancel", mouseX, mouseY, false, true);
    }

    // ---- currency picker ----

    // the seller is kept by name because the listings are refetched every 10s
    private CelestialMarket.Listing pickerListing() {
        Minecraft client = Minecraft.getInstance();
        for (CelestialMarket.Listing listing : CelestialMarket.listings()) {
            if (listing.owner().equalsIgnoreCase(pickerOwner)) {
                return listing.ready() && !CelestialMarket.isOwnListing(client, listing) ? listing : null;
            }
        }
        return null;
    }

    private List<String> pickerCurrencies(CelestialMarket.Listing listing) {
        List<String> currencies = new ArrayList<>();
        for (String currency : CelestialMarket.CURRENCIES) {
            if (listing.price(currency) != null) {
                currencies.add(currency);
            }
        }
        return currencies;
    }

    private int pickerW() {
        return Math.min(210, w - 20);
    }

    private int pickerH(int options) {
        return 34 + options * 20 + 26;
    }

    private int pickerX() {
        return cx + (w - pickerW()) / 2;
    }

    private int pickerY(int options) {
        return Math.max(panelTop + 4, cy + (h - pickerH(options)) / 2);
    }

    private Bounds pickerOption(int options, int index) {
        return new Bounds(pickerX() + 8, pickerY(options) + 34 + index * 20, pickerW() - 16, 18);
    }

    private Bounds pickerCancel(int options) {
        return new Bounds(pickerX() + pickerW() - 8 - 60, pickerY(options) + pickerH(options) - 24, 60, 16);
    }

    private void drawPicker(GuiGraphics graphics, int mouseX, int mouseY) {
        CelestialMarket.Listing listing = pickerListing();
        if (listing == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        BazzarTheme theme = BazzarTheme.current();
        List<String> currencies = pickerCurrencies(listing);
        int n = currencies.size();
        int x = pickerX();
        int y = pickerY(n);
        int pw = pickerW();
        int ph = pickerH(n);
        BazzarUi.fillRoundedRect(graphics, panelLeft, panelTop, panelW, panelH, 8, 0xA0000000);
        BazzarUi.fillRoundedRect(graphics, x - 1, y - 1, pw + 2, ph + 2, 7, theme.accentDim());
        BazzarUi.fillRoundedGradient(graphics, x, y, pw, ph, 6, 0xFF181C24, 0xFF0F1217);
        graphics.drawString(font, fitName("Buy from " + listing.owner(), pw - 20), x + 10, y + 9, TEXT, false);
        graphics.drawString(font, fitWords("Pick how you want to pay", pw - 20), x + 10, y + 20, MUTED, false);

        for (int i = 0; i < n; i++) {
            String currency = currencies.get(i);
            double price = listing.price(currency);
            Bounds option = pickerOption(n, i);
            boolean ok = buyable(client, listing, currency, price);
            boolean affordable = CelestialMarket.canAfford(client, currency, price);
            boolean hovered = option.contains(mouseX, mouseY);
            int bg = hovered && ok ? theme.accent() : hovered ? 0x22FFFFFF : 0x12FFFFFF;
            BazzarUi.fillRoundedRect(graphics, option.x(), option.y(), option.w(), option.h(), 4, bg);
            boolean onAccent = hovered && ok;
            CurrencyIcons.draw(graphics, font, currency, option.x() + 6, option.y() + 5, 1f);
            String name = currency.substring(0, 1).toUpperCase(Locale.ROOT) + currency.substring(1);
            graphics.drawString(font, name, option.x() + 18, option.y() + 5,
                    onAccent ? theme.readableOnAccent() : ok ? TEXT : MUTED, false);
            String priceText = SbText.formatCompact(price);
            int priceColor = onAccent ? theme.readableOnAccent() : !affordable ? RED_DIM : ok ? GREEN : MUTED;
            graphics.drawString(font, priceText, option.x() + option.w() - 6 - font.width(priceText), option.y() + 5,
                    priceColor, false);
            if (listing == CelestialMarket.cheapest(client, currency)) {
                int tagX = option.x() + 18 + font.width(name) + 5;
                if (tagX + 26 < option.x() + option.w() - 10 - font.width(priceText)) {
                    BazzarUi.fillPill(graphics, tagX, option.y() + 4, 26, 10, onAccent ? 0x33000000 : 0x2EFFD75A);
                    drawSmall(graphics, "BEST", tagX + 5, option.y() + 6, onAccent ? theme.readableOnAccent() : GOLD);
                }
            }
            if (hovered) {
                if (ok) {
                    tooltip = List.of("Pays " + listing.owner() + " " + priceText + " " + currency + " now");
                } else if (!affordable) {
                    double have = CelestialMarket.balance(client, currency);
                    tooltip = List.of("You need " + SbText.formatCompact(price - Math.max(0d, have)) + " more " + currency);
                } else if (CelestialMarket.waitingForPayment()) {
                    tooltip = List.of("Waiting for your last buy");
                } else {
                    tooltip = List.of("Wait for the buy delay");
                }
            }
        }

        String note = CelestialMarket.waitingForPayment() ? "Waiting for your last buy"
                : CelestialMarket.cooldownLeftMs() > 0L ? cooldownText() : "";
        Bounds cancel = pickerCancel(n);
        if (!note.isEmpty() && font.width(note) <= cancel.x() - x - 16) {
            graphics.drawString(font, note, x + 10, cancel.y() + 4, AMBER, false);
        }
        drawPill(graphics, cancel, "Cancel", mouseX, mouseY, false, true);
    }

    private void pickerClick(Minecraft client, int mouseX, int mouseY, int button) {
        CelestialMarket.Listing listing = pickerListing();
        if (listing == null || button != 0) {
            pickerOwner = null;
            return;
        }
        List<String> currencies = pickerCurrencies(listing);
        int n = currencies.size();
        for (int i = 0; i < n; i++) {
            if (pickerOption(n, i).contains(mouseX, mouseY)) {
                String currency = currencies.get(i);
                if (buyable(client, listing, currency, listing.price(currency))) {
                    pickerOwner = null;
                    CelestialMarket.buy(client, listing, currency);
                }
                return;
            }
        }
        // anywhere outside the card closes it, like Cancel
        if (pickerCancel(n).contains(mouseX, mouseY)
                || !inside(mouseX, mouseY, pickerX(), pickerY(n), pickerW(), pickerH(n))) {
            pickerOwner = null;
        }
    }

    // ---- input ----

    boolean mouseClicked(int mouseX, int mouseY, int button) {
        Minecraft client = Minecraft.getInstance();
        if (!warning && pickerOwner != null) {
            pickerClick(client, mouseX, mouseY, button);
            return true;
        }
        if (warning) {
            if (button == 0 && understandButton().contains(mouseX, mouseY)) {
                warning = false;
                warningDone = true;
                BazzarConfig.State state = BazzarConfig.load();
                state.cbWarningAccepted = true;
                state.cbWarningHidden = dontShowAgain;
                BazzarConfig.save(state);
            } else if (button == 0 && cancelButton().contains(mouseX, mouseY)) {
                // the market is a tab now, so cancelling moves to the view that can't pay anyone
                show(View.BOOSTS);
            } else if (button == 0 && checkbox().contains(mouseX, mouseY)) {
                dontShowAgain = !dontShowAgain;
            }
            return true;
        }
        if (button != 0) {
            return false;
        }
        for (View which : View.values()) {
            if (segment(which).contains(mouseX, mouseY)) {
                if (which != view) {
                    show(which);
                }
                return true;
            }
        }
        if (view == View.MARKET) {
            return marketClick(client, mouseX, mouseY);
        }
        if (view == View.MY_LISTING) {
            return listingClick(client, mouseX, mouseY);
        }
        return false;
    }

    private boolean marketClick(Minecraft client, int mouseX, int mouseY) {
        if (cooldownToggle().contains(mouseX, mouseY) || cooldownLabel().contains(mouseX, mouseY)) {
            BazzarConfig.State state = BazzarConfig.load();
            state.cbBuyCooldown = !state.cbBuyCooldown;
            BazzarConfig.save(state);
            return true;
        }
        for (int i = 0; i < CelestialMarket.CURRENCIES.length; i++) {
            if (headerCell(i).contains(mouseX, mouseY)) {
                BazzarConfig.State state = BazzarConfig.load();
                state.cbQuickCurrency = CelestialMarket.CURRENCIES[i];
                BazzarConfig.save(state);
                return true;
            }
        }
        List<CelestialMarket.Listing> listings = CelestialMarket.listings();
        int rows = marketRows();
        for (int i = 0; i < rows && scroll + i < listings.size(); i++) {
            CelestialMarket.Listing listing = listings.get(scroll + i);
            if (!listing.ready() || CelestialMarket.isOwnListing(client, listing)) {
                continue;
            }
            if (inside(mouseX, mouseY, cx, cy + marketListTop() + i * ROW_H, w, 12)) {
                pickerOwner = listing.owner();
                return true;
            }
        }
        if (requestButton().contains(mouseX, mouseY) && CelestialMarket.poolReady() > 0) {
            CelestialMarket.request(client);
            return true;
        }
        return false;
    }

    private boolean listingClick(Minecraft client, int mouseX, int mouseY) {
        if (actionButton(0).contains(mouseX, mouseY)) {
            Map<String, Double> prices = new LinkedHashMap<>();
            for (int i = 0; i < priceBoxes.length; i++) {
                String raw = priceBoxes[i].getValue().trim();
                if (raw.isEmpty()) {
                    continue;
                }
                Double price = ShardPayoutController.parseCompactNumber(raw);
                if (price == null || price <= 0d) {
                    priceBoxes[i].setValue("");
                    priceBoxes[i].setHint(Component.literal("not a price").withStyle(ChatFormatting.RED));
                    return true;
                }
                prices.put(CelestialMarket.CURRENCIES[i], price);
            }
            String self = CelestialMarket.selfName(client);
            String group = CelestialMarket.groupName(client);
            boolean alt = group != null && self != null && !group.equalsIgnoreCase(self);
            if (prices.isEmpty() && !alt) {
                priceBoxes[0].setHint(Component.literal("set at least one").withStyle(ChatFormatting.RED));
                return true;
            }
            CelestialMarket.setPrices(client, prices);
            return true;
        }
        if (actionButton(1).contains(mouseX, mouseY) && BazzarConfig.load().cbSellEnabled) {
            CelestialMarket.deleteListing(client);
            return true;
        }
        if (actionButton(2).contains(mouseX, mouseY)) {
            CelestialMarket.setPool(client, !BazzarConfig.load().cbPoolEnabled);
            return true;
        }
        return false;
    }

    boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (modal()) {
            return true;
        }
        int step = (int) Math.signum(scrollY);
        switch (view) {
            case MARKET -> scroll = Math.max(0, scroll - step);
            case MY_LISTING -> salesScroll = Math.max(0, salesScroll - step);
            case BOOSTS -> {
                if (mouseX < cx + boostColW() + 5) {
                    readyScroll = Math.max(0, readyScroll - step);
                } else {
                    coolingScroll = Math.max(0, coolingScroll - step);
                }
            }
        }
        return true;
    }

    // ---- small pieces ----

    private void drawScrollbar(GuiGraphics graphics, int top, int viewH, int offset, int rows, int total) {
        if (total <= rows) {
            return;
        }
        int barH = Math.max(12, viewH * rows / total);
        int barY = top + (viewH - barH) * offset / Math.max(1, total - rows);
        graphics.fill(cx + w + 3, top, cx + w + 5, top + viewH, 0x14FFFFFF);
        graphics.fill(cx + w + 3, barY, cx + w + 5, barY + barH, BazzarTheme.current().accentDim());
    }

    private void drawColumnScrollbar(GuiGraphics graphics, int x, int top, int viewH, int offset, int rows, int total) {
        if (total <= rows) {
            return;
        }
        int barH = Math.max(10, viewH * rows / total);
        int barY = top + (viewH - barH) * offset / Math.max(1, total - rows);
        graphics.fill(x, top, x + 2, top + viewH, 0x14FFFFFF);
        graphics.fill(x, barY, x + 2, barY + barH, BazzarTheme.current().accentDim());
    }

    private void drawToggle(GuiGraphics graphics, Bounds bounds, boolean on, boolean hovered) {
        BazzarTheme theme = BazzarTheme.current();
        int border = on ? theme.light() : 0xFF39404A;
        if (hovered) {
            border = BazzarUi.lerpColor(border, 0xFFFFFFFF, 0.25f);
        }
        BazzarUi.fillPillSmooth(graphics, bounds.x() - 1, bounds.y() - 1, bounds.w() + 2, bounds.h() + 2, border);
        BazzarUi.fillPillSmooth(graphics, bounds.x(), bounds.y(), bounds.w(), bounds.h(), on ? theme.accent() : 0xFF23282F);
        float radius = (bounds.h() - 4) / 2.0f;
        float knobX = on ? bounds.x() + bounds.w() - 2 - radius : bounds.x() + 2 + radius;
        float knobY = bounds.y() + bounds.h() / 2.0f;
        BazzarUi.fillCircleSmooth(graphics, knobX, knobY + 0.5f, radius, 0x40000000);
        BazzarUi.fillCircleSmooth(graphics, knobX, knobY, radius, 0xFFF2F4F6);
    }

    private void drawPill(GuiGraphics graphics, Bounds button, String text, int mouseX, int mouseY, boolean selected, boolean enabled) {
        BazzarTheme theme = BazzarTheme.current();
        boolean hovered = enabled && button.contains(mouseX, mouseY);
        int bg = selected ? theme.buttonBgActive() : hovered ? theme.buttonBgHover() : theme.buttonBg();
        if (selected) {
            BazzarUi.fillPill(graphics, button.x() - 1, button.y() - 1, button.w() + 2, button.h() + 2, theme.accentDim());
        }
        BazzarUi.fillPill(graphics, button.x(), button.y(), button.w(), button.h(), bg);
        graphics.drawCenteredString(font, text, button.x() + button.w() / 2, button.y() + (button.h() - 8) / 2,
                enabled ? (selected ? theme.light() : TEXT) : GREY);
    }

    private void drawPrimaryPill(GuiGraphics graphics, Bounds button, String text, int mouseX, int mouseY) {
        BazzarTheme theme = BazzarTheme.current();
        boolean hovered = button.contains(mouseX, mouseY);
        if (hovered) {
            BazzarUi.fillPill(graphics, button.x() - 2, button.y() - 2, button.w() + 4, button.h() + 4, theme.soft(0x55));
        }
        BazzarUi.fillPill(graphics, button.x(), button.y(), button.w(), button.h(), hovered ? theme.light() : theme.accent());
        graphics.drawString(font, text, button.x() + (button.w() - font.width(text)) / 2, button.y() + (button.h() - 8) / 2,
                theme.readableOnAccent(), false);
    }

    private void drawTooltip(GuiGraphics graphics, List<String> lines, int mouseX, int mouseY) {
        int textW = 0;
        for (String line : lines) {
            textW = Math.max(textW, font.width(line));
        }
        int textH = lines.size() * 10;
        int tx = Math.max(4, Math.min(mouseX + 8, graphics.guiWidth() - textW - 8));
        int ty = Math.max(4, Math.min(mouseY - 14, graphics.guiHeight() - textH - 4));
        graphics.fill(tx - 3, ty - 3, tx + textW + 3, ty + textH, 0xE6000000);
        graphics.fill(tx - 3, ty - 3, tx + textW + 3, ty - 2, BazzarTheme.current().accentDim());
        for (int i = 0; i < lines.size(); i++) {
            graphics.drawString(font, lines.get(i), tx, ty + i * 10, i == 0 ? 0xFFFFFFFF : 0xFFD1D5DB, false);
        }
    }

    // column headers use the same 0.75 scale as the sidebar's group labels
    private void drawSmall(GuiGraphics graphics, String text, int x, int y, int color) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(0.75f, 0.75f);
        graphics.drawString(font, text, 0, 0, color, false);
        graphics.pose().popMatrix();
    }

    private int smallWidth(String text) {
        return (int) Math.ceil(font.width(text) * 0.75f);
    }

    // player names are cut with ".." and shown in full in a tooltip
    private String fitName(String name, int maxW) {
        if (font.width(name) <= maxW) {
            return name;
        }
        return font.plainSubstrByWidth(name, Math.max(0, maxW - font.width(".."))) + "..";
    }

    // drops decimals until the number fits its column
    private String fitPrice(double value, int maxW) {
        String text = SbText.formatCompact(value);
        if (font.width(text) <= maxW) {
            return text;
        }
        for (int decimals = 1; decimals >= 0; decimals--) {
            text = compact(value, decimals);
            if (font.width(text) <= maxW) {
                return text;
            }
        }
        return text;
    }

    private static String compact(double value, int decimals) {
        String[] suffixes = {"", "k", "M", "B", "T"};
        double scaled = value;
        int tier = 0;
        while (Math.abs(scaled) >= 1_000d && tier < suffixes.length - 1) {
            scaled /= 1_000d;
            tier++;
        }
        String number = String.format(Locale.ROOT, "%." + decimals + "f", scaled);
        if (number.contains(".")) {
            number = number.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return number + suffixes[tier];
    }

    // wraps at spaces; a single word wider than the line is the only thing ever cut
    private List<String> wrap(String text, int maxW) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = line.length() == 0 ? word : line + " " + word;
            if (font.width(next) > maxW && line.length() > 0) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        lines.add(line.toString());
        for (int i = 0; i < lines.size(); i++) {
            if (font.width(lines.get(i)) > maxW) {
                lines.set(i, font.plainSubstrByWidth(lines.get(i), maxW));
            }
        }
        return lines;
    }

    // keeps whole words and ends with ".." when the text doesn't fit
    private String fitWords(String text, int maxW) {
        if (font.width(text) <= maxW) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = out.length() == 0 ? word : out + " " + word;
            if (font.width(next + "..") > maxW) {
                break;
            }
            out = new StringBuilder(next);
        }
        if (out.length() == 0) {
            return font.plainSubstrByWidth(text, Math.max(0, maxW - font.width(".."))) + "..";
        }
        return out.toString().replaceAll("[\\s.,]+$", "") + "..";
    }

    private static String countdown(long readyAtLocal) {
        long seconds = Math.max(0L, (readyAtLocal - System.currentTimeMillis()) / 1000L);
        long hours = seconds / 3600L;
        long minutes = seconds % 3600L / 60L;
        return hours > 0L ? hours + "h " + minutes + "m" : minutes + "m " + (seconds % 60L) + "s";
    }

    private static String ago(long atMs) {
        long minutes = Math.max(0L, (System.currentTimeMillis() - atMs) / 60_000L);
        if (minutes < 60L) {
            return minutes + "m ago";
        }
        if (minutes < 48L * 60L) {
            return (minutes / 60L) + "h ago";
        }
        return (minutes / 1440L) + "d ago";
    }

    private static boolean inside(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private record Bounds(int x, int y, int w, int h) {
        boolean contains(int mouseX, int mouseY) {
            return inside(mouseX, mouseY, x, y, w, h);
        }
    }
}

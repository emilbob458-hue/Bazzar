package bazaar.bazzar.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;

// small bar with the cheapest Celestial Boost in your quick-buy currency; the buy hotkey only works while it's shown
final class CelestialMarketHud {
    private static final int BAR_HEIGHT = 16;
    private static final String CHIP_LABEL = "CB";
    private static final long REFRESH_MS = 15_000L;
    private static final int PANEL_BG = 0xE00C0E12;
    private static final int PANEL_EDGE = 0x1FFFFFFF;
    private static final int MUTED_TEXT = 0xFF8E97A6;
    private static final int VALUE_TEXT = 0xFFE6E9EE;
    private static final int GREY = 0xFF5F6773;
    private static final int GREEN = 0xFF86EFAC;
    private static final int RED = 0xFFF87171;
    private static final int AMBER = 0xFFF5B54A;
    private static final int DIVIDER = 0x26FFFFFF;

    private static KeyMapping buyKey;
    private static int barWidth = 160;
    private static boolean dragging = false;
    private static boolean dragMoved = false;
    private static int dragOffsetX = 0;
    private static int dragOffsetY = 0;
    private static int hudX = -1;
    private static int hudY = -1;
    private static long lastRefreshMs = 0L;

    private CelestialMarketHud() {
    }

    static void setBuyKey(KeyMapping key) {
        buyKey = key;
    }

    static boolean isVisible() {
        return BazzarConfig.load().cbHudVisible;
    }

    static void toggle(Minecraft client) {
        BazzarConfig.State state = BazzarConfig.load();
        state.cbHudVisible = !state.cbHudVisible;
        BazzarConfig.save(state);
        lastRefreshMs = 0L;
        if (client.player != null) {
            McCompat.chat(client.player, BazaarChat.prefix().append(net.minecraft.network.chat.Component.literal(
                    state.cbHudVisible ? "Celestial market HUD shown, the buy key now buys the cheapest boost."
                            : "Celestial market HUD hidden, the buy key is off.")));
        }
    }

    static void tick(Minecraft client) {
        if (!isVisible() || client.player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRefreshMs >= REFRESH_MS) {
            lastRefreshMs = now;
            CelestialMarket.refresh(client);
        }
    }

    static void render(GuiGraphics graphics, int mouseX, int mouseY) {
        HudBoxes.hide("cbmarket");
        Minecraft client = Minecraft.getInstance();
        if (!isVisible() || client.player == null || client.level == null) {
            return;
        }
        BazzarConfig.State state = BazzarConfig.load();
        if (hudX < 0) {
            hudX = state.cbHudX;
            hudY = state.cbHudY;
        }
        Font font = client.font;
        String currency = state.cbQuickCurrency;
        CelestialMarket.Listing best = CelestialMarket.loaded() ? CelestialMarket.cheapest(client, currency) : null;
        BazzarTheme theme = BazzarTheme.current();

        // a ticker pill: badge, price, seller and stock, then a key cap for the buy key
        String price = "";
        String owner = "";
        String stock = "";
        String empty = "";
        String key = "";
        String tail = "";
        boolean affordable = false;
        if (!CelestialMarket.loaded()) {
            empty = "loading...";
        } else if (best == null) {
            empty = "No boosts for " + currency;
        } else {
            double amount = best.price(currency);
            affordable = CelestialMarket.canAfford(client, currency, amount);
            price = SbText.formatCompact(amount);
            owner = font.width(best.owner()) > 96
                    ? font.plainSubstrByWidth(best.owner(), 96 - font.width("..")) + ".." : best.owner();
            stock = "x" + best.stock();
            if (buyKey != null && !buyKey.isUnbound()) {
                key = buyKey.getTranslatedKeyMessage().getString();
                long cooldown = CelestialMarket.cooldownLeftMs();
                tail = CelestialMarket.waitingForPayment() ? "paying..."
                        : cooldown > 0L ? ((cooldown + 999L) / 1000L) + "s" : "buy";
            }
        }

        int x = 31;
        if (!empty.isEmpty()) {
            x += font.width(empty);
        } else {
            x += font.width(price) + 3 + 8 + 6 + 7 + font.width(owner) + 3 + font.width(stock);
            if (!key.isEmpty()) {
                x += 6 + 7 + font.width(key) + 6 + 4 + font.width(tail);
            }
        }
        barWidth = x + 7;

        hudX = clamp(hudX, 2, Math.max(2, graphics.guiWidth() - barWidth - 2));
        hudY = HudBoxes.place("cbmarket", hudX, clamp(hudY, 2, Math.max(2, graphics.guiHeight() - BAR_HEIGHT - 2)),
                barWidth, BAR_HEIGHT, graphics.guiHeight());

        BazzarUi.fillPill(graphics, hudX - 1, hudY - 1, barWidth + 2, BAR_HEIGHT + 2, PANEL_EDGE);
        BazzarUi.fillPill(graphics, hudX, hudY, barWidth, BAR_HEIGHT, PANEL_BG);
        BazzarUi.fillPill(graphics, hudX + 3, hudY + 3, 22, 10, best != null ? theme.accent() : theme.soft(0x66));
        graphics.drawString(font, CHIP_LABEL, hudX + 3 + (22 - font.width(CHIP_LABEL)) / 2, hudY + 4,
                theme.readableOnAccent(), false);
        x = hudX + 31;
        if (!empty.isEmpty()) {
            graphics.drawString(font, empty, x, hudY + 4, best == null && CelestialMarket.loaded() ? GREY : MUTED_TEXT, false);
            return;
        }
        graphics.drawString(font, price, x, hudY + 4, affordable ? GREEN : RED, false);
        x += font.width(price) + 3;
        CurrencyIcons.draw(graphics, font, currency, x, hudY + 4, 1f);
        x += 8 + 6;
        graphics.fill(x, hudY + 4, x + 1, hudY + 12, DIVIDER);
        x += 7;
        graphics.drawString(font, owner, x, hudY + 4, VALUE_TEXT, false);
        x += font.width(owner) + 3;
        graphics.drawString(font, stock, x, hudY + 4, MUTED_TEXT, false);
        x += font.width(stock);
        if (key.isEmpty()) {
            return;
        }
        x += 6;
        graphics.fill(x, hudY + 4, x + 1, hudY + 12, DIVIDER);
        x += 7;
        boolean canBuy = affordable && !CelestialMarket.waitingForPayment() && CelestialMarket.cooldownLeftMs() <= 0L;
        int capW = font.width(key) + 6;
        BazzarUi.fillRoundedRect(graphics, x, hudY + 3, capW, 10, 2, canBuy ? 0xFF2E333C : 0xFF1E2228);
        graphics.fill(x + 1, hudY + 12, x + capW - 1, hudY + 13, canBuy ? 0xFF4A505B : 0xFF15181C);
        graphics.drawString(font, key, x + 3, hudY + 4, canBuy ? VALUE_TEXT : GREY, false);
        x += capW + 4;
        graphics.drawString(font, tail, x, hudY + 4, tail.equals("buy") ? MUTED_TEXT : AMBER, false);

        // the 5s buy delay drains along the bottom edge
        long cooldown = CelestialMarket.cooldownLeftMs();
        if (cooldown > 0L) {
            int drain = (int) ((barWidth - 16) * cooldown / CelestialMarket.cooldownMs());
            graphics.fill(hudX + 8, hudY + 14, hudX + 8 + drain, hudY + 15, theme.accent());
        }
    }


    static void onMouseClick(Screen screen, MouseButtonEvent event) {
        if (!isVisible() || !isHovering((int) event.x(), (int) event.y())) {
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
            BazzarConfig.State state = BazzarConfig.load();
            state.cbHudX = hudX;
            state.cbHudY = hudY;
            BazzarConfig.save(state);
        }
        dragMoved = false;
    }

    static void onMouseDrag(Screen screen, MouseButtonEvent event, double deltaX, double deltaY) {
        if (!dragging) {
            return;
        }
        dragMoved = true;
        int[] pos = HudBoxes.drag("cbmarket", hudX, hudY, (int) event.x() - dragOffsetX, (int) event.y() - dragOffsetY);
        hudX = pos[0];
        hudY = pos[1];
    }

    private static boolean isHovering(int mouseX, int mouseY) {
        return mouseX >= hudX && mouseX <= hudX + barWidth && mouseY >= hudY && mouseY <= hudY + BAR_HEIGHT;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

package bazaar.bazzar.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

final class PinnedBazaarHud {
    private static final int COLS = 4;
    private static final int MAX_ROWS = 2;
    private static final int PANEL_W = 150;
    private static final int HEADER_H = 18;
    private static final int ROW_H = 24;
    private static final int ROW_GAP = 2;
    private static final int PAD = 4;
    private static final int FOOTER_H = 11;
    private static final String FOOTER_HINT = "Middle click in bazaar to pin · right click to unpin";
    private static final int PANEL_BG = 0xDB0C0E12;
    private static final int PANEL_EDGE = 0x14FFFFFF;
    private static final int SLOT_BG = 0x08FFFFFF;
    private static final int HUD_TEXT_WHITE = 0xFFE6E9EE;
    private static final int HUD_TEXT_MUTED = 0xFF8B95A3;
    private static final int BUY_TEXT = 0xFF86EFAC;
    private static final int SELL_TEXT = 0xFF7DD3FC;
    private static final int RATE_BAD_BG = 0x29F87171;
    private static final int RATE_BAD_TEXT = 0xFFFCA5A5;

    private static int hudX = 10;
    private static int hudY = 10;
    private static float scale = 1.0f;
    private static boolean visible = true;
    private static boolean dragging = false;
    private static int dragOffsetX = 0;
    private static int dragOffsetY = 0;
    private static int updateCooldown = 0;
    private static boolean dragMoved = false;
    private static boolean dirty = false;
    private static boolean wasInBazaar = false;

    private static final List<PinnedItem> pinned = new ArrayList<>();

    private PinnedBazaarHud() {
    }

    static void render(GuiGraphics graphics, int screenWidth, int screenHeight, int mouseX, int mouseY) {
        Minecraft client = Minecraft.getInstance();
        if (!visible || client == null || client.player == null || client.level == null) {
            return;
        }

        List<Integer> visibleIndexes = getVisibleIndexes();
        int rawHeight = panelHeight(visibleIndexes.size());
        int width = Math.round(PANEL_W * scale);
        int height = Math.round(rawHeight * scale);

        int x = clamp(hudX, 2, Math.max(2, screenWidth - width - 2));
        int y = clamp(hudY, 2, Math.max(2, screenHeight - height - 2));
        hudX = x;
        hudY = y;

        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(scale, scale);

        Minecraft mc = Minecraft.getInstance();
        BazzarTheme theme = BazzarTheme.current();
        BazzarUi.fillRoundedRect(graphics, -1, -1, PANEL_W + 2, rawHeight + 2, 7, PANEL_EDGE);
        BazzarUi.fillRoundedRect(graphics, 0, 0, PANEL_W, rawHeight, 6, PANEL_BG);

        graphics.drawString(mc.font, "Bazaar HUD", 8, 6, theme.light(), false);
        graphics.fill(8, HEADER_H - 1, PANEL_W - 8, HEADER_H, theme.soft(0x59));

        if (visibleIndexes.isEmpty()) {
            drawScaledText(graphics, "No pinned crops yet", 8, HEADER_H + 6, HUD_TEXT_WHITE, 0.75f);
        }

        for (int drawIndex = 0; drawIndex < visibleIndexes.size(); drawIndex++) {
                int sourceIndex = visibleIndexes.get(drawIndex);
                PinnedItem item = pinned.get(sourceIndex);
                int rowX = PAD;
                int rowY = rowTop(drawIndex);
                int rowW = PANEL_W - 2 * PAD;
                boolean hovered = isHovering(x + Math.round(rowX * scale), y + Math.round(rowY * scale),
                        Math.round(rowW * scale), Math.round(ROW_H * scale), mouseX, mouseY);
                BazzarUi.fillRoundedRect(graphics, rowX, rowY, rowW, ROW_H, 4, hovered ? theme.soft(0x30) : SLOT_BG);
                graphics.renderItem(item.icon, rowX + 4, rowY + 4);

                boolean expensive = item.marketRate != null && item.marketRate > 100.0;
                String rateText = item.marketRate != null ? formatShortPercent(item.marketRate) : "n/a";
                int pillW = mc.font.width(rateText) + 8;
                int pillX = rowX + rowW - 4 - pillW;
                BazzarUi.fillRoundedRect(graphics, pillX, rowY + 6, pillW, 12, 3, expensive ? RATE_BAD_BG : theme.soft(0x29));
                graphics.drawString(mc.font, rateText, pillX + 4, rowY + 8, expensive ? RATE_BAD_TEXT : theme.light(), false);

                int textX = rowX + 24;
                String name = mc.font.plainSubstrByWidth(item.name, pillX - textX - 4);
                graphics.drawString(mc.font, name, textX, rowY + 3, HUD_TEXT_WHITE, false);
                String buyText = "Buy " + (item.buy != null ? formatShort(item.buy) : "n/a");
                String sellText = "Sell " + (item.sell != null ? formatShort(item.sell) : "n/a");
                float priceScale = 0.75f;
                int sellX = textX + Math.round((mc.font.width(buyText + " · ")) * priceScale);
                drawScaledText(graphics, buyText, textX, rowY + 14, BUY_TEXT, priceScale);
                drawScaledText(graphics, " · ", textX + Math.round(mc.font.width(buyText) * priceScale), rowY + 14, HUD_TEXT_MUTED, priceScale);
                drawScaledText(graphics, sellText, sellX, rowY + 14, SELL_TEXT, priceScale);
                int owned = getOwnedCount(item.itemId);

                if (hovered) {
                    List<Component> tooltip = new ArrayList<>();
                    tooltip.add(Component.literal(item.name).withStyle(ChatFormatting.YELLOW));
                    if (item.buy != null) {
                        tooltip.add(Component.literal("Buy: " + format(item.buy)).withStyle(ChatFormatting.GREEN));
                    }
                    if (item.sell != null) {
                        tooltip.add(Component.literal("Sell: " + format(item.sell)).withStyle(ChatFormatting.AQUA));
                    }
                    if (item.marketRate != null) {
                        tooltip.add(Component.literal("Market Rate: " + formatPercent(item.marketRate))
                                .withStyle(item.marketRate > 100.0 ? ChatFormatting.RED : ChatFormatting.GRAY));
                    }
                    tooltip.add(Component.literal("Owned: " + owned).withStyle(ChatFormatting.WHITE));
                    if (item.sell != null) {
                        tooltip.add(Component.literal("Sell All: " + format(item.sell * owned)).withStyle(ChatFormatting.GOLD));
                    }
                    if (item.buy != null) {
                        tooltip.add(Component.literal("Buy All: " + format(item.buy * owned)).withStyle(ChatFormatting.GREEN));
                    }
                    graphics.setTooltipForNextFrame(Minecraft.getInstance().font, tooltip, java.util.Optional.empty(), mouseX, mouseY);
                }
        }
        int hintWidth = mc.font.width(FOOTER_HINT);
        float hintScale = Math.min(0.6f, (PANEL_W - 16.0f) / Math.max(1, hintWidth));
        drawScaledText(graphics, FOOTER_HINT, 8, rawHeight - FOOTER_H + 2, HUD_TEXT_MUTED, hintScale);
        graphics.pose().popMatrix();
    }

    private static int rowTop(int drawIndex) {
        return HEADER_H + PAD + drawIndex * (ROW_H + ROW_GAP);
    }

    private static int panelHeight(int rowCount) {
        int body = rowCount == 0 ? 16 : rowCount * ROW_H + (rowCount - 1) * ROW_GAP;
        return HEADER_H + PAD + body + PAD + FOOTER_H;
    }

    static void onMouseClick(Screen screen, MouseButtonEvent event) {
        if (!visible) {
            return;
        }
        if (screen == null) {
            return;
        }
        if (screen instanceof AbstractContainerScreen<?> containerScreen
                && BazaarFlipScanner.isBazaarScreen(screen)
                && isPinButton(event.button())) {
            Slot hovered = ScreenSlotHelper.getSlotAt(containerScreen, event.x(), event.y());
            if (hovered == null) {
                hovered = ScreenSlotHelper.getHoveredSlot(containerScreen);
            }
            if (hovered != null && !hovered.getItem().isEmpty()) {
                pinNextAvailable(hovered.getItem());
                return;
            }
        }
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        if (isHoveringHud(mouseX, mouseY)) {
            dragging = true;
            dragMoved = false;
            dragOffsetX = mouseX - hudX;
            dragOffsetY = mouseY - hudY;
        }
    }

    static void onMouseRelease(Screen screen, MouseButtonEvent event) {
        if (!visible) {
            return;
        }
        boolean wasDragging = dragging;
        boolean wasMoved = dragMoved;
        dragging = false;
        dragMoved = false;
        if (wasDragging && wasMoved) {
            dirty = true;
            return;
        }
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        int slotIndex = getPinnedIndexAt(mouseX, mouseY);
        if (slotIndex < 0) {
            return;
        }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && (event.modifiers() & GLFW.GLFW_MOD_SHIFT) == 0) {
            removePinned(slotIndex);
            dirty = true;
        }
    }

    static void onMouseDrag(Screen screen, MouseButtonEvent event, double deltaX, double deltaY) {
        if (!visible) {
            return;
        }
        if (!dragging) {
            return;
        }
        dragMoved = true;
        hudX = (int) (event.x() - dragOffsetX);
        hudY = (int) (event.y() - dragOffsetY);
    }

    static void onMouseScroll(Screen screen, double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (!visible) {
            return;
        }
        if (screen == null) {
            return;
        }
        if (!isHoveringHud((int) mouseX, (int) mouseY)) {
            return;
        }
        if (verticalAmount == 0.0d) {
            return;
        }
        float step = (float) (Math.signum(verticalAmount) * 0.1f);
        setScale(getScale() + step);
    }

    static void tick(Minecraft client) {
        boolean inBazaar = BazaarFlipScanner.isBazaarScreen(McCompat.screen(client));
        if (!inBazaar) {
            updateCooldown = 0;
            wasInBazaar = false;
            return;
        }
        if (!wasInBazaar) {
            wasInBazaar = true;
            updateCooldown = 10;
            updateFromCandidates(BazaarFlipScanner.collectCandidates(client));
            return;
        }
        if (updateCooldown > 0) {
            updateCooldown--;
            return;
        }
        updateCooldown = 10;
        updateFromCandidates(BazaarFlipScanner.collectCandidates(client));
    }

    static boolean pinHoveredBazaarItem(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return false;
        }
        Slot hovered = ScreenSlotHelper.getHoveredSlot(containerScreen);
        if (hovered == null || hovered.getItem().isEmpty()) {
            return false;
        }
        pinNextAvailable(hovered.getItem());
        return true;
    }

    static void updateFromCandidates(List<BazaarFlipScanner.FlipCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        for (PinnedItem item : pinned) {
            if (item == null || item.icon.isEmpty() || "Empty".equals(item.name)) {
                continue;
            }
            if (BazaarFlipScanner.isBlacklisted(item.name, item.itemId)) {
                continue;
            }
            BazaarFlipScanner.FlipCandidate match = null;
            for (BazaarFlipScanner.FlipCandidate c : candidates) {
                if (c.itemId().equals(item.itemId)) {
                    match = c;
                    break;
                }
            }
            if (match == null) {
                for (BazaarFlipScanner.FlipCandidate c : candidates) {
                    if (c.name().equalsIgnoreCase(item.name)) {
                        match = c;
                        break;
                    }
                }
            }
            if (match == null) {
                for (BazaarFlipScanner.FlipCandidate c : candidates) {
                    String candNorm = normalize(c.name());
                    String itemNorm = normalize(item.name);
                    if (!itemNorm.isEmpty() && !candNorm.isEmpty()
                            && (candNorm.contains(itemNorm) || itemNorm.contains(candNorm))) {
                        match = c;
                        break;
                    }
                }
            }
            if (match == null) {
                continue;
            }
            item.buy = match.buy();
            item.sell = match.sell();
            item.marketRate = match.marketRate();
        }
    }

    private static BazaarFlipScanner.FlipCandidate findBestCandidateForPinned(PinnedItem item,
                                                                               List<BazaarFlipScanner.FlipCandidate> candidates) {
        for (BazaarFlipScanner.FlipCandidate c : candidates) {
            if (c.itemId().equals(item.itemId)) {
                return c;
            }
        }
        for (BazaarFlipScanner.FlipCandidate c : candidates) {
            if (c.name().equalsIgnoreCase(item.name)) {
                return c;
            }
        }
        String itemNorm = normalize(item.name);
        for (BazaarFlipScanner.FlipCandidate c : candidates) {
            String candNorm = normalize(c.name());
            if (!itemNorm.isEmpty() && !candNorm.isEmpty()
                    && (candNorm.contains(itemNorm) || itemNorm.contains(candNorm))) {
                return c;
            }
        }
        return null;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");

        normalized = normalized.replace("rasin", "resin");
        return normalized;
    }

    private static void pinAt(int index, ItemStack stack) {
        ItemStack icon = stack.copy();
        icon.setCount(1);
        String name = stack.getHoverName().getString();
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (BazaarFlipScanner.isBlacklisted(name, itemId)) {
            Minecraft client = Minecraft.getInstance();
            if (client != null && client.player != null) {
                McCompat.chat(client.player, Component.literal("[Bazaar] " + name + " is blacklisted. Use /blacklist " + name + " to unblacklist.")
                        .withStyle(ChatFormatting.RED));
            }
            return;
        }
        ensureSize(index + 1);
        PinnedItem pinnedItem = new PinnedItem(name, itemId, icon);
        BazaarFlipScanner.FlipCandidate immediate = BazaarFlipScanner.fromStack(Minecraft.getInstance(), stack);
        if (immediate != null) {
            pinnedItem.buy = immediate.buy();
            pinnedItem.sell = immediate.sell();
            pinnedItem.marketRate = immediate.marketRate();
        } else {
            BazaarFlipScanner.FlipCandidate fallback = findCandidateForPinnedItem(pinnedItem);
            if (fallback != null) {
                pinnedItem.buy = fallback.buy();
                pinnedItem.sell = fallback.sell();
                pinnedItem.marketRate = fallback.marketRate();
            }
        }
        pinned.set(index, pinnedItem);
        dirty = true;
    }

    private static BazaarFlipScanner.FlipCandidate findCandidateForPinnedItem(PinnedItem item) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return null;
        }
        List<BazaarFlipScanner.FlipCandidate> candidates = BazaarFlipScanner.collectCandidates(client);
        return candidates.isEmpty() ? null : findBestCandidateForPinned(item, candidates);
    }

    private static void pinNextAvailable(ItemStack stack) {
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        for (int i = 0; i < pinned.size(); i++) {
            PinnedItem current = pinned.get(i);
            if (itemId.equals(current.itemId)) {
                pinAt(i, stack);
                return;
            }
        }

        for (int i = 0; i < pinned.size(); i++) {
            PinnedItem current = pinned.get(i);
            if (current.icon.isEmpty() || "Empty".equals(current.name)) {
                pinAt(i, stack);
                return;
            }
        }

        if (pinned.size() < COLS * MAX_ROWS) {
            pinAt(pinned.size(), stack);
            return;
        }

        pinAt((COLS * MAX_ROWS) - 1, stack);
    }

    private static boolean isPinButton(int button) {
        return button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE || button == 2;
    }

    private static void removePinned(int index) {
        if (index >= 0 && index < pinned.size()) {
            pinned.set(index, new PinnedItem("Empty", ItemStack.EMPTY));
        }
    }

    private static void ensureSize(int size) {
        while (pinned.size() < size && pinned.size() < COLS * MAX_ROWS) {
            pinned.add(new PinnedItem("Empty", ItemStack.EMPTY));
        }
    }

    private static int getHudSlotIndex(int mouseX, int mouseY) {
        List<Integer> visibleIndexes = getVisibleIndexes();
        if (visibleIndexes.isEmpty() || !isHoveringHud(mouseX, mouseY)) {
            return -1;
        }
        float relX = (mouseX - hudX) / scale;
        float relY = (mouseY - hudY) / scale;
        if (relX < PAD || relX > PANEL_W - PAD) {
            return -1;
        }
        for (int drawIndex = 0; drawIndex < visibleIndexes.size(); drawIndex++) {
            int top = rowTop(drawIndex);
            if (relY >= top && relY <= top + ROW_H) {
                return visibleIndexes.get(drawIndex);
            }
        }
        return -1;
    }

    private static int getPinnedIndexAt(int mouseX, int mouseY) {
        return getHudSlotIndex(mouseX, mouseY);
    }

    private static boolean isHovering(int x, int y, int width, int height, int mouseX, int mouseY) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private static boolean isHoveringHud(int mouseX, int mouseY) {
        if (!visible) {
            return false;
        }
        int width = Math.round(PANEL_W * scale);
        int height = Math.round(panelHeight(getVisibleIndexes().size()) * scale);
        return isHovering(hudX, hudY, width, height, mouseX, mouseY);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static void setScale(float newScale) {
        float clamped = Math.max(0.5f, Math.min(3.0f, newScale));
        if (Math.abs(clamped - scale) > 0.0001f) {
            scale = clamped;
            dirty = true;
        }
    }

    static float getScale() {
        return scale;
    }

    static void toggleVisibility() {
        visible = !visible;
        dirty = true;
    }

    static boolean isVisible() {
        return visible;
    }

    static void applyConfig(BazzarConfig.State state) {
        if (state == null) {
            return;
        }
        visible = state.hudVisible;
        scale = Math.max(0.5f, Math.min(3.0f, state.hudScale));
        hudX = state.hudX;
        hudY = state.hudY;
        dirty = false;
    }

    static BazzarConfig.State captureConfig() {
        BazzarConfig.State state = new BazzarConfig.State();
        state.hudVisible = visible;
        state.hudX = hudX;
        state.hudY = hudY;
        state.hudScale = scale;
        return state;
    }

    static boolean consumeDirty() {
        if (!dirty) {
            return false;
        }
        dirty = false;
        return true;
    }

    private static String format(Double value) {
        if (value == null) {
            return "n/a";
        }
        if (Math.rint(value) != value) {
            return String.format(Locale.ROOT, "%,.2f", value);
        }
        return String.format(Locale.ROOT, "%,.0f", value);
    }

    private static String formatPercent(Double value) {
        if (value == null) {
            return "n/a";
        }
        if (Math.rint(value) != value) {
            return String.format(Locale.ROOT, "%.2f%%", value);
        }
        return String.format(Locale.ROOT, "%.0f%%", value);
    }

    private static String formatShort(Double value) {
        if (value == null) {
            return "";
        }
        if (value >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fm", value / 1_000_000d);
        }
        if (value >= 1_000) {
            return String.format(Locale.ROOT, "%.1fk", value / 1_000d);
        }
        if (Math.rint(value) != value) {
            return String.format(Locale.ROOT, "%.2f", value);
        }
        return String.format(Locale.ROOT, "%.0f", value);
    }

    private static String formatShortPercent(Double value) {
        if (value == null) {
            return "";
        }
        return String.format(Locale.ROOT, "%.0f%%", value);
    }

    private static void drawScaledText(GuiGraphics graphics, String text, int x, int y, int color, float textScale) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(textScale, textScale);
        graphics.drawString(Minecraft.getInstance().font, text, 0, 0, color, false);
        graphics.pose().popMatrix();
    }

    private static int getOwnedCount(String itemId) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || itemId == null || itemId.isBlank()) {
            return 0;
        }
        Item item;
        try {
            item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
        } catch (Exception ignored) {
            return 0;
        }
        if (item == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < client.player.getInventory().getContainerSize(); i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static final class PinnedItem {
        private final String name;
        private final String itemId;
        private final ItemStack icon;
        private Double buy;
        private Double sell;
        private Double marketRate;

        private PinnedItem(String name, ItemStack icon) {
            this(name, "minecraft:air", icon);
        }

        private PinnedItem(String name, String itemId, ItemStack icon) {
            this.name = name;
            this.itemId = itemId;
            this.icon = icon;
        }
    }

    private static List<Integer> getVisibleIndexes() {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < pinned.size(); i++) {
            PinnedItem item = pinned.get(i);
            if (item == null || item.icon.isEmpty() || "Empty".equals(item.name)) {
                continue;
            }
            if (BazaarFlipScanner.isBlacklisted(item.name, item.itemId)) {
                continue;
            }
            indexes.add(i);
        }
        return indexes;
    }
}

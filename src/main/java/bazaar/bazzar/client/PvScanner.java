package bazaar.bazzar.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

final class PvScanner {
    private static final Pattern PAGE_NUMBER_PATTERN = Pattern.compile("(?:\\bpv\\b|vault)\\D{0,12}?(\\d+)");
    private static final long HIGHLIGHT_TTL_MS = 60_000L;

    private static String lastSignature = "";
    private static String highlightName = null;
    private static long highlightSetMs = 0L;

    private PvScanner() {
    }

    static void tick(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            return;
        }
        if (!(McCompat.screen(client) instanceof AbstractContainerScreen<?> screen)) {
            lastSignature = "";
            return;
        }
        String pageKey = pageKeyFrom(screen.getTitle().getString().toLowerCase(Locale.ROOT));
        if (pageKey.isEmpty()) {
            return;
        }
        List<PvIndex.Entry> items = new ArrayList<>();
        StringBuilder signature = new StringBuilder(pageKey);
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            String id = stack.getItemHolder().unwrapKey().map(key -> key.identifier().toString()).orElse("");
            if (id.contains("glass_pane")) {
                continue;
            }
            String name = SbText.stripFormattingCodes(stack.getHoverName().getString()).trim();
            if (name.isEmpty() || name.replaceAll("[^A-Za-z0-9]", "").isEmpty()) {
                continue;
            }
            PvIndex.Entry entry = new PvIndex.Entry();
            entry.name = name;
            entry.id = id;
            entry.count = stack.getCount();
            items.add(entry);
            signature.append('|').append(name).append('x').append(stack.getCount());
        }
        String snapshot = signature.toString();
        if (snapshot.equals(lastSignature)) {
            return;
        }
        lastSignature = snapshot;
        String account = ShardPayController.normalizeName(client.player.getName().getString());
        PvIndex.put(account, pageKey, items);
    }

    static void setHighlight(String name) {
        highlightName = name == null || name.isBlank() ? null : name;
        highlightSetMs = System.currentTimeMillis();
    }

    static void renderHighlight(Screen scr, GuiGraphics graphics) {
        if (highlightName == null) {
            return;
        }
        if (System.currentTimeMillis() - highlightSetMs > HIGHLIGHT_TTL_MS) {
            highlightName = null;
            return;
        }
        if (!(scr instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        if (pageKeyFrom(screen.getTitle().getString().toLowerCase(Locale.ROOT)).isEmpty()) {
            return;
        }
        int left = ScreenSlotHelper.getLeft(screen);
        int top = ScreenSlotHelper.getTop(screen);
        int accent = BazzarTheme.current().accent();
        int border = 0xFF000000 | (accent & 0xFFFFFF);
        int fill = 0x50000000 | (accent & 0xFFFFFF);
        for (Slot slot : screen.getMenu().slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            String name = SbText.stripFormattingCodes(stack.getHoverName().getString()).trim();
            if (!name.equalsIgnoreCase(highlightName)) {
                continue;
            }
            int x = left + slot.x;
            int y = top + slot.y;
            graphics.fill(x - 1, y - 1, x + 17, y, border);
            graphics.fill(x - 1, y + 16, x + 17, y + 17, border);
            graphics.fill(x - 1, y, x, y + 16, border);
            graphics.fill(x + 16, y, x + 17, y + 16, border);
            graphics.fill(x, y, x + 16, y + 16, fill);
        }
    }

    private static String pageKeyFrom(String title) {
        Matcher matcher = PAGE_NUMBER_PATTERN.matcher(title);
        return matcher.find() ? matcher.group(1) : "";
    }
}

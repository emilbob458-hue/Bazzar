package bazaar.bazzar.client;

import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

final class PlaytimeRewardsController {
    private static final long COOLDOWN_MS = 60_000L;
    private static final int CLICK_SPACING_TICKS = 6;
    private static final int IDLE_CLOSE_SCANS = 40;
    private static final int CLAIMED_CLOSE_SCANS = 10;
    private static final String GUI_TITLE_FRAGMENT = "playtime rewards";

    private static long lastTriggerMs = 0L;
    private static boolean autoOpened = false;
    private static long autoOpenedAtMs = 0L;
    private static int clickCooldown = 0;
    private static int idleScans = 0;
    private static boolean claimedAny = false;

    private PlaytimeRewardsController() {
    }

    static boolean isEnabled() {
        BazzarConfig.State state = BazzarConfig.load();
        return state != null && state.playtimeClaimEnabled;
    }

    static void setEnabled(boolean value) {
        BazzarConfig.State state = BazzarConfig.load();
        if (state == null) {
            return;
        }
        state.playtimeClaimEnabled = value;
        BazzarConfig.save(state);
    }

    static void onChatLine(Minecraft client, String line) {
        if (!isEnabled()) {
            return;
        }
        if (client == null || client.player == null || client.player.connection == null) {
            return;
        }
        if (line == null || line.isBlank()) {
            return;
        }
        String normalized = SbText.normalizeText(line);
        if (!normalized.contains("unclaimed rewards")) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTriggerMs < COOLDOWN_MS) {
            return;
        }
        lastTriggerMs = now;
        autoOpened = true;
        autoOpenedAtMs = now;
        idleScans = 0;
        client.player.connection.sendCommand("playtimerewards");
    }

    static void tick(Minecraft client) {
        if (!isEnabled()) {
            return;
        }
        if (client == null || client.player == null || client.gameMode == null || client.level == null) {
            return;
        }
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)
                || !screen.getTitle().getString().toLowerCase(Locale.ROOT).contains(GUI_TITLE_FRAGMENT)) {
            if (autoOpened && System.currentTimeMillis() - autoOpenedAtMs > 15_000L) {
                autoOpened = false;
            }
            idleScans = 0;
            claimedAny = false;
            return;
        }
        if (clickCooldown > 0) {
            clickCooldown--;
            return;
        }
        int slot = findClaimableSlot(client, screen);
        if (slot >= 0) {
            client.gameMode.handleInventoryMouseClick(screen.getMenu().containerId, slot, 0, ClickType.PICKUP, client.player);
            clickCooldown = CLICK_SPACING_TICKS;
            idleScans = 0;
            claimedAny = true;
            return;
        }
        idleScans++;
        if ((claimedAny && idleScans > CLAIMED_CLOSE_SCANS) || (autoOpened && idleScans > IDLE_CLOSE_SCANS)) {
            autoOpened = false;
            claimedAny = false;
            idleScans = 0;
            client.setScreen(null);
        }
    }

    private static int findClaimableSlot(Minecraft client, AbstractContainerScreen<?> screen) {
        for (int i = 0; i < screen.getMenu().slots.size(); i++) {
            Slot slot = screen.getMenu().slots.get(i);
            if (slot.container instanceof Inventory) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            String itemId = stack.getItemHolder().unwrapKey().map(key -> key.identifier().toString()).orElse("");
            if (itemId.contains("stained_glass")) {
                continue;
            }
            StringBuilder text = new StringBuilder(stack.getHoverName().getString());
            List<Component> lore = stack.getTooltipLines(Item.TooltipContext.of(client.level), client.player, TooltipFlag.NORMAL);
            for (Component line : lore) {
                text.append(' ').append(line.getString());
            }
            String normalized = SbText.normalizeText(text.toString());
            if (normalized.contains("claim") && !normalized.contains("claimed")) {
                return i;
            }
        }
        return -1;
    }
}

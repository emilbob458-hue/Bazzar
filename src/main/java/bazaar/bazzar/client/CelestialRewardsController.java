package bazaar.bazzar.client;

import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

final class CelestialRewardsController {
    private static final String OPEN_COMMAND = "rewards";
    private static final int CHECK_INTERVAL_TICKS = 20;
    private static final int JOIN_DELAY_TICKS = 200;
    private static final long RETRY_MS = 5L * 60_000L;
    private static final long UNKNOWN_WAIT_MS = 60L * 60_000L;
    private static final long OPEN_TIMEOUT_MS = 10_000L;
    private static final int CLICK_SPACING_TICKS = 6;
    private static final int CLOSE_SCANS = 10;
    private static final int MAX_CLICKS = 12;
    private static final Pattern READY_PATTERN = Pattern.compile("\\bready\\b");

    private static int checkCooldown = 0;
    private static int joinTicks = 0;
    private static long lastOpenMs = 0L;
    private static boolean autoOpened = false;
    private static int clickCooldown = 0;
    private static int idleScans = 0;
    private static int clicks = 0;
    private static boolean finished = false;

    private CelestialRewardsController() {
    }

    static boolean isEnabled() {
        BazzarConfig.State state = BazzarConfig.load();
        return state != null && state.celestialClaimEnabled;
    }

    static void setEnabled(boolean value) {
        BazzarConfig.State state = BazzarConfig.load();
        if (state == null) {
            return;
        }
        state.celestialClaimEnabled = value;
        BazzarConfig.save(state);
    }

    static void tick(Minecraft client) {
        if (client == null || client.player == null || client.gameMode == null || client.level == null) {
            joinTicks = 0;
            return;
        }
        if (joinTicks < JOIN_DELAY_TICKS) {
            joinTicks++;
        }
        if (!isEnabled()) {
            return;
        }
        if (client.screen instanceof AbstractContainerScreen<?> screen && isRewardsScreen(screen)) {
            tickScreen(client, screen);
            return;
        }
        idleScans = 0;
        clicks = 0;
        finished = false;
        long now = System.currentTimeMillis();
        if (autoOpened && now - lastOpenMs > OPEN_TIMEOUT_MS) {
            autoOpened = false;
        }
        if (--checkCooldown > 0) {
            return;
        }
        checkCooldown = CHECK_INTERVAL_TICKS;
        if (client.screen != null || joinTicks < JOIN_DELAY_TICKS || now - lastOpenMs < RETRY_MS) {
            return;
        }
        if (now < readyAt(client)) {
            return;
        }
        lastOpenMs = now;
        autoOpened = true;
        CommandSender.send(client, OPEN_COMMAND);
    }

    private static void tickScreen(Minecraft client, AbstractContainerScreen<?> screen) {
        if (finished) {
            return;
        }
        if (clickCooldown > 0) {
            clickCooldown--;
            return;
        }
        if (clicks < MAX_CLICKS) {
            int slot = findClaimableSlot(client, screen);
            if (slot >= 0) {
                client.gameMode.handleInventoryMouseClick(screen.getMenu().containerId, slot, 0, ClickType.PICKUP, client.player);
                clickCooldown = CLICK_SPACING_TICKS;
                idleScans = 0;
                clicks++;
                return;
            }
        }
        idleScans++;
        if (idleScans <= CLOSE_SCANS) {
            return;
        }
        long wait = clicks >= MAX_CLICKS ? RETRY_MS : nextAvailableMs(client, screen);
        setReadyAt(client, System.currentTimeMillis() + (wait > 0L ? wait : UNKNOWN_WAIT_MS));
        boolean close = autoOpened || clicks > 0;
        autoOpened = false;
        finished = true;
        if (close) {
            client.setScreen(null);
        }
    }

    private static boolean isRewardsScreen(AbstractContainerScreen<?> screen) {
        String title = SbText.normalizeText(screen.getTitle().getString()).trim();
        return title.equals("rewards") || title.contains("celestial");
    }

    private static int findClaimableSlot(Minecraft client, AbstractContainerScreen<?> screen) {
        for (int i = 0; i < screen.getMenu().slots.size(); i++) {
            String text = rewardText(client, screen.getMenu().slots.get(i));
            if (text == null) {
                continue;
            }
            if (text.contains("available in") || text.contains("locked") || text.contains("requires")) {
                continue;
            }
            if (text.contains("claimed") && !text.contains("unclaimed")) {
                continue;
            }
            if (text.contains("claim") || READY_PATTERN.matcher(text).find()) {
                return i;
            }
        }
        return -1;
    }

    private static long nextAvailableMs(Minecraft client, AbstractContainerScreen<?> screen) {
        long best = -1L;
        for (Slot slot : screen.getMenu().slots) {
            String text = rewardText(client, slot);
            if (text == null) {
                continue;
            }
            int at = text.indexOf("available in");
            if (at < 0) {
                continue;
            }
            long ms = SbText.parseDurationMs(text.substring(at));
            if (ms > 0L && (best < 0L || ms < best)) {
                best = ms;
            }
        }
        return best;
    }

    private static String rewardText(Minecraft client, Slot slot) {
        if (slot.container instanceof Inventory) {
            return null;
        }
        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) {
            return null;
        }
        String name = SbText.normalizeText(stack.getHoverName().getString());
        if (!name.contains("reward")) {
            return null;
        }
        StringBuilder text = new StringBuilder(name);
        List<Component> lore = stack.getTooltipLines(Item.TooltipContext.of(client.level), client.player, TooltipFlag.NORMAL);
        for (Component line : lore) {
            text.append(' ').append(line.getString());
        }
        return SbText.normalizeText(text.toString());
    }

    private static long readyAt(Minecraft client) {
        BazzarConfig.State state = BazzarConfig.load();
        if (state == null || state.celestialRewardReadyAt == null) {
            return 0L;
        }
        Long at = state.celestialRewardReadyAt.get(playerKey(client));
        return at == null ? 0L : at;
    }

    private static void setReadyAt(Minecraft client, long at) {
        String key = playerKey(client);
        BazzarConfig.State state = BazzarConfig.load();
        if (state == null || key.isEmpty()) {
            return;
        }
        if (state.celestialRewardReadyAt == null) {
            state.celestialRewardReadyAt = new HashMap<>();
        }
        state.celestialRewardReadyAt.put(key, at);
        BazzarConfig.save(state);
    }

    private static String playerKey(Minecraft client) {
        return ShardPayController.normalizeName(client.player.getName().getString());
    }
}

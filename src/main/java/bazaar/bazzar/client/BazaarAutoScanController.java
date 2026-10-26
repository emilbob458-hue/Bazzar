package bazaar.bazzar.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;

final class BazaarAutoScanController {
    public enum ScanMode {
        CROPS,
        GEMS
    }

    private enum State {
        IDLE,
        WAIT_FOR_OPEN,
        SCAN_CROPS_1,
        WAIT_CROPS_2,
        SCAN_CROPS_2,
        CLICK_GEMS,
        WAIT_GEMS,
        SCAN_GEMS_1,
        CLICK_GEMS_PAGE_2,
        WAIT_GEMS_PAGE_2,
        SCAN_GEMS_2,
        CLOSE,
        REPORT
    }

    private static final String BAZAAR_COMMAND = "bazaar";
    private static final String NEXT_PAGE_NAME = "next page";
    private static final String GEMS_ITEM_ID = "minecraft:rotten_flesh";
    private static final int BASE_PAGE_DELAY_TICKS = 3;
    private static final int PAGE_DELAY_BUFFER_MS = 150;
    private static final int MAX_PAGE_DELAY_TICKS = 40;
    private static final int TIMEOUT_TICKS = 200;
    private static final int DEFAULT_AUTO_INTERVAL_SECONDS = 120;
    private static final int MIN_AUTO_INTERVAL_SECONDS = 15;
    private static final int MAX_AUTO_INTERVAL_SECONDS = 3600;

    private static State state = State.IDLE;
    private static ScanMode currentScanMode = ScanMode.CROPS;
    private static boolean pendingOpen = false;
    private static int waitTicks = 0;
    private static int timeoutTicks = 0;
    private static long lastAutoTriggerMs = 0L;
    private static boolean autoScanEnabled = false;
    private static int autoScanIntervalSeconds = DEFAULT_AUTO_INTERVAL_SECONDS;
    private static boolean settingsDirty = false;
    private static final List<BazaarFlipScanner.FlipCandidate> collected = new ArrayList<>();

    private BazaarAutoScanController() {
    }

    static void triggerManual(Minecraft client) {
        start(client, ScanMode.CROPS);
    }

    static void triggerManual(Minecraft client, ScanMode mode) {
        start(client, mode);
    }

    static void triggerAuto(Minecraft client) {
        if (!autoScanEnabled) {
            return;
        }
        long now = System.currentTimeMillis();
        long cooldownMs = Math.max(1_000L, autoScanIntervalSeconds * 1000L);
        if (now - lastAutoTriggerMs < cooldownMs) {
            return;
        }
        if (start(client, ScanMode.CROPS)) {
            lastAutoTriggerMs = now;
        }
    }

    static void applyConfig(BazzarConfig.State state) {
        autoScanEnabled = state != null && state.autoBazaarScanEnabled;
        autoScanIntervalSeconds = clampIntervalSeconds(state != null ? state.autoBazaarScanIntervalSeconds : DEFAULT_AUTO_INTERVAL_SECONDS);
        settingsDirty = false;
        lastAutoTriggerMs = 0L;
    }

    static void applyConfigToState(BazzarConfig.State state) {
        if (state == null) {
            return;
        }
        state.autoBazaarScanEnabled = autoScanEnabled;
        state.autoBazaarScanIntervalSeconds = autoScanIntervalSeconds;
    }

    static boolean consumeSettingsDirty() {
        if (!settingsDirty) {
            return false;
        }
        settingsDirty = false;
        return true;
    }

    static boolean isAutoScanEnabled() {
        return autoScanEnabled;
    }

    static int getAutoScanIntervalSeconds() {
        return autoScanIntervalSeconds;
    }

    static void setAutoScanEnabled(boolean enabled) {
        if (autoScanEnabled == enabled) {
            return;
        }
        autoScanEnabled = enabled;
        settingsDirty = true;
        lastAutoTriggerMs = 0L;
    }

    static void setAutoScanIntervalSeconds(int seconds) {
        int clamped = clampIntervalSeconds(seconds);
        if (autoScanIntervalSeconds == clamped) {
            return;
        }
        autoScanIntervalSeconds = clamped;
        settingsDirty = true;
        lastAutoTriggerMs = 0L;
    }

    static void adjustAutoScanIntervalSeconds(int deltaSeconds) {
        setAutoScanIntervalSeconds(autoScanIntervalSeconds + deltaSeconds);
    }

    private static int clampIntervalSeconds(int seconds) {
        return Math.max(MIN_AUTO_INTERVAL_SECONDS, Math.min(MAX_AUTO_INTERVAL_SECONDS, seconds));
    }

    private static boolean start(Minecraft client, ScanMode mode) {
        if (state != State.IDLE || client.player == null || client.player.connection == null) {
            return false;
        }
        currentScanMode = mode;
        collected.clear();
        waitTicks = 0;
        timeoutTicks = 0;
        if (McCompat.screen(client) != null) {
            pendingOpen = true;
            state = State.WAIT_FOR_OPEN;
            return true;
        }
        sendOpenCommand(client);
        state = State.WAIT_FOR_OPEN;
        return true;
    }

    static void tick(Minecraft client) {
        if (state == State.IDLE || client.player == null) {
            return;
        }
        timeoutTicks++;
        if (timeoutTicks > TIMEOUT_TICKS) {
            reset();
            return;
        }

        if (pendingOpen && McCompat.screen(client) == null) {
            pendingOpen = false;
            sendOpenCommand(client);
        }

        switch (state) {
            case WAIT_FOR_OPEN -> {
                if (BazaarFlipScanner.isBazaarScreen(McCompat.screen(client))) {
                    if (currentScanMode == ScanMode.CROPS) {
                        state = State.SCAN_CROPS_1;
                    } else {
                        state = State.CLICK_GEMS;
                    }
                }
            }
            case SCAN_CROPS_1 -> {
                collected.addAll(BazaarFlipScanner.collectCandidatesIncludingBlacklisted(client));
                if (clickNextPage(client)) {
                    waitTicks = 0;
                    state = State.WAIT_CROPS_2;
                } else {
                    state = State.CLOSE;
                }
            }
            case WAIT_CROPS_2 -> {
                waitTicks++;
                if (waitTicks >= getAdaptivePageDelayTicks(client)) {
                    state = State.SCAN_CROPS_2;
                }
            }
            case SCAN_CROPS_2 -> {
                collected.addAll(BazaarFlipScanner.collectCandidatesIncludingBlacklisted(client));
                state = State.CLOSE;
            }
            case CLICK_GEMS -> {
                if (clickCategoryItem(client, GEMS_ITEM_ID)) {
                    waitTicks = 0;
                    state = State.WAIT_GEMS;
                } else {
                    state = State.CLOSE;
                }
            }
            case WAIT_GEMS -> {
                waitTicks++;
                if (waitTicks >= getAdaptivePageDelayTicks(client)) {
                    state = State.SCAN_GEMS_1;
                }
            }
            case SCAN_GEMS_1 -> {
                collected.addAll(BazaarFlipScanner.collectCandidatesIncludingBlacklisted(client));
                if (clickNextPage(client)) {
                    waitTicks = 0;
                    state = State.WAIT_GEMS_PAGE_2;
                } else {
                    state = State.CLOSE;
                }
            }
            case WAIT_GEMS_PAGE_2 -> {
                waitTicks++;
                if (waitTicks >= getAdaptivePageDelayTicks(client)) {
                    state = State.SCAN_GEMS_2;
                }
            }
            case SCAN_GEMS_2 -> {
                collected.addAll(BazaarFlipScanner.collectCandidatesIncludingBlacklisted(client));
                state = State.CLOSE;
            }
            case CLOSE -> {
                if (McCompat.screen(client) != null) {
                    McCompat.setScreen(client, null);
                }
                state = State.REPORT;
            }
            case REPORT -> {
                if (currentScanMode == ScanMode.CROPS) {
                    BazaarFlipScanner.cacheCropsResults(collected);
                } else {
                    BazaarFlipScanner.cacheGemsResults(collected);
                }
                PinnedBazaarHud.updateFromCandidates(collected);
                BazaarFlipScanner.reportResults(text -> McCompat.chat(client.player, text), collected);
                reset();
            }
            default -> {
            }
        }
    }

    private static void sendOpenCommand(Minecraft client) {
        CommandSender.send(client, BAZAAR_COMMAND);
    }

    private static boolean clickNextPage(Minecraft client) {
        Screen screen = McCompat.screen(client);
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return false;
        }
        if (client.gameMode == null || client.player == null) {
            return false;
        }
        AbstractContainerMenu menu = containerScreen.getMenu();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.getItem().isEmpty()) {
                continue;
            }
            String name = slot.getItem().getHoverName().getString().toLowerCase(Locale.ROOT);
            if (name.contains(NEXT_PAGE_NAME)) {
                client.gameMode.handleInventoryMouseClick(menu.containerId, i, 0, ClickType.PICKUP, client.player);
                return true;
            }
        }
        return false;
    }

    private static boolean clickCategoryItem(Minecraft client, String itemId) {
        Screen screen = McCompat.screen(client);
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return false;
        }
        if (client.gameMode == null || client.player == null) {
            return false;
        }
        AbstractContainerMenu menu = containerScreen.getMenu();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.getItem().isEmpty()) {
                continue;
            }
            if (slot.container instanceof net.minecraft.world.entity.player.Inventory) {
                continue;
            }
            String slotItemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(slot.getItem().getItem()).toString();
            if (itemId.equals(slotItemId)) {
                client.gameMode.handleInventoryMouseClick(menu.containerId, i, 0, ClickType.PICKUP, client.player);
                return true;
            }
        }
        return false;
    }

    private static int getAdaptivePageDelayTicks(Minecraft client) {
        int pingMs = getPingMillis(client);
        if (pingMs < 0) {
            return BASE_PAGE_DELAY_TICKS;
        }
        int estimatedTicks = (pingMs + PAGE_DELAY_BUFFER_MS + 49) / 50;
        return Math.max(BASE_PAGE_DELAY_TICKS, Math.min(MAX_PAGE_DELAY_TICKS, estimatedTicks));
    }

    private static int getPingMillis(Minecraft client) {
        if (client.player == null || client.player.connection == null) {
            return -1;
        }
        PlayerInfo playerInfo = client.player.connection.getPlayerInfo(client.player.getUUID());
        if (playerInfo == null) {
            return -1;
        }
        return Math.max(0, playerInfo.getLatency());
    }

    private static void reset() {
        state = State.IDLE;
        pendingOpen = false;
        waitTicks = 0;
        timeoutTicks = 0;
        collected.clear();
    }
}

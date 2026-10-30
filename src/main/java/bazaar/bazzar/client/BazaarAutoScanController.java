package bazaar.bazzar.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
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
        CLICK_CATEGORY,
        WAIT_CATEGORY,
        SCAN_PAGE,
        WAIT_PAGE,
        CLOSE,
        REPORT
    }

    private static final String BAZAAR_COMMAND = "bazaar";
    // the arrow is just called "next" on some pages, so match the word rather than the whole label
    private static final String NEXT_PAGE_NAME = "next";
    // the category is found by what it is called, so a new season changing the icons doesn't stop
    // the scan; the old gems icon stays as a fallback for a page that spells it differently
    private static final String GEMS_ITEM_ID = "minecraft:rotten_flesh";
    // in order of preference: the mob market is where the gem drops live now, but a season that
    // gives them a market of their own should win over it
    static final String[] GEMS_CATEGORY = {"gem", "spawner", "mob"};
    static final String[] CROPS_CATEGORY = {"crop", "farming"};
    // the arrows sit among the categories and must never be mistaken for one
    private static final String[] PAGE_BUTTONS = {"next", "prev"};
    // however many pages the season has, with a stop so a page button that loops can't run away
    private static final int MAX_PAGES = 12;
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
    private static int pagesScanned = 0;
    private static String lastPageKey = "";
    private static boolean categoryMissing = false;
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
        pagesScanned = 0;
        lastPageKey = "";
        categoryMissing = false;
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
                    waitTicks = 0;
                    state = State.CLICK_CATEGORY;
                }
            }
            case CLICK_CATEGORY -> {
                boolean gems = currentScanMode == ScanMode.GEMS;
                String[] wanted = gems ? GEMS_CATEGORY : CROPS_CATEGORY;
                // "Bazaar: Crop Market" says which market is open without reading a single slot
                if (categoryRank(marketTitle(client), wanted) >= 0) {
                    state = State.SCAN_PAGE;
                } else if (clickCategory(client, wanted, gems ? GEMS_ITEM_ID : null)) {
                    waitTicks = 0;
                    state = State.WAIT_CATEGORY;
                } else if (gems) {
                    // without the gems page there is nothing to read, and the last scan beats nothing
                    categoryMissing = true;
                    state = State.CLOSE;
                } else {
                    // no crops button to press, so whatever /bazaar opened on is the crops page
                    state = State.SCAN_PAGE;
                }
            }
            case WAIT_CATEGORY -> {
                waitTicks++;
                if (waitTicks >= getAdaptivePageDelayTicks(client)) {
                    state = State.SCAN_PAGE;
                }
            }
            case SCAN_PAGE -> {
                String key = pageKey(client);
                if (!key.isEmpty() && key.equals(lastPageKey)) {
                    // next page brought back the page we just read, so that was the last one
                    state = State.CLOSE;
                } else {
                    lastPageKey = key;
                    collected.addAll(BazaarFlipScanner.collectCandidatesIncludingBlacklisted(client));
                    pagesScanned++;
                    // a page read is progress, so a long season doesn't run into the timeout
                    timeoutTicks = 0;
                    if (pagesScanned < MAX_PAGES && clickNextPage(client)) {
                        waitTicks = 0;
                        state = State.WAIT_PAGE;
                    } else {
                        state = State.CLOSE;
                    }
                }
            }
            case WAIT_PAGE -> {
                waitTicks++;
                if (waitTicks >= getAdaptivePageDelayTicks(client)) {
                    state = State.SCAN_PAGE;
                }
            }
            case CLOSE -> {
                if (McCompat.screen(client) != null) {
                    McCompat.setScreen(client, null);
                }
                state = State.REPORT;
            }
            case REPORT -> {
                report(client);
                reset();
            }
            default -> {
            }
        }
    }

    // an empty scan keeps whatever was found last time rather than wiping the tab clean
    private static void report(Minecraft client) {
        if (collected.isEmpty()) {
            if (client.player == null) {
                return;
            }
            String what = currentScanMode == ScanMode.GEMS ? "gems" : "crops";
            McCompat.chat(client.player, BazaarChat.prefix().append(Component.literal(categoryMissing
                            ? "Could not find the " + what + " page in /bazaar, so nothing was scanned. Your last scan is still here."
                            : "That /bazaar page had no prices on it, so nothing was scanned.")
                    .withStyle(net.minecraft.ChatFormatting.RED)));
            return;
        }
        List<BazaarFlipScanner.FlipCandidate> results;
        if (currentScanMode == ScanMode.CROPS) {
            BazaarFlipScanner.cacheCropsResults(collected);
            results = BazaarFlipScanner.getLastCropsScan();
        } else {
            BazaarFlipScanner.cacheGemsResults(collected);
            results = BazaarFlipScanner.getLastGemsScan();
        }
        PinnedBazaarHud.updateFromCandidates(results);
        BazaarFlipScanner.reportResults(text -> McCompat.chat(client.player, text), results);
    }

    // what is on this page, so a next page button that goes nowhere is not read twice
    private static String pageKey(Minecraft client) {
        Screen screen = McCompat.screen(client);
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return "";
        }
        StringBuilder key = new StringBuilder();
        for (Slot slot : containerScreen.getMenu().slots) {
            if (slot.container instanceof net.minecraft.world.entity.player.Inventory || slot.getItem().isEmpty()) {
                continue;
            }
            key.append(slot.getItem().getHoverName().getString()).append('|');
        }
        return key.toString();
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
            // never reach into the player's own inventory, and never click something with a price
            // on it: that is an item for sale, not an arrow
            if (slot.getItem().isEmpty() || slot.container instanceof net.minecraft.world.entity.player.Inventory) {
                continue;
            }
            String name = SbText.normalizeText(slot.getItem().getHoverName().getString());
            if (name.contains(NEXT_PAGE_NAME) && !BazaarFlipScanner.hasPrices(client, slot.getItem())) {
                client.gameMode.handleInventoryMouseClick(menu.containerId, i, 0, ClickType.PICKUP, client.player);
                return true;
            }
        }
        return false;
    }

    // finds the category by its name first and only then by the icon it used to have
    private static boolean clickCategory(Minecraft client, String[] names, String fallbackItemId) {
        Screen screen = McCompat.screen(client);
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return false;
        }
        if (client.gameMode == null || client.player == null) {
            return false;
        }
        AbstractContainerMenu menu = containerScreen.getMenu();
        int byName = -1;
        int bestRank = Integer.MAX_VALUE;
        int byIcon = -1;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.getItem().isEmpty() || slot.container instanceof net.minecraft.world.entity.player.Inventory) {
                continue;
            }
            String name = SbText.normalizeText(slot.getItem().getHoverName().getString());
            int rank = isPageButton(name) ? -1 : categoryRank(name, names);
            // an item for sale has a price on it, a category button doesn't
            if (rank >= 0 && rank < bestRank && !BazaarFlipScanner.hasPrices(client, slot.getItem())) {
                bestRank = rank;
                byName = i;
            }
            if (byIcon < 0 && fallbackItemId != null && fallbackItemId.equals(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString())) {
                byIcon = i;
            }
        }
        int target = byName >= 0 ? byName : byIcon;
        if (target < 0) {
            return false;
        }
        client.gameMode.handleInventoryMouseClick(menu.containerId, target, 0, ClickType.PICKUP, client.player);
        return true;
    }

    // a button named for what it holds, and not one of the page arrows. The name comes in already
    // folded by SbText, so the small caps the server writes its menus in read as plain letters.
    static boolean looksLikeCategory(String normalizedName, String[] wanted) {
        return !isPageButton(normalizedName) && categoryRank(normalizedName, wanted) >= 0;
    }

    // which of the wanted words the name carries, as a position in that list, or -1 for none.
    // The list is in order of preference, so an earlier word beats a later one.
    static int categoryRank(String normalizedName, String[] wanted) {
        if (normalizedName == null || normalizedName.isBlank()) {
            return -1;
        }
        for (int i = 0; i < wanted.length; i++) {
            if (normalizedName.contains(wanted[i])) {
                return i;
            }
        }
        return -1;
    }

    static boolean isPageButton(String normalizedName) {
        if (normalizedName == null) {
            return true;
        }
        for (String arrow : PAGE_BUTTONS) {
            if (normalizedName.contains(arrow)) {
                return true;
            }
        }
        return false;
    }

    // the window is titled "Bazaar: Crop Market" while that market is open
    private static String marketTitle(Minecraft client) {
        Screen screen = McCompat.screen(client);
        return screen instanceof AbstractContainerScreen<?> containerScreen
                ? SbText.normalizeText(containerScreen.getTitle().getString())
                : "";
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
        pagesScanned = 0;
        lastPageKey = "";
        categoryMissing = false;
        collected.clear();
    }
}

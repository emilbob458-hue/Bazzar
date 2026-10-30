package bazaar.bazzar.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

final class GuildQuestScanner {
    private enum State {
        IDLE,
        WAIT_FOR_OPEN,
        WAIT_FOR_SCAN,
        SCAN,
        CLOSE,
        REPORT
    }

    private static final String QUEST_COMMAND = "gq";
    private static final int PAGE_DELAY_TICKS = 4;
    private static final int TIMEOUT_TICKS = 140;
    private static final long REFRESH_GRACE_MS = 3_000L;
    private static final long AUTO_RETRY_MS = 60_000L;
    // the /gq timer counts down in whole minutes, so the stored time can be up to a minute early and the
    // first scan after it runs out can still land on the old quests; retry on this gap to catch the rollover
    private static final long REFRESH_RETRY_MS = 15_000L;
    // two quick tries, then back off, so a menu with no timer line in it can't make the scan loop
    private static final int MAX_QUICK_RETRIES = 3;
    // with a guild set an AFK member scans for everyone, but once the quests have been due this long with
    // nothing shared, scan here rather than sitting on stale quests for the whole fallback window
    private static final long SHARE_WAIT_MS = 90_000L;
    // a refresh time this far from the old one is a new set of quests, not the same one counted down
    private static final long NEW_CYCLE_MS = 120_000L;
    private static final Pattern PROGRESS_PATTERN = Pattern.compile("progress\\s*:\\s*([^\\s]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DURATION_PATTERN = Pattern.compile("(\\d+)\\s*([hms])", Pattern.CASE_INSENSITIVE);
    private static final List<QuestEntry> lastEntries = new ArrayList<>();
    private static long questsRefreshAtMs = 0L;
    private static final java.util.Set<String> completedNames = new java.util.HashSet<>();
    private static final QuestLiveTracker LIVE = new QuestLiveTracker();

    private static State state = State.IDLE;
    private static int waitTicks = 0;
    private static int timeoutTicks = 0;
    private static boolean pendingOpen = false;
    private static boolean autoScanEnabled = false;
    private static long lastAutoTriggerMs = 0L;
    private static long dueSinceMs = 0L;
    private static boolean timerCaptured = false;
    private static int blindScans = 0;
    private static boolean settingsDirty = false;
    private static boolean holdOpen = false;
    private static boolean heldOpen = false;

    private GuildQuestScanner() {
    }

    static void triggerManual(Minecraft client) {
        start(client);
    }

    // while the quest key is held the menu stays up, so /gq can be read without it snapping shut
    static void setHoldOpen(boolean held) {
        holdOpen = held;
    }

    // what to do with the /gq menu once the scan has read it
    enum MenuAction {
        KEEP_OPEN,
        READ_AGAIN,
        CLOSE
    }

    static MenuAction menuAction(boolean keyHeld, boolean menuOpen, boolean wasHeld) {
        if (keyHeld && menuOpen) {
            return MenuAction.KEEP_OPEN;
        }
        // a menu that was held open may have had a quest claimed in it, so it is worth one more read
        return wasHeld && menuOpen ? MenuAction.READ_AGAIN : MenuAction.CLOSE;
    }

    static void triggerAuto(Minecraft client) {
        if (!autoScanEnabled) {
            return;
        }
        long now = System.currentTimeMillis();
        if (questsRefreshAtMs > 0L && now < questsRefreshAtMs + REFRESH_GRACE_MS) {
            dueSinceMs = 0L;
            return;
        }
        if (dueSinceMs == 0L) {
            dueSinceMs = now;
        }
        // with a guild set, an AFK member scans for everyone; scan here once their copy clearly isn't coming
        if (GuildShare.isActive() && !GuildShare.fallbackDue() && now - dueSinceMs < SHARE_WAIT_MS) {
            return;
        }
        long retryGap = blindScans >= MAX_QUICK_RETRIES ? AUTO_RETRY_MS : REFRESH_RETRY_MS;
        if (now - lastAutoTriggerMs < retryGap) {
            return;
        }
        if (start(client)) {
            lastAutoTriggerMs = now;
        }
    }

    static String nextAutoScanText() {
        if (!autoScanEnabled) {
            return "Auto scan is off";
        }
        int seconds = getSecondsUntilQuestRefresh();
        if (seconds < 0) {
            return "Right away, to read the refresh timer";
        }
        if (seconds == 0) {
            return "Now, new quests are ready";
        }
        return "When quests refresh, in " + (seconds >= 3600
                ? (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m"
                : (seconds / 60) + "m " + (seconds % 60) + "s");
    }

    static void applyConfig(BazzarConfig.State state) {
        autoScanEnabled = state != null && state.guildQuestAutoScanEnabled;
        settingsDirty = false;
        lastAutoTriggerMs = 0L;
    }

    static void applyConfigToState(BazzarConfig.State state) {
        if (state == null) {
            return;
        }
        state.guildQuestAutoScanEnabled = autoScanEnabled;
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

    static void setAutoScanEnabled(boolean enabled) {
        if (autoScanEnabled == enabled) {
            return;
        }
        autoScanEnabled = enabled;
        settingsDirty = true;
        lastAutoTriggerMs = 0L;
    }

    private static boolean start(Minecraft client) {
        if (state != State.IDLE || client.player == null || client.player.connection == null) {
            return false;
        }
        if (client.getCurrentServer() == null) {
            return false;
        }
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
        LIVE.tick(System.currentTimeMillis());
        if (state == State.IDLE || client.player == null) {
            return;
        }

        timeoutTicks++;
        if (timeoutTicks > TIMEOUT_TICKS && !(state == State.CLOSE && holdOpen)) {
            reset();
            return;
        }

        if (pendingOpen && McCompat.screen(client) == null) {
            pendingOpen = false;
            sendOpenCommand(client);
        }

        switch (state) {
            case WAIT_FOR_OPEN -> {
                if (isContainerOpen(McCompat.screen(client))) {
                    waitTicks = 0;
                    state = State.WAIT_FOR_SCAN;
                }
            }
            case WAIT_FOR_SCAN -> {
                waitTicks++;
                if (waitTicks >= PAGE_DELAY_TICKS) {
                    state = State.SCAN;
                }
            }
            case SCAN -> {
                timerCaptured = false;
                cacheEntries(collectQuestEntries(client));
                // a scan that read a fresh timer settles the cycle; one that didn't gets a few quick retries
                blindScans = timerCaptured ? 0 : blindScans + 1;
                GuildShare.onScanned(lastEntries, questsRefreshAtMs);
                state = State.CLOSE;
            }
            case CLOSE -> {
                boolean menuOpen = isContainerOpen(McCompat.screen(client));
                switch (menuAction(holdOpen, menuOpen, heldOpen)) {
                    case KEEP_OPEN -> {
                        heldOpen = true;
                        timeoutTicks = 0;
                    }
                    case READ_AGAIN -> {
                        cacheEntries(collectQuestEntries(client));
                        GuildShare.onScanned(lastEntries, questsRefreshAtMs);
                        McCompat.setScreen(client, null);
                        state = State.REPORT;
                    }
                    case CLOSE -> {
                        if (McCompat.screen(client) != null) {
                            McCompat.setScreen(client, null);
                        }
                        state = State.REPORT;
                    }
                }
            }
            case REPORT -> {
                reset();
            }
            default -> {
            }
        }
    }

    static List<QuestEntry> getLastEntries() {
        return lastEntries;
    }

    // "GUILDS • Guild quest complete: Kill 1,000 Bats" marks that quest done without reopening /gq
    static void onChatLine(String line) {
        if (line == null) {
            return;
        }
        String text = SbText.stripFormattingCodes(line);
        int idx = text.toLowerCase(Locale.ROOT).indexOf("guild quest complete:");
        if (idx < 0) {
            return;
        }
        String finished = normalize(text.substring(idx + "guild quest complete:".length()));
        if (finished.isEmpty()) {
            return;
        }
        completedNames.add(finished);
        for (int i = 0; i < lastEntries.size(); i++) {
            QuestEntry entry = lastEntries.get(i);
            if (normalize(entry.name).equals(finished)) {
                lastEntries.set(i, new QuestEntry(entry.name, entry.rightValue, entry.rightValue, true));
                return;
            }
        }
    }

    static boolean isScanning() {
        return state != State.IDLE;
    }

    // quests shared by a guild member's scan; quests that finished since then stay done
    static void applyShared(List<String[]> quests, long refreshAtMs) {
        // the cycle is settled first, so a new one has already dropped the old "complete" names
        if (refreshAtMs > 0L) {
            setRefreshAt(refreshAtMs);
            blindScans = 0;
        }
        lastEntries.clear();
        for (String[] quest : quests) {
            boolean done = completedNames.contains(normalize(quest[0]));
            lastEntries.add(new QuestEntry(quest[0], done ? quest[2] : quest[1], quest[2], done));
        }
        restartLive();
    }

    static List<QuestEntry> getOpenEntries() {
        List<QuestEntry> open = new ArrayList<>();
        for (QuestEntry entry : lastEntries) {
            if (!entry.done()) {
                open.add(entry);
            }
        }
        return open;
    }

    static boolean allDone() {
        return !lastEntries.isEmpty() && getOpenEntries().isEmpty();
    }

    static String refreshCountdownText() {
        int seconds = getSecondsUntilQuestRefresh();
        if (seconds < 0) {
            return "?";
        }
        if (seconds == 0) {
            return "Ready";
        }
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int secs = seconds % 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%dh %02dm %02ds", hours, minutes, secs);
        }
        return String.format(Locale.ROOT, "%dm %02ds", minutes, secs);
    }

    // how much this player has added to the guild quests since the last check
    static double consumeLiveContribution() {
        return LIVE.consumeContributed();
    }

    static Map<String, Long> liveCounts() {
        return LIVE.myCounts();
    }

    static void onGuildTotals(Map<String, Long> totals) {
        LIVE.onPooled(totals);
    }

    // when the current quests run out; also tells the guild HUD that a new set of quests started
    static long questsRefreshAtMs() {
        return questsRefreshAtMs;
    }

    static int getSecondsUntilQuestRefresh() {
        if (questsRefreshAtMs <= 0L) {
            return -1;
        }
        long remainingMs = questsRefreshAtMs - System.currentTimeMillis();
        return (int) Math.max(0L, (remainingMs + 999L) / 1000L);
    }

    private static void sendOpenCommand(Minecraft client) {
        CommandSender.send(client, QUEST_COMMAND);
    }

    private static boolean isContainerOpen(Screen screen) {
        return screen instanceof AbstractContainerScreen<?>;
    }

    private static List<QuestEntry> collectQuestEntries(Minecraft client) {
        Screen screen = McCompat.screen(client);
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return List.of();
        }
        AbstractContainerMenu menu = containerScreen.getMenu();
        Map<String, QuestEntry> deduped = new LinkedHashMap<>();

        for (Slot slot : menu.slots) {
            if (slot.getItem().isEmpty()) {
                continue;
            }
            if (slot.container instanceof net.minecraft.world.entity.player.Inventory) {
                continue;
            }
            ItemStack stack = slot.getItem();
            String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (!looksLikeQuestBook(itemId)) {
                captureRefreshTimer(client, stack);
                continue;
            }
            QuestEntry entry = toQuestEntry(client, stack);
            if (entry == null) {
                continue;
            }
            String key = normalize(entry.name);
            if (!key.isBlank() && !deduped.containsKey(key)) {
                deduped.put(key, entry);
            }
        }
        return new ArrayList<>(deduped.values());
    }

    private static void captureRefreshTimer(Minecraft client, ItemStack stack) {
        try {
            List<Component> tooltip = stack.getTooltipLines(
                    net.minecraft.world.item.Item.TooltipContext.of(client.level),
                    client.player,
                    net.minecraft.world.item.TooltipFlag.Default.NORMAL
            );
            List<String> lines = new ArrayList<>();
            for (Component part : tooltip) {
                if (part == null) {
                    continue;
                }
                String cleaned = cleanTooltipLine(part.getString());
                if (!cleaned.isBlank()) {
                    lines.add(cleaned);
                }
            }
            for (int i = 0; i < lines.size(); i++) {
                if (!lines.get(i).toLowerCase(Locale.ROOT).contains("new quests available")) {
                    continue;
                }
                long durationMs = parseDurationMs(lines.get(i));
                if (durationMs <= 0L && i + 1 < lines.size()) {
                    durationMs = parseDurationMs(lines.get(i + 1));
                }
                if (durationMs > 0L) {
                    setRefreshAt(System.currentTimeMillis() + durationMs);
                    timerCaptured = true;
                }
                return;
            }
        } catch (Exception ignored) {
        }
    }

    // a new cycle means the quests that chat called complete belong to the old set, so forget them
    private static void setRefreshAt(long refreshAtMs) {
        if (Math.abs(refreshAtMs - questsRefreshAtMs) > NEW_CYCLE_MS) {
            completedNames.clear();
            LIVE.newCycle();
        }
        questsRefreshAtMs = refreshAtMs;
    }

    static long parseDurationMs(String line) {
        long totalSeconds = 0L;
        Matcher matcher = DURATION_PATTERN.matcher(line);
        while (matcher.find()) {
            long value = Long.parseLong(matcher.group(1));
            switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                case "h" -> totalSeconds += value * 3600L;
                case "m" -> totalSeconds += value * 60L;
                case "s" -> totalSeconds += value;
            }
        }
        return totalSeconds * 1000L;
    }

    private static boolean looksLikeQuestBook(String itemId) {
        if (itemId == null) {
            return false;
        }
        String normalized = itemId.toLowerCase(Locale.ROOT);
        return normalized.contains("book");
    }

    private static QuestEntry toQuestEntry(Minecraft client, ItemStack stack) {
        String fallbackName = stack.getHoverName().getString();
        if (fallbackName == null || fallbackName.isBlank()) {
            return null;
        }
        String name = fallbackName;
        String left = "N/A";
        String right = "N/A";
        try {
            List<Component> tooltip = stack.getTooltipLines(
                    net.minecraft.world.item.Item.TooltipContext.of(client.level),
                    client.player,
                    net.minecraft.world.item.TooltipFlag.Default.NORMAL
            );
            List<String> lines = new ArrayList<>();
            for (Component part : tooltip) {
                if (part == null) {
                    continue;
                }
                String cleaned = cleanTooltipLine(part.getString());
                if (!cleaned.isBlank()) {
                    lines.add(cleaned);
                }
            }

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.equalsIgnoreCase("quest") && i + 1 < lines.size()) {
                    String questName = cleanTooltipLine(lines.get(i + 1));
                    if (!questName.isBlank()) {
                        name = questName;
                    }
                    continue;
                }

                Matcher progressMatcher = PROGRESS_PATTERN.matcher(line);
                if (!progressMatcher.find()) {
                    continue;
                }

                String progress = progressMatcher.group(1);
                String[] split = progress.split("/");
                if (split.length == 2) {
                    String parsedLeft = split[0].trim();
                    String parsedRight = split[1].trim();
                    left = parsedLeft.isBlank() ? "N/A" : parsedLeft;
                    right = parsedRight.isBlank() ? "N/A" : parsedRight;
                } else if (!progress.isBlank()) {
                    left = progress.trim();
                }
                break;
            }
        } catch (Exception ignored) {
        }
        return new QuestEntry(name, left, right);
    }

    private static String cleanTooltipLine(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        while (!trimmed.isEmpty()) {
            char first = trimmed.charAt(0);
            if (Character.isLetterOrDigit(first) || first == '?' || first == '#') {
                break;
            }
            trimmed = trimmed.substring(1).trim();
        }
        return trimmed;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static void cacheEntries(List<QuestEntry> scanned) {
        lastEntries.clear();
        if (scanned != null) {
            lastEntries.addAll(scanned);
        }
        restartLive();
    }

    private static void restartLive() {
        List<String[]> quests = new ArrayList<>();
        for (QuestEntry entry : lastEntries) {
            quests.add(new String[]{entry.name, entry.leftValue, entry.rightValue});
        }
        LIVE.onScan(quests);
    }

    // ---- live progress between scans ----

    // the progress to show: the last scan plus what MPS/BPS or counted kills and breaks added since
    static String liveLeftValue(QuestEntry entry) {
        double live = entry.done() ? -1d : LIVE.live(entry.name);
        return live < 0d ? entry.leftValue : QuestLiveTracker.formatLike(entry.leftValue, entry.rightValue, live);
    }

    static boolean liveDone(QuestEntry entry) {
        if (entry.done()) {
            return true;
        }
        double required = QuestLiveTracker.amount(entry.rightValue);
        return required > 0d && LIVE.live(entry.name) >= required;
    }

    static boolean isLive(QuestEntry entry) {
        return LIVE.isActive(entry.name, System.currentTimeMillis());
    }

    static long liveEtaSeconds(QuestEntry entry) {
        return LIVE.etaSeconds(entry.name, System.currentTimeMillis());
    }

    static void onActionBar(String line) {
        long now = System.currentTimeMillis();
        LIVE.onActionBar(line, now);
        // "+20 Paper (285k)" names the farm's mob drop, which tells us the mob and the crop of that farm
        for (String gain : QuestLiveTracker.gains(line)) {
            String mob = FarmMobScanner.mobForDrop(gain);
            if (mob != null) {
                LIVE.onSeen(QuestLiveTracker.Kind.KILL, mob, now);
                LIVE.onSeen(QuestLiveTracker.Kind.FARM, FarmMobScanner.cropForMob(mob), now);
            }
        }
    }

    static void onMobHit(net.minecraft.world.entity.Entity entity) {
        LIVE.onSure(QuestLiveTracker.Kind.KILL, mobNames(entity), System.currentTimeMillis());
    }

    static void onMobKilled(net.minecraft.world.entity.Entity entity) {
        LIVE.onCounted(QuestLiveTracker.Kind.KILL, 1L, mobNames(entity), System.currentTimeMillis());
    }

    static void onBlockBroken(String blockName) {
        LIVE.onCounted(QuestLiveTracker.Kind.FARM, 1L, List.of(blockName), System.currentTimeMillis());
    }

    private static List<String> mobNames(net.minecraft.world.entity.Entity entity) {
        List<String> names = new ArrayList<>();
        names.add(entity.getType().getDescription().getString());
        if (entity.getCustomName() != null) {
            names.add(entity.getCustomName().getString());
        }
        names.add(entity.getName().getString());
        return names;
    }

    private static void reset() {
        state = State.IDLE;
        waitTicks = 0;
        timeoutTicks = 0;
        pendingOpen = false;
        heldOpen = false;
    }

    static final class QuestEntry {
        private final String name;
        private final String leftValue;
        private final String rightValue;
        private final boolean completed;

        private QuestEntry(String name, String leftValue, String rightValue) {
            this(name, leftValue, rightValue, false);
        }

        private QuestEntry(String name, String leftValue, String rightValue, boolean completed) {
            this.name = name;
            this.leftValue = leftValue;
            this.rightValue = rightValue;
            this.completed = completed;
        }

        String name() {
            return name;
        }

        String leftValue() {
            return leftValue;
        }

        String rightValue() {
            return rightValue;
        }

        boolean done() {
            if (completed) {
                return true;
            }
            if ("N/A".equals(leftValue) || "N/A".equals(rightValue)) {
                return false;
            }
            double progress = SbText.firstAmount(leftValue.toLowerCase(Locale.ROOT));
            double required = SbText.firstAmount(rightValue.toLowerCase(Locale.ROOT));
            return required > 0d && progress >= required;
        }
    }
}

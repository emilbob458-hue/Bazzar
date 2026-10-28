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
    private static final int DEFAULT_AUTO_INTERVAL_SECONDS = 180;
    private static final int MIN_AUTO_INTERVAL_SECONDS = 15;
    private static final int MAX_AUTO_INTERVAL_SECONDS = 3600;
    private static final long REFRESH_GRACE_MS = 3_000L;
    private static final long AUTO_RETRY_MS = 60_000L;
    private static final Pattern PROGRESS_PATTERN = Pattern.compile("progress\\s*:\\s*([^\\s]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DURATION_PATTERN = Pattern.compile("(\\d+)\\s*([hms])", Pattern.CASE_INSENSITIVE);
    private static final List<QuestEntry> lastEntries = new ArrayList<>();
    private static long questsRefreshAtMs = 0L;

    private static State state = State.IDLE;
    private static int waitTicks = 0;
    private static int timeoutTicks = 0;
    private static boolean pendingOpen = false;
    private static boolean autoScanEnabled = false;
    private static int autoScanIntervalSeconds = DEFAULT_AUTO_INTERVAL_SECONDS;
    private static long lastAutoTriggerMs = 0L;
    private static boolean settingsDirty = false;

    private GuildQuestScanner() {
    }

    static void triggerManual(Minecraft client) {
        start(client);
    }

    static void triggerAuto(Minecraft client) {
        if (!autoScanEnabled) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean due = questsRefreshAtMs <= 0L || now >= questsRefreshAtMs + REFRESH_GRACE_MS;
        if (!due || now - lastAutoTriggerMs < AUTO_RETRY_MS) {
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
        autoScanIntervalSeconds = clampIntervalSeconds(state != null ? state.guildQuestAutoScanIntervalSeconds : DEFAULT_AUTO_INTERVAL_SECONDS);
        settingsDirty = false;
        lastAutoTriggerMs = 0L;
    }

    static void applyConfigToState(BazzarConfig.State state) {
        if (state == null) {
            return;
        }
        state.guildQuestAutoScanEnabled = autoScanEnabled;
        state.guildQuestAutoScanIntervalSeconds = autoScanIntervalSeconds;
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

    private static int clampIntervalSeconds(int seconds) {
        return Math.max(MIN_AUTO_INTERVAL_SECONDS, Math.min(MAX_AUTO_INTERVAL_SECONDS, seconds));
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
                cacheEntries(collectQuestEntries(client));
                state = State.CLOSE;
            }
            case CLOSE -> {
                if (McCompat.screen(client) != null) {
                    McCompat.setScreen(client, null);
                }
                state = State.REPORT;
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
                    questsRefreshAtMs = System.currentTimeMillis() + durationMs;
                }
                return;
            }
        } catch (Exception ignored) {
        }
    }

    private static long parseDurationMs(String line) {
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
        if (scanned == null || scanned.isEmpty()) {
            return;
        }
        lastEntries.addAll(scanned);
    }

    private static void reset() {
        state = State.IDLE;
        waitTicks = 0;
        timeoutTicks = 0;
        pendingOpen = false;
    }

    static final class QuestEntry {
        private final String name;
        private final String leftValue;
        private final String rightValue;

        private QuestEntry(String name, String leftValue, String rightValue) {
            this.name = name;
            this.leftValue = leftValue;
            this.rightValue = rightValue;
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
            if ("N/A".equals(leftValue) || "N/A".equals(rightValue)) {
                return false;
            }
            double progress = SbText.firstAmount(leftValue.toLowerCase(Locale.ROOT));
            double required = SbText.firstAmount(rightValue.toLowerCase(Locale.ROOT));
            return required > 0d && progress >= required;
        }
    }
}

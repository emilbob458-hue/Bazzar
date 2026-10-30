package bazaar.bazzar.client;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;

final class ChatCommandTriggerController {
    static final int SLOT_COUNT = 8;
    // every trigger waits this long before it can fire again, /launchtizzle included
    static final long COOLDOWN_MS = 5_000L;
    // one person repeating the trigger is paced by the same cooldown as everyone else, so the
    // five seconds above is what you actually get whoever says it
    private static final long SENDER_COOLDOWN_MS = COOLDOWN_MS;
    static final long FIRE_WINDOW_MS = 30_000L;
    // enough fires in the window for the cooldown above to be the thing that actually paces them
    static final int MAX_FIRES_PER_WINDOW = (int) (FIRE_WINDOW_MS / COOLDOWN_MS);
    private static final int MAX_QUEUED = 4;
    private static final int SEND_SPACING_TICKS = 10;

    private record Pending(String command, long sendAtMs) {
    }

    private static final String[] commandsToSend = new String[SLOT_COUNT];
    private static final String[] triggerMessages = new String[SLOT_COUNT];
    private static final long[] slotLastSentMs = new long[SLOT_COUNT];
    private static final ArrayDeque<Pending> sendQueue = new ArrayDeque<>();
    // spam guard: someone repeating the trigger must not make us spam commands and get kicked
    private static final ArrayDeque<Long> recentFires = new ArrayDeque<>();
    private static final Map<String, Long> senderLastFiredMs = new HashMap<>();
    private static int ticksUntilNextSend = 0;

    private ChatCommandTriggerController() {
    }

    static void applyConfig(BazzarConfig.State state) {
        clearAllSlots();
        sendQueue.clear();
        ticksUntilNextSend = 0;
        if (state == null) {
            return;
        }
        List<String> triggers = state.chatTriggerMessages;
        List<String> commands = state.chatTriggerCommands;
        for (int i = 0; i < SLOT_COUNT; i++) {
            String trigger = triggers != null && i < triggers.size() ? triggers.get(i) : "";
            String command = commands != null && i < commands.size() ? commands.get(i) : "";
            triggerMessages[i] = trigger == null ? "" : trigger;
            commandsToSend[i] = command == null ? "" : command;
        }
    }

    static void setCommandToSend(int slot, String command) {
        if (slot < 0 || slot >= SLOT_COUNT) {
            return;
        }
        commandsToSend[slot] = command == null ? "" : command;
    }

    static void setTriggerMessage(int slot, String trigger) {
        if (slot < 0 || slot >= SLOT_COUNT) {
            return;
        }
        triggerMessages[slot] = trigger == null ? "" : trigger;
    }

    static String getCommandToSend(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) {
            return "";
        }
        return commandsToSend[slot];
    }

    static String getTriggerMessage(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) {
            return "";
        }
        return triggerMessages[slot];
    }

    static void onChatLine(Minecraft client, String line, String senderName) {
        if (client == null || client.player == null || client.player.connection == null) {
            return;
        }
        if (line == null || line.isBlank()) {
            return;
        }
        // only look at what was said, so a nickname like "hotdogckemi" can't fire a trigger
        int bodyStart = line.indexOf('»');
        String lower = (bodyStart >= 0 ? line.substring(bodyStart + 1) : line).toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        String normalizedSender = normalizePlayerName(senderName);
        String spamKey = !normalizedSender.isEmpty() ? normalizedSender
                : normalizePlayerName(bodyStart >= 0 ? line.substring(0, bodyStart) : "");
        while (!recentFires.isEmpty() && now - recentFires.peekFirst() > FIRE_WINDOW_MS) {
            recentFires.pollFirst();
        }
        senderLastFiredMs.values().removeIf(firedMs -> now - firedMs > SENDER_COOLDOWN_MS);
        List<Boolean> selfOnlyFlags = BazzarConfig.load().chatTriggerSelfOnly;
        Boolean fromSelfOrAltsCache = null;

        for (int i = 0; i < SLOT_COUNT; i++) {
            TriggerRule rule = parseTriggerRule(triggerMessages[i]);
            String cmd = commandsToSend[i];
            if (rule.triggerText().isBlank() || cmd == null || cmd.isBlank()) {
                continue;
            }
            if (!readyToFire(slotLastSentMs[i], now)) {
                continue;
            }
            boolean slotSelfOnly = selfOnlyFlags != null && i < selfOnlyFlags.size() && Boolean.TRUE.equals(selfOnlyFlags.get(i));
            if (slotSelfOnly) {
                if (fromSelfOrAltsCache == null) {
                    fromSelfOrAltsCache = isFromSelfOrAlts(client, line, normalizedSender);
                }
                if (!fromSelfOrAltsCache) {
                    continue;
                }
            }
            if (!rule.allowedSenders().isEmpty()) {
                if (normalizedSender.isEmpty() || !rule.allowedSenders().contains(normalizedSender)) {
                    continue;
                }
            }
            String lowerTrigger = rule.triggerText().toLowerCase(Locale.ROOT);
            if (!lower.contains(lowerTrigger)) {
                continue;
            }
            String senderSlotKey = i + ":" + spamKey;
            if (senderLastFiredMs.containsKey(senderSlotKey)
                    || recentFires.size() >= MAX_FIRES_PER_WINDOW || sendQueue.size() >= MAX_QUEUED) {
                continue;
            }
            // each account waits its turn, so the alts fire one after another down the list
            long readyAt = now + staggerDelayMs(altIndex(client));
            boolean queued = false;
            for (String part : cmd.split(";")) {
                String trimmed = part.trim();
                if (trimmed.startsWith("/")) {
                    trimmed = trimmed.substring(1).trim();
                }
                if (trimmed.isBlank()) {
                    continue;
                }
                sendQueue.addLast(new Pending(trimmed, readyAt));
                queued = true;
            }
            if (queued) {
                slotLastSentMs[i] = now;
                senderLastFiredMs.put(senderSlotKey, now);
                recentFires.addLast(now);
            }
        }
    }

    // a trigger that fired at lastSentMs may go again once the cooldown has run out
    static boolean readyToFire(long lastSentMs, long now) {
        return now - lastSentMs >= COOLDOWN_MS;
    }

    // the first account goes straight away, the next waits one cooldown, and so on down the list
    static long staggerDelayMs(int altIndex) {
        return Math.max(0, altIndex) * COOLDOWN_MS;
    }

    // where this account sits in the alt list: the owner first, then whichever member slots are
    // filled in. An account that isn't on the list is on its own, so it goes first.
    static int altIndex(String self, String owner, List<String> members) {
        String me = normalizePlayerName(self);
        if (me.isEmpty()) {
            return 0;
        }
        String head = normalizePlayerName(owner);
        if (!head.isEmpty() && head.equals(me)) {
            return 0;
        }
        int index = head.isEmpty() ? 0 : 1;
        if (members != null) {
            for (String member : members) {
                String name = normalizePlayerName(member);
                if (name.isEmpty()) {
                    continue;
                }
                if (name.equals(me)) {
                    return index;
                }
                index++;
            }
        }
        return 0;
    }

    private static int altIndex(Minecraft client) {
        if (client == null || client.player == null) {
            return 0;
        }
        List<String> members = new java.util.ArrayList<>();
        for (int i = 0; i < ShardPayController.MEMBER_SLOT_COUNT; i++) {
            members.add(ShardPayController.getMember(i));
        }
        return altIndex(client.player.getName().getString(), ShardPayController.getOwner(), members);
    }

    static void tick(Minecraft client) {
        if (ticksUntilNextSend > 0) {
            ticksUntilNextSend--;
        }
        Pending next = sendQueue.peekFirst();
        if (next == null) {
            return;
        }
        if (client == null || client.player == null || client.player.connection == null) {
            sendQueue.clear();
            return;
        }
        // nothing goes out before this account's turn comes round
        if (ticksUntilNextSend > 0 || System.currentTimeMillis() < next.sendAtMs()) {
            return;
        }
        sendQueue.pollFirst();
        if (!next.command().isBlank()) {
            CommandSender.send(client, next.command());
        }
        ticksUntilNextSend = SEND_SPACING_TICKS;
    }

    private static void clearAllSlots() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            commandsToSend[i] = "";
            triggerMessages[i] = "";
            slotLastSentMs[i] = 0L;
        }
    }

    private static boolean isFromSelfOrAlts(Minecraft client, String line, String normalizedSender) {
        Set<String> allowed = selfAndAltNames(client);
        if (allowed.isEmpty()) {
            return false;
        }
        if (!normalizedSender.isEmpty()) {
            return allowed.contains(normalizedSender);
        }
        String prefix = line;
        int sep = firstSeparatorIndex(line);
        if (sep >= 0) {
            prefix = line.substring(0, sep);
        }
        String normalizedPrefix = normalizePlayerName(prefix);
        for (String name : allowed) {
            if (!name.isEmpty() && normalizedPrefix.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private static int firstSeparatorIndex(String line) {
        int best = -1;
        for (String sep : new String[]{"»", ":", ">"}) {
            int idx = line.indexOf(sep);
            if (idx >= 0 && (best < 0 || idx < best)) {
                best = idx;
            }
        }
        return best;
    }

    private static Set<String> selfAndAltNames(Minecraft client) {
        Set<String> names = new HashSet<>();
        if (client != null && client.player != null) {
            String self = normalizePlayerName(client.player.getName().getString());
            if (!self.isEmpty()) {
                names.add(self);
            }
        }
        String owner = normalizePlayerName(ShardPayController.getOwner());
        if (!owner.isEmpty()) {
            names.add(owner);
        }
        for (int i = 0; i < ShardPayController.MEMBER_SLOT_COUNT; i++) {
            String member = normalizePlayerName(ShardPayController.getMember(i));
            if (!member.isEmpty()) {
                names.add(member);
            }
        }
        return names;
    }

    private static TriggerRule parseTriggerRule(String rawRule) {
        if (rawRule == null) {
            return new TriggerRule("", Set.of());
        }
        String trimmed = rawRule.trim();
        if (!trimmed.startsWith("@")) {
            return new TriggerRule(trimmed, Set.of());
        }
        int separator = trimmed.indexOf(' ');
        if (separator <= 1) {
            return new TriggerRule("", Set.of());
        }
        String namesRaw = trimmed.substring(1, separator).trim();
        String trigger = trimmed.substring(separator + 1).trim();
        Set<String> allowedSenders = new HashSet<>();
        for (String part : namesRaw.split(",")) {
            String normalized = normalizePlayerName(part);
            if (!normalized.isEmpty()) {
                allowedSenders.add(normalized);
            }
        }
        return new TriggerRule(trigger, allowedSenders);
    }

    private static String normalizePlayerName(String name) {
        if (name == null) {
            return "";
        }
        String normalized = stripFormattingCodes(name).trim().toLowerCase(Locale.ROOT);
        while (normalized.startsWith("@")) {
            normalized = normalized.substring(1).trim();
        }
        if (normalized.contains("]")) {
            int idx = normalized.lastIndexOf(']');
            if (idx >= 0 && idx + 1 < normalized.length()) {
                normalized = normalized.substring(idx + 1).trim();
            }
        }
        return normalized.replaceAll("[^a-z0-9_]", "");
    }

    private static String stripFormattingCodes(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '§' && i + 1 < raw.length()) {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private record TriggerRule(String triggerText, Set<String> allowedSenders) {
    }
}

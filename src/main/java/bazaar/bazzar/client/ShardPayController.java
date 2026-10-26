package bazaar.bazzar.client;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

final class ShardPayController {
    static final int MEMBER_SLOT_COUNT = 6;

    private static final long CHECK_INTERVAL_MS = 30_000L;
    private static final long PAY_COOLDOWN_MS = 180_000L;
    private static final double MIN_AUTO_PAY = 5.0d;
    private static final int PAY_GAP_TICKS = 60;
    private static final int PAY_GAP_JITTER_TICKS = 20;
    private static final long ALT_LINE_DEDUPE_MS = 1_500L;
    private static final Pattern ALT_RECEIVE_PATTERN = Pattern.compile(
            "(?i)^\\s*you\\s+received\\s+[^\\w\\s]?\\s*([\\d,.]+[a-z]{0,2})\\s+(shards?|gems?|cobble)\\s+from\\s+(\\w{1,16})");

    private static boolean enabled = false;
    private static String owner = "";
    private static final String[] members = new String[MEMBER_SLOT_COUNT];
    private static long lastCheckMs = 0L;
    private static long lastPayMs = 0L;
    private static int scoreboardFailStreak = 0;
    private static final EnumSet<NoticeKind> shownNotices = EnumSet.noneOf(NoticeKind.class);
    private static final java.util.ArrayDeque<String> payQueue = new java.util.ArrayDeque<>();
    private static int payQueueTicks = 0;
    private static String lastAltLine = "";
    private static long lastAltLineMs = 0L;

    private ShardPayController() {
    }

    static boolean isLocalPayer(Minecraft client, BazzarConfig.State state) {
        if (state == null) {
            return false;
        }
        return roleFor(client, state.shardPayOwner, state.shardPayMembers) == Role.PAYER;
    }

    static boolean isLocalOwner(Minecraft client, BazzarConfig.State state) {
        if (state == null) {
            return false;
        }
        return roleFor(client, state.shardPayOwner, state.shardPayMembers) == Role.OWNER;
    }

    static void applyConfig(BazzarConfig.State state) {
        for (int i = 0; i < MEMBER_SLOT_COUNT; i++) {
            members[i] = "";
        }
        enabled = false;
        owner = "";
        lastCheckMs = 0L;
        lastPayMs = 0L;
        scoreboardFailStreak = 0;
        shownNotices.clear();
        payQueue.clear();
        payQueueTicks = 0;
        if (state == null) {
            return;
        }
        enabled = state.shardPayEnabled;
        owner = state.shardPayOwner == null ? "" : state.shardPayOwner;
        List<String> saved = state.shardPayMembers;
        for (int i = 0; i < MEMBER_SLOT_COUNT; i++) {
            String member = saved != null && i < saved.size() ? saved.get(i) : "";
            members[i] = member == null ? "" : member;
        }
    }

    static void applyConfigToState(BazzarConfig.State state) {
        if (state == null) {
            return;
        }
        state.shardPayEnabled = enabled;
        state.shardPayOwner = owner;
        state.shardPayMembers = new java.util.ArrayList<>(List.of(
                members[0], members[1], members[2], members[3], members[4], members[5]));
    }

    static boolean isEnabled() {
        return enabled;
    }

    static void setEnabled(boolean value) {
        enabled = value;
        lastCheckMs = 0L;
    }

    static String getOwner() {
        return owner;
    }

    static void setOwner(String value) {
        owner = value == null ? "" : value;
    }

    static String getMember(int slot) {
        if (slot < 0 || slot >= MEMBER_SLOT_COUNT) {
            return "";
        }
        return members[slot];
    }

    static void setMember(int slot, String value) {
        if (slot < 0 || slot >= MEMBER_SLOT_COUNT) {
            return;
        }
        members[slot] = value == null ? "" : value;
    }

    static void onChatLine(Minecraft client, String message) {
        if (client == null || client.player == null || message == null) {
            return;
        }
        BazzarConfig.State state = BazzarConfig.load();
        if (!isLocalOwner(client, state)) {
            return;
        }
        String clean = SbText.stripSprites(message);
        Matcher matcher = ALT_RECEIVE_PATTERN.matcher(clean);
        if (!matcher.find()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (clean.equals(lastAltLine) && now - lastAltLineMs < ALT_LINE_DEDUPE_MS) {
            return;
        }
        lastAltLine = clean;
        lastAltLineMs = now;
        String sender = matcher.group(3);
        if (!isListedMember(state, sender)) {
            return;
        }
        Double amount = ShardPayoutController.parseCompactNumber(matcher.group(1));
        if (amount == null || amount <= 0.0d) {
            return;
        }
        String unit = matcher.group(2).toLowerCase(Locale.ROOT);
        String kind = unit.startsWith("shard") ? "shard" : unit.startsWith("gem") ? "gem" : "cobble";
        PayoutLog.addFromAlt(kind, sender, amount);
    }

    private static boolean isListedMember(BazzarConfig.State state, String name) {
        String normalized = normalizeName(name);
        if (normalized.isEmpty() || state.shardPayMembers == null) {
            return false;
        }
        for (String member : state.shardPayMembers) {
            if (normalized.equals(normalizeName(member))) {
                return true;
            }
        }
        return false;
    }

    static void triggerAuto(Minecraft client) {
        drainQueue(client);
        BazzarConfig.State state = BazzarConfig.load();
        if (state == null || !state.shardPayEnabled) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCheckMs < CHECK_INTERVAL_MS || now - lastPayMs < PAY_COOLDOWN_MS) {
            return;
        }
        lastCheckMs = now;
        if (client == null || client.player == null || client.player.connection == null) {
            return;
        }
        String configOwner = state.shardPayOwner == null ? "" : state.shardPayOwner;
        Role role = roleFor(client, configOwner, state.shardPayMembers);
        if (role == Role.NO_OWNER) {
            noticeOnce(client, NoticeKind.NO_OWNER, "enabled but no owner name is set, add one in the Shard Pay tab.");
            return;
        }
        if (role == Role.OWNER) {
            return;
        }
        if (role == Role.NOT_LISTED) {
            noticeOnce(client, NoticeKind.NOT_LISTED,
                    client.player.getName().getString() + " is not in the payer list, add this name in the Shard Pay tab.");
            return;
        }
        String target = cleanOwner(configOwner);
        if (target.isEmpty()) {
            return;
        }
        List<ScoreboardReader.Line> lines = ScoreboardReader.readSidebar(client);
        double shards = shardsFromLines(lines);
        if (shards < 0d) {
            scoreboardFailStreak++;
            if (scoreboardFailStreak >= 2) {
                noticeOnce(client, NoticeKind.NO_SHARD_LINE, "couldn't find a shard line on the scoreboard.");
            }
        } else {
            scoreboardFailStreak = 0;
        }
        boolean queued = false;
        if (shards >= MIN_AUTO_PAY) {
            payQueue.addLast("shardpay " + formatAmount(shards) + " " + target);
            queued = true;
        }
        if (state.cobblePayEnabled) {
            double cobble = cobbleFromLines(lines);
            if (cobble >= MIN_AUTO_PAY) {
                payQueue.addLast("cobblepay " + formatWhole(cobble) + " " + target);
                queued = true;
            }
        }
        if (state.gemPayEnabled) {
            double gems = gemsFromLines(lines);
            if (gems >= MIN_AUTO_PAY) {
                payQueue.addLast("gempay " + formatWhole(gems) + " " + target);
                queued = true;
            }
        }
        if (queued) {
            lastPayMs = now;
        }
    }

    private static void drainQueue(Minecraft client) {
        if (payQueueTicks > 0) {
            payQueueTicks--;
        }
        if (payQueue.isEmpty()) {
            return;
        }
        if (client == null || client.player == null || client.player.connection == null) {
            payQueue.clear();
            return;
        }
        if (payQueueTicks > 0) {
            return;
        }
        String command = payQueue.pollFirst();
        if (command != null && !command.isBlank()) {
            client.player.connection.sendCommand(command);
        }
        payQueueTicks = PAY_GAP_TICKS + java.util.concurrent.ThreadLocalRandom.current().nextInt(PAY_GAP_JITTER_TICKS + 1);
    }

    private static String cleanOwner(String raw) {
        String target = raw == null ? "" : raw.trim();
        if (target.startsWith("/")) {
            target = target.substring(1).trim();
        }
        return target;
    }

    private static void noticeOnce(Minecraft client, NoticeKind kind, String text) {
        if (client == null || client.player == null || !shownNotices.add(kind)) {
            return;
        }
        client.player.displayClientMessage(
                Component.literal("[Bazaar] Shard Pay: " + text).withStyle(ChatFormatting.YELLOW),
                false
        );
    }

    static String payNow(Minecraft client) {
        if (client == null || client.player == null || client.player.connection == null) {
            return "Not connected to a server.";
        }
        Role role = localRole(client);
        if (role == Role.NO_OWNER) {
            return "Set an owner name first.";
        }
        if (role == Role.OWNER) {
            return "This account is the owner, it receives and never pays.";
        }
        if (role == Role.NOT_LISTED) {
            return "This account is not in the payer list.";
        }
        String target = cleanOwner(owner);
        if (target.isEmpty()) {
            return "Set an owner name first.";
        }
        BazzarConfig.State state = BazzarConfig.load();
        List<ScoreboardReader.Line> lines = ScoreboardReader.readSidebar(client);
        double shards = shardsFromLines(lines);
        StringBuilder summary = new StringBuilder();
        if (shards >= 0.01d) {
            payQueue.addLast("shardpay " + formatAmount(shards) + " " + target);
            summary.append(formatAmount(shards)).append(" shards");
        }
        if (state != null && state.cobblePayEnabled) {
            double cobble = cobbleFromLines(lines);
            if (cobble >= 1.0d) {
                payQueue.addLast("cobblepay " + formatWhole(cobble) + " " + target);
                if (summary.length() > 0) {
                    summary.append(", ");
                }
                summary.append(SbText.formatCompact(cobble)).append(" cobble");
            }
        }
        if (state != null && state.gemPayEnabled) {
            double gems = gemsFromLines(lines);
            if (gems >= 1.0d) {
                payQueue.addLast("gempay " + formatWhole(gems) + " " + target);
                if (summary.length() > 0) {
                    summary.append(", ");
                }
                summary.append(SbText.formatCompact(gems)).append(" gems");
            }
        }
        if (summary.length() == 0) {
            return shards < 0d
                    ? "Couldn't read anything to pay from the scoreboard."
                    : "Nothing to pay right now.";
        }
        lastPayMs = System.currentTimeMillis();
        return "Paying " + summary + " to " + target;
    }

    static String localStatusLine(Minecraft client) {
        if (client == null || client.player == null) {
            return "Not in game.";
        }
        String name = client.player.getName().getString();
        return switch (localRole(client)) {
            case NO_OWNER -> name + ": set an owner name below";
            case OWNER -> name + ": owner (receives shards)";
            case PAYER -> name + ": payer (auto pays the owner)";
            case NOT_LISTED -> name + ": not in the payer list";
        };
    }

    private static Role localRole(Minecraft client) {
        return roleFor(client, owner, Arrays.asList(members));
    }

    private static Role roleFor(Minecraft client, String ownerName, List<String> memberNames) {
        if (client == null || client.player == null) {
            return Role.NOT_LISTED;
        }
        String normalizedOwner = normalizeName(ownerName);
        if (normalizedOwner.isEmpty()) {
            return Role.NO_OWNER;
        }
        String local = normalizeName(client.player.getName().getString());
        if (local.equals(normalizedOwner)) {
            return Role.OWNER;
        }
        if (memberNames != null) {
            for (String member : memberNames) {
                String normalized = normalizeName(member);
                if (!normalized.isEmpty() && normalized.equals(local)) {
                    return Role.PAYER;
                }
            }
        }
        return Role.NOT_LISTED;
    }

    private static String formatWhole(double amount) {
        return java.math.BigDecimal.valueOf(Math.floor(amount)).toBigInteger().toString();
    }

    private static String formatAmount(double amount) {
        long whole = (long) amount;
        if (amount == whole) {
            return Long.toString(whole);
        }
        String text = String.format(Locale.ROOT, "%.2f", Math.floor(amount * 100d) / 100d);
        while (text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    static double shardsFromLines(List<ScoreboardReader.Line> lines) {
        for (ScoreboardReader.Line line : lines) {
            if (!line.normalizedText().contains("shard")) {
                continue;
            }
            double parsed = SbText.firstAmount(line.normalizedText());
            if (parsed >= 0d) {
                return parsed;
            }
            if (line.score() > 0) {
                return line.score();
            }
        }
        for (ScoreboardReader.Line line : lines) {
            double parsed = SbText.timerAmount(line.normalizedText());
            if (parsed >= 0d) {
                return parsed;
            }
        }
        return -1d;
    }

    static double cobbleFromLines(List<ScoreboardReader.Line> lines) {
        for (ScoreboardReader.Line line : lines) {
            String text = line.normalizedText();
            if (text.contains("cobble") && !text.contains("cobblestone")) {
                double parsed = SbText.amountAfterLabel(text, "cobble");
                if (parsed >= 0d) {
                    return parsed;
                }
            }
        }
        for (ScoreboardReader.Line line : lines) {
            String text = line.normalizedText();
            int idx = text.indexOf("purse");
            if (idx >= 0) {
                return SbText.segmentAmount(text.substring(idx), 2);
            }
        }
        return -1d;
    }

    static double gemsFromLines(List<ScoreboardReader.Line> lines) {
        for (ScoreboardReader.Line line : lines) {
            String text = line.normalizedText();
            int idx = text.indexOf("purse");
            if (idx >= 0) {
                return SbText.segmentAmount(text.substring(idx), 1);
            }
        }
        return -1d;
    }

    static String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        return SbText.stripFormattingCodes(name).trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
    }

    private enum Role {
        NO_OWNER,
        OWNER,
        PAYER,
        NOT_LISTED
    }

    private enum NoticeKind {
        NO_OWNER,
        NOT_LISTED,
        NO_SHARD_LINE
    }
}

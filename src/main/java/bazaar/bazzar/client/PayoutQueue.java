package bazaar.bazzar.client;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

final class PayoutQueue {
    private static final long SEND_GAP_MS = 3_000L;
    private static final long REPLY_TIMEOUT_MS = 6_000L;
    private static final int MAX_SLOW_DOWN_RETRIES = 5;
    private static final double MIN_RECEIVED = 5.0d;
    private static final Pattern SENT_PATTERN =
            Pattern.compile("(?i)^\\s*you\\s+sent\\s+.*?tokens?\\s+to\\s+(\\w{1,16})");

    private static final ArrayDeque<Pending> QUEUE = new ArrayDeque<>();
    private static Pending inFlight = null;
    private static long lastSendMs = 0L;

    private PayoutQueue() {
    }

    static void enqueue(Minecraft client, String kind, String sender, double received, double total, String amountText) {
        if (received <= MIN_RECEIVED) {
            return;
        }
        PayoutLog.Entry entry = PayoutLog.addQueued(kind, sender, received, total);
        QUEUE.addLast(new Pending(entry, sender, amountText));
        String unit = "gem".equals(kind) ? (received == 1.0d ? " gem" : " gems")
                : "shard".equals(kind) ? (received == 1.0d ? " shard" : " shards") : " cobble";
        message(client, prefix()
                .append(Component.literal("Received ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(SbText.formatCompact(received) + unit).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" from ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(sender).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(", paying ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(amountText).withStyle(ChatFormatting.GOLD))
                .append(Component.literal(QUEUE.size() + (inFlight != null ? 1 : 0) > 1
                        ? " (" + (QUEUE.size() + (inFlight != null ? 1 : 0) - 1) + " ahead)" : "").withStyle(ChatFormatting.DARK_GRAY)));
    }

    static void tick(Minecraft client) {
        long now = System.currentTimeMillis();
        if (inFlight != null) {
            if (now - inFlight.sentMs < REPLY_TIMEOUT_MS) {
                return;
            }
            PayoutLog.setStatus(inFlight.entry, "unconfirmed", "no reply from the server");
            inFlight = null;
        }
        if (QUEUE.isEmpty() || now - lastSendMs < SEND_GAP_MS) {
            return;
        }
        if (client == null || client.player == null || client.player.connection == null) {
            return;
        }
        Pending next = QUEUE.pollFirst();
        client.player.connection.sendCommand("paytokens " + next.amountText + " " + next.sender);
        next.sentMs = now;
        lastSendMs = now;
        inFlight = next;
    }

    static void onChatLine(Minecraft client, String message) {
        if (inFlight == null || message == null) {
            return;
        }
        String clean = SbText.stripSprites(message).trim();
        String lower = clean.toLowerCase(Locale.ROOT);
        Matcher sent = SENT_PATTERN.matcher(clean);
        if (sent.find() && sent.group(1).equalsIgnoreCase(inFlight.sender)) {
            PayoutLog.setStatus(inFlight.entry, "paid", null);
            inFlight = null;
            return;
        }
        if (lower.startsWith("slow down")) {
            Pending retry = inFlight;
            inFlight = null;
            lastSendMs = System.currentTimeMillis();
            if (++retry.slowDowns > MAX_SLOW_DOWN_RETRIES) {
                fail(client, retry, "server kept saying slow down");
            } else {
                QUEUE.addFirst(retry);
            }
            return;
        }
        if (lower.contains("not enough") || lower.contains("don't have enough") || lower.contains("do not have enough")
                || lower.contains("insufficient") || lower.contains("can't afford") || lower.contains("cannot afford")) {
            Pending failed = inFlight;
            inFlight = null;
            fail(client, failed, clean);
        }
    }

    private static void fail(Minecraft client, Pending pending, String reason) {
        PayoutLog.setStatus(pending.entry, "unpaid", reason);
        message(client, prefix()
                .append(Component.literal("Couldn't pay ").withStyle(ChatFormatting.RED))
                .append(Component.literal(pending.sender).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" " + pending.amountText).withStyle(ChatFormatting.GOLD))
                .append(Component.literal(": " + reason + ". It's marked unpaid in Payouts.").withStyle(ChatFormatting.RED)));
    }

    private static void message(Minecraft client, Component text) {
        if (client != null && client.player != null) {
            client.player.displayClientMessage(text, false);
        }
    }

    private static MutableComponent prefix() {
        return Component.literal("[Bazaar] ").withStyle(ChatFormatting.DARK_AQUA);
    }

    private static final class Pending {
        final PayoutLog.Entry entry;
        final String sender;
        final String amountText;
        long sentMs;
        int slowDowns;

        Pending(PayoutLog.Entry entry, String sender, String amountText) {
            this.entry = entry;
            this.sender = sender;
            this.amountText = amountText;
        }
    }
}

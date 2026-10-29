package bazaar.bazzar.client;

import java.util.ArrayDeque;
import java.util.Iterator;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
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
    private static final Pattern SENT_PATTERN =
            Pattern.compile("(?i)^\\s*you\\s+sent\\s+.*?tokens?\\s+to\\s+(\\w{1,16})");

    // how long a payment waits while we find out whether it was someone buying a Celestial Boost
    private static final long BOOST_HOLD_MS = 6_000L;

    private static final ArrayDeque<Pending> QUEUE = new ArrayDeque<>();
    private static final ArrayDeque<Held> HELD = new ArrayDeque<>();
    private static Pending inFlight = null;
    private static long lastSendMs = 0L;

    private PayoutQueue() {
    }

    // payments at or below this are ignored, set per currency with /setshardprice min <amount> etc.
    static double minimum(String kind) {
        BazzarConfig.State state = BazzarConfig.load();
        return switch (kind) {
            case "gem" -> state.gemPayoutMin;
            case "cobble" -> state.cobblePayoutMin;
            default -> state.shardPayoutMin;
        };
    }

    static String minimumNote(String kind, String units) {
        double min = minimum(kind);
        return min <= 0d ? " Pays for any amount." : " Only pays when more than " + SbText.formatCompact(min) + " " + units + " arrive at once.";
    }

    static int runSetMinimum(CommandContext<FabricClientCommandSource> context, String kind, String units) {
        Double amount = ShardPayoutController.parseCompactNumber(StringArgumentType.getString(context, "amount"));
        if (amount == null || amount < 0d) {
            context.getSource().sendFeedback(prefix().append(Component.literal("Use a number, e.g. min 5, or min 0 to pay for any amount.")
                    .withStyle(ChatFormatting.RED)));
            return 0;
        }
        BazzarConfig.State state = BazzarConfig.load();
        switch (kind) {
            case "gem" -> state.gemPayoutMin = amount;
            case "cobble" -> state.cobblePayoutMin = amount;
            default -> state.shardPayoutMin = amount;
        }
        BazzarConfig.save(state);
        context.getSource().sendFeedback(prefix().append(Component.literal(
                amount <= 0d ? "Auto pay now pays for any amount of " + units + "."
                        : "Auto pay now only pays when more than " + SbText.formatCompact(amount) + " " + units + " arrive at once.")
                .withStyle(ChatFormatting.GREEN)));
        return 1;
    }

    static void enqueue(Minecraft client, String kind, String sender, double received, double total, String amountText) {
        if (received <= minimum(kind)) {
            return;
        }
        String currency = currencyOf(kind);
        if (CelestialMarket.mayPayForBoost(sender, currency, received)) {
            // they may be paying for a Celestial Boost, which is not something to pay them back for
            HELD.addLast(new Held(kind, currency, sender, received, total, amountText,
                    System.currentTimeMillis() + BOOST_HOLD_MS));
            return;
        }
        queue(client, kind, sender, received, total, amountText);
    }

    private static void queue(Minecraft client, String kind, String sender, double received, double total, String amountText) {
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
        releaseHeld(client, now);
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

    private static void releaseHeld(Minecraft client, long now) {
        Iterator<Held> it = HELD.iterator();
        while (it.hasNext()) {
            Held held = it.next();
            if (CelestialMarket.claimedForBoost(held.sender(), held.currency(), held.received())) {
                it.remove();
                message(client, prefix()
                        .append(Component.literal("Didn't auto pay ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(held.sender()).withStyle(ChatFormatting.AQUA))
                        .append(Component.literal(", that was payment for your Celestial Boost.").withStyle(ChatFormatting.GRAY)));
            } else if (now >= held.readyAtMs()) {
                it.remove();
                queue(client, held.kind(), held.sender(), held.received(), held.total(), held.amountText());
            }
        }
    }

    private static String currencyOf(String kind) {
        return switch (kind) {
            case "gem" -> "gems";
            case "cobble" -> "cobble";
            default -> "shards";
        };
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
            McCompat.chat(client.player, text);
        }
    }

    private static MutableComponent prefix() {
        return BazaarChat.prefix();
    }

    private record Held(String kind, String currency, String sender, double received, double total,
            String amountText, long readyAtMs) {
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

package bazaar.bazzar.client;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

final class ShardPayoutController {

    private static final Pattern[] RECEIVE_PATTERNS = {
            Pattern.compile("(?i)^\\s*(\\w{1,16})\\s+(?:has\\s+)?(?:sent|paid|gave)\\s+you\\s+(?:x|[^\\w\\s])?\\s*([\\d,.]+[a-z]{0,2})\\s+shards?"),
            Pattern.compile("(?i)^\\s*you\\s+(?:have\\s+)?(?:received|got)\\s+(?:x|[^\\w\\s])?\\s*([\\d,.]+[a-z]{0,2})\\s+shards?\\s+from\\s+(\\w{1,16})"),
            Pattern.compile("(?i)^\\s*received\\s+(?:x|[^\\w\\s])?\\s*([\\d,.]+[a-z]{0,2})\\s+shards?\\s+from\\s+(\\w{1,16})")
    };


    private ShardPayoutController() {
    }

    static int runStatus(CommandContext<FabricClientCommandSource> context) {
        BazzarConfig.State state = BazzarConfig.load();
        String priceText = state.shardPrice > 0.0d ? SbText.formatCompact(state.shardPrice) : "not set";
        context.getSource().sendFeedback(prefix()
                .append(Component.literal("Shard auto pay: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(state.shardPayoutEnabled ? "ON" : "OFF")
                        .withStyle(state.shardPayoutEnabled ? ChatFormatting.GREEN : ChatFormatting.RED))
                .append(Component.literal("  price/shard: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(priceText).withStyle(ChatFormatting.GOLD))
                .append(Component.literal("  (/setshardprice <price>, /setshardprice on|off)").withStyle(ChatFormatting.DARK_GRAY)));
        return 1;
    }

    static int runToggle(CommandContext<FabricClientCommandSource> context, boolean enable) {
        BazzarConfig.State state = BazzarConfig.load();
        if (enable && state.shardPrice <= 0.0d) {
            context.getSource().sendFeedback(prefix()
                    .append(Component.literal("Set a price first: /setshardprice <price>").withStyle(ChatFormatting.RED)));
            return 0;
        }
        state.shardPayoutEnabled = enable;
        BazzarConfig.save(state);
        context.getSource().sendFeedback(prefix()
                .append(Component.literal(enable
                        ? "Shard auto pay ON, paying " + SbText.formatCompact(state.shardPrice) + " per shard received."
                        : "Shard auto pay OFF.")
                        .withStyle(enable ? ChatFormatting.GREEN : ChatFormatting.YELLOW)));
        return 1;
    }

    static void onChatLine(Minecraft client, String message) {
        if (client == null || client.player == null || client.player.connection == null || message == null) {
            return;
        }
        BazzarConfig.State state = BazzarConfig.load();
        if (state == null || !state.shardPayoutEnabled || state.shardPrice <= 0.0d) {
            return;
        }
        Parsed parsed = parseReceive(SbText.stripSprites(message));
        if (parsed == null) {
            return;
        }
        String self = client.player.getName().getString();
        if (parsed.sender.equalsIgnoreCase(self)) {
            return;
        }
        if (isOwnAccount(state, parsed.sender)) {
            return;
        }
        double total = parsed.amount * state.shardPrice;
        String amountText = formatPayAmount(total);
        PayoutQueue.enqueue(client, "shard", parsed.sender, parsed.amount, total, amountText);
    }

    static boolean isOwnAccount(BazzarConfig.State state, String name) {
        String normalized = ShardPayController.normalizeName(name);
        if (normalized.isEmpty()) {
            return false;
        }
        if (normalized.equals(ShardPayController.normalizeName(state.shardPayOwner))) {
            return true;
        }
        if (state.shardPayMembers != null) {
            for (String member : state.shardPayMembers) {
                if (normalized.equals(ShardPayController.normalizeName(member))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Parsed parseReceive(String message) {
        for (int i = 0; i < RECEIVE_PATTERNS.length; i++) {
            Matcher matcher = RECEIVE_PATTERNS[i].matcher(message);
            if (!matcher.find()) {
                continue;
            }
            String name = i == 0 ? matcher.group(1) : matcher.group(2);
            String amountRaw = i == 0 ? matcher.group(2) : matcher.group(1);
            Double amount = parseCompactNumber(amountRaw);
            if (amount == null || amount < 1.0d) {
                return null;
            }
            return new Parsed(name, amount);
        }
        return null;
    }

    static Double parseCompactNumber(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().replace(",", "").replace("_", "");
        Matcher matcher = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)([a-zA-Z]{0,2})$").matcher(normalized);
        if (!matcher.matches()) {
            return null;
        }
        double value;
        try {
            value = Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException ex) {
            return null;
        }
        String suffix = matcher.group(2);
        if (suffix.isEmpty()) {
            return value;
        }
        Double multiplier = ShardCalculator.suffixMultiplier(suffix);
        return multiplier == null ? null : value * multiplier;
    }

    private static final String[] PAY_SUFFIXES = {"k", "m", "b", "t", "q", "qi", "sx", "sp", "oc", "no", "dc"};

    static String formatPayAmount(double total) {
        double scaled = total;
        int tier = -1;
        while (scaled >= 1_000d && tier < PAY_SUFFIXES.length - 1) {
            scaled /= 1_000d;
            tier++;
        }
        scaled = Math.floor(scaled * 1_000d) / 1_000d;
        String text = String.format(Locale.ROOT, "%.3f", scaled);
        while (text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        return tier < 0 ? text : text + PAY_SUFFIXES[tier];
    }

    private static MutableComponent prefix() {
        return BazaarChat.prefix();
    }

    private record Parsed(String sender, double amount) {
    }
}

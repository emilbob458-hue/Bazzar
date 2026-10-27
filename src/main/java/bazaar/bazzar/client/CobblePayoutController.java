package bazaar.bazzar.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

final class CobblePayoutController {

    private static final Pattern[] RECEIVE_PATTERNS = {
            Pattern.compile("(?i)^\\s*(\\w{1,16})\\s+(?:has\\s+)?(?:sent|paid|gave)\\s+you\\s+(?:x|[ⓒ©$])?\\s*([\\d,.]+[a-z]{0,2})\\s+cobble"),
            Pattern.compile("(?i)^\\s*you\\s+(?:have\\s+)?(?:received|got)\\s+(?:x|[ⓒ©$])?\\s*([\\d,.]+[a-z]{0,2})\\s+cobble\\s+from\\s+(\\w{1,16})"),
            Pattern.compile("(?i)^\\s*received\\s+(?:x|[ⓒ©$])?\\s*([\\d,.]+[a-z]{0,2})\\s+cobble\\s+from\\s+(\\w{1,16})")
    };


    private CobblePayoutController() {
    }

    static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                literal("setcobble")
                        .requires(source -> !BazzarGuard.isBlocked())
                        .executes(CobblePayoutController::runStatus)
                        .then(literal("on").executes(ctx -> runToggle(ctx, true)))
                        .then(literal("off").executes(ctx -> runToggle(ctx, false)))
                        .then(argument("price", StringArgumentType.greedyString())
                                .executes(CobblePayoutController::runSetPrice))));
    }

    private static int runStatus(CommandContext<FabricClientCommandSource> context) {
        BazzarConfig.State state = BazzarConfig.load();
        String priceText = state.cobblePayoutPrice > 0.0d ? SbText.formatCompact(state.cobblePayoutPrice) : "not set";
        context.getSource().sendFeedback(prefix()
                .append(Component.literal("Cobble auto pay: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(state.cobblePayoutEnabled ? "ON" : "OFF")
                        .withStyle(state.cobblePayoutEnabled ? ChatFormatting.GREEN : ChatFormatting.RED))
                .append(Component.literal("  price/cobble: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(priceText).withStyle(ChatFormatting.GOLD))
                .append(Component.literal("  (/setcobble <price>, /setcobble on|off)").withStyle(ChatFormatting.DARK_GRAY)));
        return 1;
    }

    private static int runToggle(CommandContext<FabricClientCommandSource> context, boolean enable) {
        BazzarConfig.State state = BazzarConfig.load();
        if (enable && state.cobblePayoutPrice <= 0.0d) {
            context.getSource().sendFeedback(prefix()
                    .append(Component.literal("Set a price first: /setcobble <price>").withStyle(ChatFormatting.RED)));
            return 0;
        }
        state.cobblePayoutEnabled = enable;
        BazzarConfig.save(state);
        context.getSource().sendFeedback(prefix()
                .append(Component.literal(enable
                        ? "Cobble auto pay ON, paying " + SbText.formatCompact(state.cobblePayoutPrice) + " per cobble received."
                        : "Cobble auto pay OFF.")
                        .withStyle(enable ? ChatFormatting.GREEN : ChatFormatting.YELLOW)));
        return 1;
    }

    private static int runSetPrice(CommandContext<FabricClientCommandSource> context) {
        String raw = StringArgumentType.getString(context, "price").trim();
        Boolean toggle = null;
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.endsWith(" on")) {
            toggle = true;
            raw = raw.substring(0, raw.length() - 3);
        } else if (lower.endsWith(" off")) {
            toggle = false;
            raw = raw.substring(0, raw.length() - 4);
        }
        Double parsed = parseCompactNumber(raw);
        if (parsed == null || parsed <= 0.0d) {
            context.getSource().sendFeedback(prefix()
                    .append(Component.literal("Invalid price. Use number/k/m/b/t.").withStyle(ChatFormatting.RED)));
            return 0;
        }
        BazzarConfig.State state = BazzarConfig.load();
        state.cobblePayoutPrice = parsed;
        BazzarConfig.save(state);
        MutableComponent feedback = prefix()
                .append(Component.literal("Cobble price set to ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(SbText.formatCompact(parsed)).withStyle(ChatFormatting.GOLD))
                .append(Component.literal(" per cobble.").withStyle(ChatFormatting.GREEN));
        if (toggle == null && !state.cobblePayoutEnabled) {
            feedback.append(Component.literal(" Auto pay is OFF, enable with /setcobble on").withStyle(ChatFormatting.YELLOW));
        }
        context.getSource().sendFeedback(feedback);
        if (toggle != null) {
            return runToggle(context, toggle);
        }
        return 1;
    }

    static void onChatLine(Minecraft client, String message) {
        if (client == null || client.player == null || client.player.connection == null || message == null) {
            return;
        }
        BazzarConfig.State state = BazzarConfig.load();
        if (state == null || !state.cobblePayoutEnabled || state.cobblePayoutPrice <= 0.0d) {
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
        double total = parsed.amount * state.cobblePayoutPrice;
        String amountText = formatPayAmount(total);
        PayoutQueue.enqueue(client, "cobble", parsed.sender, parsed.amount, total, amountText);
    }

    private static boolean isOwnAccount(BazzarConfig.State state, String name) {
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

    private static Double parseCompactNumber(String raw) {
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

    private static String formatPayAmount(double total) {
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
        return Component.literal("[Bazaar] ").withStyle(ChatFormatting.DARK_AQUA);
    }

    private record Parsed(String sender, double amount) {
    }
}

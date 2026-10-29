package bazaar.bazzar.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

final class ShardCalculator {
    private static final Pattern COMPACT_NUMBER_PATTERN =
            Pattern.compile("^\\s*([0-9]+(?:\\.[0-9]+)?)\\s*([a-zA-Z]{0,2})\\s*$");
    private static final double BASE_MULTI = 1.0d;
    private static final double MULTI_BASE_COST = 3_000.0d;
    private static final double MULTI_COST_GROWTH = 2.24d;
    private static final double MULTI_STEP = 0.5d;

    private static double shardPrice = 0.0d;
    private static double gemPrice = 0.0d;
    private static double currentMulti = 1.0d;

    private ShardCalculator() {
    }

    static void setShardPrice(double value) {
        shardPrice = Math.max(0.0d, value);
    }

    static void setGemPrice(double value) {
        gemPrice = Math.max(0.0d, value);
    }

    static void setCurrentMulti(double value) {
        currentMulti = Math.max(BASE_MULTI, value);
    }

    static double getCurrentMulti() {
        return currentMulti;
    }

    static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(literal("setshardprice")
                    .requires(source -> !BazzarGuard.isBlocked())
                    .executes(ShardPayoutController::runStatus)
                    .then(literal("on").executes(ctx -> ShardPayoutController.runToggle(ctx, true)))
                    .then(literal("off").executes(ctx -> ShardPayoutController.runToggle(ctx, false)))
                    .then(literal("min").then(argument("amount", StringArgumentType.greedyString())
                            .executes(ctx -> PayoutQueue.runSetMinimum(ctx, "shard", "shards"))))
                    .then(argument("price", StringArgumentType.greedyString())
                            .executes(ShardCalculator::runSetShardPrice)));
            dispatcher.register(literal("setmulti")
                    .requires(source -> !BazzarGuard.isBlocked())
                    .then(argument("multi", StringArgumentType.greedyString())
                            .executes(ShardCalculator::runSetMulti)));

            dispatcher.register(literal("calcshard")
                    .requires(source -> !BazzarGuard.isBlocked())
                    .then(argument("count", StringArgumentType.greedyString())
                            .executes(ShardCalculator::runShardCalc)));

            dispatcher.register(literal("setgemprice")
                    .requires(source -> !BazzarGuard.isBlocked())
                    .executes(GemPayoutController::runStatus)
                    .then(literal("on").executes(ctx -> GemPayoutController.runToggle(ctx, true)))
                    .then(literal("off").executes(ctx -> GemPayoutController.runToggle(ctx, false)))
                    .then(literal("min").then(argument("amount", StringArgumentType.greedyString())
                            .executes(ctx -> PayoutQueue.runSetMinimum(ctx, "gem", "gems"))))
                    .then(argument("price", StringArgumentType.greedyString())
                            .executes(ShardCalculator::runSetGemPrice)));

            dispatcher.register(literal("calcgems")
                    .requires(source -> !BazzarGuard.isBlocked())
                    .then(argument("count", StringArgumentType.greedyString())
                            .executes(ShardCalculator::runGemCalc)));

            dispatcher.register(literal("calcxp")
                    .requires(source -> !BazzarGuard.isBlocked())
                    .then(argument("levels", StringArgumentType.greedyString())
                            .executes(ShardCalculator::runXpCalc)));

            dispatcher.register(literal("calcmulti")
                    .requires(source -> !BazzarGuard.isBlocked())
                    .then(argument("multiplier", StringArgumentType.greedyString())
                            .executes(ShardCalculator::runCalcMulti)));

            dispatcher.register(literal("calc")
                    .requires(source -> !BazzarGuard.isBlocked())
                    .then(literal("multi")
                            .then(argument("multiplier", StringArgumentType.greedyString())
                                    .executes(ShardCalculator::runCalcMulti)))
                    .then(argument("expression", StringArgumentType.greedyString())
                            .executes(ShardCalculator::runCalcExpression)));
        });
    }

    private static int runSetShardPrice(CommandContext<FabricClientCommandSource> context) {
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
        if (parsed == null || parsed < 0.0d) {
            context.getSource().sendFeedback(prefix().append(Component.literal("Invalid price. Use number/k/m/b/t.")
                    .withStyle(ChatFormatting.RED)));
            return 0;
        }
        shardPrice = parsed;
        BazzarConfig.State state = BazzarConfig.load();
        state.shardPrice = parsed;
        BazzarConfig.save(state);

        MutableComponent feedback = prefix().append(Component.literal("Shard price set to ")
                .withStyle(ChatFormatting.GREEN))
                .append(Component.literal(format(parsed)).withStyle(ChatFormatting.GOLD));
        if (toggle == null && !state.shardPayoutEnabled) {
            feedback.append(Component.literal(" Shard auto pay is OFF, enable with /setshardprice on").withStyle(ChatFormatting.YELLOW));
        }
        context.getSource().sendFeedback(feedback);
        if (toggle != null) {
            return ShardPayoutController.runToggle(context, toggle);
        }
        return 1;
    }

    private static int runShardCalc(CommandContext<FabricClientCommandSource> context) {
        String raw = StringArgumentType.getString(context, "count");
        Double parsedCount = parseCompactNumber(raw);
        if (parsedCount == null || parsedCount < 0.0d) {
            context.getSource().sendFeedback(prefix().append(Component.literal("Invalid shard count.")
                    .withStyle(ChatFormatting.RED)));
            return 0;
        }
        if (shardPrice <= 0.0d) {
            context.getSource().sendFeedback(prefix().append(Component.literal("Set shard price first with /setshardprice <price>.")
                    .withStyle(ChatFormatting.RED)));
            return 0;
        }

        double total = parsedCount * shardPrice;
        context.getSource().sendFeedback(prefix().append(Component.literal("Shards: ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(format(parsedCount)).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" | Price: ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(format(shardPrice)).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" | Total: ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(format(total)).withStyle(ChatFormatting.GOLD)));
        return 1;
    }

    private static int runSetGemPrice(CommandContext<FabricClientCommandSource> context) {
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
        if (parsed == null || parsed < 0.0d) {
            context.getSource().sendFeedback(prefix().append(Component.literal("Invalid price. Use number/k/m/b/t/q/qi.")
                    .withStyle(ChatFormatting.RED)));
            return 0;
        }
        gemPrice = parsed;
        BazzarConfig.State state = BazzarConfig.load();
        state.gemPrice = parsed;
        BazzarConfig.save(state);

        MutableComponent feedback = prefix().append(Component.literal("Gem price set to ")
                .withStyle(ChatFormatting.GREEN))
                .append(Component.literal(format(parsed)).withStyle(ChatFormatting.GOLD));
        if (toggle == null && !state.gemPayoutEnabled) {
            feedback.append(Component.literal(" Gem auto pay is OFF, enable with /setgemprice on").withStyle(ChatFormatting.YELLOW));
        }
        context.getSource().sendFeedback(feedback);
        if (toggle != null) {
            return GemPayoutController.runToggle(context, toggle);
        }
        return 1;
    }

    private static int runGemCalc(CommandContext<FabricClientCommandSource> context) {
        String raw = StringArgumentType.getString(context, "count");
        Double parsedCount = parseCompactNumber(raw);
        if (parsedCount == null || parsedCount < 0.0d) {
            context.getSource().sendFeedback(prefix().append(Component.literal("Invalid gem count.")
                    .withStyle(ChatFormatting.RED)));
            return 0;
        }
        if (gemPrice <= 0.0d) {
            context.getSource().sendFeedback(prefix().append(Component.literal("Set gem price first with /setgemprice <price>.")
                    .withStyle(ChatFormatting.RED)));
            return 0;
        }

        double total = parsedCount * gemPrice;
        context.getSource().sendFeedback(prefix().append(Component.literal("Gems: ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(format(parsedCount)).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" | Price: ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(format(gemPrice)).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" | Total: ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(format(total)).withStyle(ChatFormatting.GOLD)));
        return 1;
    }

    private static int runXpCalc(CommandContext<FabricClientCommandSource> context) {
        String raw = StringArgumentType.getString(context, "levels");
        String[] parts = raw.trim().split("\\s+");
        Integer from = parts.length >= 1 ? parseLevel(parts[0]) : null;
        Integer to = parts.length >= 2 ? parseLevel(parts[1]) : null;
        if (from == null || from < 1 || parts.length > 2 || (parts.length == 2 && (to == null || to <= from))) {
            context.getSource().sendFeedback(prefix().append(Component.literal(
                    "Usage: /calcxp <level> or /calcxp <from> <to>").withStyle(ChatFormatting.RED)));
            return 0;
        }
        if (to == null) {
            double xp = xpToNextLevel(from);
            context.getSource().sendFeedback(prefix().append(Component.literal("Level ").withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(from + " → " + (from + 1)).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" | XP: ").withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(format(xp)).withStyle(ChatFormatting.GOLD)));
            return 1;
        }
        double total = 0.0d;
        for (int level = from; level < to; level++) {
            total += xpToNextLevel(level);
        }
        context.getSource().sendFeedback(prefix().append(Component.literal("Level ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(from + " → " + to).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" | Total XP: ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(format(total)).withStyle(ChatFormatting.GOLD)));
        return 1;
    }

    private static Integer parseLevel(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static double xpToNextLevel(int level) {
        double base = 140.0d * level;
        if (level <= 100) {
            return Math.pow(base, 1.05d);
        }
        if (level <= 300) {
            return Math.pow(base, 1.18d) - 55_500d;
        }
        if (level <= 500) {
            return Math.pow(base, 1.25d) - 371_000d;
        }
        if (level <= 600) {
            return Math.pow(base, 1.50d) - 17_750_000d;
        }
        return Math.pow(base, 1.55d) - 36_300_000d;
    }

    private static int runSetMulti(CommandContext<FabricClientCommandSource> context) {
        String raw = StringArgumentType.getString(context, "multi");
        Double parsed = parseCompactNumber(raw);
        if (parsed == null || parsed < BASE_MULTI) {
            context.getSource().sendFeedback(prefix().append(Component.literal("Invalid multi. Must be >= 1.")
                    .withStyle(ChatFormatting.RED)));
            return 0;
        }
        currentMulti = parsed;
        BazzarConfig.State state = BazzarConfig.load();
        state.currentMulti = parsed;
        BazzarConfig.save(state);

        context.getSource().sendFeedback(prefix().append(Component.literal("Current multi set to ")
                .withStyle(ChatFormatting.GREEN))
                .append(Component.literal(format(parsed) + "x").withStyle(ChatFormatting.GOLD)));
        return 1;
    }

    private static int runCalcMulti(CommandContext<FabricClientCommandSource> context) {
        String raw = StringArgumentType.getString(context, "multiplier");
        Double targetMultiplier = parseCompactNumber(raw);
        if (targetMultiplier == null || targetMultiplier < BASE_MULTI) {
            context.getSource().sendFeedback(prefix().append(Component.literal("Invalid multiplier.")
                    .withStyle(ChatFormatting.RED)));
            return 0;
        }
        if (targetMultiplier <= currentMulti) {
            context.getSource().sendFeedback(prefix().append(Component.literal("Target must be above current multi (")
                    .withStyle(ChatFormatting.RED))
                    .append(Component.literal(format(currentMulti) + "x").withStyle(ChatFormatting.GOLD))
                    .append(Component.literal(").").withStyle(ChatFormatting.RED)));
            return 0;
        }

        double totalCost = getTotalCostToReach(currentMulti, targetMultiplier);
        double nextStepCost = getMultiplierCost(currentMulti);
        context.getSource().sendFeedback(prefix().append(Component.literal("Current: ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(format(currentMulti) + "x").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" | Target: ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(format(targetMultiplier) + "x").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" | Need: ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(format(totalCost)).withStyle(ChatFormatting.GOLD))
                .append(Component.literal(" | Next +0.5: ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(format(nextStepCost)).withStyle(ChatFormatting.WHITE)));
        return 1;
    }

    private static int runCalcExpression(CommandContext<FabricClientCommandSource> context) {
        String raw = StringArgumentType.getString(context, "expression");
        Double result = new ExpressionParser(raw).parse();
        if (result == null || result.isNaN() || result.isInfinite()) {
            context.getSource().sendFeedback(prefix().append(Component.literal(
                    "Invalid expression. Example: /calc 5q x 5 + 200t").withStyle(ChatFormatting.RED)));
            return 0;
        }
        context.getSource().sendFeedback(prefix().append(Component.literal(raw.trim() + " = ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(format(result)).withStyle(ChatFormatting.GOLD)));
        return 1;
    }

    private static final class ExpressionParser {
        private final String text;
        private int pos;

        ExpressionParser(String text) {
            this.text = text == null ? "" : text;
        }

        Double parse() {
            Double value = expression();
            skipWhitespace();
            return pos == text.length() ? value : null;
        }

        private Double expression() {
            Double value = term();
            while (value != null) {
                skipWhitespace();
                if (eat('+')) {
                    Double right = term();
                    value = right == null ? null : value + right;
                } else if (eat('-')) {
                    Double right = term();
                    value = right == null ? null : value - right;
                } else {
                    break;
                }
            }
            return value;
        }

        private Double term() {
            Double value = factor();
            while (value != null) {
                skipWhitespace();
                if (eat('*') || eat('x') || eat('X') || eat('×')) {
                    Double right = factor();
                    value = right == null ? null : value * right;
                } else if (eat('/')) {
                    Double right = factor();
                    value = right == null || right == 0.0d ? null : value / right;
                } else {
                    break;
                }
            }
            return value;
        }

        private Double factor() {
            skipWhitespace();
            if (eat('-')) {
                Double value = factor();
                return value == null ? null : -value;
            }
            if (eat('(')) {
                Double value = expression();
                skipWhitespace();
                return eat(')') ? value : null;
            }
            return number();
        }

        private Double number() {
            int start = pos;
            while (pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.' || text.charAt(pos) == ',')) {
                pos++;
            }
            if (pos == start) {
                return null;
            }
            double value;
            try {
                value = Double.parseDouble(text.substring(start, pos).replace(",", ""));
            } catch (NumberFormatException ex) {
                return null;
            }
            int letterStart = pos;
            while (pos < text.length() && Character.isLetter(text.charAt(pos)) && pos - letterStart < 2) {
                pos++;
            }
            while (pos > letterStart) {
                String suffix = text.substring(letterStart, pos);
                Double multiplier = suffixMultiplier(suffix);
                if (multiplier != null) {
                    return value * multiplier;
                }
                pos--;
            }
            return value;
        }

        private boolean eat(char c) {
            if (pos < text.length() && text.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }
    }

    static double getMultiplierCost(double multiplier) {
        long level = Math.max(0L, Math.round((multiplier - BASE_MULTI) / MULTI_STEP));
        return MULTI_BASE_COST * Math.pow(MULTI_COST_GROWTH, level);
    }

    static double multiplierFromNextCost(double cost) {
        return BASE_MULTI + MULTI_STEP * (Math.log(cost / MULTI_BASE_COST) / Math.log(MULTI_COST_GROWTH));
    }

    private static double getTotalCostToReach(double startMultiplier, double targetMultiplier) {
        if (targetMultiplier <= startMultiplier) {
            return 0.0d;
        }
        int startTenths = (int) Math.round(startMultiplier / MULTI_STEP);
        int targetTenths = (int) Math.round(targetMultiplier / MULTI_STEP);
        double total = 0.0d;
        for (int i = startTenths; i < targetTenths; i++) {
            double current = i * MULTI_STEP;
            total += getMultiplierCost(current);
        }
        return total;
    }

    private static Double parseCompactNumber(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.replace(",", "").replace("_", "");
        Matcher matcher = COMPACT_NUMBER_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            return null;
        }
        double value;
        try {
            value = Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException ex) {
            return null;
        }
        String suffix = matcher.group(2).toLowerCase(Locale.ROOT);
        if (suffix.isEmpty()) {
            return value;
        }
        Double multiplier = suffixMultiplier(suffix);
        if (multiplier == null) {
            return null;
        }
        return value * multiplier;
    }

    static Double suffixMultiplier(String suffix) {
        return switch (suffix.toLowerCase(Locale.ROOT)) {
            case "k" -> 1e3d;
            case "m" -> 1e6d;
            case "b" -> 1e9d;
            case "t" -> 1e12d;
            case "q", "qa" -> 1e15d;
            case "qi" -> 1e18d;
            case "sx" -> 1e21d;
            case "sp" -> 1e24d;
            case "oc" -> 1e27d;
            case "no" -> 1e30d;
            case "dc" -> 1e33d;
            default -> null;
        };
    }

    private static String format(double value) {
        if (value >= 1e33d) {
            return String.format(Locale.ROOT, "%.2fdc", value / 1e33d);
        }
        if (value >= 1e30d) {
            return String.format(Locale.ROOT, "%.2fno", value / 1e30d);
        }
        if (value >= 1e27d) {
            return String.format(Locale.ROOT, "%.2foc", value / 1e27d);
        }
        if (value >= 1e24d) {
            return String.format(Locale.ROOT, "%.2fsp", value / 1e24d);
        }
        if (value >= 1e21d) {
            return String.format(Locale.ROOT, "%.2fsx", value / 1e21d);
        }
        if (value >= 1e18d) {
            return String.format(Locale.ROOT, "%.2fqi", value / 1e18d);
        }
        if (value >= 1e15d) {
            return String.format(Locale.ROOT, "%.2fq", value / 1e15d);
        }
        if (value >= 1_000_000_000_000d) {
            return String.format(Locale.ROOT, "%.2ft", value / 1_000_000_000_000d);
        }
        if (value >= 1_000_000_000d) {
            return String.format(Locale.ROOT, "%.2fb", value / 1_000_000_000d);
        }
        if (value >= 1_000_000d) {
            return String.format(Locale.ROOT, "%.2fm", value / 1_000_000d);
        }
        if (value >= 1_000d) {
            return String.format(Locale.ROOT, "%.2fk", value / 1_000d);
        }
        if (Math.rint(value) != value) {
            return String.format(Locale.ROOT, "%,.2f", value);
        }
        return String.format(Locale.ROOT, "%,.0f", value);
    }

    private static MutableComponent prefix() {
        return BazaarChat.prefix();
    }
}

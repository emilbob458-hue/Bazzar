package bazaar.bazzar.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

final class BazaarFlipScanner {
    private static final Set<String> ELYSIUM_NAMES = new HashSet<>(List.of(
            "lightwood",
            "gold",
            "resin",
            "rasin",
            "glowstone",
            "seraphicrose",
            "cactusflower",
            "godgrass",
            "talldrygrass",
            "godflowers",
            "wildflowers",
            "hallowedheart",
            "hollowedheart",
            "creakingheart"
    ));
    private static final Set<String> CROP_BLACKLIST = new HashSet<>();
    private static final List<FlipCandidate> LAST_CROPS_SCAN = new ArrayList<>();
    private static final List<FlipCandidate> LAST_GEMS_SCAN = new ArrayList<>();

    private static final Pattern BUY_PATTERN =
            Pattern.compile("(?i)\\bbuy\\b[^0-9]*([0-9][0-9,._]*)(?:\\s*([kmbtKMBT]))?");
    private static final Pattern SELL_PATTERN =
            Pattern.compile("(?i)\\bsell\\b[^0-9]*([0-9][0-9,._]*)(?:\\s*([kmbtKMBT]))?");
    private static final Pattern VALUE_PATTERN =
            Pattern.compile("(?i)\\bvalue\\b[^0-9]*([0-9][0-9,._]*)(?:\\s*([kmbtKMBT]))?");
    private static final Pattern MARKET_RATE_PATTERN =
            Pattern.compile("(?i)market rate:\\s*([+-]?[0-9]+(?:\\.[0-9]+)?)%");
    private static final Pattern STOCK_PATTERN =
            Pattern.compile("(?i)(?:stock|supply):\\s*([0-9][0-9,._]*)(?:\\s*([kmbtKMBT]))?");
    private static final Pattern LOOSE_NUMBER_PATTERN =
            Pattern.compile("([0-9][0-9,._]*\\.?[0-9]*)(?:\\s*([kmbtKMBT]))?");

    private BazaarFlipScanner() {
    }

    static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(literal("blacklist")
                    .requires(source -> !BazzarGuard.isBlocked())
                    .then(argument("crop", StringArgumentType.greedyString())
                            .executes(BazaarFlipScanner::runBlacklistToggle)));
        });
    }

    static void applyConfig(BazzarConfig.State state) {
        CROP_BLACKLIST.clear();
        if (state == null || state.cropBlacklist == null) {
            return;
        }
        for (String entry : state.cropBlacklist) {
            String normalized = normalize(entry);
            if (!normalized.isBlank()) {
                CROP_BLACKLIST.add(normalized);
            }
        }
    }

    private static int runBlacklistToggle(CommandContext<FabricClientCommandSource> context) {
        String rawCrop = StringArgumentType.getString(context, "crop");
        String normalized = normalize(rawCrop);
        if (normalized.isBlank()) {
            context.getSource().sendFeedback(prefix().append(Component.literal("Usage: /blacklist <crop>")
                    .withStyle(ChatFormatting.RED)));
            return 0;
        }
        if (CROP_BLACKLIST.contains(normalized)) {
            CROP_BLACKLIST.remove(normalized);
            saveBlacklist();
            context.getSource().sendFeedback(prefix().append(Component.literal("Removed from blacklist: ")
                    .withStyle(ChatFormatting.GREEN))
                    .append(Component.literal(rawCrop).withStyle(ChatFormatting.WHITE)));
            return 1;
        }
        CROP_BLACKLIST.add(normalized);
        saveBlacklist();
        context.getSource().sendFeedback(prefix().append(Component.literal("Added to blacklist: ")
                .withStyle(ChatFormatting.GREEN))
                .append(Component.literal(rawCrop).withStyle(ChatFormatting.WHITE)));
        return 1;
    }

    private static void saveBlacklist() {
        BazzarConfig.State state = BazzarConfig.load();
        List<String> entries = new ArrayList<>(CROP_BLACKLIST);
        entries.sort(String::compareToIgnoreCase);
        state.cropBlacklist = entries;
        BazzarConfig.save(state);
    }

    static Set<String> getBlacklistEntries() {
        return new HashSet<>(CROP_BLACKLIST);
    }

    static boolean setCropBlacklisted(String cropOrItemId, boolean blacklisted) {
        String normalized = normalizeItemId(cropOrItemId);
        if (normalized.isBlank()) {
            return false;
        }
        boolean changed = blacklisted
                ? CROP_BLACKLIST.add(normalized)
                : CROP_BLACKLIST.remove(normalized);
        if (changed) {
            saveBlacklist();
        }
        return changed;
    }

    static void cacheCropsResults(List<FlipCandidate> candidates) {
        cacheResults(LAST_CROPS_SCAN, candidates);
    }

    static void cacheGemsResults(List<FlipCandidate> candidates) {
        cacheResults(LAST_GEMS_SCAN, candidates);
    }

    private static void cacheResults(List<FlipCandidate> target, List<FlipCandidate> candidates) {
        target.clear();
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        LinkedHashMap<String, FlipCandidate> deduped = new LinkedHashMap<>();
        for (FlipCandidate candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String key = normalizeItemId(candidate.itemId());
            if (key.isBlank()) {
                key = normalize(candidate.name());
            }
            if (key.isBlank()) {
                continue;
            }
            deduped.put(key, candidate);
        }
        target.addAll(deduped.values());
    }

    static List<FlipCandidate> getLastCropsScan() {
        return new ArrayList<>(LAST_CROPS_SCAN);
    }

    static List<FlipCandidate> getLastGemsScan() {
        return new ArrayList<>(LAST_GEMS_SCAN);
    }

    static List<FlipCandidate> getLastScanCandidates() {
        return new ArrayList<>(LAST_CROPS_SCAN);
    }

    static void runCommand(FabricClientCommandSource source) {
        List<FlipCandidate> candidates = collectCandidatesIncludingBlacklisted(source.getClient());
        cacheCropsResults(candidates);
        if (candidates.isEmpty()) {
            source.sendFeedback(prefix().append(Component.literal("Open the Cobblestone price GUI first.")
                    .withStyle(ChatFormatting.RED)));
            return;
        }
        reportResults(source::sendFeedback, candidates);
    }

    static void runKeybind(Minecraft client) {
        if (client.player == null) {
            return;
        }
        List<FlipCandidate> candidates = collectCandidatesIncludingBlacklisted(client);
        cacheCropsResults(candidates);
        if (candidates.isEmpty()) {
            client.player.displayClientMessage(prefix().append(Component.literal("Open the Cobblestone price GUI first.")
                    .withStyle(ChatFormatting.RED)), false);
            return;
        }
        reportResults(text -> client.player.displayClientMessage(text, false), candidates);
    }

    static boolean isBazaarScreen(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return false;
        }
        String title = containerScreen.getTitle().getString().toLowerCase(Locale.ROOT);
        return title.contains("bazaar");
    }

    static List<FlipCandidate> collectCandidates(Minecraft client) {
        return collectCandidates(client, false);
    }

    static List<FlipCandidate> collectCandidatesIncludingBlacklisted(Minecraft client) {
        return collectCandidates(client, true);
    }

    private static List<FlipCandidate> collectCandidates(Minecraft client, boolean includeBlacklisted) {
        Screen current = client.screen;
        if (!isBazaarScreen(current)) {
            return List.of();
        }
        AbstractContainerScreen<?> handledScreen = (AbstractContainerScreen<?>) current;
        AbstractContainerMenu handler = handledScreen.getMenu();
        List<FlipCandidate> candidates = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (slot.container instanceof Inventory) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            PriceInfo priceInfo = PriceInfo.fromStack(client, stack);
            if (priceInfo.buy == null && priceInfo.sell == null && priceInfo.marketRate == null) {
                continue;
            }
            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            FlipCandidate candidate = new FlipCandidate(stack.getHoverName().getString(), itemId, priceInfo.buy, priceInfo.sell, priceInfo.marketRate, priceInfo.stock);
            if (!includeBlacklisted && isBlacklisted(candidate.name(), candidate.itemId())) {
                continue;
            }
            candidates.add(candidate);
        }
        return candidates;
    }

    static FlipCandidate fromStack(Minecraft client, ItemStack stack) {
        if (client == null || stack == null || stack.isEmpty()) {
            return null;
        }
        PriceInfo priceInfo = PriceInfo.fromStack(client, stack);
        if (priceInfo.buy == null && priceInfo.sell == null && priceInfo.marketRate == null) {
            return null;
        }
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        FlipCandidate candidate = new FlipCandidate(stack.getHoverName().getString(), itemId, priceInfo.buy, priceInfo.sell, priceInfo.marketRate, priceInfo.stock);
        return isBlacklisted(candidate.name(), candidate.itemId()) ? null : candidate;
    }

    static void reportResults(Consumer<Component> feedback, List<FlipCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            feedback.accept(prefix().append(Component.literal("No price data found in the current GUI.")
                    .withStyle(ChatFormatting.RED)));
            return;
        }
        List<FlipCandidate> filtered = candidates.stream()
                .filter(c -> !isBlacklisted(c.name(), c.itemId()))
                .sorted(Comparator.comparing(FlipCandidate::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        if (filtered.isEmpty()) {
            feedback.accept(prefix().append(Component.literal("All scanned crops are blacklisted.")
                    .withStyle(ChatFormatting.RED)));
            return;
        }
        sendSummary(feedback, filtered);
        sendMarketRateAlerts(feedback, filtered);
        sendTopHighestSell(feedback, filtered);
        feedback.accept(prefix().append(Component.literal("================================")
                .withStyle(ChatFormatting.DARK_GRAY)));
    }

    private static void sendSummary(Consumer<Component> feedback, List<FlipCandidate> candidates) {
        FlipCandidate cheapestBuy = candidates.stream()
                .filter(BazaarFlipScanner::isElysiumCrop)
                .filter(c -> c.buy != null)
                .min(Comparator.comparingDouble(c -> c.buy))
                .orElse(null);

        FlipCandidate bestSell = candidates.stream()
                .filter(c -> c.sell != null)
                .max(Comparator.comparingDouble(c -> c.sell))
                .orElse(null);

        feedback.accept(prefix().append(Component.literal("================================")
                .withStyle(ChatFormatting.DARK_GRAY)));
        feedback.accept(prefix().append(Component.literal("Bazaar Scan")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));

        if (cheapestBuy != null) {
            feedback.accept(prefix().append(Component.literal("Cheapest Buy: ")
                    .withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(cheapestBuy.name + " " + format(cheapestBuy.buy))
                            .withStyle(ChatFormatting.GREEN)));
        } else {
            feedback.accept(prefix().append(Component.literal("Cheapest Buy: n/a")
                    .withStyle(ChatFormatting.DARK_GRAY)));
        }

        if (bestSell != null) {
            feedback.accept(prefix().append(Component.literal("Best Sell: ")
                    .withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(bestSell.name + " " + format(bestSell.sell))
                            .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));
        } else {
            feedback.accept(prefix().append(Component.literal("Best Sell: n/a")
                    .withStyle(ChatFormatting.DARK_GRAY)));
        }
    }

    private static void sendTopHighestSell(Consumer<Component> feedback, List<FlipCandidate> candidates) {
        List<FlipCandidate> highestSell = candidates.stream()
                .filter(c -> c.sell != null)
                .sorted(Comparator.comparingDouble((FlipCandidate c) -> c.sell).reversed())
                .toList();

        if (highestSell.isEmpty()) {
            feedback.accept(prefix().append(Component.literal("No sell prices found in the current GUI.")
                    .withStyle(ChatFormatting.RED)));
            return;
        }

        feedback.accept(prefix().append(Component.literal("Top 5 Highest Sell")
                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));

        int limit = Math.min(5, highestSell.size());
        for (int i = 0; i < limit; i++) {
            FlipCandidate entry = highestSell.get(i);
            boolean topOne = i == 0;
            feedback.accept(prefix().append(Component.literal((i + 1) + ". ")
                    .withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(entry.name + " " + format(entry.sell))
                            .withStyle(topOne ? ChatFormatting.BOLD : ChatFormatting.WHITE)));
        }
    }

    private static void sendMarketRateAlerts(Consumer<Component> feedback, List<FlipCandidate> candidates) {
        List<FlipCandidate> alerts = candidates.stream()
                .filter(c -> c.marketRate != null && c.marketRate > 100.0)
                .sorted(Comparator.comparingDouble(FlipCandidate::marketRate).reversed())
                .toList();

        if (alerts.isEmpty()) {
            return;
        }

        int limit = Math.min(5, alerts.size());
        for (int i = 0; i < limit; i++) {
            FlipCandidate entry = alerts.get(i);
            feedback.accept(prefix().append(Component.literal("Market Rate > 100%: ")
                    .withStyle(ChatFormatting.RED))
                    .append(Component.literal(entry.name + " " + formatPercent(entry.marketRate))
                            .withStyle(ChatFormatting.GOLD)));
        }
    }

    private static String format(Double value) {
        if (value == null) {
            return "n/a";
        }
        double abs = Math.abs(value);
        if (abs < 1.0) {
            return String.format(Locale.ROOT, "%.3f", value);
        }
        if (Math.rint(value) != value) {
            return String.format(Locale.ROOT, "%,.2f", value);
        }
        return String.format(Locale.ROOT, "%,.0f", value);
    }

    private static String formatPercent(Double value) {
        if (value == null) {
            return "n/a";
        }
        if (Math.rint(value) != value) {
            return String.format(Locale.ROOT, "%.2f%%", value);
        }
        return String.format(Locale.ROOT, "%.0f%%", value);
    }

    private static MutableComponent prefix() {
        return Component.literal("[Bazaar] ").withStyle(ChatFormatting.DARK_AQUA);
    }

    static boolean isBlacklisted(String name, String itemId) {
        if (CROP_BLACKLIST.isEmpty()) {
            return false;
        }
        String normalizedName = normalize(name);
        String normalizedItemId = normalizeItemId(itemId);
        for (String blocked : CROP_BLACKLIST) {
            if (blocked.isBlank()) {
                continue;
            }
            if (!normalizedName.isBlank() && normalizedName.contains(blocked)) {
                return true;
            }
            if (!normalizedItemId.isBlank() && normalizedItemId.equals(blocked)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isElysiumCrop(FlipCandidate candidate) {
        if (candidate == null || candidate.name == null) {
            return false;
        }
        String normalized = normalize(candidate.name);
        if (normalized.isBlank()) {
            return false;
        }
        return ELYSIUM_NAMES.stream().anyMatch(normalized::contains);
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");

        return normalized.replace("rasin", "resin");
    }

    private static String normalizeItemId(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return "";
        }
        int idx = itemId.indexOf(':');
        String path = idx >= 0 && idx + 1 < itemId.length() ? itemId.substring(idx + 1) : itemId;
        return normalize(path);
    }

    record FlipCandidate(String name, String itemId, Double buy, Double sell, Double marketRate, Integer stock) {
    }

    private static final class PriceInfo {
        private final Double buy;
        private final Double sell;
        private final Double marketRate;
        private final Integer stock;

        private PriceInfo(Double buy, Double sell, Double marketRate, Integer stock) {
            this.buy = buy;
            this.sell = sell;
            this.marketRate = marketRate;
            this.stock = stock;
        }

        static PriceInfo fromStack(Minecraft client, ItemStack stack) {
            List<String> loreLines = readLoreLines(client, stack);
            Double buy = null;
            Double sell = null;
            Double unitValue = null;
            Double marketRate = null;
            Integer stock = null;
            List<Double> looseNumbers = new ArrayList<>();
            for (String line : loreLines) {
                String lowerLine = line.replace("§", "").toLowerCase(Locale.ROOT);
                boolean priceLessLine = lowerLine.contains("supply")
                        || lowerLine.contains("stock")
                        || lowerLine.contains("you have")
                        || lowerLine.contains("click");
                Double buyValue = priceLessLine ? null : parsePriceAfterKeyword(line, BUY_PATTERN);
                if (buyValue != null) {
                    buy = buyValue;
                }
                Double sellValue = priceLessLine ? null : parsePriceAfterKeyword(line, SELL_PATTERN);
                if (sellValue != null) {
                    sell = sellValue;
                }
                Double valueValue = priceLessLine ? null : parsePriceAfterKeyword(line, VALUE_PATTERN);
                if (valueValue != null) {
                    unitValue = valueValue;
                }
                Double rateValue = parseMarketRate(line);
                if (rateValue != null) {
                    marketRate = rateValue;
                }
                Integer stockValue = parseStock(line);
                if (stockValue != null) {
                    stock = stockValue;
                }
                if (!priceLessLine) {
                    collectLooseNumbers(line, looseNumbers);
                }
            }

            if (unitValue != null) {
                if (buy == null) {
                    buy = unitValue;
                }
                if (sell == null) {
                    sell = unitValue;
                }
            }
            if (buy == null && !looseNumbers.isEmpty()) {
                buy = looseNumbers.get(0);
            }
            if (sell == null && looseNumbers.size() > 1) {
                sell = looseNumbers.get(1);
            }
            return new PriceInfo(buy, sell, marketRate, stock);
        }

        private static List<String> readLoreLines(Minecraft client, ItemStack stack) {
            if (client.level == null) {
                return List.of();
            }
            List<Component> tooltip = stack.getTooltipLines(Item.TooltipContext.of(client.level), client.player, TooltipFlag.NORMAL);
            if (tooltip.isEmpty()) {
                return List.of();
            }
            List<String> lines = new ArrayList<>(tooltip.size());
            for (Component component : tooltip) {
                String plain = component.getString();
                if (!plain.isBlank()) {
                    lines.add(plain);
                }
            }
            return lines;
        }

        private static Double parsePriceAfterKeyword(String line, Pattern pattern) {
            Matcher matcher = pattern.matcher(line.replace("§", ""));
            if (!matcher.find()) {
                return null;
            }
            return parseNumber(matcher.group(1), matcher.group(2));
        }

        private static Double parseNumber(String numberRaw, String suffix) {
            if (numberRaw == null) {
                return null;
            }
            String normalized = numberRaw.replace(",", "").replace("_", "");
            double value;
            try {
                value = Double.parseDouble(normalized);
            } catch (NumberFormatException ex) {
                return null;
            }
            if (suffix != null && !suffix.isEmpty()) {
                switch (suffix.toLowerCase(Locale.ROOT)) {
                    case "k" -> value *= 1_000d;
                    case "m" -> value *= 1_000_000d;
                    case "b" -> value *= 1_000_000_000d;
                    case "t" -> value *= 1_000_000_000_000d;
                    default -> {
                    }
                }
            }
            return value;
        }

        private static Double parseMarketRate(String line) {
            Matcher matcher = MARKET_RATE_PATTERN.matcher(line.replace("§", ""));
            if (!matcher.find()) {
                return null;
            }
            try {
                return Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException ex) {
                return null;
            }
        }

        private static Integer parseStock(String line) {
            Matcher matcher = STOCK_PATTERN.matcher(line.replace("§", ""));
            if (!matcher.find()) {
                return null;
            }
            Double value = parseNumber(matcher.group(1), matcher.group(2));
            if (value == null || value < 0) {
                return null;
            }
            return (int) Math.min(Integer.MAX_VALUE, Math.round(value));
        }
        private static void collectLooseNumbers(String line, List<Double> out) {
            String plain = line.replace("§", "").replace("Â§", "");
            if (plain.contains("%")) {
                return;
            }
            Matcher matcher = LOOSE_NUMBER_PATTERN.matcher(plain);
            while (matcher.find()) {
                Double value = parseNumber(matcher.group(1), matcher.group(2));
                if (value != null) {
                    out.add(value);
                }
            }
        }
    }
}

package bazaar.bazzar.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

final class MultiplierMenuScanner {
    private static final int SCAN_INTERVAL_TICKS = 10;
    private static final double MULTI_STEP = 0.5d;
    private static final int MAX_STEPS_PER_UPGRADE = 6;
    private static final Pattern SHOWN_MULTI_PATTERN =
            Pattern.compile("crop multiplier:\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*x");
    private static final Pattern COST_PATTERN =
            Pattern.compile("([0-9][0-9,]*)(?:\\.([0-9]+))?\\s*([A-Za-z]{0,2})");

    private static int tickCounter = 0;
    private static double lastHandledOnceCost = -1.0d;

    private MultiplierMenuScanner() {
    }

    static void tick(Minecraft client) {
        if (client.player == null || !(McCompat.screen(client) instanceof AbstractContainerScreen<?> screen)) {
            tickCounter = 0;
            lastHandledOnceCost = -1.0d;
            return;
        }
        tickCounter++;
        if (tickCounter % SCAN_INTERVAL_TICKS != 0) {
            return;
        }

        for (Slot slot : screen.getMenu().slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            if (slot.container instanceof net.minecraft.world.entity.player.Inventory) {
                continue;
            }
            String name = stack.getHoverName().getString();
            String lowerName = name == null ? "" : name.toLowerCase(Locale.ROOT);
            if (lowerName.contains("token upgrades")) {
                Double shown = shownMultiplier(client, stack);
                if (shown != null) {
                    apply(shown);
                }
                continue;
            }
            if (!lowerName.contains("upgrade multiplier")) {
                continue;
            }
            handleUpgradeItem(client, stack);
            return;
        }
    }

    private static void handleUpgradeItem(Minecraft client, ItemStack stack) {
        List<String> lines = tooltipLines(client, stack);
        ParsedCost once = null;
        ParsedCost max = null;
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (once == null && lower.contains("+0.5x multiplier")) {
                once = parseCostAfter(line);
            } else if (max == null && lower.contains("+3x multiplier")) {
                max = parseCostAfter(line);
            }
        }
        if (once == null) {
            return;
        }
        if (lastHandledOnceCost >= 0.0d && Math.abs(once.value - lastHandledOnceCost) <= once.tolerance) {
            return;
        }
        double estimate = ShardCalculator.multiplierFromNextCost(once.value);
        List<Double> matches = deriveCandidates(estimate, once, max);
        if (matches.isEmpty()) {
            return;
        }
        lastHandledOnceCost = once.value;

        double current = ShardCalculator.getCurrentMulti();
        boolean exact = matches.size() == 1;
        if (!exact) {
            double min = matches.get(0);
            double maxCandidate = matches.get(matches.size() - 1);
            if (current >= min - MULTI_STEP / 2.0d && current <= maxCandidate + MULTI_STEP / 2.0d) {
                return;
            }
        }
        double best = matches.get(0);
        double bestDiff = Double.MAX_VALUE;
        for (double candidate : matches) {
            double diff = Math.abs(candidate - estimate);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = candidate;
            }
        }
        apply(best);
    }

    private static List<Double> deriveCandidates(double estimate, ParsedCost once, ParsedCost max) {
        long centerSteps = Math.round(estimate / MULTI_STEP);
        List<Double> matches = new ArrayList<>();
        for (long steps = centerSteps - 8; steps <= centerSteps + 8; steps++) {
            double candidate = steps * MULTI_STEP;
            if (candidate < 1.0d) {
                continue;
            }
            double costOnce = ShardCalculator.getMultiplierCost(candidate);
            if (Math.abs(costOnce - once.value) > once.tolerance) {
                continue;
            }
            if (max != null) {
                double costMax = 0.0d;
                for (int i = 0; i < MAX_STEPS_PER_UPGRADE; i++) {
                    costMax += ShardCalculator.getMultiplierCost(candidate + i * MULTI_STEP);
                }
                if (Math.abs(costMax - max.value) > max.tolerance) {
                    continue;
                }
            }
            matches.add(candidate);
        }
        return matches;
    }

    private static Double shownMultiplier(Minecraft client, ItemStack stack) {
        for (String line : tooltipLines(client, stack)) {
            Matcher matcher = SHOWN_MULTI_PATTERN.matcher(line.toLowerCase(Locale.ROOT));
            if (matcher.find()) {
                try {
                    return Double.parseDouble(matcher.group(1).replace(",", ""));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static void apply(double multi) {
        double previous = ShardCalculator.getCurrentMulti();
        if (Math.abs(multi - previous) < MULTI_STEP / 2.0d) {
            return;
        }
        ShardCalculator.setCurrentMulti(multi);
        BazzarConfig.State state = BazzarConfig.load();
        state.currentMulti = multi;
        BazzarConfig.save(state);
    }

    private static ParsedCost parseCostAfter(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        int index = lower.indexOf("multiplier");
        if (index < 0) {
            return null;
        }
        String segment = line.substring(index + "multiplier".length());
        Matcher matcher = COST_PATTERN.matcher(segment);
        if (!matcher.find()) {
            return null;
        }
        String wholePart = matcher.group(1).replace(",", "");
        String decimalPart = matcher.group(2);
        String suffix = matcher.group(3) == null ? "" : matcher.group(3);
        double value;
        try {
            value = Double.parseDouble(decimalPart == null ? wholePart : wholePart + "." + decimalPart);
        } catch (NumberFormatException ex) {
            return null;
        }
        double unit = 1.0d;
        if (!suffix.isEmpty()) {
            Double parsedUnit = ShardCalculator.suffixMultiplier(suffix);
            if (parsedUnit == null) {
                return null;
            }
            unit = parsedUnit;
        }
        int decimals = decimalPart == null ? 0 : decimalPart.length();
        double tolerance = 0.5d * Math.pow(10.0d, -decimals) * unit * 1.05d;
        return new ParsedCost(value * unit, tolerance);
    }

    private static List<String> tooltipLines(Minecraft client, ItemStack stack) {
        List<String> lines = new ArrayList<>();
        try {
            List<Component> tooltip = stack.getTooltipLines(
                    net.minecraft.world.item.Item.TooltipContext.of(client.level),
                    client.player,
                    net.minecraft.world.item.TooltipFlag.Default.NORMAL
            );
            for (Component part : tooltip) {
                if (part != null) {
                    lines.add(SbText.stripSprites(part.getString()));
                }
            }
        } catch (Exception ignored) {
        }
        return lines;
    }

    private record ParsedCost(double value, double tolerance) {
    }
}

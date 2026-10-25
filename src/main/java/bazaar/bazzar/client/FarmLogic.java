package bazaar.bazzar.client;

import java.util.List;
import java.util.Locale;

final class FarmLogic {
    private FarmLogic() {
    }

    static String questToFarmCommand(String questName) {
        if (questName == null) {
            return null;
        }
        String lower = questName.trim().toLowerCase(Locale.ROOT);
        if (!lower.startsWith("farm ")) {
            return null;
        }
        String rest = lower.substring(5).trim();
        rest = rest.replaceFirst("^[\\d.,]+\\s*", "");
        return cropToFarmCommand(rest);
    }

    static String cropToFarmCommand(String cropName) {
        if (cropName == null) {
            return null;
        }
        String compact = cropName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (compact.isEmpty()) {
            return null;
        }
        return "farms " + compact;
    }

    static int pickBestIndex(List<String> names, List<Double> values) {
        if (names == null || values == null) {
            return -1;
        }
        int best = -1;
        double bestValue = 0d;
        int count = Math.min(names.size(), values.size());
        for (int i = 0; i < count; i++) {
            Double value = values.get(i);
            if (value == null || value <= 0d) {
                continue;
            }
            if (cropToFarmCommand(names.get(i)) == null) {
                continue;
            }
            if (value > bestValue) {
                bestValue = value;
                best = i;
            }
        }
        return best;
    }
}

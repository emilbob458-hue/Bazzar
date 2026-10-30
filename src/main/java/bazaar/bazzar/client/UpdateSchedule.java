package bazaar.bazzar.client;

// when a required update is allowed to close the game. Kept apart from AutoUpdater, which reaches
// for the Fabric config directory as soon as it loads, so the rules can be read and tested on their own.
final class UpdateSchedule {
    // the longest the update can be put off for, and what it waits by default if nobody picks
    static final long MAX_DELAY_MS = 24L * 60L * 60_000L;
    // the countdown title only runs over the last minute, not for however many hours are left
    static final long FINAL_WARNING_MS = 60_000L;

    private UpdateSchedule() {
    }

    // what the player asked for, never past the day they are allowed to put it off for
    static long clampDelayMs(long requestedMs) {
        return Math.max(0L, Math.min(MAX_DELAY_MS, requestedMs));
    }

    // "4h", "90m", "1h30m", "1h 30m", "now" or a plain number of minutes. -1 if it reads as none
    // of those, so the command can say so instead of quietly closing the game at the wrong time.
    static long parseDelayMs(String text) {
        if (text == null) {
            return -1L;
        }
        String cleaned = text.trim().toLowerCase().replace(" ", "");
        if (cleaned.isEmpty()) {
            return -1L;
        }
        if (cleaned.equals("now")) {
            return 0L;
        }
        long total = 0L;
        int i = 0;
        boolean any = false;
        while (i < cleaned.length()) {
            int start = i;
            while (i < cleaned.length() && Character.isDigit(cleaned.charAt(i))) {
                i++;
            }
            if (i == start) {
                return -1L;
            }
            long amount;
            try {
                amount = Long.parseLong(cleaned.substring(start, i));
            } catch (NumberFormatException e) {
                return -1L;
            }
            int unitStart = i;
            while (i < cleaned.length() && Character.isLetter(cleaned.charAt(i))) {
                i++;
            }
            long unitMs = unitMs(cleaned.substring(unitStart, i));
            if (unitMs < 0L) {
                return -1L;
            }
            total += amount * unitMs;
            any = true;
        }
        return any ? total : -1L;
    }

    // a bare number is minutes, which is what the buttons send and what people type most
    private static long unitMs(String unit) {
        if (unit.isEmpty() || unit.startsWith("min") || unit.equals("m")) {
            return 60_000L;
        }
        if (unit.startsWith("h")) {
            return 60L * 60_000L;
        }
        if (unit.startsWith("s")) {
            return 1_000L;
        }
        if (unit.startsWith("d")) {
            return 24L * 60L * 60_000L;
        }
        return -1L;
    }

    static String whenText(long leftMs) {
        if (leftMs <= 0L) {
            return "now";
        }
        long minutes = (leftMs + 59_999L) / 60_000L;
        if (minutes < 60L) {
            return "in " + minutes + "m";
        }
        long hours = minutes / 60L;
        long rest = minutes % 60L;
        return rest == 0L ? "in " + hours + "h" : "in " + hours + "h " + rest + "m";
    }
}

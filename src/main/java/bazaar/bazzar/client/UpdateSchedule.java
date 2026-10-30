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

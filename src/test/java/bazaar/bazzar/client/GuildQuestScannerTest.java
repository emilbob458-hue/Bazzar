package bazaar.bazzar.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

// the refresh timer the auto scan runs off: what the /gq tooltip is read as, and that a new set of
// quests starts clean instead of inheriting the last set's completed ones
class GuildQuestScannerTest {
    private static final long HOUR_MS = 3_600_000L;

    private static void share(long refreshAtMs, String... names) {
        List<String[]> quests = new java.util.ArrayList<>();
        for (String name : names) {
            quests.add(new String[]{name, "0", "1,000"});
        }
        GuildQuestScanner.applyShared(quests, refreshAtMs);
    }

    private static boolean done(String name) {
        for (GuildQuestScanner.QuestEntry entry : GuildQuestScanner.getLastEntries()) {
            if (entry.name().equals(name)) {
                return entry.done();
            }
        }
        throw new AssertionError("no quest called " + name);
    }

    // ---- reading the tooltip ----

    @Test
    void readsTheTooltipCountdown() {
        assertEquals(2 * HOUR_MS + 14 * 60_000L, GuildQuestScanner.parseDurationMs("New quests available in 2h 14m"));
        assertEquals(HOUR_MS, GuildQuestScanner.parseDurationMs("1h"));
        assertEquals(45_000L, GuildQuestScanner.parseDurationMs("in 45s"));
        assertEquals(HOUR_MS + 2 * 60_000L + 3_000L, GuildQuestScanner.parseDurationMs("1h 2m 3s"));
        // nothing to count down to, so the scan has no time to wait for
        assertEquals(0L, GuildQuestScanner.parseDurationMs("New quests available soon"));
        assertEquals(0L, GuildQuestScanner.parseDurationMs(""));
    }

    // ---- the quest cycle ----

    @Test
    void keepsQuestsDoneWhileTheSameCycleCountsDown() {
        long refreshAt = System.currentTimeMillis() + 4 * HOUR_MS;
        share(refreshAt, "Kill 1,000 Bats", "Farm 5,000 Wheat");
        GuildQuestScanner.onChatLine("GUILDS • Guild quest complete: Kill 1,000 Bats");
        assertTrue(done("Kill 1,000 Bats"));
        assertFalse(done("Farm 5,000 Wheat"));

        // the tooltip only counts whole minutes, so the same cycle comes back a little different
        share(refreshAt - 30_000L, "Kill 1,000 Bats", "Farm 5,000 Wheat");
        assertTrue(done("Kill 1,000 Bats"));
    }

    @Test
    void anewCycleDoesNotInheritTheLastOneAsDone() {
        long refreshAt = System.currentTimeMillis() + 4 * HOUR_MS;
        share(refreshAt, "Kill 1,000 Bats");
        GuildQuestScanner.onChatLine("GUILDS • Guild quest complete: Kill 1,000 Bats");
        assertTrue(done("Kill 1,000 Bats"));

        // the quests refreshed and handed out the same one again, which nobody has touched yet
        share(refreshAt + 4 * HOUR_MS, "Kill 1,000 Bats");
        assertFalse(done("Kill 1,000 Bats"));
        assertEquals(1, GuildQuestScanner.getOpenEntries().size());
        assertFalse(GuildQuestScanner.allDone());
    }

    @Test
    void countsDownToTheRefreshItWasGiven() {
        share(System.currentTimeMillis() + 90_000L, "Kill 1,000 Bats");
        int seconds = GuildQuestScanner.getSecondsUntilQuestRefresh();
        assertTrue(seconds > 80 && seconds <= 90, "seconds was " + seconds);
        assertTrue(GuildQuestScanner.refreshCountdownText().startsWith("1m "),
                GuildQuestScanner.refreshCountdownText());

        share(System.currentTimeMillis() + 2 * HOUR_MS, "Kill 1,000 Bats");
        assertTrue(GuildQuestScanner.refreshCountdownText().contains("h "),
                GuildQuestScanner.refreshCountdownText());

        // a cycle that has run out reads as ready, which is what makes the auto scan due
        share(System.currentTimeMillis() - 60_000L, "Kill 1,000 Bats");
        assertEquals(0, GuildQuestScanner.getSecondsUntilQuestRefresh());
        assertEquals("Ready", GuildQuestScanner.refreshCountdownText());
    }

    @Test
    void chatCompletionOnlyMatchesTheQuestItNames() {
        share(System.currentTimeMillis() + 7 * HOUR_MS, "Kill 1,000 Bats", "Kill 1,000 Cave Spiders");
        GuildQuestScanner.onChatLine("GUILDS • Guild quest complete: Kill 1,000 Cave Spiders");
        assertFalse(done("Kill 1,000 Bats"));
        assertTrue(done("Kill 1,000 Cave Spiders"));

        GuildQuestScanner.onChatLine("GUILDS • Somebody said guild quest complete");
        assertFalse(done("Kill 1,000 Bats"));
        GuildQuestScanner.onChatLine(null);
        assertFalse(done("Kill 1,000 Bats"));
    }

    // ---- holding the quest key to keep /gq up ----

    private static GuildQuestScanner.MenuAction action(boolean keyHeld, boolean menuOpen, boolean wasHeld) {
        return GuildQuestScanner.menuAction(keyHeld, menuOpen, wasHeld);
    }

    @Test
    void theMenuStaysUpWhileTheKeyIsHeld() {
        assertEquals(GuildQuestScanner.MenuAction.KEEP_OPEN, action(true, true, false));
        assertEquals(GuildQuestScanner.MenuAction.KEEP_OPEN, action(true, true, true));
    }

    @Test
    void lettingGoReadsTheMenuOnceMoreBeforeClosing() {
        // a quest may have been claimed while it was up, so the numbers are read again
        assertEquals(GuildQuestScanner.MenuAction.READ_AGAIN, action(false, true, true));
    }

    @Test
    void aScanNobodyHeldClosesWithoutASecondRead() {
        assertEquals(GuildQuestScanner.MenuAction.CLOSE, action(false, true, false));
    }

    @Test
    void closingTheMenuYourselfEndsTheHold() {
        assertEquals(GuildQuestScanner.MenuAction.CLOSE, action(true, false, true));
        assertEquals(GuildQuestScanner.MenuAction.CLOSE, action(false, false, true));
        assertEquals(GuildQuestScanner.MenuAction.CLOSE, action(false, false, false));
    }
}

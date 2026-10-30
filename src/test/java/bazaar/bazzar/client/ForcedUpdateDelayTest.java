package bazaar.bazzar.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// a forced update now waits for the player to say when, up to a day, instead of closing the game
// out from under them after a minute
class ForcedUpdateDelayTest {
    private static final long MINUTE = 60_000L;

    @Test
    void theLongestYouCanPutItOffIsADay() {
        assertEquals(24L * 60L * MINUTE, UpdateSchedule.MAX_DELAY_MS);
        assertEquals(UpdateSchedule.MAX_DELAY_MS, UpdateSchedule.clampDelayMs(UpdateSchedule.MAX_DELAY_MS));
        // asking for a week still only buys you the day
        assertEquals(UpdateSchedule.MAX_DELAY_MS, UpdateSchedule.clampDelayMs(7L * 24L * 60L * MINUTE));
    }

    @Test
    void askingForNowMeansNow() {
        assertEquals(0L, UpdateSchedule.clampDelayMs(0L));
        assertEquals(0L, UpdateSchedule.clampDelayMs(-5L * MINUTE));
    }

    @Test
    void anythingInsideTheDayIsKept() {
        assertEquals(60L * MINUTE, UpdateSchedule.clampDelayMs(60L * MINUTE));
        assertEquals(4L * 60L * MINUTE, UpdateSchedule.clampDelayMs(4L * 60L * MINUTE));
        assertEquals(12L * 60L * MINUTE, UpdateSchedule.clampDelayMs(12L * 60L * MINUTE));
    }

    @Test
    void theWaitReadsTheWayAPersonWouldSayIt() {
        assertEquals("now", UpdateSchedule.whenText(0L));
        assertEquals("in 1m", UpdateSchedule.whenText(MINUTE));
        assertEquals("in 45m", UpdateSchedule.whenText(45L * MINUTE));
        assertEquals("in 1h", UpdateSchedule.whenText(60L * MINUTE));
        assertEquals("in 4h", UpdateSchedule.whenText(4L * 60L * MINUTE));
        assertEquals("in 1h 30m", UpdateSchedule.whenText(90L * MINUTE));
        assertEquals("in 24h", UpdateSchedule.whenText(UpdateSchedule.MAX_DELAY_MS));
    }

    @Test
    void hoursAreReadTheWayPeopleTypeThem() {
        // the buttons say 4h, so typing 4h has to mean the same thing
        assertEquals(4L * 60L * MINUTE, UpdateSchedule.parseDelayMs("4h"));
        assertEquals(60L * MINUTE, UpdateSchedule.parseDelayMs("1h"));
        assertEquals(12L * 60L * MINUTE, UpdateSchedule.parseDelayMs("12h"));
        assertEquals(2L * 60L * MINUTE, UpdateSchedule.parseDelayMs("2 hours"));
        assertEquals(3L * 60L * MINUTE, UpdateSchedule.parseDelayMs("3HR"));
    }

    @Test
    void minutesStillWork() {
        assertEquals(30L * MINUTE, UpdateSchedule.parseDelayMs("30m"));
        assertEquals(45L * MINUTE, UpdateSchedule.parseDelayMs("45 mins"));
        // a bare number is minutes, which is what the old command took
        assertEquals(90L * MINUTE, UpdateSchedule.parseDelayMs("90"));
        assertEquals(0L, UpdateSchedule.parseDelayMs("0"));
        assertEquals(0L, UpdateSchedule.parseDelayMs("now"));
        assertEquals(0L, UpdateSchedule.parseDelayMs("Now"));
    }

    @Test
    void hoursAndMinutesTogether() {
        assertEquals(90L * MINUTE, UpdateSchedule.parseDelayMs("1h30m"));
        assertEquals(90L * MINUTE, UpdateSchedule.parseDelayMs("1h 30m"));
        // a number with no unit after an hour is still minutes
        assertEquals(90L * MINUTE, UpdateSchedule.parseDelayMs("1h30"));
    }

    @Test
    void nonsenseIsRefusedInsteadOfGuessed() {
        assertEquals(-1L, UpdateSchedule.parseDelayMs("later"));
        assertEquals(-1L, UpdateSchedule.parseDelayMs("4 weeks"));
        assertEquals(-1L, UpdateSchedule.parseDelayMs("h"));
        assertEquals(-1L, UpdateSchedule.parseDelayMs(""));
        assertEquals(-1L, UpdateSchedule.parseDelayMs("   "));
        assertEquals(-1L, UpdateSchedule.parseDelayMs(null));
        assertEquals(-1L, UpdateSchedule.parseDelayMs("-5m"));
    }

    @Test
    void whatYouTypeAndWhatItSaysBackAgree() {
        for (String option : new String[]{"now", "30m", "1h", "4h", "12h", "24h"}) {
            long parsed = UpdateSchedule.parseDelayMs(option);
            assertTrue(parsed >= 0L, option);
            assertEquals(parsed, UpdateSchedule.clampDelayMs(parsed), option);
        }
        assertEquals("in 4h", UpdateSchedule.whenText(UpdateSchedule.parseDelayMs("4h")));
        assertEquals("in 1h 30m", UpdateSchedule.whenText(UpdateSchedule.parseDelayMs("1h30m")));
    }

    @Test
    void theCountdownOnlyRunsOverTheLastMinute() {
        // otherwise a day's wait would put a red title on screen every ten seconds for 24 hours
        assertEquals(60_000L, UpdateSchedule.FINAL_WARNING_MS);
        assertTrue(UpdateSchedule.FINAL_WARNING_MS < UpdateSchedule.MAX_DELAY_MS);
    }
}

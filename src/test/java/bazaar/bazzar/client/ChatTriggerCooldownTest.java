package bazaar.bazzar.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// the gap between two firings of a chat trigger, which is what stops "hotdog" in chat turning
// into a stream of commands
class ChatTriggerCooldownTest {
    private static final long FIRED_AT = 1_700_000_000_000L;

    @Test
    void aTriggerWaitsFiveSecondsBeforeItCanGoAgain() {
        assertEquals(5_000L, ChatCommandTriggerController.COOLDOWN_MS);
        assertFalse(ChatCommandTriggerController.readyToFire(FIRED_AT, FIRED_AT));
        assertFalse(ChatCommandTriggerController.readyToFire(FIRED_AT, FIRED_AT + 1_000L));
        assertFalse(ChatCommandTriggerController.readyToFire(FIRED_AT, FIRED_AT + 4_999L));
        assertTrue(ChatCommandTriggerController.readyToFire(FIRED_AT, FIRED_AT + 5_000L));
        assertTrue(ChatCommandTriggerController.readyToFire(FIRED_AT, FIRED_AT + 60_000L));
    }

    @Test
    void aTriggerThatHasNeverFiredIsReady() {
        assertTrue(ChatCommandTriggerController.readyToFire(0L, FIRED_AT));
    }

    @Test
    void theSpamGuardDoesNotQuietlyOverrideTheCooldown() {
        // the window has to hold a full run at the cooldown's pace, or the real gap between
        // commands would silently be longer than the five seconds above
        long paced = ChatCommandTriggerController.MAX_FIRES_PER_WINDOW * ChatCommandTriggerController.COOLDOWN_MS;
        assertTrue(paced <= ChatCommandTriggerController.FIRE_WINDOW_MS,
                paced + "ms of firing in a " + ChatCommandTriggerController.FIRE_WINDOW_MS + "ms window");
        assertTrue(ChatCommandTriggerController.MAX_FIRES_PER_WINDOW >= 2);
    }

    // ---- the alts taking turns ----

    private static int indexOf(String self, String owner, String... members) {
        return ChatCommandTriggerController.altIndex(self, owner, java.util.List.of(members));
    }

    @Test
    void eachAltWaitsOneMoreCooldownThanTheLast() {
        assertEquals(0L, ChatCommandTriggerController.staggerDelayMs(0));
        assertEquals(5_000L, ChatCommandTriggerController.staggerDelayMs(1));
        assertEquals(10_000L, ChatCommandTriggerController.staggerDelayMs(2));
        assertEquals(15_000L, ChatCommandTriggerController.staggerDelayMs(3));
    }

    @Test
    void theOwnerGoesFirstThenTheMembersInOrder() {
        assertEquals(0, indexOf("Main", "Main", "AltOne", "AltTwo"));
        assertEquals(1, indexOf("AltOne", "Main", "AltOne", "AltTwo"));
        assertEquals(2, indexOf("AltTwo", "Main", "AltOne", "AltTwo"));
    }

    @Test
    void emptySlotsDoNotLeaveAGapInTheTurns() {
        // a blank slot between two alts must not make one of them wait an extra five seconds
        assertEquals(1, indexOf("AltOne", "Main", "", "AltOne", ""));
        assertEquals(2, indexOf("AltTwo", "Main", "", "AltOne", "", "AltTwo"));
    }

    @Test
    void withNoOwnerSetTheFirstMemberGoesFirst() {
        assertEquals(0, indexOf("AltOne", "", "AltOne", "AltTwo"));
        assertEquals(1, indexOf("AltTwo", "", "AltOne", "AltTwo"));
    }

    @Test
    void anAccountOnNobodysListGoesStraightAway() {
        assertEquals(0, indexOf("Stranger", "Main", "AltOne", "AltTwo"));
        assertEquals(0, indexOf("", "Main", "AltOne"));
        assertEquals(0, indexOf("Main", "", ""));
        assertEquals(0, ChatCommandTriggerController.altIndex("Main", "Main", null));
    }

    @Test
    void theNameIsMatchedHoweverTheServerDressesItUp() {
        assertEquals(1, indexOf("§aAltOne", "Main", "AltOne"));
        assertEquals(1, indexOf("AltOne", "Main", "[MVP] AltOne"));
        assertEquals(1, indexOf("altone", "MAIN", "AltOne"));
    }

    @Test
    void aStaggerNeverRunsBackwards() {
        assertEquals(0L, ChatCommandTriggerController.staggerDelayMs(-1));
    }
}

package bazaar.bazzar.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// the one gate every command this mod sends has to pass through, after the server kicked for
// "sending commands too fast" when a cookie switch, a claimall and a payout landed together
class CommandSenderTest {
    private static final long SENT_AT = 1_700_000_000_000L;

    @Test
    void aRunOfCommandsIsSpacedOut() {
        assertFalse(CommandSender.readyToSend(SENT_AT, SENT_AT));
        assertFalse(CommandSender.readyToSend(SENT_AT, SENT_AT + 1L));
        assertFalse(CommandSender.readyToSend(SENT_AT, SENT_AT + CommandSender.MIN_GAP_MS - 1L));
        assertTrue(CommandSender.readyToSend(SENT_AT, SENT_AT + CommandSender.MIN_GAP_MS));
    }

    @Test
    void theFirstCommandAfterAQuietSpellGoesStraightOut() {
        assertTrue(CommandSender.readyToSend(0L, SENT_AT));
        assertTrue(CommandSender.readyToSend(SENT_AT, SENT_AT + 60_000L));
    }

    @Test
    void theGapIsLongEnoughToBeWorthHaving() {
        // four commands in a tick was enough to get kicked, so they must not go out together
        assertTrue(CommandSender.MIN_GAP_MS >= 500L, "gap is only " + CommandSender.MIN_GAP_MS + "ms");
    }

    @Test
    void aSlashIsOptionalAndBlanksAreDropped() {
        assertEquals("cookie suspicious", CommandSender.clean("/cookie suspicious"));
        assertEquals("cookie suspicious", CommandSender.clean("  cookie suspicious  "));
        assertEquals("claimall", CommandSender.clean("/ claimall"));
        assertEquals("", CommandSender.clean("/"));
        assertEquals("", CommandSender.clean("   "));
        assertEquals("", CommandSender.clean(null));
    }

    @Test
    void sendingWithNoGameQueuesNothing() {
        int before = CommandSender.queued();
        CommandSender.send(null, "cookie suspicious");
        assertEquals(before, CommandSender.queued());
    }
}

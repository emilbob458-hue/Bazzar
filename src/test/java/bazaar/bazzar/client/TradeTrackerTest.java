package bazaar.bazzar.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TradeTrackerTest {
    private static final long T = 1_700_000_000_000L;

    @BeforeEach
    void clear() {
        TradeTracker.reset();
    }

    @Test
    void outgoingTradeBlocksDuringAndRightAfter() {
        TradeTracker.onChatLine("[Trade] The player RuinedByEagles was invited.", T);
        assertFalse(TradeTracker.recentlyTraded("RuinedByEagles", T + 1_000L));
        TradeTracker.onChatLine("[Trade] Your trade request to RuinedByEagles was accepted.", T + 2_000L);
        assertTrue(TradeTracker.recentlyTraded("ruinedbyeagles", T + 5_000L));
        TradeTracker.onChatLine("[Trade] The trade was finished.", T + 10_000L);
        assertTrue(TradeTracker.recentlyTraded("RuinedByEagles", T + 10_000L + TradeTracker.RECENT_TRADE_MS - 1L));
        assertFalse(TradeTracker.recentlyTraded("RuinedByEagles", T + 10_000L + TradeTracker.RECENT_TRADE_MS));
        assertFalse(TradeTracker.recentlyTraded("Smushle", T + 11_000L));
    }

    @Test
    void incomingTradeUsesTheRequester() {
        TradeTracker.onChatLine("[Trade] The player DZJA wants to trade with you. [Accept | Deny]", T);
        TradeTracker.onChatLine("[Trade] You have accepted the trade request.", T + 1_000L);
        TradeTracker.onChatLine("[Trade] The trade was finished.", T + 5_000L);
        assertTrue(TradeTracker.recentlyTraded("DZJA", T + 6_000L));
    }

    @Test
    void cancelledTradeDoesNotBlock() {
        TradeTracker.onChatLine("[Trade] Your trade request to RuinedByEagles was accepted.", T);
        TradeTracker.onChatLine("[Trade] The trade was cancelled.", T + 1_000L);
        assertFalse(TradeTracker.recentlyTraded("RuinedByEagles", T + 2_000L));
    }

    @Test
    void aTradeThatNeverEndsStopsBlockingEventually() {
        TradeTracker.onChatLine("[Trade] Your trade request to RuinedByEagles was accepted.", T);
        assertFalse(TradeTracker.recentlyTraded("RuinedByEagles", T + TradeTracker.ACTIVE_TRADE_MAX_MS));
    }
}

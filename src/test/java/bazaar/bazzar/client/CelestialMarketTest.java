package bazaar.bazzar.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

// the Celestial Boost market without a game around it: the chat lines a payment arrives as, the
// commands and amounts a purchase sends, and which listing the quick buy picks
class CelestialMarketTest {
    private static final String SELF = "RuinedBySeagulls";
    private static final long NOW = 1_700_000_000_000L;

    private static CelestialMarket.Payment parse(String line) {
        return CelestialMarket.parsePayment(clean(line), SELF, NOW);
    }

    // chat reaches onChatLine with sprites and colour codes already taken out
    private static String clean(String line) {
        return line == null ? null : SbText.stripFormattingCodes(SbText.stripSprites(line)).trim();
    }

    private static CelestialMarket.Listing listing(String owner, int stock, long readyAt, double tokens) {
        Map<String, Double> prices = new LinkedHashMap<>();
        prices.put("tokens", tokens);
        return new CelestialMarket.Listing(owner, prices, stock, "cobble", readyAt, true);
    }

    // ---- reading a payment out of chat ----

    @Test
    void readsTheServerPaymentWordings() {
        CelestialMarket.Payment sent = parse("§aTinyTurtle sent you §f1.00M Tokens§a.");
        assertNotNull(sent);
        assertEquals("TinyTurtle", sent.sender());
        assertEquals("tokens", sent.currency());
        assertEquals(1_000_000d, sent.amount());
        assertEquals(NOW, sent.atMs());

        CelestialMarket.Payment paid = parse("TinyTurtle has paid you 250k shards");
        assertNotNull(paid);
        assertEquals("shards", paid.currency());
        assertEquals(250_000d, paid.amount());

        CelestialMarket.Payment gave = parse("TinyTurtle gave you 12 gems");
        assertNotNull(gave);
        assertEquals("gems", gave.currency());
        assertEquals(12d, gave.amount());

        CelestialMarket.Payment received = parse("You received [item/gold_ingot@items]1.5M Tokens from TinyTurtle.");
        assertNotNull(received);
        assertEquals("TinyTurtle", received.sender());
        assertEquals("tokens", received.currency());
        assertEquals(1_500_000d, received.amount());

        CelestialMarket.Payment cobble = parse("You have received 3.25k Cobble from TinyTurtle");
        assertNotNull(cobble);
        assertEquals("cobble", cobble.currency());
        assertEquals(3_250d, cobble.amount());
    }

    @Test
    void readsAPublicTransferOnlyWhenItIsAddressedToUs() {
        CelestialMarket.Payment mine = parse("TinyTurtle sent 1M tokens to " + SELF);
        assertNotNull(mine);
        assertEquals("TinyTurtle", mine.sender());
        assertEquals(1_000_000d, mine.amount());

        assertNull(parse("TinyTurtle sent 1M tokens to SomebodyElse"));
    }

    @Test
    void ignoresLinesThatAreNotAPaymentToUs() {
        assertNull(parse("TinyTurtle wants to buy a Celestial Boost"));
        assertNull(parse("Balance: 4.2M Tokens"));
        // our own payments out must never be read back in as money arriving
        assertNull(parse(SELF + " sent you 1M tokens"));
        assertNull(parse("You received 1M Tokens from " + SELF));
        assertNull(parse(null));
        assertNull(CelestialMarket.parsePayment("TinyTurtle sent you 1M tokens", null, NOW));
    }

    @Test
    void readsCurrencyNamesInEitherNumber() {
        assertEquals("shards", CelestialMarket.currencyOf("Shard"));
        assertEquals("shards", CelestialMarket.currencyOf("shards"));
        assertEquals("gems", CelestialMarket.currencyOf("GEMS"));
        assertEquals("cobble", CelestialMarket.currencyOf("Cobble"));
        assertEquals("tokens", CelestialMarket.currencyOf("Token"));
        assertNull(CelestialMarket.currencyOf("pearls"));
        assertNull(CelestialMarket.currencyOf(null));
    }

    // ---- a failed pay command ----

    @Test
    void spotsAPayCommandThatBounced() {
        assertTrue(CelestialMarket.payFailed("You do not have enough tokens"));
        assertTrue(CelestialMarket.payFailed("Insufficient funds."));
        assertTrue(CelestialMarket.payFailed("You cannot afford that"));
        assertTrue(CelestialMarket.payFailed("You don" + "'" + "t have enough gems"));
        assertFalse(CelestialMarket.payFailed("You sent 1M tokens to TinyTurtle"));
        assertFalse(CelestialMarket.payFailed(null));
    }

    // ---- paying the right amount with the right command ----

    @Test
    void sendsThePayCommandEachCurrencyUses() {
        assertEquals("paytokens", CelestialMarket.payCommand("tokens"));
        assertEquals("gempay", CelestialMarket.payCommand("gems"));
        assertEquals("cobblepay", CelestialMarket.payCommand("cobble"));
        assertEquals("shardpay", CelestialMarket.payCommand("shards"));
    }

    @Test
    void writesPayAmountsTheServerAccepts() {
        // tokens go out compact, the way the payout feature already sends them
        assertEquals("1m", CelestialMarket.payAmount("tokens", 1_000_000d));
        assertEquals("1.5m", CelestialMarket.payAmount("tokens", 1_500_000d));
        assertEquals("250", CelestialMarket.payAmount("tokens", 250d));
        // gems and cobble are whole units only
        assertEquals("12", CelestialMarket.payAmount("gems", 12.9d));
        assertEquals("40", CelestialMarket.payAmount("cobble", 40.2d));
        // shards keep up to two decimals
        assertEquals("5", CelestialMarket.payAmount("shards", 5d));
        assertEquals("5.25", CelestialMarket.payAmount("shards", 5.25d));
    }

    @Test
    void neverPaysMoreThanThePrice() {
        for (String currency : CelestialMarket.CURRENCIES) {
            double price = 1_234_567d;
            Double sent = ShardPayoutController.parseCompactNumber(CelestialMarket.payAmount(currency, price));
            assertNotNull(sent, currency);
            assertTrue(sent <= price, currency + " sent " + sent + " for " + price);
            // and still covers the price the seller's mod checks the payment against
            assertTrue(CelestialMarket.covers(sent, price), currency + " paid " + sent + " of " + price);
        }
    }

    // ---- a payment covering an order ----

    @Test
    void acceptsARoundedPaymentButNotAShortOne() {
        // chat shows "1.00M" for 1,000,000, so a hair under the exact price still has to count
        assertTrue(CelestialMarket.covers(1_000_000d, 1_000_000d));
        assertTrue(CelestialMarket.covers(999_000d, 1_000_000d));
        assertTrue(CelestialMarket.covers(1_200_000d, 1_000_000d));
        assertFalse(CelestialMarket.covers(994_000d, 1_000_000d));
        // two half payments only clear the order together
        assertFalse(CelestialMarket.covers(500_000d, 1_000_000d));
        assertTrue(CelestialMarket.covers(500_000d + 500_000d, 1_000_000d));
    }

    // ---- which listing the shop and the quick buy pick ----

    @Test
    void putsListingsWithStockFirstThenTheSoonestReady() {
        CelestialMarket.Listing ready = listing("Ready", 1, 0L, 5d);
        CelestialMarket.Listing soon = listing("Soon", 0, NOW + 60_000L, 1d);
        CelestialMarket.Listing later = listing("Later", 0, NOW + 600_000L, 1d);
        CelestialMarket.Listing alsoReady = listing("AlsoReady", 2, 0L, 9d);

        List<CelestialMarket.Listing> sorted = CelestialMarket.sorted(List.of(later, soon, ready, alsoReady));
        assertEquals(List.of("Ready", "AlsoReady", "Soon", "Later"),
                sorted.stream().map(CelestialMarket.Listing::owner).toList());
    }

    @Test
    void sortingLeavesTheListItWasGivenAlone() {
        List<CelestialMarket.Listing> original = List.of(listing("B", 0, NOW, 1d), listing("A", 1, 0L, 2d));
        CelestialMarket.sorted(original);
        assertEquals("B", original.get(0).owner());
    }

    @Test
    void quickBuyTakesTheCheapestListingItCanActuallyBuy() {
        CelestialMarket.Listing cheapButEmpty = listing("Empty", 0, NOW, 1d);
        CelestialMarket.Listing mine = listing("Me", 5, 0L, 2d);
        CelestialMarket.Listing cheapest = listing("Cheap", 1, 0L, 3d);
        CelestialMarket.Listing dearest = listing("Dear", 1, 0L, 4d);
        List<CelestialMarket.Listing> all = List.of(cheapButEmpty, mine, cheapest, dearest);

        CelestialMarket.Listing picked = CelestialMarket.cheapest(all, "tokens", "Me");
        assertNotNull(picked);
        assertEquals("Cheap", picked.owner());

        // with no listing priced in that currency there is nothing to buy
        assertNull(CelestialMarket.cheapest(all, "gems", "Me"));
        assertNull(CelestialMarket.cheapest(List.of(), "tokens", "Me"));
        // an alt paying into the same owner must not buy its own boost either
        assertEquals("Cheap", CelestialMarket.cheapest(all, "tokens", "me").owner());
    }

    @Test
    void knowsWhichListingIsYourOwn() {
        CelestialMarket.Listing own = listing("Me", 1, 0L, 1d);
        assertTrue(CelestialMarket.ownedBy("Me", own));
        assertTrue(CelestialMarket.ownedBy("ME", own));
        assertFalse(CelestialMarket.ownedBy("Someone", own));
        assertFalse(CelestialMarket.ownedBy(null, own));
    }

    @Test
    void readsStockAsWhetherABoostIsReady() {
        assertTrue(listing("A", 1, 0L, 1d).ready());
        assertFalse(listing("A", 0, NOW, 1d).ready());
        assertEquals(1d, listing("A", 1, 0L, 1d).price("tokens"));
        assertNull(listing("A", 1, 0L, 1d).price("gems"));
    }

    // ---- the price line the listing shows ----

    @Test
    void writesPricesInTheOrderTheMarketListsCurrencies() {
        Map<String, Double> prices = new LinkedHashMap<>();
        prices.put("cobble", 40d);
        prices.put("tokens", 1_000_000d);
        assertEquals("1M tokens, 40 cobble", CelestialMarket.priceText(prices));

        prices.put("gems", 0d);
        assertEquals("1M tokens, 40 cobble", CelestialMarket.priceText(prices));
        assertEquals("", CelestialMarket.priceText(Map.of()));
    }
}

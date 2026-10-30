package bazaar.bazzar.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// kills counted per kind of mob, keeping "Angelic Vindicator" apart from plain "Vindicator"
class MobKillTrackerTest {
    @BeforeEach
    void freshAccount() {
        MobKillTracker.useAccountForTest("tester");
    }

    private static long session(String mob) {
        MobKillTracker.Entry entry = MobKillTracker.find(mob);
        return entry == null ? -1L : entry.session();
    }

    private static long total(String mob) {
        MobKillTracker.Entry entry = MobKillTracker.find(mob);
        return entry == null ? -1L : entry.total();
    }

    // ---- reading the name off the mob ----

    @Test
    void takesTheNameOverTheMobsHead() {
        assertEquals("Angelic Vindicator", MobKillTracker.tidy("Angelic Vindicator"));
        assertEquals("Angelic Vindicator", MobKillTracker.tidy("§6§lAngelic Vindicator"));
        assertEquals("Vindicator", MobKillTracker.tidy("Vindicator"));
    }

    @Test
    void dropsTheHealthBarTheServerWritesAfterTheName() {
        assertEquals("Angelic Vindicator", MobKillTracker.tidy("Angelic Vindicator 11.45T/11.45T"));
        assertEquals("Vindicator", MobKillTracker.tidy("§cVindicator §a1.43T/1.43T"));
        assertEquals("Vindicator", MobKillTracker.tidy("Vindicator 1,430/1,430"));
        // a label that was nothing but a health bar is not a mob at all
        assertEquals("", MobKillTracker.tidy("11.45T/11.45T"));
        assertEquals("", MobKillTracker.tidy("§a1.43T/1.43T"));
    }

    @Test
    void readsTheSmallCapsAndSymbolsTheServerUses() {
        assertEquals("vindicator", MobKillTracker.tidy("ᴠɪɴᴅɪᴄᴀᴛᴏʀ"));
        assertEquals("Angelic Vindicator", MobKillTracker.tidy("✦ Angelic Vindicator ✦"));
        assertEquals("", MobKillTracker.tidy(null));
        assertEquals("", MobKillTracker.tidy("   "));
    }

    @Test
    void keepsTheTiersApart() {
        // the whole point: the angelic one is not just another vindicator
        assertTrue(!MobKillTracker.key("Angelic Vindicator").equals(MobKillTracker.key("Vindicator")));
        // but the same name written differently is the same mob
        assertEquals(MobKillTracker.key("Angelic Vindicator"), MobKillTracker.key("angelic  vindicator"));
        assertEquals(MobKillTracker.key("Angelic Vindicator"), MobKillTracker.key("Angelic-Vindicator"));
    }

    // ---- counting ----

    @Test
    void countsEachKindSeparately() {
        MobKillTracker.add("Vindicator", 1L);
        MobKillTracker.add("Vindicator", 1L);
        MobKillTracker.add("Angelic Vindicator", 1L);

        assertEquals(2L, session("Vindicator"));
        assertEquals(2L, total("Vindicator"));
        assertEquals(1L, session("Angelic Vindicator"));
        assertNull(MobKillTracker.find("Evoker"));
    }

    @Test
    void addsTheKillsMpsWorkedOutToTheMobYouAreOn() {
        // one kill this mod watched, then MPS takes over as the grinder speeds up
        MobKillTracker.onKill("Angelic Vindicator");
        MobKillTracker.onRateKills(240L);
        assertEquals(241L, session("Angelic Vindicator"));
        assertEquals(241L, total("Angelic Vindicator"));
    }

    @Test
    void rateKillsGoNowhereBeforeAnyMobIsSeen() {
        MobKillTracker.onRateKills(100L);
        assertTrue(MobKillTracker.entries(true).isEmpty());
    }

    @Test
    void ignoresNothingToCount() {
        MobKillTracker.add("Vindicator", 0L);
        MobKillTracker.add("Vindicator", -5L);
        MobKillTracker.add("", 3L);
        MobKillTracker.add(null, 3L);
        assertTrue(MobKillTracker.entries(true).isEmpty());
    }

    // ---- session against all time ----

    @Test
    void resetClearsTheSessionButKeepsTheTotal() {
        MobKillTracker.add("Angelic Vindicator", 40L);
        MobKillTracker.resetSession();
        assertEquals(0L, session("Angelic Vindicator"));
        assertEquals(40L, total("Angelic Vindicator"));

        MobKillTracker.add("Angelic Vindicator", 2L);
        assertEquals(2L, session("Angelic Vindicator"));
        assertEquals(42L, total("Angelic Vindicator"));
    }

    @Test
    void sortsBySessionOrByAllTime() {
        MobKillTracker.add("Vindicator", 500L);
        MobKillTracker.resetSession();
        MobKillTracker.add("Angelic Vindicator", 10L);

        List<MobKillTracker.Entry> bySession = MobKillTracker.entries(false);
        assertEquals("Angelic Vindicator", bySession.get(0).name());

        List<MobKillTracker.Entry> byTotal = MobKillTracker.entries(true);
        assertEquals("Vindicator", byTotal.get(0).name());
    }

    @Test
    void findsAMobWhateverWayItIsTyped() {
        MobKillTracker.add("Angelic Vindicator", 7L);
        assertNotNull(MobKillTracker.find("angelic vindicator"));
        assertNotNull(MobKillTracker.find("ANGELICVINDICATOR"));
        assertEquals(7L, total("Angelic Vindicator"));
    }

    @Test
    void aServerThatNamesEveryMobDifferentlyCannotGrowForEver() {
        for (int i = 0; i < 400; i++) {
            MobKillTracker.add("Mob " + i, i + 1L);
        }
        List<MobKillTracker.Entry> kept = MobKillTracker.entries(true);
        assertTrue(kept.size() <= 300, "kept " + kept.size());
        // the ones dropped are the ones you killed fewest of
        assertEquals("Mob 399", kept.get(0).name());
        assertNull(MobKillTracker.find("Mob 0"));
    }
}

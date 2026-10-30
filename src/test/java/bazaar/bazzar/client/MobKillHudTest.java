package bazaar.bazzar.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// which mobs the kill HUD puts on screen: the one you are on now at the top, then whatever you
// have killed most of this session, without ever listing the same mob twice
class MobKillHudTest {
    @BeforeEach
    void freshAccount() {
        MobKillTracker.useAccountForTest("tester");
    }

    private static List<String> names(List<MobKillTracker.Entry> rows) {
        return rows.stream().map(MobKillTracker.Entry::name).toList();
    }

    private static List<MobKillTracker.Entry> hudRows(String current, int max) {
        return MobKillHud.rows(MobKillTracker.entries(false), current, max);
    }

    @Test
    void putsTheMobYouAreOnAtTheTop() {
        MobKillTracker.add("Vindicator", 900L);
        MobKillTracker.add("Evoker", 400L);
        MobKillTracker.add("Angelic Vindicator", 12L);

        // you have killed far fewer of the angelic one, but it is what you are on
        assertEquals(List.of("Angelic Vindicator", "Vindicator", "Evoker"),
                names(hudRows("Angelic Vindicator", 8)));
    }

    @Test
    void neverListsTheSameMobTwice() {
        MobKillTracker.add("Vindicator", 900L);
        MobKillTracker.add("Evoker", 400L);

        List<String> rows = names(hudRows("Vindicator", 8));
        assertEquals(List.of("Vindicator", "Evoker"), rows);
        assertEquals(rows.size(), rows.stream().distinct().count());
    }

    @Test
    void fallsBackToMostKilledWhenYouAreOnNothing() {
        MobKillTracker.add("Vindicator", 900L);
        MobKillTracker.add("Evoker", 400L);

        assertEquals(List.of("Vindicator", "Evoker"), names(hudRows("", 8)));
        assertEquals(List.of("Vindicator", "Evoker"), names(hudRows(null, 8)));
    }

    @Test
    void aMobYouHaveNotKilledYetDoesNotMakeARow() {
        MobKillTracker.add("Vindicator", 5L);
        // seen but never killed, so there is nothing to count for it
        assertEquals(List.of("Vindicator"), names(hudRows("Pillager", 8)));
    }

    @Test
    void keepsTheListToTheRoomItHas() {
        for (int i = 0; i < 20; i++) {
            MobKillTracker.add("Mob " + i, 100L - i);
        }
        List<MobKillTracker.Entry> rows = hudRows("Mob 19", 8);
        assertEquals(8, rows.size());
        // the one you are on still gets in, even from the bottom of the list
        assertEquals("Mob 19", rows.get(0).name());
        assertEquals(8, names(rows).stream().distinct().count());
    }

    @Test
    void showsNothingBeforeAnythingIsCounted() {
        assertTrue(MobKillHud.rows(MobKillTracker.entries(false), "Vindicator", 8).isEmpty());
        assertTrue(MobKillHud.rows(null, "Vindicator", 8).isEmpty());
    }

    @Test
    void asksForNoRowsAndGetsNone() {
        MobKillTracker.add("Vindicator", 5L);
        assertTrue(MobKillHud.rows(MobKillTracker.entries(false), "Vindicator", 0).isEmpty());
    }
}

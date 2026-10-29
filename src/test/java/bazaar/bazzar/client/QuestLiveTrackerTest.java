package bazaar.bazzar.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

// sims of a player farming between /gq scans: ticks every 50ms like the client, action bar about once a second
class QuestLiveTrackerTest {
    private static final long TICK_MS = 50L;
    private static final long START = 1_000_000L;

    private final QuestLiveTracker tracker = new QuestLiveTracker();
    private long now = START;

    private void scan(String... quest) {
        List<String[]> quests = new ArrayList<>();
        for (int i = 0; i < quest.length; i += 3) {
            quests.add(new String[]{quest[i], quest[i + 1], quest[i + 2]});
        }
        tracker.onScan(quests);
    }

    // runs the game for ms, showing actionBar every second (null = nothing on the action bar)
    private void run(long ms, String actionBar) {
        long end = now + ms;
        long nextBar = now;
        while (now < end) {
            if (actionBar != null && now >= nextBar) {
                tracker.onActionBar(actionBar, now);
                nextBar = now + 1_000L;
            }
            tracker.tick(now);
            now += TICK_MS;
        }
    }

    // ---- reading quests ----

    @Test
    void readsQuestKindsAndTargets() {
        assertEquals(QuestLiveTracker.Kind.KILL, QuestLiveTracker.kind("Kill 1,000 Bats"));
        assertEquals(QuestLiveTracker.Kind.KILL, QuestLiveTracker.kind("Slay 500 Zombies"));
        assertEquals(QuestLiveTracker.Kind.FARM, QuestLiveTracker.kind("Farm 5,000 Wheat"));
        assertEquals(QuestLiveTracker.Kind.FARM, QuestLiveTracker.kind("farm 2.5k Nether Warts"));
        assertNull(QuestLiveTracker.kind("Earn 1M Shards"));
        assertNull(QuestLiveTracker.kind("Killer quest"));
        assertNull(QuestLiveTracker.kind(null));

        assertEquals("bat", QuestLiveTracker.target("Kill 1,000 Bats"));
        assertEquals("zombifiedpiglin", QuestLiveTracker.target("Kill 1,000 Zombified Piglins"));
        assertEquals("witherskeleton", QuestLiveTracker.target("Kill 250 Wither Skeletons"));
        assertEquals("wolf", QuestLiveTracker.target("Kill 100 Wolves"));
        assertEquals("enderman", QuestLiveTracker.target("Kill 100 Endermen"));
        assertEquals("witch", QuestLiveTracker.target("Kill 50 Witches"));
        assertEquals("sheep", QuestLiveTracker.target("Kill 50 Sheep"));
        assertEquals("silverfish", QuestLiveTracker.target("Kill 50 Silverfish"));
        assertEquals("wheat", QuestLiveTracker.target("Farm 5,000 Wheat"));
        assertEquals("potato", QuestLiveTracker.target("Farm 5,000 Potatoes"));
        assertEquals("cactus", QuestLiveTracker.target("Farm 1,000 Cactus"));
        assertEquals("glass", QuestLiveTracker.target("Mine 1,000 Glass"));
        assertEquals("netherquartz", QuestLiveTracker.target("Farm 10k Nether Quartz"));
    }

    @Test
    void pluralQuestsMatchSingularMobs() {
        String[][] pairs = {
                {"Kill 1,000 Zombies", "Zombie"}, {"Kill 1,000 Bats", "Bat"}, {"Kill 100 Wolves", "Wolf"},
                {"Kill 100 Endermen", "Enderman"}, {"Kill 50 Witches", "Witch"}, {"Kill 50 Foxes", "Fox"},
                {"Kill 50 Sheep", "Sheep"}, {"Kill 50 Slimes", "Slime"}, {"Kill 50 Bees", "Bee"},
                {"Kill 50 Horses", "Horse"}, {"Kill 50 Vexes", "Vex"}, {"Kill 50 Breezes", "Breeze"},
                {"Kill 50 Zombified Piglins", "Zombified Piglin"}, {"Kill 50 Wither Skeletons", "Wither Skeleton"},
                {"Kill 50 Magma Cubes", "Magma Cube"}, {"Kill 50 Silverfish", "Silverfish"}, {"Kill 50 Allays", "Allay"},
                {"Kill 50 Glow Squids", "Glow Squid"}, {"Kill 50 Zombie Villagers", "Zombie Villager"},
                {"Kill 50 Hoglins", "Hoglin"}, {"Kill 50 Striders", "Strider"}, {"Kill 50 Ghasts", "Ghast"},
        };
        for (String[] pair : pairs) {
            assertEquals(QuestLiveTracker.target(pair[0]), QuestLiveTracker.nameKey(pair[1]), pair[0]);
        }
        String[][] crops = {
                {"Farm 1,000 Wheat", "Wheat Crops"}, {"Farm 1,000 Carrots", "Carrots"}, {"Farm 1,000 Potatoes", "Potatoes"},
                {"Farm 1,000 Beetroots", "Beetroots"}, {"Farm 1,000 Nether Wart", "Nether Wart"},
                {"Farm 1,000 Sugar Cane", "Sugar Cane"}, {"Farm 1,000 Cactus", "Cactus"}, {"Farm 1,000 Melons", "Melon"},
                {"Farm 1,000 Pumpkins", "Pumpkin"}, {"Farm 1,000 Cocoa Beans", "Cocoa"},
                {"Farm 1,000 Sweet Berries", "Sweet Berry Bush"}, {"Farm 1,000 Nether Quartz", "Nether Quartz Ore"},
                {"Farm 1,000 Crimson Nylium", "Crimson Nylium"}, {"Farm 1,000 Raw Gold", "Raw Gold Block"},
                {"Farm 1,000 Redstone", "Redstone Ore"}, {"Farm 1,000 Shroomlight", "Shroomlight"},
        };
        for (String[] pair : crops) {
            assertEquals(QuestLiveTracker.target(pair[0]), QuestLiveTracker.blockKey(pair[1]), pair[0]);
        }
        // different mobs must stay apart
        assertFalse(QuestLiveTracker.target("Kill 50 Piglins").equals(QuestLiveTracker.nameKey("Zombified Piglin")));
        assertFalse(QuestLiveTracker.target("Kill 50 Zombies").equals(QuestLiveTracker.nameKey("Zombie Villager")));
        assertFalse(QuestLiveTracker.target("Kill 50 Skeletons").equals(QuestLiveTracker.nameKey("Wither Skeleton")));
    }

    @Test
    void blockNamesMatchTheirQuest() {
        assertEquals("wheat", QuestLiveTracker.blockKey("Wheat Crops"));
        assertEquals("carrot", QuestLiveTracker.blockKey("Carrots"));
        assertEquals("netherquartz", QuestLiveTracker.blockKey("Nether Quartz Ore"));
        assertEquals("rawgold", QuestLiveTracker.blockKey("Raw Gold Block"));
        assertEquals("cocoa", QuestLiveTracker.blockKey("Cocoa Beans"));
        assertEquals("cocoa", QuestLiveTracker.blockKey("Cocoa"));
        // a lone block word is the whole name, not something to strip
        assertEquals("ore", QuestLiveTracker.blockKey("Ore"));
        assertEquals("", QuestLiveTracker.blockKey("   "));
    }

    // ---- live counting from MPS / BPS ----

    @Test
    void mpsCountsUpTheKillQuest() {
        scan("Kill 1,000 Bats", "100", "1,000");
        tracker.onSeen(QuestLiveTracker.Kind.KILL, "Bat", now);
        run(10_000L, "+20 Paper (285k)  10 MPS");
        assertEquals(200d, tracker.live("Kill 1,000 Bats"), 1d);
        assertTrue(tracker.isActive("Kill 1,000 Bats", now - TICK_MS));
    }

    @Test
    void bpsCountsUpTheFarmQuest() {
        scan("Farm 5,000 Wheat", "0", "5,000");
        tracker.onCounted(QuestLiveTracker.Kind.FARM, 1L, List.of("Wheat Crops"), now);
        run(4_000L, "BPS: 25");
        // the one counted break plus 4s at 25/s
        assertEquals(100d, tracker.live("Farm 5,000 Wheat"), 1d);
    }

    @Test
    void kRatesAreThousands() {
        scan("Farm 50,000 Wheat", "0", "50,000");
        tracker.onSeen(QuestLiveTracker.Kind.FARM, "Wheat", now);
        run(2_000L, "1.2k BPS");
        assertEquals(2_400d, tracker.live("Farm 50,000 Wheat"), 70d);
    }

    @Test
    void stopsWhenTheRateGoesAway() {
        scan("Kill 1,000 Bats", "0", "1,000");
        tracker.onSeen(QuestLiveTracker.Kind.KILL, "Bat", now);
        run(5_000L, "10 MPS");
        double atStop = tracker.live("Kill 1,000 Bats");
        // the last reading still counts for RATE_FRESH_MS, then nothing
        run(30_000L, null);
        assertTrue(tracker.live("Kill 1,000 Bats") - atStop <= 10d * QuestLiveTracker.RATE_FRESH_MS / 1000d + 1d);
        double later = tracker.live("Kill 1,000 Bats");
        run(30_000L, null);
        assertEquals(later, tracker.live("Kill 1,000 Bats"));
        assertFalse(tracker.isActive("Kill 1,000 Bats", now));
    }

    @Test
    void nothingCountsWithoutKnowingTheMob() {
        scan("Kill 1,000 Bats", "0", "1,000");
        run(10_000L, "10 MPS");
        assertEquals(0d, tracker.live("Kill 1,000 Bats"));
    }

    @Test
    void killingAnotherMobDoesNotCount() {
        scan("Kill 1,000 Piglins", "0", "1,000", "Kill 1,000 Bats", "0", "1,000");
        tracker.onSeen(QuestLiveTracker.Kind.KILL, "Zombified Piglin", now);
        run(10_000L, "10 MPS");
        assertEquals(0d, tracker.live("Kill 1,000 Piglins"));
        assertEquals(0d, tracker.live("Kill 1,000 Bats"));
    }

    @Test
    void switchingMobsMovesTheRate() {
        scan("Kill 1,000 Bats", "0", "1,000", "Kill 1,000 Zombies", "0", "1,000");
        tracker.onSeen(QuestLiveTracker.Kind.KILL, "Bat", now);
        run(5_000L, "10 MPS");
        tracker.onSeen(QuestLiveTracker.Kind.KILL, "Zombie", now);
        run(5_000L, "10 MPS");
        assertEquals(50d, tracker.live("Kill 1,000 Bats"), 1d);
        assertEquals(50d, tracker.live("Kill 1,000 Zombies"), 1d);
    }

    @Test
    void forgetsTheMobAfterAWhile() {
        scan("Kill 1,000 Bats", "0", "1,000");
        tracker.onSeen(QuestLiveTracker.Kind.KILL, "Bat", now);
        run(QuestLiveTracker.ACTIVE_MS + 5_000L, "10 MPS");
        // only the first ACTIVE_MS counted, since nothing said it's still bats after that
        assertEquals(100d, tracker.live("Kill 1,000 Bats"), 1d);
    }

    @Test
    void killAndFarmRatesStaySeparate() {
        scan("Kill 1,000 Hoglins", "0", "1,000", "Farm 5,000 Crimson Nylium", "0", "5,000");
        tracker.onSeen(QuestLiveTracker.Kind.KILL, "Hoglin", now);
        tracker.onSeen(QuestLiveTracker.Kind.FARM, "Crimson Nylium", now);
        run(5_000L, "4 MPS | 20 BPS");
        assertEquals(20d, tracker.live("Kill 1,000 Hoglins"), 1d);
        assertEquals(100d, tracker.live("Farm 5,000 Crimson Nylium"), 1d);
    }

    @Test
    void brokenBlocksBeatDropGuesses() {
        // a hoglin drops nether wart at the crimson nylium farm; the BPS is for the nylium you're breaking
        scan("Farm 5,000 Nether Wart", "0", "5,000", "Farm 5,000 Crimson Nylium", "0", "5,000");
        long end = now + 5_000L;
        long nextBar = now;
        while (now < end) {
            if (now >= nextBar) {
                tracker.onActionBar("+5 Nether Wart (1.2k)  20 BPS", now);
                nextBar = now + 1_000L;
            }
            if ((now - START) % 200L == 0L) {
                tracker.onCounted(QuestLiveTracker.Kind.FARM, 1L, List.of("Crimson Nylium"), now);
            }
            tracker.tick(now);
            now += TICK_MS;
        }
        assertEquals(0d, tracker.live("Farm 5,000 Nether Wart"));
        assertEquals(100d, tracker.live("Farm 5,000 Crimson Nylium"), 2d);
    }

    @Test
    void dropGuessesWorkWhenNothingIsBroken() {
        scan("Farm 5,000 Wheat", "0", "5,000");
        run(3_000L, "+3 Wheat (40)  10 BPS");
        assertEquals(30d, tracker.live("Farm 5,000 Wheat"), 1d);
    }

    @Test
    void hittingAMobNoQuestWantsStopsTheCredit() {
        scan("Kill 1,000 Bats", "0", "1,000");
        tracker.onSure(QuestLiveTracker.Kind.KILL, List.of("Bat"), now);
        run(3_000L, "10 MPS");
        double bats = tracker.live("Kill 1,000 Bats");
        tracker.onSure(QuestLiveTracker.Kind.KILL, List.of("Zombie"), now);
        run(5_000L, "10 MPS");
        assertEquals(bats, tracker.live("Kill 1,000 Bats"));
        // back on bats, it counts again
        tracker.onSure(QuestLiveTracker.Kind.KILL, List.of("Bat"), now);
        run(2_000L, "10 MPS");
        assertEquals(bats + 20d, tracker.live("Kill 1,000 Bats"), 1d);
    }

    @Test
    void customNamesStillMatchTheType() {
        scan("Kill 1,000 Bats", "0", "1,000");
        // type first, then the name tag the server puts on it
        tracker.onCounted(QuestLiveTracker.Kind.KILL, 1L, List.of("Bat", "[Lv5] Cave Thing ❤ 20/20", "Bat"), now);
        tracker.onCounted(QuestLiveTracker.Kind.KILL, 1L, List.of("Zombie", "Bat"), now);
        assertEquals(2d, tracker.live("Kill 1,000 Bats"));
    }

    // ---- counted kills and breaks ----

    @Test
    void countedKillsAddWithoutMps() {
        scan("Kill 1,000 Bats", "10", "1,000");
        for (int i = 0; i < 25; i++) {
            tracker.onCounted(QuestLiveTracker.Kind.KILL, 1L, List.of("Bat", "Bat"), now);
            run(200L, null);
        }
        assertEquals(35d, tracker.live("Kill 1,000 Bats"));
    }

    @Test
    void countedKillsAreNotDoubledWhileMpsShows() {
        scan("Kill 1,000 Bats", "0", "1,000");
        long end = now + 10_000L;
        long nextBar = now;
        while (now < end) {
            if (now >= nextBar) {
                tracker.onActionBar("5 MPS", now);
                nextBar = now + 1_000L;
            }
            if ((now - START) % 200L == 0L) {
                tracker.onCounted(QuestLiveTracker.Kind.KILL, 1L, List.of("Bat"), now);
            }
            tracker.tick(now);
            now += TICK_MS;
        }
        // 5/s for 10s from MPS; the 50 counted kills only told it which quest
        assertEquals(50d, tracker.live("Kill 1,000 Bats"), 1d);
    }

    @Test
    void countedBreaksUseBlockNames() {
        scan("Farm 5,000 Nether Quartz", "1.2k", "5k");
        for (int i = 0; i < 40; i++) {
            tracker.onCounted(QuestLiveTracker.Kind.FARM, 1L, List.of("Nether Quartz Ore"), now);
        }
        tracker.onCounted(QuestLiveTracker.Kind.FARM, 1L, List.of("Stone"), now);
        assertEquals(1_240d, tracker.live("Farm 5,000 Nether Quartz"));
    }

    @Test
    void nullAndJunkNamesAreIgnored() {
        scan("Kill 1,000 Bats", "0", "1,000");
        List<String> names = new ArrayList<>();
        names.add(null);
        names.add("");
        tracker.onCounted(QuestLiveTracker.Kind.KILL, 1L, names, now);
        tracker.onSeen(QuestLiveTracker.Kind.KILL, null, now);
        tracker.onSeen(QuestLiveTracker.Kind.KILL, "   ", now);
        tracker.onActionBar(null, now);
        tracker.onActionBar("", now);
        tracker.onActionBar("MPS", now);
        tracker.onActionBar("1.2.3 MPS", now);
        run(3_000L, null);
        assertEquals(0d, tracker.live("Kill 1,000 Bats"));
    }

    // ---- limits and resets ----

    @Test
    void stopsAtTheRequiredAmount() {
        scan("Kill 1,000 Bats", "990", "1,000");
        tracker.onSeen(QuestLiveTracker.Kind.KILL, "Bat", now);
        run(10_000L, "50 MPS");
        assertEquals(1_000d, tracker.live("Kill 1,000 Bats"));
        assertEquals(-1L, tracker.etaSeconds("Kill 1,000 Bats", now));
    }

    @Test
    void aFinishedQuestPassesTheRateOnToTheNextOne() {
        // two quests on the same mob: the server counts toward both, and the small one fills first
        scan("Kill 100 Bats", "95", "100", "Kill 1,000 Bats", "500", "1,000");
        tracker.onSeen(QuestLiveTracker.Kind.KILL, "Bat", now);
        run(2_000L, "10 MPS");
        assertEquals(100d, tracker.live("Kill 100 Bats"));
        assertEquals(520d, tracker.live("Kill 1,000 Bats"), 1d);
    }

    @Test
    void aNewScanStartsOverFromTheScan() {
        scan("Kill 1,000 Bats", "0", "1,000");
        tracker.onSeen(QuestLiveTracker.Kind.KILL, "Bat", now);
        run(5_000L, "10 MPS");
        assertEquals(50d, tracker.live("Kill 1,000 Bats"), 1d);
        scan("Kill 1,000 Bats", "47", "1,000");
        assertEquals(47d, tracker.live("Kill 1,000 Bats"));
        // still farming bats after the rescan, so it keeps counting from 47
        run(2_000L, "10 MPS");
        assertEquals(67d, tracker.live("Kill 1,000 Bats"), 1d);
    }

    @Test
    void questsItCannotReadAreLeftAlone() {
        scan("Earn 1M Shards", "10k", "1M", "Kill 1,000 Bats", "N/A", "N/A", "Kill 500 Zombies", "0", "N/A");
        assertEquals(-1d, tracker.live("Earn 1M Shards"));
        assertEquals(-1d, tracker.live("Kill 1,000 Bats"));
        assertEquals(-1d, tracker.live("Kill 500 Zombies"));
        assertEquals(-1d, tracker.live("Never scanned"));
        assertEquals(-1d, tracker.live(null));
    }

    @Test
    void lagSpikesDoNotDumpKills() {
        scan("Kill 10,000 Bats", "0", "10,000");
        tracker.onSeen(QuestLiveTracker.Kind.KILL, "Bat", now);
        tracker.onActionBar("10 MPS", now);
        tracker.tick(now);
        // a 1s freeze, then one tick: at most MAX_STEP_MS of kills, like the milestone counter
        now += 1_000L;
        tracker.onActionBar("10 MPS", now);
        tracker.tick(now);
        assertTrue(tracker.live("Kill 10,000 Bats") <= 10d * QuestLiveTracker.MAX_STEP_MS / 1000d);
    }

    @Test
    void clockGoingBackwardsIsHarmless() {
        scan("Kill 1,000 Bats", "0", "1,000");
        tracker.onSeen(QuestLiveTracker.Kind.KILL, "Bat", now);
        tracker.onActionBar("10 MPS", now);
        tracker.tick(now);
        tracker.tick(now - 5_000L);
        tracker.tick(now - 4_950L);
        assertTrue(tracker.live("Kill 1,000 Bats") >= 0d);
        assertTrue(tracker.live("Kill 1,000 Bats") <= 1d);
    }

    @Test
    void etaFollowsTheRate() {
        scan("Kill 1,000 Bats", "400", "1,000");
        tracker.onSeen(QuestLiveTracker.Kind.KILL, "Bat", now);
        tracker.onActionBar("10 MPS", now);
        tracker.tick(now);
        assertEquals(60L, tracker.etaSeconds("Kill 1,000 Bats", now));
        assertEquals(-1L, tracker.etaSeconds("Kill 1,000 Bats", now + 10_000L));
    }

    // ---- showing the number ----

    @Test
    void formatsLikeTheScan() {
        assertEquals("1,234", QuestLiveTracker.formatLike("1,000", "5,000", 1_234.9d));
        assertEquals("1,234", QuestLiveTracker.formatLike("999", "5,000", 1_234d));
        assertEquals("1234", QuestLiveTracker.formatLike("999", "5000", 1_234d));
        assertEquals("12.3k", QuestLiveTracker.formatLike("12k", "50k", 12_300d));
        assertEquals("1.5M", QuestLiveTracker.formatLike("1.4M", "2M", 1_500_000d));
        // whatever it shows has to read back as the same amount
        for (double value : new double[]{0d, 7d, 999d, 1_000d, 9_999d, 10_000d, 123_456d, 1_000_000d}) {
            assertEquals(Math.floor(value), QuestLiveTracker.amount(QuestLiveTracker.formatLike("1,000", "5,000", value)));
            assertEquals(Math.floor(value), QuestLiveTracker.amount(QuestLiveTracker.formatLike("1k", "5k", value)), value * 0.005d);
        }
    }

    @Test
    void readsScanAmounts() {
        assertEquals(1_000d, QuestLiveTracker.amount("1,000"));
        assertEquals(2_500d, QuestLiveTracker.amount("2.5k"));
        assertEquals(1_000_000d, QuestLiveTracker.amount("1M"));
        assertEquals(-1d, QuestLiveTracker.amount("N/A"));
        assertEquals(-1d, QuestLiveTracker.amount(null));
    }

    // ---- long random sim ----

    @Test
    void randomSessionsNeverGoWrong() {
        Random random = new Random(42L);
        String[] mobs = {"Bat", "Zombie", "Hoglin", "Zombified Piglin"};
        String[] crops = {"Wheat Crops", "Carrots", "Nether Quartz Ore"};
        for (int session = 0; session < 200; session++) {
            QuestLiveTracker sim = new QuestLiveTracker();
            List<String[]> quests = List.of(
                    new String[]{"Kill 2,000 Bats", String.valueOf(random.nextInt(2_000)), "2,000"},
                    new String[]{"Kill 500 Zombies", String.valueOf(random.nextInt(500)), "500"},
                    new String[]{"Farm 5,000 Wheat", String.valueOf(random.nextInt(5_000)), "5,000"},
                    new String[]{"Farm 3k Nether Quartz", "1k", "3k"});
            sim.onScan(quests);
            double[] last = new double[quests.size()];
            for (int i = 0; i < quests.size(); i++) {
                last[i] = sim.live(quests.get(i)[0]);
            }
            long t = START;
            for (int step = 0; step < 4_000; step++) {
                t += random.nextInt(20) == 0 ? random.nextInt(2_000) : TICK_MS;
                int roll = random.nextInt(100);
                if (roll < 3) {
                    sim.onSeen(QuestLiveTracker.Kind.KILL, mobs[random.nextInt(mobs.length)], t);
                } else if (roll < 6) {
                    sim.onCounted(QuestLiveTracker.Kind.FARM, 1L, List.of(crops[random.nextInt(crops.length)]), t);
                } else if (roll < 9) {
                    sim.onCounted(QuestLiveTracker.Kind.KILL, 1L, List.of(mobs[random.nextInt(mobs.length)]), t);
                } else if (roll < 15) {
                    sim.onActionBar(random.nextInt(30) + " MPS  " + random.nextInt(40) + " BPS", t);
                }
                sim.tick(t);
                for (int i = 0; i < quests.size(); i++) {
                    String[] quest = quests.get(i);
                    double live = sim.live(quest[0]);
                    double required = QuestLiveTracker.amount(quest[2]);
                    assertTrue(live >= last[i], "went down: " + quest[0]);
                    assertTrue(live <= required, "went past the goal: " + quest[0]);
                    assertTrue(!Double.isNaN(live) && !Double.isInfinite(live));
                    last[i] = live;
                }
            }
        }
    }
}

package bazaar.bazzar.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// picking the right market out of /bazaar. The buttons are named "Crop Market" and "Mob Market",
// the window is titled "Bazaar: Mob Market", and neither the icons nor the wording survive a season.
class BazaarCategoryTest {
    private static boolean gems(String menuName) {
        return BazaarAutoScanController.looksLikeCategory(
                SbText.normalizeText(menuName), BazaarAutoScanController.GEMS_CATEGORY);
    }

    private static boolean crops(String menuName) {
        return BazaarAutoScanController.looksLikeCategory(
                SbText.normalizeText(menuName), BazaarAutoScanController.CROPS_CATEGORY);
    }

    private static int gemRank(String menuName) {
        return BazaarAutoScanController.categoryRank(
                SbText.normalizeText(menuName), BazaarAutoScanController.GEMS_CATEGORY);
    }

    // ---- the buttons as they are actually named ----

    @Test
    void findsTheTwoMarketsInTheMenu() {
        assertTrue(crops("Crop Market"));
        assertTrue(gems("Mob Market"));
        // and neither answers for the other
        assertFalse(gems("Crop Market"));
        assertFalse(crops("Mob Market"));
    }

    @Test
    void readsTheWindowTitleAsTheOpenMarket() {
        assertTrue(crops(SbText.normalizeText("Bazaar: Crop Market")));
        assertTrue(gems(SbText.normalizeText("Bazaar: Mob Market")));
        assertFalse(crops(SbText.normalizeText("Bazaar: Mob Market")));
        assertFalse(gems(SbText.normalizeText("Bazaar: Crop Market")));
    }

    @Test
    void aMarketOfItsOwnWinsOverTheMobMarket() {
        // the gem drops sit in the mob market today, but a season that splits them out should win
        assertTrue(gemRank("Gem Market") < gemRank("Mob Market"));
        assertTrue(gemRank("Spawner Market") < gemRank("Mob Market"));
        assertEquals(-1, gemRank("Crop Market"));
    }

    // ---- wording and font the season may change ----

    @Test
    void findsTheMarketsHoweverTheyAreWritten() {
        assertTrue(crops("Crops"));
        assertTrue(crops("Farming"));
        assertTrue(crops("§a§lCROP MARKET"));
        assertTrue(gems("Gems"));
        assertTrue(gems("Spawners"));
        assertTrue(gems("§c§lMOB MARKET"));
    }

    @Test
    void readsTheSmallCapsTheServerWritesItsMenusIn() {
        // the same small caps the fishing action bar uses, folded back to plain letters first
        assertTrue(gems("ᴍᴏʙ ᴍᴀʀᴋᴇᴛ"));
        assertTrue(crops("ᴄʀᴏᴘ ᴍᴀʀᴋᴇᴛ"));
        assertTrue(crops("ꜰᴀʀᴍɪɴɢ"));
    }

    // ---- the arrows beside them ----

    @Test
    void neverMistakesThePageArrowsForAMarket() {
        assertTrue(BazaarAutoScanController.isPageButton(SbText.normalizeText("Next Page")));
        assertTrue(BazaarAutoScanController.isPageButton(SbText.normalizeText("Previous Page")));
        assertTrue(BazaarAutoScanController.isPageButton(SbText.normalizeText("Next")));
        assertFalse(BazaarAutoScanController.isPageButton(SbText.normalizeText("Crop Market")));

        assertFalse(crops("Next Page"));
        assertFalse(gems("Previous Page"));
        // an arrow that names where it goes is still an arrow
        assertFalse(crops("Next Page · Crop Market"));
        assertFalse(gems("§7Previous Page (Mob Market)"));
    }

    @Test
    void ignoresAnEmptyOrMissingName() {
        assertFalse(gems(""));
        assertFalse(crops("   "));
        assertFalse(BazaarAutoScanController.looksLikeCategory(null, BazaarAutoScanController.GEMS_CATEGORY));
        assertEquals(-1, BazaarAutoScanController.categoryRank(null, BazaarAutoScanController.CROPS_CATEGORY));
    }
}

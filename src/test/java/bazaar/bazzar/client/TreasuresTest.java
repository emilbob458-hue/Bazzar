package bazaar.bazzar.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class TreasuresTest {
    @Test
    void readsTheVaultTooltip() {
        Treasures.Info info = Treasures.fromItem("Netherite Treasure", List.of(
                "Netherite Treasure", "Relic", "Buffs every Infernal Citadel crop.", "Stats:", " | Fortune: ?",
                " | Sell Value: ?", "Level: 0/3 ◇◇◇", "Chance: 1/116.42k", "Guaranteed in: 349.25k",
                "Undiscovered", "[GODLIKE]"), "Infernal Citadel", 5L);
        assertEquals("Netherite", info.name);
        assertEquals("Infernal Citadel", info.world);
        assertEquals(0, info.level);
        assertEquals(3, info.maxLevel);
        assertEquals("1/116.42k", info.chance);
        assertEquals("349.25k", info.guaranteed);
        assertEquals("GODLIKE", info.rarity);
        assertFalse(info.discovered);
        assertNull(Treasures.fromItem("Next Page", List.of(), "Swamp", 5L));
    }

    @Test
    void readsDuplicates() {
        Treasures.Drop drop = Treasures.duplicate("⛏ Duplicate Nether Quartz Treasure » Level 3/3 ❖❖❖");
        assertEquals("Nether Quartz", drop.crop());
        assertEquals(3, drop.level());
        assertEquals(3, drop.maxLevel());
        assertNull(Treasures.duplicate("ᴛʀᴀᴄᴛᴏʀ igxendooor » ⛏ Duplicate Nether Quartz Treasure » Level 3/3"));
        assertNull(Treasures.duplicate("igxendooor: Duplicate Nether Quartz Treasure » Level 3/3"));
    }

    @Test
    void onlyCountsYourOwnUnearth() {
        String line = " ᴛʀᴀᴄᴛᴏʀ igxendooor unearthed [ᴍʏᴛʜɪᴄᴀʟ] Ochre Froglight Treasure! (1/47.68k)";
        Treasures.Drop drop = Treasures.unearthed(line, "IGXENDOOOR");
        assertEquals("Ochre Froglight", drop.crop());
        assertEquals("1/47.68k", drop.chance());
        assertNull(Treasures.unearthed(line, "someoneelse"));
        // a player typing the line still has their own name and a colon in front of it
        assertNull(Treasures.unearthed("ᴛʀᴀᴄᴛᴏʀ faker: igxendooor unearthed [ᴍʏᴛʜɪᴄᴀʟ] Ochre Froglight Treasure! (1/47.68k)",
                "igxendooor"));
        assertNull(Treasures.unearthed("faker igxendooor unearthed [ᴍʏᴛʜɪᴄᴀʟ] Ochre Froglight Treasure! (1/47.68k)",
                "igxendooor"));
    }

    @Test
    void aDropRaisesTheLevelAndClearsThePity() {
        Treasures.Info before = new Treasures.Info();
        before.name = "Nether Quartz";
        before.level = 1;
        before.maxLevel = 3;
        before.guaranteed = "12k";
        Treasures.Info after = Treasures.apply(before, new Treasures.Drop("Nether Quartz", 2, 3, ""), "Infernal Citadel");
        assertEquals(2, after.level);
        assertTrue(after.discovered);
        assertEquals("", after.guaranteed);
        assertEquals("Infernal Citadel", after.world);
    }
}

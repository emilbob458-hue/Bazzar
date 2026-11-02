package bazaar.bazzar.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

class GuildWorkHudTest {
    @Test
    void countsEachQuestAsAtMostOne() {
        assertEquals(1.5d, GuildWorkHud.questsDone(Map.of("killzombies:5000", 9000L, "farmwheat:2000", 1000L)), 1e-9);
        assertEquals(0d, GuildWorkHud.questsDone(Map.of("killzombies", 9000L, "farmwheat:0", 50L)), 1e-9);
    }

    @Test
    void showsQuestsOutOfTheHour() {
        assertEquals("1.5 of 5", GuildWorkHud.questsText(1.5d, 5));
        assertEquals("2 of 5", GuildWorkHud.questsText(2d, 5));
        assertEquals("5 of 5", GuildWorkHud.questsText(7d, 5));
    }
}

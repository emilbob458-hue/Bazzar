package bazaar.bazzar.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class GuildRosterTest {
    @Test
    void readsGuildNameFromTheMenuTitle() {
        assertEquals("mommy", GuildRoster.parseGuildName("Guild Info | mommy"));
        assertEquals("celestial", GuildRoster.parseGuildName("Guild Info | ᴄᴇʟᴇꜱᴛɪᴀʟ"));
        assertEquals("the best", GuildRoster.parseGuildName("§8Guild Info §7| §9The  Best"));
        assertNull(GuildRoster.parseGuildName("Guild Quests"));
        assertNull(GuildRoster.parseGuildName("Guild Info"));
    }

    @Test
    void readsMembersFromHeadNames() {
        assertEquals("shadow_man986", GuildRoster.parseMember("shadow_man986 (Online)"));
        assertEquals("runterzz", GuildRoster.parseMember("§aRunterzz §7(Offline)"));
        assertNull(GuildRoster.parseMember("Guild Bank"));
        assertNull(GuildRoster.parseMember("Click to Open Stats"));
    }
}

package bazaar.bazzar.client;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// crop treasures, read off the /treasures vault menus and the lines the server sends when one drops.
// No Minecraft classes in here, so the tests can feed it menu lore and chat lines straight.
final class Treasures {
    // one crop's treasure as the vault menu last showed it, kept in the config between sessions
    static final class Info {
        String name = "";
        String world = "";
        int level = 0;
        int maxLevel = 0;
        boolean discovered = false;
        String chance = "";
        String guaranteed = "";
        String rarity = "";
        long scannedAt = 0L;
    }

    record Drop(String crop, int level, int maxLevel, String chance) {
    }

    private static final Pattern NAME = Pattern.compile("^(.+?)\\s+treasure$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEVEL = Pattern.compile("level:?\\s*(\\d+)\\s*/\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHANCE = Pattern.compile("chance:?\\s*(1\\s*/\\s*[\\d.,]+\\s*[kmbt]?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern GUARANTEED = Pattern.compile("guaranteed in:?\\s*([\\d.,]+\\s*[kmbt]?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RARITY = Pattern.compile("^\\[([^\\]]+)\\]$");
    // "⛏ Duplicate Nether Quartz Treasure » Level 3/3 ❖❖❖": only the pickaxe may come before it, so a
    // player typing the same words can't match, their chat line starts with their rank and name
    private static final Pattern DUPLICATE = Pattern.compile(
            "^[^A-Za-z0-9]{0,4}?duplicate\\s+(.+?)\\s+treasure\\s*»\\s*level\\s*(\\d+)\\s*/\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE);
    // "ᴛʀᴀᴄᴛᴏʀ igxendooor unearthed [ᴍʏᴛʜɪᴄᴀʟ] Ochre Froglight Treasure! (1/47.68k)": before the name only
    // the small-caps rank is allowed, which a player can't type in front of their own chat name
    private static final Pattern UNEARTHED = Pattern.compile(
            "^(?:[^\\x00-\\x7F\\s]+\\s+)*([A-Za-z0-9_]{3,16})\\s+unearthed\\s+\\[[^\\]]*\\]\\s+(.+?)\\s+treasure!\\s*"
                    + "\\((1\\s*/\\s*[\\d.,]+\\s*[kmbt]?)\\)\\s*$",
            Pattern.CASE_INSENSITIVE);

    private Treasures() {
    }

    static String key(String crop) {
        return crop == null ? "" : crop.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    // "Netherite Treasure" -> "Netherite"
    static String cropOf(String itemName) {
        if (itemName == null) {
            return null;
        }
        Matcher matcher = NAME.matcher(itemName.trim());
        return matcher.matches() ? matcher.group(1).trim() : null;
    }

    static Info fromItem(String itemName, List<String> lore, String world, long now) {
        String crop = cropOf(itemName);
        if (crop == null) {
            return null;
        }
        Info info = new Info();
        info.name = crop;
        info.world = world;
        info.scannedAt = now;
        boolean undiscovered = false;
        for (String raw : lore) {
            String line = raw.trim();
            Matcher level = LEVEL.matcher(line);
            if (level.find()) {
                info.level = Integer.parseInt(level.group(1));
                info.maxLevel = Integer.parseInt(level.group(2));
            }
            Matcher chance = CHANCE.matcher(line);
            if (chance.find()) {
                info.chance = chance.group(1).replace(" ", "");
            }
            Matcher guaranteed = GUARANTEED.matcher(line);
            if (guaranteed.find()) {
                info.guaranteed = guaranteed.group(1).replace(" ", "");
            }
            Matcher rarity = RARITY.matcher(line);
            if (rarity.matches()) {
                info.rarity = rarity.group(1).trim();
            }
            if (line.equalsIgnoreCase("undiscovered")) {
                undiscovered = true;
            }
        }
        info.discovered = !undiscovered && info.level > 0;
        return info;
    }

    static Drop duplicate(String line) {
        Matcher matcher = DUPLICATE.matcher(line == null ? "" : line.trim());
        if (!matcher.find()) {
            return null;
        }
        return new Drop(matcher.group(1).trim(), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)), "");
    }

    // only your own unearth counts; everyone else's is broadcast to the whole server too
    static Drop unearthed(String line, String self) {
        Matcher matcher = UNEARTHED.matcher(line == null ? "" : line.trim());
        if (!matcher.matches() || self == null || !matcher.group(1).equalsIgnoreCase(self)) {
            return null;
        }
        return new Drop(matcher.group(2).trim(), 1, 0, matcher.group(3).replace(" ", ""));
    }

    // what a drop does to the stored treasure: it's found now, and at least at the level the line says
    static Info apply(Info before, Drop drop, String world) {
        Info info = before == null ? new Info() : before;
        if (info.name.isEmpty()) {
            info.name = drop.crop();
        }
        if (info.world.isEmpty() && world != null) {
            info.world = world;
        }
        info.discovered = true;
        info.level = Math.max(info.level, drop.level());
        if (drop.maxLevel() > 0) {
            info.maxLevel = drop.maxLevel();
        }
        if (!drop.chance().isEmpty()) {
            info.chance = drop.chance();
        }
        // the pity counter restarts on a drop, so the old number would be wrong until the next scan
        info.guaranteed = "";
        return info;
    }
}

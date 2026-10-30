package bazaar.bazzar.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// guild quest progress between /gq scans: "Kill" quests count up from MPS, "Farm" quests from BPS,
// or from the kills and breaks this mod counts itself when the action bar shows no rate. Fishing has
// no rate on the action bar, so "Catch" quests count off the running total in brackets instead.
// No Minecraft classes in here, so the sims in src/test can drive it.
final class QuestLiveTracker {
    enum Kind {
        KILL,
        FARM,
        CATCH
    }

    static final long RATE_FRESH_MS = 1_500L;
    static final long MAX_STEP_MS = 250L;
    static final long ACTIVE_MS = 10_000L;
    // fishing is slow next to farming: two catches this far apart still measure as a rate, and a
    // quest stays live this long after the last one so the ETA doesn't blink out between bites
    static final long CATCH_WINDOW_MS = 30_000L;

    private static final Pattern QUEST_PATTERN = Pattern.compile(
            "^(kill|slay|farm|harvest|break|mine|catch|fish|reel)\\s+(?:[0-9][0-9.,]*\\s*[kmb]?\\s+)?(.+)$");
    private static final Pattern MPS_PATTERN = rate("MPS");
    private static final Pattern BPS_PATTERN = rate("BPS");
    private static final Pattern GAIN_PATTERN = Pattern.compile("\\+[0-9][0-9.,]*[a-zA-Z]?\\s+([A-Za-z][A-Za-z' ]*?)\\s*\\(");
    // "GOOD · +1 Pilchard · 0.07 kg (+93 ms)", "PERFECT · +5 Pilchard HOTSPOT x2 · 0.17 kg (-21 ms)":
    // one line per fish landed, so the +N on it is the catch. Only the first +N counts, the one in
    // brackets at the end is the timing.
    private static final Pattern CATCH_PATTERN = Pattern.compile(
            "\\+([0-9][0-9.,]*\\s*[kmb]?)\\s+([A-Za-z][A-Za-z' ]*)");
    // block names carry words the quest leaves out: "Wheat Crops", "Nether Quartz Ore", "Raw Gold Block"
    private static final String[] BLOCK_WORDS = {"crops", "crop", "ore", "block", "stem", "bush", "plant", "beans", "bean"};

    private static final class Quest {
        final Kind kind;
        final String target;
        final double base;
        final double required;
        double added;

        Quest(Kind kind, String target, double base, double required) {
            this.kind = kind;
            this.target = target;
            this.base = base;
            this.required = required;
        }

        boolean open() {
            return base + added < required;
        }
    }

    private final Map<String, Quest> quests = new HashMap<>();
    private final Map<Kind, Double> rate = new HashMap<>();
    private final Map<Kind, Long> rateSeenMs = new HashMap<>();
    private final Map<Kind, String> lastName = new HashMap<>();
    private final Map<Kind, Long> lastNameMs = new HashMap<>();
    private final Map<Kind, Long> lastSureMs = new HashMap<>();
    private String lastCatchLine = "";
    private long lastStepMs = 0L;
    private double contributed = 0d;
    private long catchWindowMs = 0L;
    private double catchWindowGained = 0d;

    // a fresh /gq read or guild share is the truth again, so live counting starts over from it
    void onScan(List<String[]> scanned) {
        quests.clear();
        for (String[] quest : scanned) {
            Kind kind = kind(quest[0]);
            double base = amount(quest[1]);
            double required = amount(quest[2]);
            if (kind == null || base < 0d || required <= 0d) {
                continue;
            }
            quests.put(questKey(quest[0]), new Quest(kind, target(quest[0]), base, required));
        }
    }

    // reads "12.5 MPS", "BPS: 1.2k" and "+20 Wheat (1.2k)" off the action bar
    void onActionBar(String line, long now) {
        if (line == null) {
            return;
        }
        readRate(Kind.KILL, MPS_PATTERN.matcher(line), now);
        readRate(Kind.FARM, BPS_PATTERN.matcher(line), now);
        for (String gain : gains(line)) {
            onSeen(Kind.FARM, gain, now);
        }
        onCatch(line, now);
    }

    // a landed fish: counted straight off the line, since the server writes one per catch
    private void onCatch(String line, long now) {
        Matcher matcher = CATCH_PATTERN.matcher(line);
        if (!matcher.find()) {
            return;
        }
        String target = catchTarget(matcher.group(2));
        if (target == null) {
            return;
        }
        // the very same line again is the server redrawing it, not a second fish. The timing in
        // brackets differs catch to catch, so two real ones are never written identically.
        if (line.equals(lastCatchLine)) {
            return;
        }
        lastCatchLine = line;
        double caught = amount(matcher.group(1));
        if (caught <= 0d) {
            return;
        }
        lastName.put(Kind.CATCH, target);
        lastNameMs.put(Kind.CATCH, now);
        for (Quest quest : matching(Kind.CATCH, target)) {
            add(quest, caught);
        }
        measureCatchRate(caught, now);
    }

    // the name carries the server's own tags after it ("Pilchard HOTSPOT x2"), so the longest
    // leading words a quest actually wants are the fish
    private String catchTarget(String name) {
        String cleaned = clean(name).trim();
        while (!cleaned.isEmpty()) {
            if (!matching(Kind.CATCH, cleaned).isEmpty()) {
                return cleaned;
            }
            int cut = cleaned.lastIndexOf(' ');
            if (cut < 0) {
                return null;
            }
            cleaned = cleaned.substring(0, cut);
        }
        return null;
    }

    // the server shows no catches per second, so the ETA runs off what we counted over the last window
    private void measureCatchRate(double gained, long now) {
        long span = now - catchWindowMs;
        if (catchWindowMs == 0L || span <= 0L || span > CATCH_WINDOW_MS) {
            // nothing recent to measure against, so this catch only opens the window
            catchWindowMs = now;
            catchWindowGained = 0d;
            return;
        }
        catchWindowGained += gained;
        if (span >= 1_000L) {
            // eased towards the new reading so one lucky or slow bite doesn't swing the ETA
            double perSecond = catchWindowGained * 1000d / span;
            Double previous = rate.get(Kind.CATCH);
            rate.put(Kind.CATCH, previous == null ? perSecond : previous * 0.7d + perSecond * 0.3d);
            rateSeenMs.put(Kind.CATCH, now);
            catchWindowMs = now;
            catchWindowGained = 0d;
        }
    }

    // a mob you hit or a block you broke is sure: guesses from drops can't override it for ACTIVE_MS,
    // and hitting something no quest wants stops the rate going to the last quest right away
    void onSure(Kind kind, List<String> names, long now) {
        String matched = firstMatch(kind, names);
        if (matched == null) {
            if (hasName(names)) {
                lastName.remove(kind);
            }
            return;
        }
        lastSureMs.put(kind, now);
        lastName.put(kind, matched);
        lastNameMs.put(kind, now);
    }

    static List<String> gains(String line) {
        List<String> gains = new ArrayList<>();
        if (line == null) {
            return gains;
        }
        Matcher gain = GAIN_PATTERN.matcher(line);
        while (gain.find()) {
            gains.add(gain.group(1).trim());
        }
        return gains;
    }

    // a guess at what you're farming, from drops on the action bar
    void onSeen(Kind kind, String name, long now) {
        Long sure = lastSureMs.get(kind);
        if (sure != null && now - sure <= ACTIVE_MS && now >= sure) {
            return;
        }
        if (name == null || name.isBlank() || matching(kind, name).isEmpty()) {
            return;
        }
        lastName.put(kind, name);
        lastNameMs.put(kind, now);
    }

    // a kill or break the mod saw itself; only counted while the action bar shows no rate
    void onCounted(Kind kind, long count, List<String> names, long now) {
        onSure(kind, names, now);
        String matched = firstMatch(kind, names);
        if (matched == null || rateFresh(kind, now)) {
            return;
        }
        for (Quest quest : matching(kind, matched)) {
            add(quest, count);
        }
    }

    void tick(long now) {
        long last = lastStepMs;
        lastStepMs = now;
        if (last == 0L || now <= last) {
            return;
        }
        long step = Math.min(MAX_STEP_MS, now - last);
        for (Kind kind : Kind.values()) {
            // catches are already counted exactly off the action bar total, so no rate is added here
            if (kind == Kind.CATCH || !rateFresh(kind, now)) {
                continue;
            }
            for (Quest quest : active(kind, now)) {
                add(quest, rate.getOrDefault(kind, 0d) * step / 1000d);
            }
        }
    }

    // the live count for a quest, or -1 when it isn't one this tracks
    double live(String questName) {
        Quest quest = quests.get(questKey(questName));
        if (quest == null) {
            return -1d;
        }
        return Math.min(quest.required, Math.floor(quest.base + quest.added));
    }

    // seconds until this quest is done at the current rate, or -1 when it isn't being worked on
    long etaSeconds(String questName, long now) {
        Quest quest = quests.get(questKey(questName));
        if (quest == null || !quest.open() || !rateFresh(quest.kind, now) || !active(quest.kind, now).contains(quest)) {
            return -1L;
        }
        double perSecond = rate.getOrDefault(quest.kind, 0d);
        if (perSecond <= 0d) {
            return -1L;
        }
        return (long) Math.ceil((quest.required - quest.base - quest.added) / perSecond);
    }

    boolean isActive(String questName, long now) {
        Quest quest = quests.get(questKey(questName));
        return quest != null && rateFresh(quest.kind, now) && active(quest.kind, now).contains(quest);
    }

    private void add(Quest quest, double amount) {
        if (amount <= 0d || !quest.open()) {
            return;
        }
        double before = quest.added;
        quest.added = Math.min(quest.required - quest.base, quest.added + amount);
        contributed += quest.added - before;
    }

    // everything this player has put into the guild quests since the last call, for the "who's working" HUD.
    // It survives a rescan, which clears the quests, so a guild share doesn't wipe what you did.
    double consumeContributed() {
        double total = contributed;
        contributed = 0d;
        return total;
    }

    private List<Quest> active(Kind kind, long now) {
        String name = lastName.get(kind);
        if (name == null || now - lastNameMs.getOrDefault(kind, 0L) > activeMs(kind)) {
            return List.of();
        }
        return matching(kind, name);
    }

    private String firstMatch(Kind kind, List<String> names) {
        for (String name : names) {
            if (name != null && !matching(kind, name).isEmpty()) {
                return name;
            }
        }
        return null;
    }

    private static boolean hasName(List<String> names) {
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private List<Quest> matching(Kind kind, String name) {
        List<Quest> matched = new ArrayList<>();
        String wanted = kind == Kind.FARM ? blockKey(name) : nameKey(name);
        if (wanted.isEmpty()) {
            return matched;
        }
        for (Quest quest : quests.values()) {
            if (quest.kind == kind && quest.open() && quest.target.equals(wanted)) {
                matched.add(quest);
            }
        }
        return matched;
    }

    private boolean rateFresh(Kind kind, long now) {
        Long seen = rateSeenMs.get(kind);
        return seen != null && now - seen <= rateFreshMs(kind) && now >= seen;
    }

    // MPS and BPS are redrawn every second so they go stale fast, but catches are far apart
    private static long rateFreshMs(Kind kind) {
        return kind == Kind.CATCH ? CATCH_WINDOW_MS : RATE_FRESH_MS;
    }

    private static long activeMs(Kind kind) {
        return kind == Kind.CATCH ? CATCH_WINDOW_MS : ACTIVE_MS;
    }

    private void readRate(Kind kind, Matcher matcher, long now) {
        if (!matcher.find()) {
            return;
        }
        String digits = matcher.group(1) != null ? matcher.group(1) : matcher.group(3);
        String suffix = matcher.group(1) != null ? matcher.group(2) : matcher.group(4);
        double value = SbText.parseNumber(digits);
        if (value < 0d) {
            return;
        }
        rate.put(kind, suffix == null || suffix.isEmpty() ? value : value * 1000d);
        rateSeenMs.put(kind, now);
    }

    private static Pattern rate(String unit) {
        return Pattern.compile("([0-9][0-9.,]*)\\s*([kK]?)\\s*" + unit + "\\b|\\b" + unit + "\\s*:?\\s*([0-9][0-9.,]*)\\s*([kK]?)");
    }

    static Kind kind(String questName) {
        Matcher matcher = QUEST_PATTERN.matcher(clean(questName));
        if (!matcher.find()) {
            return null;
        }
        String verb = matcher.group(1);
        if (verb.equals("kill") || verb.equals("slay")) {
            return Kind.KILL;
        }
        if (verb.equals("catch") || verb.equals("fish") || verb.equals("reel")) {
            return Kind.CATCH;
        }
        return Kind.FARM;
    }

    // "Kill 1,000 Bats" -> "bat", "Farm 5k Nether Warts" -> "netherwart", "Catch 500 Pilchards" -> "pilchard"
    static String target(String questName) {
        Matcher matcher = QUEST_PATTERN.matcher(clean(questName));
        if (!matcher.find()) {
            return "";
        }
        // only farmed names carry block words to strip; mobs and fish are named as they are
        return kind(questName) == Kind.FARM ? blockKey(matcher.group(2)) : nameKey(matcher.group(2));
    }

    static String nameKey(String name) {
        String[] words = clean(name).replaceAll("[^a-z ]", " ").trim().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) {
            return "";
        }
        words[words.length - 1] = singular(words[words.length - 1]);
        return String.join("", words);
    }

    static String blockKey(String name) {
        List<String> words = new ArrayList<>(List.of(clean(name).replaceAll("[^a-z ]", " ").trim().split("\\s+")));
        while (words.size() > 1 && isBlockWord(words.get(words.size() - 1))) {
            words.remove(words.size() - 1);
        }
        return nameKey(String.join(" ", words));
    }

    private static boolean isBlockWord(String word) {
        for (String blockWord : BLOCK_WORDS) {
            if (blockWord.equals(word)) {
                return true;
            }
        }
        return false;
    }

    // quest and mob names both go through this, so "Zombies" and "Zombie" (both "zomby") or "Wolves" and "Wolf" meet
    static String singular(String word) {
        String single = dropPlural(word);
        if (single.endsWith("ie")) {
            return single.substring(0, single.length() - 2) + "y";
        }
        if (single.endsWith("fe")) {
            return single.substring(0, single.length() - 2) + "f";
        }
        return single;
    }

    private static String dropPlural(String word) {
        if (word.length() <= 3) {
            return word;
        }
        if (word.endsWith("men")) {
            return word.substring(0, word.length() - 3) + "man";
        }
        if (word.endsWith("ies")) {
            return word.substring(0, word.length() - 3) + "y";
        }
        if (word.endsWith("ves")) {
            return word.substring(0, word.length() - 3) + "f";
        }
        if (word.endsWith("ches") || word.endsWith("shes") || word.endsWith("xes") || word.endsWith("sses") || word.endsWith("oes")) {
            return word.substring(0, word.length() - 2);
        }
        if (word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us")) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    // "1,234" stays comma style and "1.2k" stays short, so the live number looks like what /gq showed
    static String formatLike(String left, String right, double value) {
        long whole = (long) Math.floor(value);
        if (isPlain(left) && isPlain(right)) {
            return left.contains(",") || right.contains(",") ? String.format(Locale.ROOT, "%,d", whole) : Long.toString(whole);
        }
        return SbText.formatCompact(whole);
    }

    private static boolean isPlain(String value) {
        return value != null && value.trim().matches("[0-9,]+");
    }

    static double amount(String value) {
        if (value == null || value.equals("N/A")) {
            return -1d;
        }
        return SbText.firstAmount(value.toLowerCase(Locale.ROOT));
    }

    private static String questKey(String questName) {
        return clean(questName).replaceAll("[^a-z0-9]", "");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}

package bazaar.bazzar.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

// how many of each kind of mob you have killed, counted by the name over its head so that
// "Angelic Vindicator" and plain "Vindicator" are kept apart. Session resets every launch, the
// total is kept per player. The kills come from the same two places the milestones use: the ones
// this mod sees die, or MPS when abilities are doing the killing too fast to watch.
public final class MobKillTracker {
    // "Angelic Vindicator 11.45T/11.45T" - the health the server writes after the name
    private static final Pattern HEALTH_PATTERN =
            Pattern.compile("[0-9][0-9.,]*\\s*[a-zA-Z]{0,3}\\s*/\\s*[0-9][0-9.,]*\\s*[a-zA-Z]{0,3}");
    private static final long SAVE_DELAY_MS = 30_000L;
    // a server that names every mob differently should not grow the file for ever
    private static final int MAX_TRACKED = 300;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DATA_PATH = Path.of(System.getProperty("user.home"), ".bazzar", "mob_kills.json");

    static final class Entry {
        String name = "";
        long total;
        transient long session;

        String name() {
            return name;
        }

        long total() {
            return total;
        }

        long session() {
            return session;
        }
    }

    private static final class Store {
        Map<String, Map<String, Entry>> players = new HashMap<>();
    }

    private static Store store;
    private static String currentName = "";
    private static long unsavedSinceMs = 0L;
    // set only by the tests, which have no game to ask for the player's name and must not write a file
    private static String accountOverride;

    private MobKillTracker() {
    }

    static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                literal("kills")
                        .requires(source -> !BazzarGuard.isBlocked())
                        .executes(ctx -> {
                            report(ctx.getSource()::sendFeedback, false);
                            return 1;
                        })
                        .then(literal("all").executes(ctx -> {
                            report(ctx.getSource()::sendFeedback, true);
                            return 1;
                        }))
                        .then(literal("hud").executes(ctx -> {
                            boolean shown = MobKillHud.toggle();
                            ctx.getSource().sendFeedback(BazaarChat.prefix().append(Component.literal(shown
                                            ? "Kill HUD shown. Drag it to move, scroll to resize."
                                            : "Kill HUD hidden.")
                                    .withStyle(ChatFormatting.AQUA)));
                            return 1;
                        }))
                        .then(literal("reset").executes(ctx -> {
                            resetSession();
                            ctx.getSource().sendFeedback(BazaarChat.prefix().append(
                                    Component.literal("Session kills reset.").withStyle(ChatFormatting.AQUA)));
                            return 1;
                        }))
                        .then(argument("mob", StringArgumentType.greedyString()).executes(ctx -> {
                            reportOne(ctx.getSource()::sendFeedback, StringArgumentType.getString(ctx, "mob"));
                            return 1;
                        }))));
    }

    // ---- counting ----

    // the mob you are on, so MPS kills land on the right name even when you never land a hit
    static void onSeen(Entity entity) {
        onSeen(displayName(entity));
    }

    static void onSeen(String name) {
        if (name != null && !name.isEmpty()) {
            currentName = name;
        }
    }

    static void onKill(Entity entity) {
        onKill(displayName(entity));
    }

    static void onKill(String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        currentName = name;
        add(name, 1L);
    }

    // kills worked out from MPS, which only says how fast, not what
    static void onRateKills(long count) {
        add(currentName, count);
    }

    static void add(String name, long count) {
        if (name == null || name.isBlank() || count <= 0L) {
            return;
        }
        Map<String, Entry> mobs = mobs();
        Entry entry = mobs.get(key(name));
        if (entry == null) {
            if (mobs.size() >= MAX_TRACKED) {
                forgetSmallest(mobs);
            }
            entry = new Entry();
            entry.name = name;
            mobs.put(key(name), entry);
        }
        entry.total += count;
        entry.session += count;
        if (unsavedSinceMs == 0L) {
            unsavedSinceMs = System.currentTimeMillis();
        }
    }

    // only ever drops the one that would be missed least
    private static void forgetSmallest(Map<String, Entry> mobs) {
        String smallest = null;
        long fewest = Long.MAX_VALUE;
        for (Map.Entry<String, Entry> candidate : mobs.entrySet()) {
            long total = candidate.getValue().total;
            if (total < fewest) {
                fewest = total;
                smallest = candidate.getKey();
            }
        }
        if (smallest != null) {
            mobs.remove(smallest);
        }
    }

    static void tick() {
        if (unsavedSinceMs > 0L && System.currentTimeMillis() - unsavedSinceMs >= SAVE_DELAY_MS) {
            save();
        }
    }

    static void resetSession() {
        for (Entry entry : mobs().values()) {
            entry.session = 0L;
        }
        currentName = "";
    }

    // ---- reading ----

    static List<Entry> entries(boolean byTotal) {
        List<Entry> sorted = new ArrayList<>(mobs().values());
        sorted.sort(byTotal
                ? Comparator.comparingLong((Entry entry) -> entry.total).reversed()
                : Comparator.comparingLong((Entry entry) -> entry.session).reversed()
                        .thenComparing(Comparator.comparingLong((Entry entry) -> entry.total).reversed()));
        return sorted;
    }

    static Entry find(String name) {
        return mobs().get(key(name));
    }

    static String currentName() {
        return currentName;
    }

    // "§6Angelic Vindicator §a11.45T/11.45T" -> "Angelic Vindicator"
    static String displayName(Entity entity) {
        if (entity == null) {
            return "";
        }
        String custom = entity.getCustomName() == null ? null : entity.getCustomName().getString();
        String tidied = tidy(custom);
        return tidied.isEmpty() ? tidy(entity.getName().getString()) : tidied;
    }

    static String tidy(String raw) {
        if (raw == null) {
            return "";
        }
        String text = SbText.stripFormattingCodes(SbText.stripSprites(raw));
        StringBuilder folded = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            folded.append(SbText.foldChar(text.charAt(i)));
        }
        text = HEALTH_PATTERN.matcher(folded.toString()).replaceAll(" ");
        text = text.replaceAll("[^A-Za-z0-9' ]", " ").replaceAll("\\s+", " ").trim();
        // what is left of a name that was only a health bar is not a mob
        return text.replaceAll("[0-9' ]", "").isEmpty() ? "" : text;
    }

    static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    // ---- the chat report ----

    private static void report(Consumer<Component> feedback, boolean byTotal) {
        List<Entry> sorted = entries(byTotal);
        if (sorted.isEmpty()) {
            feedback.accept(BazaarChat.prefix().append(Component.literal(
                    "No kills counted yet. Go hit something.").withStyle(ChatFormatting.GRAY)));
            return;
        }
        feedback.accept(BazaarChat.gradient(byTotal ? "✦ KILLS · ALL TIME ✦" : "✦ KILLS · THIS SESSION ✦", true));
        int limit = Math.min(10, sorted.size());
        for (int i = 0; i < limit; i++) {
            feedback.accept(line(sorted.get(i)));
        }
        if (sorted.size() > limit) {
            feedback.accept(Component.literal("  and " + (sorted.size() - limit) + " more, /kills <mob> for one")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    private static void reportOne(Consumer<Component> feedback, String wanted) {
        Entry exact = find(wanted);
        if (exact != null) {
            feedback.accept(line(exact));
            return;
        }
        String needle = key(wanted);
        List<Entry> matches = new ArrayList<>();
        for (Entry entry : entries(true)) {
            if (key(entry.name).contains(needle)) {
                matches.add(entry);
            }
        }
        if (matches.isEmpty()) {
            feedback.accept(BazaarChat.prefix().append(Component.literal("No kills counted for " + wanted + ".")
                    .withStyle(ChatFormatting.GRAY)));
            return;
        }
        for (int i = 0; i < Math.min(10, matches.size()); i++) {
            feedback.accept(line(matches.get(i)));
        }
    }

    private static Component line(Entry entry) {
        return Component.literal("  " + entry.name + " ").withStyle(ChatFormatting.WHITE)
                .append(Component.literal(SbText.formatCompact(entry.session)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" this session · ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(SbText.formatCompact(entry.total)).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" all time").withStyle(ChatFormatting.DARK_GRAY));
    }

    // ---- where it is kept ----

    private static Map<String, Entry> mobs() {
        if (store == null) {
            store = load();
        }
        return store.players.computeIfAbsent(playerKey(), name -> new LinkedHashMap<>());
    }

    // counts are per account, so two alts on one computer keep their own totals
    private static String playerKey() {
        if (accountOverride != null) {
            return accountOverride;
        }
        net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
        return client == null || client.player == null
                ? "unknown"
                : client.player.getName().getString().toLowerCase(Locale.ROOT);
    }

    // a clean slate under a named account, for the tests
    static void useAccountForTest(String account) {
        store = new Store();
        accountOverride = account;
        currentName = "";
        unsavedSinceMs = 0L;
    }

    private static Store load() {
        if (!Files.exists(DATA_PATH)) {
            return new Store();
        }
        try {
            Store loaded = GSON.fromJson(Files.readString(DATA_PATH), Store.class);
            if (loaded == null || loaded.players == null) {
                return new Store();
            }
            return loaded;
        } catch (IOException | JsonSyntaxException e) {
            return new Store();
        }
    }

    static void save() {
        unsavedSinceMs = 0L;
        if (store == null || accountOverride != null) {
            return;
        }
        try {
            Files.createDirectories(DATA_PATH.getParent());
            Files.writeString(DATA_PATH, GSON.toJson(store));
        } catch (IOException ignored) {
        }
    }
}

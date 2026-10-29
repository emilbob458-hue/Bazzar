package bazaar.bazzar.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

final class CelestialTracker {
    static final long COOLDOWN_MS = 4L * 60L * 60L * 1000L;

    private static final String ACCEPT_TEXT = "accept celestial";
    private static final long FORGET_MS = 14L * 24L * 60L * 60L * 1000L;
    // "Runterzz just activated the 2x Celestial Booster!" or "... just added a minute to the 2x Celestial Booster!"
    private static final Pattern USED_PATTERN = Pattern.compile(
            "^([A-Za-z0-9_]{3,16}) just (?:activated|added a minute to) the \\S+ Celestial Booster!");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DATA_PATH = Path.of(System.getProperty("user.home"), ".bazzar", "celestials.json");

    private static final class Player {
        String name;
        long lastUsedMs;
        long lastSeenMs;
        // runs Bazzar, per the last shared sync; only these are shown
        boolean mod;
    }

    private static final class Store {
        Map<String, Player> players = new HashMap<>();
    }

    private static Store store;
    // uses and celestial chatters this game saw, sent to the Bazzar server in small batches
    private static final Set<String> pendingUsed = new HashSet<>();
    private static final Set<String> pendingSeen = new HashSet<>();
    private static final Map<String, Long> reportedSeenMs = new HashMap<>();
    private static final long REPORT_MS = 5_000L;
    private static final long SEEN_REPORT_GAP_MS = 10L * 60_000L;
    private static final long SYNC_MS = 5L * 60_000L;
    private static long nextReportMs = 0L;
    private static long nextSyncMs = 0L;

    private CelestialTracker() {
    }

    static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(literal("requestcelestial")
                    .requires(source -> !BazzarGuard.isBlocked())
                    .executes(context -> {
                        Minecraft client = context.getSource().getClient();
                        if (client.player == null || client.player.connection == null) {
                            return 0;
                        }
                        CelestialMarket.request(client);
                        showList(client);
                        return 1;
                    }));
            dispatcher.register(literal("acceptcelestial")
                    .requires(source -> !BazzarGuard.isBlocked())
                    .executes(context -> CelestialMarket.accept(context.getSource().getClient(), -1) ? 1 : 0)
                    .then(argument("request", IntegerArgumentType.integer(0))
                            .executes(context -> CelestialMarket.accept(context.getSource().getClient(),
                                    IntegerArgumentType.getInteger(context, "request")) ? 1 : 0)));
            dispatcher.register(literal("celestials")
                    .requires(source -> !BazzarGuard.isBlocked())
                    .executes(context -> {
                        // the Boosts tab pulls what everyone's mods have seen, so all players get the same timers
                        CelestialMarket.openScreen(CelestialPanel.View.BOOSTS);
                        return 1;
                    }));
        });
    }

    static void tick(Minecraft client) {
        long now = System.currentTimeMillis();
        if (client.player == null || client.getCurrentServer() == null) {
            return;
        }
        if (now >= nextReportMs && (!pendingUsed.isEmpty() || !pendingSeen.isEmpty())) {
            nextReportMs = now + REPORT_MS;
            JsonObject body = CelestialMarket.baseBody(client.player.getName().getString());
            body.add("used", toArray(pendingUsed));
            body.add("seen", toArray(pendingSeen));
            pendingUsed.clear();
            pendingSeen.clear();
            CelestialMarket.post("/cb/seen", body, (code, json) -> {
            });
        }
        if (now >= nextSyncMs) {
            sync(null);
        }
    }

    // merges the shared list in; the newest use of each player wins
    static void sync(Runnable after) {
        nextSyncMs = System.currentTimeMillis() + SYNC_MS;
        CelestialMarket.get("/cb/celestials", (code, json) -> {
            if (code == 200 && json != null && json.has("players")) {
                long offset = System.currentTimeMillis() - json.get("now").getAsLong();
                boolean changed = false;
                Set<String> modUsers = new HashSet<>();
                for (JsonElement element : json.getAsJsonArray("players")) {
                    JsonObject shared = element.getAsJsonObject();
                    if (shared.has("mod") && shared.get("mod").getAsBoolean()) {
                        modUsers.add(key(shared.get("name").getAsString()));
                    }
                }
                for (Player player : store().players.values()) {
                    boolean mod = modUsers.contains(key(player.name));
                    if (player.mod != mod) {
                        player.mod = mod;
                        changed = true;
                    }
                }
                for (JsonElement element : json.getAsJsonArray("players")) {
                    JsonObject shared = element.getAsJsonObject();
                    Player player = player(shared.get("name").getAsString());
                    if (modUsers.contains(key(player.name)) && !player.mod) {
                        player.mod = true;
                        changed = true;
                    }
                    long used = shared.get("lastUsed").getAsLong();
                    long seen = shared.get("lastSeen").getAsLong();
                    if (used > 0L && used + offset > player.lastUsedMs) {
                        player.lastUsedMs = used + offset;
                        changed = true;
                    }
                    if (seen > 0L && seen + offset > player.lastSeenMs) {
                        player.lastSeenMs = seen + offset;
                        changed = true;
                    }
                }
                if (changed) {
                    save();
                }
            }
            if (after != null) {
                after.run();
            }
        });
    }

    private static JsonArray toArray(Set<String> names) {
        JsonArray array = new JsonArray();
        for (String name : names) {
            array.add(name);
        }
        return array;
    }

    // typing "accept celestial" in chat accepts the last pool request instead of sending the message
    static boolean onOwnChat(String message) {
        if (message == null || !message.trim().equalsIgnoreCase(ACCEPT_TEXT)) {
            return false;
        }
        return CelestialMarket.accept(Minecraft.getInstance(), CelestialMarket.lastPromptedRequest());
    }

    // when this player's boost is off cooldown, from the broadcasts and your own cooldown message
    static long readyAt(String name) {
        long readyAt = 0L;
        Player player = store().players.get(key(name));
        if (player != null && player.lastUsedMs > 0L) {
            readyAt = player.lastUsedMs + COOLDOWN_MS;
        }
        Map<String, Long> own = BazzarConfig.load().celestialBoosterReadyAt;
        Long reminder = own == null ? null : own.get(ShardPayController.normalizeName(name));
        return reminder != null ? Math.max(readyAt, reminder) : readyAt;
    }

    static void onChatLine(Minecraft client, String line) {
        if (line == null || line.isBlank() || client.player == null) {
            return;
        }
        String text = fold(line).trim();
        Matcher used = USED_PATTERN.matcher(text);
        if (used.find()) {
            Player player = player(used.group(1));
            player.lastUsedMs = System.currentTimeMillis();
            player.lastSeenMs = player.lastUsedMs;
            save();
            pendingUsed.add(player.name);
            return;
        }
        int bodyStart = text.indexOf('»');
        if (bodyStart < 0) {
            return;
        }
        String prefix = text.substring(0, bodyStart);
        String body = text.substring(bodyStart + 1).trim().toLowerCase(Locale.ROOT);
        String sender = senderName(client, prefix);
        if (sender == null) {
            return;
        }
        if (prefix.toLowerCase(Locale.ROOT).contains("celestial")) {
            long now = System.currentTimeMillis();
            if (now - reportedSeenMs.getOrDefault(key(sender), 0L) >= SEEN_REPORT_GAP_MS) {
                reportedSeenMs.put(key(sender), now);
                pendingSeen.add(sender);
            }
            Player player = store().players.get(key(sender));
            if (player == null) {
                player(sender).lastSeenMs = System.currentTimeMillis();
                save();
            } else {
                player.lastSeenMs = System.currentTimeMillis();
            }
        }
    }

    record Entry(String name, boolean online, long readyAtMs) {
        boolean ready(long now) {
            return readyAtMs <= now;
        }
    }

    // everyone running Bazzar, ready ones first by name, then cooldowns by who's up soonest
    static List<Entry> entries(Minecraft client) {
        long now = System.currentTimeMillis();
        Set<String> online = onlineNames(client);
        store().players.values().removeIf(player -> now - Math.max(player.lastSeenMs, player.lastUsedMs) > FORGET_MS);
        List<Entry> entries = new ArrayList<>();
        for (Player player : store().players.values()) {
            if (!player.mod) {
                continue;
            }
            long readyAt = player.lastUsedMs <= 0L ? 0L : player.lastUsedMs + COOLDOWN_MS;
            entries.add(new Entry(player.name, online.contains(key(player.name)), readyAt));
        }
        entries.sort(Comparator.comparing((Entry entry) -> !entry.ready(now))
                .thenComparingLong(entry -> entry.ready(now) ? 0L : entry.readyAtMs())
                .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    private static final int CHAT_NEXT_UP = 6;

    // a short chat summary; the full list lives in the Boosts tab of /cbshop
    static void showList(Minecraft client) {
        if (client.player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Entry> entries = entries(client);
        List<Entry> readyOnline = new ArrayList<>();
        List<Entry> cooling = new ArrayList<>();
        int readyOffline = 0;
        for (Entry entry : entries) {
            if (!entry.ready(now)) {
                cooling.add(entry);
            } else if (entry.online()) {
                readyOnline.add(entry);
            } else {
                readyOffline++;
            }
        }

        McCompat.chat(client.player, BazaarChat.prefix().append(Component.literal("Celestial boosts")
                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));
        if (entries.isEmpty()) {
            McCompat.chat(client.player, Component.literal(" Nobody tracked yet. Celestials show up once they chat or use their booster.")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        McCompat.chat(client.player, Component.literal(" Ready now ").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
                .append(Component.literal("(" + readyOnline.size() + " online"
                        + (readyOffline > 0 ? ", " + readyOffline + " offline" : "") + ")").withStyle(ChatFormatting.GRAY)));
        if (!readyOnline.isEmpty()) {
            MutableComponent names = Component.literal("  ");
            for (int i = 0; i < readyOnline.size(); i++) {
                if (i > 0) {
                    names.append(Component.literal("  ·  ").withStyle(ChatFormatting.GRAY));
                }
                names.append(Component.literal(readyOnline.get(i).name()).withStyle(ChatFormatting.GREEN));
            }
            McCompat.chat(client.player, names);
        }
        if (!cooling.isEmpty()) {
            McCompat.chat(client.player, Component.literal(" Next up ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                    .append(Component.literal("(" + cooling.size() + " on cooldown)").withStyle(ChatFormatting.GRAY)));
            MutableComponent next = Component.literal("  ");
            for (int i = 0; i < Math.min(CHAT_NEXT_UP, cooling.size()); i++) {
                Entry entry = cooling.get(i);
                if (i > 0) {
                    next.append(Component.literal("  ·  ").withStyle(ChatFormatting.GRAY));
                }
                next.append(Component.literal(formatLeft(entry.readyAtMs() - now) + " ").withStyle(ChatFormatting.YELLOW))
                        .append(Component.literal(entry.name()).withStyle(entry.online() ? ChatFormatting.WHITE : ChatFormatting.GRAY));
            }
            McCompat.chat(client.player, next);
        }
        McCompat.chat(client.player, Component.literal(" [Open full list]").withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent.RunCommand("/celestials"))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Opens the Boosts tab of the Celestial Market")))));
    }

    static String formatLeft(long ms) {
        long minutes = Math.max(1L, ms / 60_000L);
        return minutes >= 60L ? (minutes / 60L) + "h " + (minutes % 60L) + "m" : minutes + "m";
    }

    // the sender is whichever word before » is a player in the tab list, since ranks and tags sit around the name
    private static String senderName(Minecraft client, String prefix) {
        Set<String> online = onlineNames(client);
        for (String token : prefix.split("\\s+")) {
            String name = token.replaceAll("[^A-Za-z0-9_]", "");
            if (name.length() >= 3 && online.contains(key(name))) {
                return name;
            }
        }
        return null;
    }

    private static Set<String> onlineNames(Minecraft client) {
        Set<String> names = new HashSet<>();
        if (client.getConnection() == null) {
            return names;
        }
        for (PlayerInfo info : client.getConnection().getOnlinePlayers()) {
            names.add(key(info.getProfile().name()));
        }
        return names;
    }

    private static String fold(String raw) {
        String stripped = SbText.stripFormattingCodes(SbText.stripSprites(raw));
        StringBuilder out = new StringBuilder(stripped.length());
        for (int i = 0; i < stripped.length(); i++) {
            out.append(SbText.foldChar(stripped.charAt(i)));
        }
        return out.toString();
    }

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private static Player player(String name) {
        return store().players.computeIfAbsent(key(name), k -> {
            Player player = new Player();
            player.name = name;
            return player;
        });
    }

    private static Store store() {
        if (store == null) {
            store = load();
        }
        return store;
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
        } catch (IOException | JsonSyntaxException ignored) {
            return new Store();
        }
    }

    private static void save() {
        try {
            Files.createDirectories(DATA_PATH.getParent());
            Files.writeString(DATA_PATH, GSON.toJson(store()));
        } catch (IOException ignored) {
        }
    }
}

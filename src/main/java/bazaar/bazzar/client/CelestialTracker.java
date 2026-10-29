package bazaar.bazzar.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
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
import net.minecraft.sounds.SoundEvents;

final class CelestialTracker {
    static final long COOLDOWN_MS = 4L * 60L * 60L * 1000L;

    private static final String REQUEST_WORD = "requestcelestial";
    private static final String ACCEPT_TEXT = "accept celestial";
    private static final long PROMPT_GAP_MS = 60_000L;
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
    }

    private static final class Store {
        Map<String, Player> players = new HashMap<>();
    }

    private static Store store;
    private static long lastPromptMs = 0L;

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
                        client.player.connection.sendChat(REQUEST_WORD);
                        showList(client);
                        return 1;
                    }));
            dispatcher.register(literal("acceptcelestial")
                    .requires(source -> !BazzarGuard.isBlocked())
                    .executes(context -> accept(context.getSource().getClient()) ? 1 : 0));
            dispatcher.register(literal("celestials")
                    .requires(source -> !BazzarGuard.isBlocked())
                    .executes(context -> {
                        showList(context.getSource().getClient());
                        return 1;
                    }));
        });
    }

    // typing "accept celestial" in chat uses your booster instead of sending the message
    static boolean onOwnChat(String message) {
        if (message == null || !message.trim().equalsIgnoreCase(ACCEPT_TEXT)) {
            return false;
        }
        return accept(Minecraft.getInstance());
    }

    private static boolean accept(Minecraft client) {
        if (client.player == null || client.player.connection == null) {
            return false;
        }
        CommandSender.send(client, "celestialbooster");
        return true;
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
            Player player = store().players.get(key(sender));
            if (player == null) {
                player(sender).lastSeenMs = System.currentTimeMillis();
                save();
            } else {
                player.lastSeenMs = System.currentTimeMillis();
            }
        }
        if (body.contains(REQUEST_WORD)) {
            onRequest(client, sender);
        }
    }

    private static void onRequest(Minecraft client, String requester) {
        String self = client.player.getName().getString();
        if (requester.equalsIgnoreCase(self)) {
            return;
        }
        Player me = store().players.get(key(self));
        long now = System.currentTimeMillis();
        // only celestials whose own boost is up get asked, and at most once a minute so a spammer can't flood you
        if (me == null || !isReady(me, now) || now - lastPromptMs < PROMPT_GAP_MS) {
            return;
        }
        lastPromptMs = now;
        MutableComponent accept = Component.literal("[Accept]").withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withBold(true)
                .withClickEvent(new ClickEvent.RunCommand("/acceptcelestial"))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Runs /celestialbooster"))));
        McCompat.chat(client.player, BazaarChat.prefix()
                .append(Component.literal(requester + " is asking for a Celestial Boost. ").withStyle(ChatFormatting.AQUA))
                .append(accept)
                .append(Component.literal(" or type \"accept celestial\"").withStyle(ChatFormatting.GRAY)));
        client.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 0.8f, 1.4f);
    }

    static void showList(Minecraft client) {
        if (client.player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Set<String> online = onlineNames(client);
        store().players.values().removeIf(player -> now - Math.max(player.lastSeenMs, player.lastUsedMs) > FORGET_MS);

        List<String> readyOnline = new ArrayList<>();
        int readyOffline = 0;
        List<Player> cooling = new ArrayList<>();
        for (Player player : store().players.values()) {
            if (!isReady(player, now)) {
                cooling.add(player);
            } else if (online.contains(key(player.name))) {
                readyOnline.add(player.name);
            } else {
                readyOffline++;
            }
        }
        readyOnline.sort(String.CASE_INSENSITIVE_ORDER);
        cooling.sort(Comparator.comparingLong(player -> player.lastUsedMs));

        McCompat.chat(client.player, BazaarChat.prefix().append(Component.literal("Celestial boosts (4h cooldown)")
                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));
        McCompat.chat(client.player, Component.literal(" Ready & online: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(readyOnline.isEmpty() ? "nobody yet" : String.join(", ", readyOnline))
                        .withStyle(ChatFormatting.GREEN))
                .append(Component.literal(readyOffline > 0 ? "  (+" + readyOffline + " offline)" : "")
                        .withStyle(ChatFormatting.DARK_GRAY)));
        if (!cooling.isEmpty()) {
            MutableComponent line = Component.literal(" On cooldown: ").withStyle(ChatFormatting.GRAY);
            for (int i = 0; i < cooling.size(); i++) {
                Player player = cooling.get(i);
                if (i > 0) {
                    line.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
                }
                ChatFormatting color = online.contains(key(player.name)) ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY;
                line.append(Component.literal(player.name + " ").withStyle(color))
                        .append(Component.literal(formatLeft(player.lastUsedMs + COOLDOWN_MS - now)).withStyle(ChatFormatting.YELLOW));
            }
            McCompat.chat(client.player, line);
        }
        if (store().players.isEmpty()) {
            McCompat.chat(client.player, Component.literal(" Nobody tracked yet. Celestials show up once they chat or use their booster.")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    private static boolean isReady(Player player, long now) {
        return player.lastUsedMs <= 0L || now - player.lastUsedMs >= COOLDOWN_MS;
    }

    private static String formatLeft(long ms) {
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

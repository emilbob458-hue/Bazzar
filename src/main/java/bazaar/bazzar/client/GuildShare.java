package bazaar.bazzar.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

// shares guild quests through the Bazzar server: an AFK member's mod scans /gq and uploads it,
// everyone else in the guild reads that copy instead of having the /gq menu pop up
final class GuildShare {
    private static final long POLL_MS = 3L * 60_000L;
    private static final long STALE_GRACE_MS = 5_000L;
    private static final long MAX_AGE_MS = 6L * 60L * 60_000L;
    // if no AFK member has scanned for this long, the normal auto scan takes over again
    private static final long FALLBACK_MS = 20L * 60_000L;
    // how often everyone tells the guild what they've put into the quests, for the "who's working" HUD
    private static final long WORKERS_MS = 5_000L;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Bazzar-GuildShare");
        thread.setDaemon(true);
        return thread;
    });
    private static final Queue<Runnable> MAIN_THREAD = new ConcurrentLinkedQueue<>();

    private static long nextPollMs = 0L;
    private static long nextWorkersMs = 0L;
    private static long lastAppliedUpdated = 0L;
    private static long staleSinceMs = 0L;
    private static boolean claiming = false;
    private static long workCycle = 0L;
    private static double myWork = 0d;
    private static List<Worker> workers = List.of();

    // a guildmate running Bazzar and how much of the current quests they've done
    record Worker(String name, double done, boolean afk) {
    }

    private GuildShare() {
    }

    static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                literal("bazzarguild")
                        .requires(source -> !BazzarGuard.isBlocked())
                        .executes(ctx -> {
                            String guild = guild();
                            say(ctx.getSource().getClient(), guild.isEmpty()
                                    ? "Guild quest sharing is off. Use /bazzarguild <your guild name>."
                                    : "Sharing guild quests with " + guild + ". (/bazzarguild off to stop)");
                            return 1;
                        })
                        .then(literal("off").executes(ctx -> {
                            setGuild("");
                            say(ctx.getSource().getClient(), "Guild quest sharing is off, /gq scans work like before.");
                            return 1;
                        }))
                        .then(literal("hud").executes(ctx -> {
                            boolean shown = GuildWorkHud.toggle();
                            say(ctx.getSource().getClient(), shown
                                    ? "Guild HUD shown, it lists who's working on the quests. Drag it to move, scroll to resize."
                                    : "Guild HUD hidden.");
                            return 1;
                        }))
                        .then(argument("guild", StringArgumentType.greedyString()).executes(ctx -> {
                            String guild = StringArgumentType.getString(ctx, "guild").trim();
                            if (!guild.matches("[A-Za-z0-9_ ]{2,32}")) {
                                say(ctx.getSource().getClient(), "Use letters, numbers and spaces, e.g. /bazzarguild Chillppl");
                                return 0;
                            }
                            setGuild(guild);
                            nextPollMs = 0L;
                            say(ctx.getSource().getClient(), "Sharing guild quests with " + guild
                                    + ". Set the same name on your guildmates' mods.");
                            return 1;
                        }))));
    }

    static boolean isActive() {
        return !guild().isEmpty();
    }

    static boolean fallbackDue() {
        return staleSinceMs > 0L && System.currentTimeMillis() - staleSinceMs >= FALLBACK_MS;
    }

    static void tick(Minecraft client) {
        Runnable task;
        while ((task = MAIN_THREAD.poll()) != null) {
            task.run();
        }
        trackWork();
        String guild = guild();
        long now = System.currentTimeMillis();
        if (guild.isEmpty() || client.player == null || client.getCurrentServer() == null) {
            return;
        }
        if (now >= nextWorkersMs) {
            nextWorkersMs = now + WORKERS_MS;
            reportWork(client, guild);
        }
        if (now < nextPollMs) {
            return;
        }
        nextPollMs = now + POLL_MS;
        get("/gq/get?guild=" + URLEncoder.encode(guild, StandardCharsets.UTF_8), json -> onShared(client, guild, json));
    }

    // the next poll normally sits a few minutes out, but never past the moment the quests refresh:
    // that's when a fresh copy is worth having, and when somebody has to notice nobody scanned
    private static void pollAgain(long now, long refreshAtLocal) {
        long next = now + POLL_MS;
        long atRefresh = refreshAtLocal + STALE_GRACE_MS;
        nextPollMs = refreshAtLocal > 0L && atRefresh > now && atRefresh < next ? atRefresh : next;
    }

    // ---- who's actually doing the quests ----

    static List<Worker> workers() {
        return workers;
    }

    static double myWork() {
        return myWork;
    }

    // the kills and farms this mod counted, kept per set of quests so it starts over when they refresh
    private static void trackWork() {
        double gained = GuildQuestScanner.consumeLiveContribution();
        long cycle = GuildQuestScanner.questsRefreshAtMs();
        if (cycle != workCycle) {
            workCycle = cycle;
            myWork = 0d;
        }
        if (gained > 0d) {
            myWork += gained;
        }
    }

    // everyone posts their own number and gets the whole guild's back, so idle members show up too
    private static void reportWork(Minecraft client, String guild) {
        JsonObject body = body(client, guild);
        body.addProperty("done", Math.round(myWork));
        body.addProperty("afk", PlayerStats.isAfk());
        JsonObject counts = new JsonObject();
        for (Map.Entry<String, Long> count : GuildQuestScanner.liveCounts().entrySet()) {
            counts.addProperty(count.getKey(), count.getValue());
        }
        body.add("counts", counts);
        post("/gq/who", body, GuildShare::onWorkers);
    }

    private static void onWorkers(JsonObject json) {
        if (json == null || !json.has("members")) {
            return;
        }
        if (json.has("totals") && json.get("totals").isJsonObject()) {
            Map<String, Long> totals = new HashMap<>();
            for (Map.Entry<String, JsonElement> total : json.getAsJsonObject("totals").entrySet()) {
                totals.put(total.getKey(), total.getValue().getAsLong());
            }
            GuildQuestScanner.onGuildTotals(totals);
        }
        List<Worker> found = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray("members")) {
            JsonObject member = element.getAsJsonObject();
            found.add(new Worker(member.get("name").getAsString(), member.get("done").getAsDouble(),
                    member.has("afk") && member.get("afk").getAsBoolean()));
        }
        workers = found;
    }

    private static void onShared(Minecraft client, String guild, JsonObject json) {
        if (json == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long offset = json.has("now") ? now - json.get("now").getAsLong() : 0L;
        long updated = json.get("updated").getAsLong();
        long refreshAt = json.get("refreshAt").getAsLong();
        long refreshAtLocal = refreshAt > 0L ? refreshAt + offset : 0L;
        JsonArray quests = json.getAsJsonArray("quests");
        if (quests != null && !quests.isEmpty() && updated > lastAppliedUpdated) {
            lastAppliedUpdated = updated;
            List<String[]> entries = new ArrayList<>();
            for (JsonElement element : quests) {
                JsonObject quest = element.getAsJsonObject();
                entries.add(new String[]{quest.get("name").getAsString(), quest.get("left").getAsString(),
                        quest.get("right").getAsString()});
            }
            GuildQuestScanner.applyShared(entries, refreshAtLocal);
        }
        boolean stale = quests == null || quests.isEmpty() || now >= refreshAtLocal + STALE_GRACE_MS
                || now - (updated + offset) > MAX_AGE_MS;
        if (!stale) {
            staleSinceMs = 0L;
            pollAgain(now, refreshAtLocal);
            return;
        }
        if (staleSinceMs == 0L) {
            staleSinceMs = now;
        }
        // only an AFK player scans, so nobody active gets the /gq menu popping up
        if (!PlayerStats.isAfk() || claiming || GuildQuestScanner.isScanning() || McCompat.screen(client) != null) {
            return;
        }
        claiming = true;
        post("/gq/claim", body(client, guild), claim -> {
            claiming = false;
            if (claim != null && claim.has("ok") && claim.get("ok").getAsBoolean() && PlayerStats.isAfk()) {
                GuildQuestScanner.triggerManual(client);
            }
        });
    }

    // every scan, manual or automatic, is shared so the guild stays up to date
    static void onScanned(List<GuildQuestScanner.QuestEntry> entries, long refreshAtMs) {
        Minecraft client = Minecraft.getInstance();
        String guild = guild();
        if (guild.isEmpty() || entries.isEmpty() || client.player == null) {
            return;
        }
        JsonObject body = body(client, guild);
        JsonArray quests = new JsonArray();
        for (GuildQuestScanner.QuestEntry entry : entries) {
            JsonObject quest = new JsonObject();
            quest.addProperty("name", entry.name());
            quest.addProperty("left", entry.leftValue());
            quest.addProperty("right", entry.rightValue());
            quests.add(quest);
        }
        body.add("quests", quests);
        body.addProperty("refreshIn", Math.max(0L, refreshAtMs - System.currentTimeMillis()));
        staleSinceMs = 0L;
        pollAgain(System.currentTimeMillis(), refreshAtMs);
        post("/gq/push", body, json -> {
        });
    }

    private static String guild() {
        String guild = BazzarConfig.load().guildName;
        return guild == null ? "" : guild.trim();
    }

    private static void setGuild(String guild) {
        BazzarConfig.State state = BazzarConfig.load();
        state.guildName = guild;
        BazzarConfig.save(state);
        lastAppliedUpdated = 0L;
        staleSinceMs = 0L;
        nextWorkersMs = 0L;
        workers = List.of();
    }

    private static JsonObject body(Minecraft client, String guild) {
        JsonObject body = new JsonObject();
        try {
            body.addProperty("id", UsageStats.installId());
        } catch (Exception ignored) {
        }
        body.addProperty("name", client.player.getName().getString());
        body.addProperty("guild", guild);
        return body;
    }

    private static void say(Minecraft client, String text) {
        if (client.player != null) {
            McCompat.chat(client.player, BazaarChat.prefix().append(Component.literal(text).withStyle(ChatFormatting.AQUA)));
        }
    }

    private static void get(String path, Consumer<JsonObject> reply) {
        send(HttpRequest.newBuilder(URI.create(UsageStats.ENDPOINT + path)).GET(), reply);
    }

    private static void post(String path, JsonObject body, Consumer<JsonObject> reply) {
        send(HttpRequest.newBuilder(URI.create(UsageStats.ENDPOINT + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())), reply);
    }

    // network work stays off the game thread, the reply runs back on it during the next tick
    private static void send(HttpRequest.Builder builder, Consumer<JsonObject> reply) {
        HttpRequest request = builder.timeout(Duration.ofSeconds(15)).header("User-Agent", "Bazzar-Guild").build();
        EXECUTOR.execute(() -> {
            JsonObject json = null;
            try {
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonElement parsed = JsonParser.parseString(response.body());
                    json = parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
                }
            } catch (Exception ignored) {
            }
            JsonObject result = json;
            MAIN_THREAD.add(() -> reply.accept(result));
        });
    }
}

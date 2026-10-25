package bazaar.bazzar.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.context.CommandContext;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

final class UsageStats {
    static final String ENDPOINT = "https://bazzar-stats.bazzar-stats.workers.dev";
    private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("bazzar");
    private static final Path ID_FILE = DIR.resolve("install-id.txt");
    private static final Path DISABLED = DIR.resolve("usage-stats-off");
    private static final long PING_INTERVAL_SECONDS = 10L * 60L;
    private static final Queue<Component> MESSAGES = new ConcurrentLinkedQueue<>();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Bazzar-Stats");
        thread.setDaemon(true);
        return thread;
    });

    private UsageStats() {
    }

    static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                literal("bazzarusers")
                        .requires(source -> !BazzarGuard.isBlocked())
                        .executes(UsageStats::runShow)
                        .then(literal("on").executes(ctx -> runToggle(ctx, true)))
                        .then(literal("off").executes(ctx -> runToggle(ctx, false)))));
    }

    static void start() {
        EXECUTOR.scheduleAtFixedRate(UsageStats::pingQuietly, 20L, PING_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    static void tick(Minecraft client) {
        if (client.player == null) {
            return;
        }
        Component message;
        while ((message = MESSAGES.poll()) != null) {
            client.player.displayClientMessage(message, false);
        }
    }

    private static int runShow(CommandContext<FabricClientCommandSource> context) {
        EXECUTOR.execute(() -> {
            try {
                JsonObject stats = JsonParser.parseString(send("GET", "/stats", null)).getAsJsonObject();
                MutableComponent line = prefix()
                        .append(Component.literal("Bazzar users online: ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(Integer.toString(stats.get("online").getAsInt())).withStyle(ChatFormatting.GREEN))
                        .append(Component.literal("  (" + stats.get("total").getAsInt() + " installs total)").withStyle(ChatFormatting.DARK_GRAY));
                MESSAGES.add(line);
                if (stats.has("versions") && stats.get("versions").isJsonObject()) {
                    StringBuilder versions = new StringBuilder();
                    for (Map.Entry<String, JsonElement> entry : stats.getAsJsonObject("versions").entrySet()) {
                        if (!versions.isEmpty()) {
                            versions.append(", ");
                        }
                        versions.append(entry.getKey()).append(": ").append(entry.getValue().getAsInt());
                    }
                    if (!versions.isEmpty()) {
                        MESSAGES.add(prefix().append(Component.literal("Online by version: " + versions).withStyle(ChatFormatting.GRAY)));
                    }
                }
                if (Files.exists(DISABLED)) {
                    MESSAGES.add(prefix().append(Component.literal("You are not counted. /bazzarusers on to be counted.").withStyle(ChatFormatting.DARK_GRAY)));
                }
            } catch (Exception e) {
                MESSAGES.add(prefix().append(Component.literal("Could not load user count: " + e.getMessage()).withStyle(ChatFormatting.RED)));
            }
        });
        return 1;
    }

    private static int runToggle(CommandContext<FabricClientCommandSource> context, boolean enable) {
        try {
            if (enable) {
                Files.deleteIfExists(DISABLED);
            } else {
                Files.createDirectories(DIR);
                Files.writeString(DISABLED, "");
            }
        } catch (IOException e) {
            context.getSource().sendFeedback(prefix()
                    .append(Component.literal("Could not save setting: " + e.getMessage()).withStyle(ChatFormatting.RED)));
            return 0;
        }
        EXECUTOR.execute(enable ? UsageStats::pingQuietly : UsageStats::forgetQuietly);
        context.getSource().sendFeedback(prefix()
                .append(Component.literal("Anonymous user count: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(enable ? "ON" : "OFF").withStyle(enable ? ChatFormatting.GREEN : ChatFormatting.RED))
                .append(Component.literal(enable ? "" : "  (your install was removed from the count)").withStyle(ChatFormatting.DARK_GRAY)));
        return 1;
    }

    private static void pingQuietly() {
        if (Files.exists(DISABLED)) {
            return;
        }
        try {
            JsonObject body = new JsonObject();
            body.addProperty("id", installId());
            body.addProperty("version", FabricLoader.getInstance().getModContainer("bazzar")
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse(""));
            send("POST", "/ping", body.toString());
        } catch (Exception ignored) {
        }
    }

    private static void forgetQuietly() {
        try {
            if (!Files.isRegularFile(ID_FILE)) {
                return;
            }
            JsonObject body = new JsonObject();
            body.addProperty("id", installId());
            send("POST", "/forget", body.toString());
        } catch (Exception ignored) {
        }
    }

    static String installId() throws IOException {
        if (Files.isRegularFile(ID_FILE)) {
            String stored = Files.readString(ID_FILE).trim();
            try {
                return UUID.fromString(stored).toString();
            } catch (IllegalArgumentException ignored) {
            }
        }
        String fresh = UUID.randomUUID().toString();
        Files.createDirectories(DIR);
        Files.writeString(ID_FILE, fresh);
        return fresh;
    }

    static String send(String method, String path, String json) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(ENDPOINT + path))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Bazzar-Stats");
        if (json == null) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        }
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("server returned " + response.statusCode());
        }
        return response.body();
    }

    private static MutableComponent prefix() {
        return Component.literal("[Bazaar] ").withStyle(ChatFormatting.DARK_AQUA);
    }
}

package bazaar.bazzar.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

final class PublicPresets {
    static final Set<String> LOCAL_ONLY = Set.of(
            "shardPayOwner", "shardPayMembers", "shardPayEnabled",
            "cobblePayEnabled", "gemPayEnabled",
            "cobblePayoutEnabled", "cobblePayoutPrice",
            "shardPayoutEnabled", "shardPayoutPrice", "gemPayoutEnabled",
            "celestialBoosterReadyAt",
            "shardPrice", "gemPrice", "currentMulti",
            "autoDumpEnabled", "autoDumpPvPage", "defaultTriggerAdded",
            "hudX", "hudY", "shadyHudX", "shadyHudY", "questHudX", "questHudY", "farmFavX", "farmFavY",
            "profitHudX", "profitHudY", "altHudX", "altHudY",
            "scoreboardGlowOffsetX", "scoreboardGlowOffsetY");
    private static final Set<String> SHARED_KEYS = sharedKeys();
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Bazzar-Presets");
        thread.setDaemon(true);
        return thread;
    });

    record Summary(String code, String name, String author, String description, int downloads) {
    }

    record Preset(String code, String name, String author, JsonObject config, List<String> commands) {
    }

    private PublicPresets() {
    }

    static LiteralArgumentBuilder<FabricClientCommandSource> command() {
        return literal("public")
                .executes(ctx -> runList(ctx, "new"))
                .then(literal("list").executes(ctx -> runList(ctx, "new"))
                        .then(literal("top").executes(ctx -> runList(ctx, "top"))))
                .then(literal("view").then(argument("code", StringArgumentType.word())
                        .executes(ctx -> runView(StringArgumentType.getString(ctx, "code")))))
                .then(literal("apply").then(argument("code", StringArgumentType.word())
                        .executes(ctx -> runApply(StringArgumentType.getString(ctx, "code"), false))
                        .then(literal("confirm")
                                .executes(ctx -> runApply(StringArgumentType.getString(ctx, "code"), true)))))
                .then(literal("upload").then(argument("name", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            ConfigPresets.names().forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .executes(ctx -> runUpload(StringArgumentType.getString(ctx, "name")))))
                .then(literal("delete").then(argument("code", StringArgumentType.word())
                        .executes(ctx -> runDelete(StringArgumentType.getString(ctx, "code")))));
    }

    static void list(String sort, Consumer<List<Summary>> done, Consumer<String> failed) {
        async(() -> {
            JsonObject body = request("GET", "/presets?sort=" + ("top".equals(sort) ? "top" : "new"), null);
            List<Summary> presets = new ArrayList<>();
            JsonArray array = body.has("presets") && body.get("presets").isJsonArray() ? body.getAsJsonArray("presets") : new JsonArray();
            for (JsonElement element : array) {
                if (element.isJsonObject()) {
                    JsonObject item = element.getAsJsonObject();
                    presets.add(new Summary(text(item, "code"), text(item, "name"), text(item, "author"),
                            text(item, "description"), item.has("downloads") ? item.get("downloads").getAsInt() : 0));
                }
            }
            return presets;
        }, done, failed);
    }

    static void fetch(String code, Consumer<Preset> done, Consumer<String> failed) {
        async(() -> {
            JsonObject body = request("GET", "/presets/" + cleanCode(code), null);
            if (!body.has("config") || !body.get("config").isJsonObject()) {
                throw new IOException("that preset has no settings");
            }
            JsonObject config = filter(body.getAsJsonObject("config"));
            return new Preset(text(body, "code"), text(body, "name"), text(body, "author"), config, List.of());
        }, preset -> done.accept(new Preset(preset.code(), preset.name(), preset.author(), preset.config(),
                newCommands(preset.config()))), failed);
    }

    static void upload(String localName, Consumer<String> done, Consumer<String> failed) {
        Path local = ConfigPresets.file(localName);
        String name = local != null ? localName : localName.trim();
        JsonObject config;
        try {
            if (local == null) {
                BazzarConfig.save(BazzarConfig.load());
            }
            config = filter(JsonParser.parseString(Files.readString(local != null ? local : BazzarConfig.path())).getAsJsonObject());
        } catch (Exception e) {
            failed.accept("Could not read " + (local != null ? "preset " + localName : "your settings") + ".");
            return;
        }
        String author = Minecraft.getInstance().getUser().getName();
        async(() -> {
            JsonObject body = new JsonObject();
            body.addProperty("id", UsageStats.installId());
            body.addProperty("name", name);
            body.addProperty("author", author);
            body.addProperty("version", FabricLoader.getInstance().getModContainer("bazzar")
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse(""));
            body.add("config", config);
            JsonObject reply = request("POST", "/presets", body.toString());
            return text(reply, "code");
        }, done, failed);
    }

    static void delete(String code, Consumer<String> done, Consumer<String> failed) {
        async(() -> {
            JsonObject body = new JsonObject();
            body.addProperty("id", UsageStats.installId());
            return text(request("POST", "/presets/" + cleanCode(code) + "/delete", body.toString()), "name");
        }, done, failed);
    }

    static void unshare(String localName, Consumer<Integer> done, Consumer<String> failed) {
        String key = nameKey(localName);
        String author = Minecraft.getInstance().getUser().getName();
        async(() -> {
            JsonObject list = request("GET", "/presets?sort=new", null);
            JsonArray array = list.has("presets") && list.get("presets").isJsonArray() ? list.getAsJsonArray("presets") : new JsonArray();
            int removed = 0;
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                if (!key.equals(nameKey(text(item, "name"))) || !author.equalsIgnoreCase(text(item, "author"))) {
                    continue;
                }
                JsonObject body = new JsonObject();
                body.addProperty("id", UsageStats.installId());
                try {
                    request("POST", "/presets/" + cleanCode(text(item, "code")) + "/delete", body.toString());
                    removed++;
                } catch (IOException ignored) {
                }
            }
            return removed;
        }, done, failed);
    }

    private static String nameKey(String name) {
        return Objects.requireNonNullElse(name, "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
    }

    static ConfigPresets.Result apply(Preset preset) {
        BazzarConfig.State current = BazzarConfig.load();
        JsonObject merged = GSON.toJsonTree(current).getAsJsonObject();
        for (String key : preset.config().keySet()) {
            merged.add(key, preset.config().get(key));
        }
        BazzarConfig.State next;
        try {
            next = GSON.fromJson(merged, BazzarConfig.State.class);
        } catch (RuntimeException e) {
            return new ConfigPresets.Result(false, "Preset " + preset.name() + " has broken settings, nothing changed.");
        }
        if (next == null) {
            return new ConfigPresets.Result(false, "Preset " + preset.name() + " is empty.");
        }
        BazzarConfig.save(next);
        BazzarConfig.reload();
        BazzarClient.applyConfig(BazzarConfig.load());
        async(() -> request("GET", "/presets/" + cleanCode(preset.code()) + "?apply=1", null), ignored -> {
        }, ignored -> {
        });
        return new ConfigPresets.Result(true, "Applied " + preset.name() + " by " + preset.author() + ".");
    }

    private static List<String> newCommands(JsonObject config) {
        JsonObject current = GSON.toJsonTree(BazzarConfig.load()).getAsJsonObject();
        List<String> lines = new ArrayList<>();
        List<String> messages = strings(config, current, "chatTriggerMessages");
        List<String> commands = strings(config, current, "chatTriggerCommands");
        List<String> oldCommands = strings(new JsonObject(), current, "chatTriggerCommands");
        List<String> oldMessages = strings(new JsonObject(), current, "chatTriggerMessages");
        for (int i = 0; i < commands.size(); i++) {
            String command = commands.get(i);
            String message = i < messages.size() ? messages.get(i) : "";
            boolean same = i < oldCommands.size() && command.equals(oldCommands.get(i))
                    && i < oldMessages.size() && message.equals(oldMessages.get(i));
            if (!command.isBlank() && !same) {
                lines.add("Chat trigger \"" + message + "\" runs " + command);
            }
        }
        List<String> macros = strings(config, current, "macroKeyMessages");
        List<String> oldMacros = strings(new JsonObject(), current, "macroKeyMessages");
        for (int i = 0; i < macros.size(); i++) {
            String macro = macros.get(i);
            if (!macro.isBlank() && !(i < oldMacros.size() && macro.equals(oldMacros.get(i)))) {
                lines.add("Macro key " + (i + 1) + " sends " + macro);
            }
        }
        String timer = config.has("macroTimerMessage") && config.get("macroTimerMessage").isJsonPrimitive()
                ? config.get("macroTimerMessage").getAsString() : "";
        if (!timer.isBlank() && !timer.equals(text(current, "macroTimerMessage"))) {
            lines.add("Timer macro sends " + timer);
        }
        return lines;
    }

    private static List<String> strings(JsonObject config, JsonObject fallback, String key) {
        JsonObject source = config.has(key) ? config : fallback;
        List<String> values = new ArrayList<>();
        if (source.has(key) && source.get(key).isJsonArray()) {
            for (JsonElement element : source.getAsJsonArray(key)) {
                values.add(element.isJsonPrimitive() ? element.getAsString().trim() : "");
            }
        }
        return values;
    }

    private static JsonObject filter(JsonObject config) {
        JsonObject filtered = new JsonObject();
        for (String key : config.keySet()) {
            JsonElement value = config.get(key);
            if (SHARED_KEYS.contains(key) && !value.isJsonNull() && !hasNullEntry(value)) {
                filtered.add(key, value);
            }
        }
        return filtered;
    }

    private static boolean hasNullEntry(JsonElement value) {
        if (value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray()) {
                if (element.isJsonNull()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<String> sharedKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (Field field : BazzarConfig.State.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && !LOCAL_ONLY.contains(field.getName())) {
                keys.add(field.getName());
            }
        }
        return keys;
    }

    private static int runList(CommandContext<FabricClientCommandSource> context, String sort) {
        say(Component.literal("Loading public presets...").withStyle(ChatFormatting.GRAY));
        list(sort, presets -> {
            if (presets.isEmpty()) {
                say(Component.literal("No public presets yet. Share yours with /config public upload <name>.")
                        .withStyle(ChatFormatting.GRAY));
                return;
            }
            say(Component.literal(("top".equals(sort) ? "Most used" : "Newest") + " public presets:")
                    .withStyle(ChatFormatting.GRAY));
            for (Summary preset : presets.subList(0, Math.min(15, presets.size()))) {
                MutableComponent line = Component.literal(" " + preset.name()).withStyle(ChatFormatting.WHITE)
                        .append(Component.literal(" by " + preset.author() + " · " + preset.downloads() + " uses  ")
                                .withStyle(ChatFormatting.DARK_GRAY))
                        .append(button("[View]", "/config public view " + preset.code(), ChatFormatting.AQUA))
                        .append(Component.literal(" "))
                        .append(button("[Apply]", "/config public apply " + preset.code(), ChatFormatting.GREEN));
                say(line);
            }
            say(Component.literal("Sort by uses: /config public list top").withStyle(ChatFormatting.DARK_GRAY));
        }, PublicPresets::sayError);
        return 1;
    }

    private static int runView(String code) {
        fetch(code, preset -> {
            say(Component.literal(preset.name()).withStyle(ChatFormatting.WHITE)
                    .append(Component.literal(" by " + preset.author() + " (code " + preset.code() + ")")
                            .withStyle(ChatFormatting.GRAY)));
            say(Component.literal("Changes " + preset.config().size() + " settings.").withStyle(ChatFormatting.GRAY));
            for (String line : preset.commands()) {
                say(Component.literal(" " + line).withStyle(ChatFormatting.YELLOW));
            }
            say(button("[Apply]", "/config public apply " + preset.code(), ChatFormatting.GREEN));
        }, PublicPresets::sayError);
        return 1;
    }

    private static int runApply(String code, boolean confirmed) {
        fetch(code, preset -> {
            if (!confirmed && !preset.commands().isEmpty()) {
                say(Component.literal(preset.name() + " adds commands that run for you:").withStyle(ChatFormatting.GOLD));
                for (String line : preset.commands()) {
                    say(Component.literal(" " + line).withStyle(ChatFormatting.YELLOW));
                }
                say(Component.literal("Only apply if you trust these. ").withStyle(ChatFormatting.GRAY)
                        .append(button("[Apply anyway]", "/config public apply " + preset.code() + " confirm",
                                ChatFormatting.GREEN)));
                return;
            }
            ConfigPresets.Result result = apply(preset);
            say(Component.literal(result.message()).withStyle(result.ok() ? ChatFormatting.GREEN : ChatFormatting.RED));
        }, PublicPresets::sayError);
        return 1;
    }

    private static int runUpload(String name) {
        boolean local = ConfigPresets.file(name) != null;
        say(Component.literal("Uploading " + (local ? "preset " + name : "your current settings as " + name) + "...")
                .withStyle(ChatFormatting.GRAY));
        upload(name, code -> {
            say(Component.literal("Shared! Code: ").withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(code).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal("  Others can use /config public apply " + code).withStyle(ChatFormatting.GRAY)));
            say(Component.literal("Pay group, prices, timers and HUD positions were not uploaded. Remove it with /config public delete "
                    + code).withStyle(ChatFormatting.DARK_GRAY));
        }, PublicPresets::sayError);
        return 1;
    }

    private static int runDelete(String code) {
        delete(code, name -> say(Component.literal("Removed public preset " + name + ".").withStyle(ChatFormatting.GREEN)),
                PublicPresets::sayError);
        return 1;
    }

    private interface Call<T> {
        T run() throws Exception;
    }

    private static <T> void async(Call<T> call, Consumer<T> done, Consumer<String> failed) {
        EXECUTOR.execute(() -> {
            try {
                T result = call.run();
                Minecraft.getInstance().execute(() -> done.accept(result));
            } catch (Exception e) {
                String message = e.getMessage() == null ? "could not reach the preset server" : e.getMessage();
                Minecraft.getInstance().execute(() -> failed.accept(message));
            }
        });
    }

    private static JsonObject request(String method, String path, String json) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(UsageStats.ENDPOINT + path))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Bazzar-Presets");
        if (json == null) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        }
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        JsonObject body;
        try {
            JsonElement parsed = JsonParser.parseString(response.body());
            body = parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            body = new JsonObject();
        }
        if (response.statusCode() != 200) {
            if ((response.statusCode() == 404 && path.startsWith("/presets?")) || response.statusCode() == 503) {
                throw new IOException("public presets are not available yet");
            }
            String error = text(body, "error");
            throw new IOException(error.isEmpty() ? "server returned " + response.statusCode() : error);
        }
        return body;
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    static String cleanCode(String code) {
        return Objects.requireNonNullElse(code, "").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static MutableComponent button(String label, String command, ChatFormatting color) {
        return Component.literal(label).withStyle(style -> style.withColor(color)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
    }

    private static void sayError(String message) {
        if (message.isEmpty()) {
            message = "something went wrong";
        }
        say(Component.literal(message.substring(0, 1).toUpperCase(Locale.ROOT) + message.substring(1) + ".")
                .withStyle(ChatFormatting.RED));
    }

    private static void say(Component message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            McCompat.chat(client.player, BazaarChat.prefix().append(message));
        }
    }
}

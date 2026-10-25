package com.shady.mod.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

final class ShadyConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("shady.json");

    private ShadyConfig() {
    }

    static State load() {
        if (!Files.exists(CONFIG_PATH)) {
            return new State();
        }
        try {
            String json = Files.readString(CONFIG_PATH);
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                return new State();
            }
            JsonObject root = parsed.getAsJsonObject();
            State state = GSON.fromJson(root, State.class);
            return applyDefaults(state, root);
        } catch (IOException | JsonSyntaxException ignored) {
            return new State();
        }
    }

    static void save(State state) {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(state));
        } catch (IOException ignored) {
        }
    }

    private static State applyDefaults(State loaded, JsonObject root) {
        State defaults = new State();
        if (loaded == null) {
            return defaults;
        }

        if (!root.has("hudX")) {
            loaded.hudX = defaults.hudX;
        }
        if (!root.has("hudY")) {
            loaded.hudY = defaults.hudY;
        }
        return loaded;
    }

    static final class State {
        int hudX = 8;
        int hudY = 8;
    }
}

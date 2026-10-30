package bazaar.bazzar.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

final class BazzarConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("bazzar.json");
    private static final long CACHE_TTL_MS = 1000L;
    private static final String DEFAULT_TRIGGER = "hotdog";
    private static final String DEFAULT_TRIGGER_COMMAND = "/launchtizzle";

    private static State cached;
    private static long cachedAtMs;

    private BazzarConfig() {
    }

    static State load() {
        long now = System.currentTimeMillis();
        if (cached != null && now - cachedAtMs < CACHE_TTL_MS) {
            return cached;
        }
        State loaded = loadFromDisk();
        cached = loaded;
        cachedAtMs = now;
        return loaded;
    }

    static Path path() {
        return CONFIG_PATH;
    }

    static void reload() {
        cached = null;
    }

    private static State loadFromDisk() {
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
            State normalized = normalizeForSave(state);
            Files.writeString(CONFIG_PATH, GSON.toJson(normalized));
            cached = normalized;
            cachedAtMs = System.currentTimeMillis();
        } catch (IOException ignored) {
        }
    }

    private static State normalizeForSave(State state) {
        if (state == null) {
            return new State();
        }
        if (state.cropBlacklist == null) {
            state.cropBlacklist = new ArrayList<>();
        }
        if (state.farmFavorites == null) {
            state.farmFavorites = new ArrayList<>();
        }
        if (state.fishWarps == null) {
            state.fishWarps = new ArrayList<>();
        }
        if (state.chatTriggerMessages == null) {
            state.chatTriggerMessages = new ArrayList<>(List.of("", "", "", "", "", "", "", ""));
        }
        if (state.chatTriggerCommands == null) {
            state.chatTriggerCommands = new ArrayList<>(List.of("", "", "", "", "", "", "", ""));
        }
        if (state.chatTriggerSelfOnly == null) {
            state.chatTriggerSelfOnly = new ArrayList<>(List.of(false, false, false, false, false, false, false, false));
        }
        while (state.chatTriggerSelfOnly.size() < 8) {
            state.chatTriggerSelfOnly.add(false);
        }
        if (state.macroKeyMessages == null) {
            state.macroKeyMessages = new ArrayList<>(List.of("", "", "", "", "", "", "", ""));
        }
        while (state.macroKeyMessages.size() < 8) {
            state.macroKeyMessages.add("");
        }
        if (state.macroTimerMessage == null) {
            state.macroTimerMessage = "";
        }
        state.macroTimerIntervalSeconds = Math.max(5, Math.min(3600, state.macroTimerIntervalSeconds));
        if (state.cookieAutoFallback == null || state.cookieAutoFallback.isBlank()) {
            state.cookieAutoFallback = "cobbled";
        }
        if (state.cookieRushType == null || state.cookieRushType.isBlank()) {
            state.cookieRushType = "suspicious";
        }
        if (state.shardPayOwner == null) {
            state.shardPayOwner = "";
        }
        if (state.shardPayMembers == null) {
            state.shardPayMembers = new ArrayList<>(List.of("", "", "", "", "", ""));
        }
        if (state.celestialBoosterReadyAt == null) {
            state.celestialBoosterReadyAt = new HashMap<>();
        }
        if (state.autoBazaarScanIntervalSeconds < 15) {
            state.autoBazaarScanIntervalSeconds = 15;
        } else if (state.autoBazaarScanIntervalSeconds > 3600) {
            state.autoBazaarScanIntervalSeconds = 3600;
        }
        state.questHudScale = Math.max(0.65f, Math.min(2.5f, state.questHudScale));
        state.questHudBackgroundMode = Math.max(0, Math.min(2, state.questHudBackgroundMode));
        state.autoPunchIntervalSeconds = Math.max(5, Math.min(600, state.autoPunchIntervalSeconds));
        state.profitHudScale = Math.max(0.65f, Math.min(2.5f, state.profitHudScale));
        state.scoreboardGlowScale = Math.max(0.5f, Math.min(2.0f, state.scoreboardGlowScale));
        if (state.milestoneSelected == null) {
            state.milestoneSelected = new ArrayList<>();
        }
        state.milestoneMode = Math.max(0, Math.min(2, state.milestoneMode));
        state.milestoneHudStyle = Math.max(0, Math.min(1, state.milestoneHudStyle));
        if (state.cbPrices == null) {
            state.cbPrices = new HashMap<>();
        }
        if (state.cbQuickCurrency == null || state.cbQuickCurrency.isBlank()) {
            state.cbQuickCurrency = "tokens";
        }
        state.milestoneHudScale = Math.max(0.65f, Math.min(2.5f, state.milestoneHudScale));
        state.autoDumpPvPage = Math.max(1, Math.min(50, state.autoDumpPvPage));
        state.guildWorkHudScale = Math.max(0.65f, Math.min(2.5f, state.guildWorkHudScale));
        state.killHudScale = Math.max(0.65f, Math.min(2.5f, state.killHudScale));
        return state;
    }

    private static State applyDefaults(State loaded, JsonObject root) {
        State defaults = new State();
        if (loaded == null) {
            return defaults;
        }

        if (!root.has("hudVisible")) {
            loaded.hudVisible = defaults.hudVisible;
        }
        if (!root.has("hudX")) {
            loaded.hudX = defaults.hudX;
        }
        if (!root.has("hudY")) {
            loaded.hudY = defaults.hudY;
        }
        if (!root.has("hudScale")) {
            loaded.hudScale = defaults.hudScale;
        }
        if (!root.has("shardPrice")) {
            loaded.shardPrice = defaults.shardPrice;
        }
        if (!root.has("currentMulti")) {
            loaded.currentMulti = defaults.currentMulti;
        }
        if (!root.has("autoBazaarScanEnabled")) {
            loaded.autoBazaarScanEnabled = defaults.autoBazaarScanEnabled;
        }
        if (!root.has("autoBazaarScanIntervalSeconds")) {
            loaded.autoBazaarScanIntervalSeconds = defaults.autoBazaarScanIntervalSeconds;
        }
        if (!root.has("shadyHudX")) {
            loaded.shadyHudX = defaults.shadyHudX;
        }
        if (!root.has("shadyHudY")) {
            loaded.shadyHudY = defaults.shadyHudY;
        }
        if (!root.has("autoEggEnabled")) {
            loaded.autoEggEnabled = defaults.autoEggEnabled;
        }
        if (!root.has("questHudVisible")) {
            loaded.questHudVisible = defaults.questHudVisible;
        }
        if (!root.has("farmFavX")) {
            loaded.farmFavX = defaults.farmFavX;
        }
        if (!root.has("farmFavY")) {
            loaded.farmFavY = defaults.farmFavY;
        }
        if (!root.has("questHudX")) {
            loaded.questHudX = defaults.questHudX;
        }
        if (!root.has("questHudY")) {
            loaded.questHudY = defaults.questHudY;
        }
        if (!root.has("questHudScale")) {
            loaded.questHudScale = defaults.questHudScale;
        }
        if (!root.has("questHudBackgroundMode")) {
            loaded.questHudBackgroundMode = defaults.questHudBackgroundMode;
        }
        if (!root.has("guildQuestAutoScanEnabled")) {
            loaded.guildQuestAutoScanEnabled = defaults.guildQuestAutoScanEnabled;
        }
        if (!root.has("cookieAfkFailsafeEnabled")) {
            loaded.cookieAfkFailsafeEnabled = defaults.cookieAfkFailsafeEnabled;
        }
        if (!root.has("farmQuestsEnabled")) {
            loaded.farmQuestsEnabled = defaults.farmQuestsEnabled;
        }
        if (!root.has("scoreboardGlowEnabled")) {
            loaded.scoreboardGlowEnabled = defaults.scoreboardGlowEnabled;
        }
        if (!root.has("customThemeRgb")) {
            loaded.customThemeRgb = defaults.customThemeRgb;
        }

        if (loaded.themeId == null || loaded.themeId.isBlank()) {
            loaded.themeId = defaults.themeId;
        }
        if (loaded.chatTriggerMessages == null) {
            loaded.chatTriggerMessages = new ArrayList<>(List.of("", "", "", "", "", "", "", ""));
        }
        if (loaded.chatTriggerCommands == null) {
            loaded.chatTriggerCommands = new ArrayList<>(List.of("", "", "", "", "", "", "", ""));
        }
        if (loaded.macroKeyMessages == null) {
            loaded.macroKeyMessages = new ArrayList<>(List.of("", "", "", "", "", "", "", ""));
        }
        while (loaded.macroKeyMessages.size() < 8) {
            loaded.macroKeyMessages.add("");
        }
        if (loaded.macroTimerMessage == null) {
            loaded.macroTimerMessage = "";
        }
        if (!root.has("macroTimerIntervalSeconds")) {
            loaded.macroTimerIntervalSeconds = defaults.macroTimerIntervalSeconds;
        }
        if (loaded.chatTriggerSelfOnly == null) {
            loaded.chatTriggerSelfOnly = new ArrayList<>(List.of(false, false, false, false, false, false, false, false));
        }
        while (loaded.chatTriggerSelfOnly.size() < 8) {
            loaded.chatTriggerSelfOnly.add(false);
        }
        if (!root.has("defaultTriggerAdded")) {
            addDefaultTrigger(loaded);
        }
        if (loaded.cookieAutoFallback == null || loaded.cookieAutoFallback.isBlank()) {
            loaded.cookieAutoFallback = defaults.cookieAutoFallback;
        }
        if (loaded.cookieRushType == null || loaded.cookieRushType.isBlank()) {
            loaded.cookieRushType = defaults.cookieRushType;
        }
        if (loaded.shardPayOwner == null) {
            loaded.shardPayOwner = "";
        }
        if (loaded.shardPayMembers == null) {
            loaded.shardPayMembers = new ArrayList<>(List.of("", "", "", "", "", ""));
        }
        if (loaded.cropBlacklist == null) {
            loaded.cropBlacklist = new ArrayList<>();
        }
        if (loaded.farmFavorites == null) {
            loaded.farmFavorites = new ArrayList<>();
        }
        if (loaded.fishWarps == null) {
            loaded.fishWarps = new ArrayList<>();
        }
        if (loaded.celestialBoosterReadyAt == null) {
            loaded.celestialBoosterReadyAt = new HashMap<>();
        }
        if (loaded.shardPayoutPrice > 0.0d) {
            loaded.shardPrice = loaded.shardPayoutPrice;
            loaded.shardPayoutPrice = 0.0d;
        }
        return loaded;
    }

    private static void addDefaultTrigger(State loaded) {
        loaded.defaultTriggerAdded = true;
        List<String> messages = loaded.chatTriggerMessages;
        List<String> commands = loaded.chatTriggerCommands;
        for (String message : messages) {
            if (message != null && message.trim().equalsIgnoreCase(DEFAULT_TRIGGER)) {
                return;
            }
        }
        int rows = Math.min(messages.size(), commands.size());
        for (int i = 0; i < rows; i++) {
            boolean emptyMessage = messages.get(i) == null || messages.get(i).isBlank();
            boolean emptyCommand = commands.get(i) == null || commands.get(i).isBlank();
            if (emptyMessage && emptyCommand) {
                messages.set(i, DEFAULT_TRIGGER);
                commands.set(i, DEFAULT_TRIGGER_COMMAND);
                return;
            }
        }
    }

    static final class State {
        boolean hudVisible = true;
        int hudX = 10;
        int hudY = 10;
        float hudScale = 1.0f;
        double shardPrice = 0.0d;
        double gemPrice = 0.0d;
        double currentMulti = 1.0d;
        List<String> cropBlacklist = new ArrayList<>();
        List<String> farmFavorites = new ArrayList<>();
        List<String> fishWarps = new ArrayList<>();
        int farmFavX = -1;
        int farmFavY = -1;
        boolean farmQuestsEnabled = true;
        boolean autoBazaarScanEnabled = false;
        int autoBazaarScanIntervalSeconds = 120;
        int shadyHudX = 8;
        int shadyHudY = 8;
        boolean autoEggEnabled = false;
        boolean questHudVisible = true;
        int questHudX = 6;
        int questHudY = 6;
        float questHudScale = 1.0f;
        int questHudBackgroundMode = 0;
        boolean guildQuestAutoScanEnabled = false;
        String themeId = "indigo";
        List<String> chatTriggerMessages = new ArrayList<>(List.of(DEFAULT_TRIGGER, "", "", "", "", "", "", ""));
        List<String> chatTriggerCommands = new ArrayList<>(List.of(DEFAULT_TRIGGER_COMMAND, "", "", "", "", "", "", ""));
        boolean defaultTriggerAdded = true;
        List<String> macroKeyMessages = new ArrayList<>(List.of("", "", "", "", "", "", "", ""));
        boolean macroTimerEnabled = false;
        String macroTimerMessage = "";
        int macroTimerIntervalSeconds = 60;
        List<Boolean> chatTriggerSelfOnly = new ArrayList<>(List.of(false, false, false, false, false, false, false, false));
        boolean cookieAutoEnabled = false;
        String cookieAutoFallback = "cobbled";
        boolean cookieAutoDisableAfterCelestial = false;
        boolean cookieAutoClaimAllOnGift = false;
        boolean cookieCelestialStartAlertEnabled = false;
        boolean cookieAfkFailsafeEnabled = true;
        boolean cookieAutoOnRush = false;
        String cookieRushType = "suspicious";
        boolean scoreboardGlowEnabled = true;
        int scoreboardGlowOffsetX = 0;
        int scoreboardGlowOffsetY = 0;
        int customThemeRgb = 0xFF5CA8;
        boolean shadyFinderEnabled = true;
        boolean shardPayEnabled = false;
        String shardPayOwner = "";
        List<String> shardPayMembers = new ArrayList<>(List.of("", "", "", "", "", ""));
        boolean autoPunchEnabled = false;
        int autoPunchIntervalSeconds = 30;
        boolean profitHudEnabled = true;
        int profitHudX = 6;
        int profitHudY = 140;
        boolean cobblePayEnabled = true;
        boolean gemPayEnabled = true;
        Map<String, Long> celestialBoosterReadyAt = new HashMap<>();
        boolean playtimeClaimEnabled = true;
        boolean altHudEnabled = true;
        int altHudX = 6;
        int altHudY = 230;
        float profitHudScale = 1.0f;
        float scoreboardGlowScale = 1.0f;
        boolean autoDumpEnabled = false;
        int autoDumpPvPage = 1;
        boolean cobblePayoutEnabled = false;
        double cobblePayoutPrice = 0.0d;
        boolean shardPayoutEnabled = false;
        double shardPayoutPrice = 0.0d;
        boolean gemPayoutEnabled = false;
        boolean milestoneHudEnabled = true;
        int milestoneMode = 0;
        List<String> milestoneSelected = new ArrayList<>();
        int milestoneHudX = 6;
        int milestoneHudY = 60;
        float milestoneHudScale = 1.0f;
        int milestoneHudStyle = 0;
        double shardPayoutMin = 5.0d;
        double gemPayoutMin = 5.0d;
        double cobblePayoutMin = 5.0d;
        boolean cbSellEnabled = false;
        Map<String, Double> cbPrices = new HashMap<>();
        boolean cbPoolEnabled = false;
        String cbQuickCurrency = "tokens";
        boolean cbHudVisible = false;
        int cbHudX = 6;
        int cbHudY = 100;
        String guildName = "";
        boolean guildShareOff = false;
        boolean guildWorkHudVisible = false;
        int guildWorkHudX = 6;
        int guildWorkHudY = 140;
        float guildWorkHudScale = 1.0f;
        boolean killHudVisible = false;
        int killHudX = 6;
        int killHudY = 200;
        float killHudScale = 1.0f;
        boolean cbTutorialSeen = false;
        boolean cbWarningAccepted = false;
        boolean cbWarningHidden = false;
        boolean cbBuyCooldown = true;
        long lastAnnouncementId = 0L;
    }
}

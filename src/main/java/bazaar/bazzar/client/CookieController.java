package bazaar.bazzar.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.BossEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CookieController {
    static final String COOKIE_SUSPICIOUS = "suspicious";
    static final String COOKIE_COBBLED = "cobbled";
    static final String COOKIE_COLOSSAL = "colossal";
    static final String COOKIE_GOLDEN = "golden";
    static final String COOKIE_GRANDMA = "grandma";
    static final String COOKIE_ABNORMAL = "abnormal";
    static final String COOKIE_NORMAL = "normal";
    static final String COOKIE_WEAK = "weak";

    private static final String[] COOKIE_TIERS = {
            COOKIE_SUSPICIOUS,
            COOKIE_COBBLED,
            COOKIE_COLOSSAL,
            COOKIE_GOLDEN,
            COOKIE_GRANDMA,
            COOKIE_ABNORMAL,
            COOKIE_NORMAL,
            COOKIE_WEAK
    };

    private static final long CELESTIAL_CACHE_MS = 1000L;
    private static final long AUTO_TRIGGER_COOLDOWN_MS = 10_000L;
    private static final long CELESTIAL_DEBOUNCE_MS = 200L;
    private static final long CLAIMALL_COOLDOWN_MS = 3000L;
    private static final long RUSH_TRIGGER_COOLDOWN_MS = 10_000L;
    private static final long AFK_DISABLE_MS = 3 * 60_000L;
    private static final long RUSH_RESTORE_TIMEOUT_MS = 30_000L;
    private static final long RUSH_RESTORE_CHECK_MS = 2_500L;
    private static final long RUSH_ENDED_IGNORE_MS = 15_000L;
    private static final Path RUSH_LOG = FabricLoader.getInstance().getConfigDir().resolve("bazzar").resolve("rush-log.txt");
    private static final Logger LOGGER = LoggerFactory.getLogger("bazzar");
    private static final Pattern COOKIE_GIFT_PATTERN =
            Pattern.compile("(?i)your\\s+cookie\\s+charm\\s+gifted\\s+you");
    private static final Pattern CHAIN_DONE_PATTERN =
            Pattern.compile("(?i)rush\\s+chain\\s*\\(");
    private static final Pattern RUSH_COUNTER_PATTERN =
            Pattern.compile("(?i)\u26a1\\s*[\\d.,]+\\s*[a-z]{0,3}x\\s*\u26a1");
    private static final Pattern COOKIE_SET_PATTERN =
            Pattern.compile("(?i)^you\\s+set\\s+your\\s+active\\s+fortune\\s+cookie\\s+to\\s+(.+)$");
    private static final Pattern COOKIE_OFF_PATTERN =
            Pattern.compile("(?i)^(you\\s+deactivated\\s+your\\s+fortune\\s+cookie|your\\s+fortune\\s+cookie\\s+ran\\s+out)");

    private static long lastCelestialCheckMs = 0L;
    private static boolean lastCelestialActive = false;
    private static boolean lastStableCelestialActive = false;
    private static boolean lastObservedCelestialActive = false;
    private static long lastCelestialStateChangeMs = 0L;
    private static long lastAutoTriggerMs = 0L;
    private static boolean hadCelestialSession = false;
    private static long lastClaimAllMs = 0L;
    private static long lastRushTriggerMs = 0L;
    private static boolean rushCookiePending = false;
    private static String rushPreviousCookie = null;
    private static String rushCookieType = null;
    private static long rushStartedMs = 0L;
    private static long lastRushChainEndMs = 0L;
    private static long rushRestoreCheckAtMs = 0L;
    private static String rushRestoreTarget = null;
    private static String rushRestoreFrom = null;
    private static String lastUsedLabel = "";
    private static String lastCookieCommand = null;
    private static String chatActiveCookie = null;

    private static long lastActivityMs = 0L;
    private static double lastActivityX;
    private static double lastActivityY;
    private static double lastActivityZ;
    private static float lastActivityYaw;
    private static float lastActivityPitch;
    private static boolean afkCookieDisabled = false;

    private CookieController() {
    }

    static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                literal("cookie")
                        .requires(source -> !BazzarGuard.isBlocked())
                        .executes(context -> runCookie(context, CookieMode.AUTO))
                        .then(argument("choice", StringArgumentType.greedyString())
                                .executes(CookieController::runCookieChoice))
        ));
    }

    private static int runCookieChoice(CommandContext<FabricClientCommandSource> context) {
        String choice = StringArgumentType.getString(context, "choice");
        if (choice == null) {
            choice = "";
        }
        String lower = choice.trim().toLowerCase(Locale.ROOT);
        if (lower.isBlank() || lower.contains("auto")) {
            return runCookie(context, CookieMode.AUTO);
        }
        CookieMode mode = parseCookieMode(lower);
        if (mode != null) {
            return runCookie(context, mode);
        }
        context.getSource().sendFeedback(prefix().append(Component.literal(
                "Unknown cookie choice. Use: /cookie [auto|suspicious|cobbled|colossal|golden|grandma|abnormal|normal|weak]")));
        return 0;
    }

    private static int runCookie(CommandContext<FabricClientCommandSource> context, CookieMode mode) {
        Minecraft client = context.getSource().getClient();
        if (BazzarGuard.isBlocked()) {
            return 0;
        }
        if (client == null || client.player == null || client.player.connection == null) {
            context.getSource().sendFeedback(prefix().append(Component.literal("You must be in game.")));
            return 0;
        }
        String cookieType = chooseCookieType(client, mode);
        if (cookieType == null) {
            context.getSource().sendFeedback(prefix().append(Component.literal("Unable to determine cookie type.")));
            return 0;
        }
        if (!useCookie(client, mode, cookieType)) {
            context.getSource().sendFeedback(prefix().append(Component.literal("Failed to use cookie.")));
            return 0;
        }
        context.getSource().sendFeedback(prefix().append(Component.literal("Used cookie: " + cookieType)));
        return 1;
    }

    static CookieSnapshot getSnapshot(Minecraft client) {
        return new CookieSnapshot(isCelestialBoostActive(client));
    }

    static boolean canUseCookie(Minecraft client, CookieMode mode) {
        return client != null && client.player != null && client.player.connection != null;
    }

    static String recommendedLabel(CookieSnapshot snapshot, CookieMode mode) {
        if (snapshot == null) {
            return "none";
        }
        if (mode == CookieMode.AUTO) {
            if (snapshot.celestialBoostActive()) {
                return "5x (Suspicious)";
            }
            return getCookieLabel(getFallbackCookieType());
        }
        return getCookieLabel(mapCookieModeToType(mode));
    }

    static String getLastUsedLabel() {
        return lastUsedLabel;
    }

    static boolean useCookie(Minecraft client, CookieMode mode) {
        if (client == null || client.player == null || client.player.connection == null) {
            return false;
        }
        String cookieType = chooseCookieType(client, mode);
        if (cookieType == null) {
            return false;
        }
        return useCookie(client, mode, cookieType);
    }

    private static boolean useCookie(Minecraft client, CookieMode mode, String cookieType) {
        if (client == null || client.player == null || client.player.connection == null) {
            return false;
        }
        try {
            if (cookieType.equals(detectActiveCookie(client))) {
                lastUsedLabel = cookieType;
                lastCookieCommand = cookieType;
                return true;
            }
            sendServerCommand(client, "cookie " + cookieType);
            lastUsedLabel = cookieType;
            lastCookieCommand = cookieType;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void sendServerCommand(Minecraft client, String command) {
        client.player.connection.send(new ServerboundChatCommandPacket(command));
    }

    public static void onTitle(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        boolean counter = RUSH_COUNTER_PATTERN.matcher(text).find();
        if (!counter) {
            if (lower.contains("rush") || lower.contains("echo") || lower.contains("chain")) {
                logRush("title: " + text);
            }
            return;
        }
        if (rushCookiePending) {
            return;
        }
        boolean justEnded = System.currentTimeMillis() - lastRushChainEndMs < RUSH_ENDED_IGNORE_MS;
        String verdict = justEnded ? "ignored (rush already ended)" : "rush started";
        LOGGER.info("[Bazzar] rush counter \"{}\" -> {}", text, verdict);
        logRush("title: " + text + " -> " + verdict);
        if (!justEnded) {
            onRushFound(Minecraft.getInstance());
        }
    }

    private static void logRush(String line) {
        try {
            Files.createDirectories(RUSH_LOG.getParent());
            Files.writeString(RUSH_LOG, LocalTime.now() + " " + line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    static void onRushFound(Minecraft client) {
        BazzarConfig.State state = BazzarConfig.load();
        if (state == null || !state.cookieAutoOnRush) {
            return;
        }
        if (client == null || client.player == null || client.player.connection == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRushTriggerMs < RUSH_TRIGGER_COOLDOWN_MS) {
            return;
        }
        String cookieType = normalizeCookieType(state.cookieRushType);
        if (cookieType == null) {
            cookieType = COOKIE_SUSPICIOUS;
        }
        String previous = detectActiveCookie(client);
        if (cookieType.equals(previous)) {
            lastRushTriggerMs = now;
            return;
        }
        if (useCookie(client, CookieMode.AUTO, cookieType)) {
            logRush("  -> switched to " + cookieType + (previous == null ? " (no cookie before)" : " (was " + previous + ")"));
            lastRushTriggerMs = now;
            if (!rushCookiePending) {
                rushPreviousCookie = previous;
            }
            rushCookiePending = true;
            rushCookieType = cookieType;
            rushStartedMs = now;
        }
    }

    private static void endRushCookie(Minecraft client) {
        if (!rushCookiePending) {
            return;
        }
        String previous = rushPreviousCookie;
        String rushType = rushCookieType;
        rushCookiePending = false;
        rushPreviousCookie = null;
        rushCookieType = null;
        if (COOKIE_SUSPICIOUS.equals(rushType) && isCelestialBoostActive(client)) {
            logRush("  -> keeping " + rushType + " (celestial boost)");
            return;
        }
        restoreAfterRush(client, previous, rushType);
        rushRestoreCheckAtMs = System.currentTimeMillis() + RUSH_RESTORE_CHECK_MS;
        rushRestoreTarget = previous;
        rushRestoreFrom = rushType;
    }

    private static void restoreAfterRush(Minecraft client, String previous, String rushType) {
        if (rushType == null || !rushType.equals(detectActiveCookie(client))) {
            return;
        }
        if (previous != null) {
            sendServerCommand(client, "cookie " + previous);
            lastUsedLabel = previous;
            lastCookieCommand = previous;
            logRush("  -> switched back to " + previous);
        } else {
            sendServerCommand(client, "cookie " + rushType);
            lastUsedLabel = "disabled " + rushType;
            lastCookieCommand = null;
            logRush("  -> turned " + rushType + " off");
        }
    }

    private static void checkRushRestore(Minecraft client, long now) {
        if (rushRestoreCheckAtMs == 0L || now < rushRestoreCheckAtMs) {
            return;
        }
        String previous = rushRestoreTarget;
        String rushType = rushRestoreFrom;
        rushRestoreCheckAtMs = 0L;
        rushRestoreTarget = null;
        rushRestoreFrom = null;
        if (COOKIE_SUSPICIOUS.equals(rushType) && isCelestialBoostActive(client)) {
            return;
        }
        restoreAfterRush(client, previous, rushType);
    }

    static String[] getRushCookieOptions() {
        return COOKIE_TIERS.clone();
    }

    static void onChatMessage(Minecraft client, String message) {
        trackActiveCookie(message);
        if (client != null && client.player != null && client.player.connection != null && message != null) {
            BazzarConfig.State state = BazzarConfig.load();
            if (state.cookieAutoClaimAllOnGift && COOKIE_GIFT_PATTERN.matcher(message).find()) {
                long now = System.currentTimeMillis();
                if (now - lastClaimAllMs >= CLAIMALL_COOLDOWN_MS) {
                    client.player.connection.sendCommand("claimall");
                    lastClaimAllMs = now;
                }
            }
            if (CHAIN_DONE_PATTERN.matcher(message).find()) {
                lastRushChainEndMs = System.currentTimeMillis();
                logRush("chat: " + message + (rushCookiePending ? " -> switching back" : ""));
                if (rushCookiePending) {
                    endRushCookie(client);
                }
            }
        }

        if (message == null || !message.contains("You don't have any of those cookies")) {
            return;
        }
        if (client == null || client.player == null || client.player.connection == null) {
            return;
        }
        if (lastCookieCommand == null) {
            return;
        }

        String nextCookie = nextCookieTierDown(lastCookieCommand);
        if (nextCookie == null) {
            return;
        }
        // /cookie toggles, so stepping down onto the cookie that's already running would turn it off.
        String active = detectActiveCookie(client);
        if (nextCookie.equals(active) || (rushCookiePending && nextCookie.equals(rushPreviousCookie))) {
            lastUsedLabel = nextCookie;
            lastCookieCommand = nextCookie;
            if (rushCookiePending) {
                logRush("  -> no " + rushCookieType + " cookies, kept " + nextCookie);
                rushCookiePending = false;
                rushPreviousCookie = null;
                rushCookieType = null;
            }
            return;
        }
        sendServerCommand(client, "cookie " + nextCookie);
        lastUsedLabel = nextCookie;
        lastCookieCommand = nextCookie;
        if (rushCookiePending) {
            logRush("  -> no " + rushCookieType + " cookies, using " + nextCookie);
            rushCookieType = nextCookie;
        }
    }

    private static void trackActiveCookie(String message) {
        if (message == null) {
            return;
        }
        String text = SbText.stripFormattingCodes(message).trim();
        var set = COOKIE_SET_PATTERN.matcher(text);
        if (set.find()) {
            String type = normalizeCookieType(set.group(1));
            if (type != null) {
                chatActiveCookie = type;
            }
        } else if (COOKIE_OFF_PATTERN.matcher(text).find()) {
            chatActiveCookie = null;
        }
    }

    static String nextCookieTierDown(String current) {
        for (int i = 0; i < COOKIE_TIERS.length - 1; i++) {
            if (COOKIE_TIERS[i].equals(current)) {
                return COOKIE_TIERS[i + 1];
            }
        }
        return null;
    }

    private static String chooseCookieType(Minecraft client, CookieMode mode) {
        if (client == null) {
            return null;
        }
        if (mode == CookieMode.AUTO) {
            CookieSnapshot snapshot = getSnapshot(client);
            if (snapshot == null) {
                return null;
            }
            if (snapshot.celestialBoostActive()) {

                return COOKIE_SUSPICIOUS;
            }
            return getFallbackCookieType();
        }
        return mapCookieModeToType(mode);
    }

    static String getFallbackCookieType() {
        BazzarConfig.State state = BazzarConfig.load();
        String normalized = normalizeCookieType(state.cookieAutoFallback);
        return normalized != null ? normalized : COOKIE_COBBLED;
    }

    static String[] getFallbackCookieOptions() {
        return new String[]{
                COOKIE_COLOSSAL,
                COOKIE_GOLDEN,
                COOKIE_GRANDMA,
                COOKIE_ABNORMAL,
                COOKIE_NORMAL,
                COOKIE_WEAK,
                COOKIE_COBBLED
        };
    }

    static String normalizeCookieType(String raw) {
        if (raw == null) {
            return null;
        }
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) {
            return null;
        }
        if (lower.contains("susp")) {
            return COOKIE_SUSPICIOUS;
        }
        if (lower.contains("cobb")) {
            return COOKIE_COBBLED;
        }
        if (lower.contains(COOKIE_COLOSSAL)) {
            return COOKIE_COLOSSAL;
        }
        if (lower.contains(COOKIE_GOLDEN)) {
            return COOKIE_GOLDEN;
        }
        if (lower.contains(COOKIE_GRANDMA)) {
            return COOKIE_GRANDMA;
        }
        if (lower.contains(COOKIE_ABNORMAL)) {
            return COOKIE_ABNORMAL;
        }
        if (lower.contains(COOKIE_NORMAL)) {
            return COOKIE_NORMAL;
        }
        if (lower.contains(COOKIE_WEAK)) {
            return COOKIE_WEAK;
        }
        if (lower.contains("2.25")) {
            return COOKIE_GOLDEN;
        }
        if (lower.contains("2.5")) {
            return COOKIE_COLOSSAL;
        }
        if (lower.contains("1.75")) {
            return COOKIE_ABNORMAL;
        }
        if (lower.contains("1.5")) {
            return COOKIE_NORMAL;
        }
        if (lower.contains("1.25")) {
            return COOKIE_WEAK;
        }
        if (lower.contains("5")) {
            return COOKIE_SUSPICIOUS;
        }
        if (lower.contains("3")) {
            return COOKIE_COBBLED;
        }
        if (lower.contains("2")) {
            return COOKIE_GRANDMA;
        }
        return null;
    }

    static CookieMode parseCookieMode(String raw) {
        String normalized = normalizeCookieType(raw);
        if (normalized == null) {
            return null;
        }
        return switch (normalized) {
            case COOKIE_SUSPICIOUS -> CookieMode.SUSPICIOUS;
            case COOKIE_COBBLED -> CookieMode.COBBLED;
            case COOKIE_COLOSSAL -> CookieMode.COLOSSAL;
            case COOKIE_GOLDEN -> CookieMode.GOLDEN;
            case COOKIE_GRANDMA -> CookieMode.GRANDMA;
            case COOKIE_ABNORMAL -> CookieMode.ABNORMAL;
            case COOKIE_NORMAL -> CookieMode.NORMAL;
            case COOKIE_WEAK -> CookieMode.WEAK;
            default -> null;
        };
    }

    static String nextFallbackCookieType(String current) {
        String[] options = getFallbackCookieOptions();
        String normalized = normalizeCookieType(current);
        int index = 0;
        if (normalized != null) {
            for (int i = 0; i < options.length; i++) {
                if (options[i].equals(normalized)) {
                    index = i;
                    break;
                }
            }
        }
        return options[(index + 1) % options.length];
    }

    static String getCookieLabel(String cookieType) {
        String normalized = normalizeCookieType(cookieType);
        if (normalized == null) {
            return "Unknown";
        }
        return switch (normalized) {
            case COOKIE_SUSPICIOUS -> "5x (Suspicious)";
            case COOKIE_COBBLED -> "3x (Cobbled)";
            case COOKIE_COLOSSAL -> "2.5x (Colossal)";
            case COOKIE_GOLDEN -> "2.25x (Golden)";
            case COOKIE_GRANDMA -> "2x (Grandma)";
            case COOKIE_ABNORMAL -> "1.75x (Abnormal)";
            case COOKIE_NORMAL -> "1.5x (Normal)";
            case COOKIE_WEAK -> "1.25x (Weak)";
            default -> "Unknown";
        };
    }

    static String mapCookieModeToType(CookieMode mode) {
        return switch (mode) {
            case SUSPICIOUS -> COOKIE_SUSPICIOUS;
            case COBBLED -> COOKIE_COBBLED;
            case COLOSSAL -> COOKIE_COLOSSAL;
            case GOLDEN -> COOKIE_GOLDEN;
            case GRANDMA -> COOKIE_GRANDMA;
            case ABNORMAL -> COOKIE_ABNORMAL;
            case NORMAL -> COOKIE_NORMAL;
            case WEAK -> COOKIE_WEAK;
            default -> COOKIE_COBBLED;
        };
    }

    static boolean isCelestialBoostActive(Minecraft client) {
        if (client == null || client.gui == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastCelestialCheckMs < CELESTIAL_CACHE_MS) {
            return lastCelestialActive;
        }
        lastCelestialCheckMs = now;
        try {
            BossHealthOverlay overlay = client.gui.getBossOverlay();
            if (overlay == null) {
                lastCelestialActive = false;
                return false;
            }
            boolean active = false;
            for (Field field : overlay.getClass().getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(overlay);
                if (!(value instanceof Map<?, ?> map)) {
                    continue;
                }
                for (Object event : map.values()) {
                    if (!(event instanceof BossEvent bossEvent)) {
                        continue;
                    }
                    Component name = bossEvent.getName();
                    if (name == null) {
                        continue;
                    }
                    if (SbText.normalizeText(name.getString()).contains("celestial boost")) {
                        active = true;
                        break;
                    }
                }
                if (active) {
                    break;
                }
            }
            lastCelestialActive = active;
            return active;
        } catch (Throwable ignored) {
            lastCelestialActive = false;
            return false;
        }
    }

    static void triggerAuto(Minecraft client) {
        BazzarConfig.State state = BazzarConfig.load();
        if (client == null || client.player == null || client.player.connection == null) {
            return;
        }
        long now = System.currentTimeMillis();

        if (rushCookiePending && now - rushStartedMs >= RUSH_RESTORE_TIMEOUT_MS) {
            endRushCookie(client);
        }
        checkRushRestore(client, now);

        boolean afk = updateAfkState(client, now);
        if (state.cookieAfkFailsafeEnabled && afk) {
            if (!afkCookieDisabled) {
                afkCookieDisabled = true;
                if (disableActiveCookie(client)) {
                    client.player.displayClientMessage(
                            prefix().append(Component.literal("AFK for 3 min, disabled your active cookie.")),
                            false
                    );
                }
            }

            return;
        }

        boolean active = isCelestialBoostActive(client);

        if (active != lastObservedCelestialActive) {
            lastObservedCelestialActive = active;
            lastCelestialStateChangeMs = now;
            return;
        }
        if (now - lastCelestialStateChangeMs < CELESTIAL_DEBOUNCE_MS) {
            return;
        }

        if (active && !lastStableCelestialActive && state.cookieCelestialStartAlertEnabled) {
            showCelestialStartAlert(client);
        }

        if (!state.cookieAutoEnabled) {
            lastStableCelestialActive = active;
            if (!active) {
                hadCelestialSession = false;
            }
            return;
        }

        if (!canUseCookie(client, CookieMode.AUTO)) {
            lastStableCelestialActive = active;
            return;
        }

        if (active) {
            lastStableCelestialActive = true;
            if (!hadCelestialSession && now - lastAutoTriggerMs >= AUTO_TRIGGER_COOLDOWN_MS
                    && useCookie(client, CookieMode.SUSPICIOUS)) {
                lastAutoTriggerMs = now;
                hadCelestialSession = true;
            }
        } else {
            boolean hadSession = hadCelestialSession;
            lastStableCelestialActive = false;
            hadCelestialSession = false;
            if (hadSession && now - lastAutoTriggerMs >= AUTO_TRIGGER_COOLDOWN_MS) {
                lastAutoTriggerMs = now;
                if (state.cookieAutoDisableAfterCelestial) {
                    disableActiveCookie(client);
                } else {
                    CookieMode fallbackMode = parseCookieMode(getFallbackCookieType());
                    if (fallbackMode != null) {
                        useCookie(client, fallbackMode);
                    }
                }
            }
        }
    }

    private static boolean updateAfkState(Minecraft client, long now) {
        var player = client.player;
        boolean moved = player.getX() != lastActivityX
                || player.getY() != lastActivityY
                || player.getZ() != lastActivityZ
                || player.getYRot() != lastActivityYaw
                || player.getXRot() != lastActivityPitch;
        if (moved || lastActivityMs == 0L) {
            lastActivityX = player.getX();
            lastActivityY = player.getY();
            lastActivityZ = player.getZ();
            lastActivityYaw = player.getYRot();
            lastActivityPitch = player.getXRot();
            lastActivityMs = now;
            if (afkCookieDisabled) {
                afkCookieDisabled = false;
                lastStableCelestialActive = false;
                if (hadCelestialSession && isCelestialBoostActive(client)) {
                    hadCelestialSession = false;
                }
            }
            return false;
        }
        return now - lastActivityMs >= AFK_DISABLE_MS;
    }

    private static void showCelestialStartAlert(Minecraft client) {
        if (client == null || client.gui == null) {
            return;
        }
        MutableComponent title = Component.literal("CELESTIAL").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
        client.gui.setTimes(5, 60, 5);
        client.gui.setSubtitle(Component.empty());
        client.gui.setTitle(title);
    }

    private static boolean disableActiveCookie(Minecraft client) {
        if (client == null || client.player == null || client.player.connection == null || client.gui == null) {
            return false;
        }
        String activeCookie = detectActiveCookie(client);
        if (activeCookie == null || activeCookie.isBlank()) {
            return false;
        }
        sendServerCommand(client, "cookie " + activeCookie);
        lastUsedLabel = "disabled " + activeCookie;
        lastCookieCommand = null;
        return true;
    }

    // The cookie bossbar isn't always readable (e.g. during a celestial boost), so fall back to what chat last said.
    private static String detectActiveCookie(Minecraft client) {
        String fromBossbar = detectActiveCookieFromBossbar(client);
        return fromBossbar != null ? fromBossbar : chatActiveCookie;
    }

    private static String detectActiveCookieFromBossbar(Minecraft client) {
        try {
            BossHealthOverlay overlay = client.gui.getBossOverlay();
            if (overlay == null) {
                return null;
            }
            for (Field field : overlay.getClass().getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(overlay);
                if (!(value instanceof Map<?, ?> map)) {
                    continue;
                }
                for (Object event : map.values()) {
                    if (!(event instanceof BossEvent bossEvent)) {
                        continue;
                    }
                    Component name = bossEvent.getName();
                    if (name == null) {
                        continue;
                    }
                    String lower = SbText.normalizeText(name.getString());
                    if (lower.contains(COOKIE_SUSPICIOUS)) {
                        return COOKIE_SUSPICIOUS;
                    }
                    if (lower.contains(COOKIE_COBBLED)) {
                        return COOKIE_COBBLED;
                    }
                    if (lower.contains(COOKIE_COLOSSAL)) {
                        return COOKIE_COLOSSAL;
                    }
                    if (lower.contains(COOKIE_GOLDEN)) {
                        return COOKIE_GOLDEN;
                    }
                    if (lower.contains(COOKIE_GRANDMA)) {
                        return COOKIE_GRANDMA;
                    }
                    if (lower.contains(COOKIE_ABNORMAL)) {
                        return COOKIE_ABNORMAL;
                    }
                    if (lower.contains(COOKIE_NORMAL)) {
                        return COOKIE_NORMAL;
                    }
                    if (lower.contains(COOKIE_WEAK)) {
                        return COOKIE_WEAK;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static MutableComponent prefix() {
        return Component.literal("[Bazaar] ").withStyle(ChatFormatting.DARK_AQUA);
    }

    enum CookieMode {
        AUTO,
        SUSPICIOUS,
        COBBLED,
        COLOSSAL,
        GOLDEN,
        GRANDMA,
        ABNORMAL,
        NORMAL,
        WEAK
    }

    record CookieSnapshot(boolean celestialBoostActive) {
    }
}

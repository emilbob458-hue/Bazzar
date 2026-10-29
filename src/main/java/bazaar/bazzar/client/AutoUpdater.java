package bazaar.bazzar.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.context.CommandContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

final class AutoUpdater {
    private static final String MOD_ID = "bazzar";
    private static final String TARGET_KEY = "bazzar:minecraft";
    private static final String LEGACY_TARGET = "1.21.11";
    private static final String RELEASE_API = "https://api.github.com/repos/emilbob458-hue/Bazzar/releases/latest";
    private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("bazzar").resolve("update");
    private static final Path PENDING_JAR = DIR.resolve("Bazzar.jar");
    private static final Path PENDING_VERSION = DIR.resolve("version.txt");
    private static final Path PART_JAR = DIR.resolve("Bazzar.jar.part");
    private static final Path DISABLED = DIR.resolve("disabled");
    private static final Path HELPER_DIR = DIR.resolve("helper");
    private static final long RECHECK_MS = 30L * 60_000L;
    private static final long REMIND_MS = 5L * 60_000L;
    // 5 or more versions behind, or below a "min-version: 1.19" line in the release notes, updates right away
    private static final java.util.regex.Pattern MIN_VERSION_PATTERN =
            java.util.regex.Pattern.compile("(?im)^\\s*min-version:\\s*v?([0-9][0-9.]*)");
    private static final int FORCE_VERSIONS_BEHIND = 5;
    private static final long FORCE_CLOSE_DELAY_MS = 60_000L;
    private static final long FORCE_WARN_MS = 10_000L;
    private static volatile boolean forced;
    private static volatile long forceCloseAtMs;
    private static long nextForceWarnMs;
    private static final AtomicBoolean CHECKING = new AtomicBoolean();
    private static long nextCheckMs;
    private static volatile String outdatedVersion;
    private static volatile long nextRemindMs;
    private static final Queue<Component> MESSAGES = new ConcurrentLinkedQueue<>();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private AutoUpdater() {
    }

    static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                literal("bazzarupdate")
                        .requires(source -> !BazzarGuard.isBlocked())
                        .executes(AutoUpdater::runCheck)
                        .then(literal("on").executes(ctx -> runToggle(ctx, true)))
                        .then(literal("off").executes(ctx -> runToggle(ctx, false)))));
    }

    static void start() {
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> applyPending());
        nextCheckMs = System.currentTimeMillis() + RECHECK_MS;
        checkAsync(false);
    }

    static void tick(Minecraft client) {
        long now = System.currentTimeMillis();
        long closeAt = forceCloseAtMs;
        if (closeAt > 0L) {
            if (now >= closeAt) {
                forceCloseAtMs = 0L;
                client.stop();
                return;
            }
            if (client.player != null && now >= nextForceWarnMs) {
                nextForceWarnMs = now + FORCE_WARN_MS;
                long seconds = (closeAt - now + 999L) / 1000L;
                McCompat.showTitle(client, 0, 50, 10, Component.literal("Closing in " + seconds + "s to update Bazzar")
                        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            }
        }
        if (now >= nextCheckMs) {
            nextCheckMs = now + RECHECK_MS;
            checkAsync(false);
        }
        if (client.player == null) {
            return;
        }
        String outdated = outdatedVersion;
        if (outdated != null && now >= nextRemindMs) {
            nextRemindMs = now + REMIND_MS;
            remind(outdated);
        }
        Component message;
        while ((message = MESSAGES.poll()) != null) {
            McCompat.chat(client.player, message);
        }
    }

    private static void remind(String latest) {
        String current = currentVersionText();
        String how;
        if (Files.exists(DISABLED)) {
            how = "Use /bazzarupdate on to update.";
        } else if (Files.isRegularFile(PENDING_JAR)) {
            how = "Restart Minecraft to update.";
        } else {
            how = "Use /bazzarupdate to download it.";
        }
        post("You are on Bazzar " + current + ", " + latest + " is out. " + how, ChatFormatting.YELLOW);
    }

    private static void markOutdated(String latest, boolean announced) {
        if (!latest.equals(outdatedVersion)) {
            nextRemindMs = System.currentTimeMillis() + (announced ? REMIND_MS : 0L);
            outdatedVersion = latest;
        }
    }

    private static int runCheck(CommandContext<FabricClientCommandSource> context) {
        context.getSource().sendFeedback(prefix()
                .append(Component.literal("You are on Bazzar ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(currentVersionText()).withStyle(ChatFormatting.AQUA)));
        if (Files.exists(DISABLED)) {
            context.getSource().sendFeedback(prefix()
                    .append(Component.literal("Auto update is OFF. Checking anyway... (/bazzarupdate on to enable)").withStyle(ChatFormatting.GRAY)));
        } else {
            context.getSource().sendFeedback(prefix()
                    .append(Component.literal("Checking for updates...").withStyle(ChatFormatting.GRAY)));
        }
        if (!checkAsync(true)) {
            context.getSource().sendFeedback(prefix()
                    .append(Component.literal("A check is already running.").withStyle(ChatFormatting.YELLOW)));
        }
        return 1;
    }

    private static int runToggle(CommandContext<FabricClientCommandSource> context, boolean enable) {
        try {
            if (enable) {
                Files.deleteIfExists(DISABLED);
            } else {
                Files.createDirectories(DIR);
                Files.writeString(DISABLED, "");
                Files.deleteIfExists(PENDING_JAR);
                Files.deleteIfExists(PENDING_VERSION);
            }
        } catch (IOException e) {
            context.getSource().sendFeedback(prefix()
                    .append(Component.literal("Could not save setting: " + e.getMessage()).withStyle(ChatFormatting.RED)));
            return 0;
        }
        context.getSource().sendFeedback(prefix()
                .append(Component.literal("Auto update: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(enable ? "ON" : "OFF").withStyle(enable ? ChatFormatting.GREEN : ChatFormatting.RED)));
        if (enable) {
            checkAsync(false);
        }
        return 1;
    }

    private static boolean checkAsync(boolean manual) {
        if (!CHECKING.compareAndSet(false, true)) {
            return false;
        }
        Thread thread = new Thread(() -> {
            try {
                check(manual);
            } catch (Exception e) {
                if (manual) {
                    post("Update check failed: " + e.getMessage(), ChatFormatting.RED);
                }
            } finally {
                CHECKING.set(false);
            }
        }, "Bazzar-Updater");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    private static void check(boolean manual) throws Exception {
        Path jar = ownJar();
        if (jar == null) {
            if (manual) {
                post("Not running from a jar, auto update is skipped.", ChatFormatting.YELLOW);
            }
            return;
        }
        Version current = currentVersion();

        HttpRequest request = HttpRequest.newBuilder(URI.create(RELEASE_API))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Bazzar-Updater")
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("GitHub returned " + response.statusCode());
        }
        JsonObject release = JsonParser.parseString(response.body()).getAsJsonObject();
        String tag = release.get("tag_name").getAsString();
        Version latest = parseVersion(tag);
        forced = belowMinimum(release, current) || versionsBehind(current, latest) >= FORCE_VERSIONS_BEHIND;

        if (latest.compareTo(current) <= 0) {
            outdatedVersion = null;
            clearPending();
            if (manual) {
                post("You are on the newest version (" + current.getFriendlyString() + ").", ChatFormatting.GREEN);
            }
            return;
        }

        String target = target();
        String wanted = ("_" + target + ".jar").toLowerCase(Locale.ROOT);
        String wantedOld = ("Bazzar-" + target + ".jar").toLowerCase(Locale.ROOT);
        JsonObject asset = null;
        JsonObject legacy = null;
        for (JsonElement element : release.getAsJsonArray("assets")) {
            JsonObject candidate = element.getAsJsonObject();
            String name = candidate.get("name").getAsString().toLowerCase(Locale.ROOT);
            if ((name.startsWith("bazzar_v") && name.endsWith(wanted)) || name.equals(wantedOld)) {
                asset = candidate;
                break;
            }
            if (legacy == null && name.endsWith(".jar") && !name.startsWith("bazzar-") && !name.startsWith("bazzar_v")) {
                legacy = candidate;
            }
        }
        if (asset == null && LEGACY_TARGET.equals(target)) {
            asset = legacy;
        }
        if (asset == null) {
            throw new IOException("release " + tag + " has no jar for Minecraft " + target);
        }
        String digest = asset.has("digest") && !asset.get("digest").isJsonNull() ? asset.get("digest").getAsString() : "";
        if (!digest.startsWith("sha256:")) {
            throw new IOException("release " + tag + " has no sha256 digest");
        }
        String expectedHash = digest.substring("sha256:".length()).toLowerCase(Locale.ROOT);

        if (Files.isRegularFile(PENDING_JAR) && expectedHash.equals(sha256(PENDING_JAR))) {
            if (manual) {
                post("Bazzar " + latest.getFriendlyString() + " is downloaded. Restart Minecraft to finish updating.", ChatFormatting.AQUA);
            }
            markOutdated(latest.getFriendlyString(), manual);
            if (forced) {
                scheduleForceClose(latest.getFriendlyString());
            }
            return;
        }
        if (!manual && !forced && Files.exists(DISABLED)) {
            markOutdated(latest.getFriendlyString(), false);
            return;
        }

        try {
            download(asset, expectedHash, latest);
        } catch (Exception e) {
            markOutdated(latest.getFriendlyString(), false);
            throw e;
        }
    }

    private static void download(JsonObject asset, String expectedHash, Version latest) throws Exception {
        Files.createDirectories(DIR);
        HttpRequest download = HttpRequest.newBuilder(URI.create(asset.get("browser_download_url").getAsString()))
                .timeout(Duration.ofMinutes(2))
                .header("User-Agent", "Bazzar-Updater")
                .build();
        HttpResponse<Path> downloaded = HTTP.send(download, HttpResponse.BodyHandlers.ofFile(PART_JAR));
        if (downloaded.statusCode() != 200) {
            Files.deleteIfExists(PART_JAR);
            throw new IOException("download returned " + downloaded.statusCode());
        }
        if (!expectedHash.equals(sha256(PART_JAR))) {
            Files.deleteIfExists(PART_JAR);
            throw new IOException("downloaded jar failed the sha256 check");
        }
        if (!isBazzarJar(PART_JAR)) {
            Files.deleteIfExists(PART_JAR);
            throw new IOException("downloaded jar is not a Bazzar mod");
        }
        Files.move(PART_JAR, PENDING_JAR, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(PENDING_VERSION, latest.getFriendlyString());
        post("Bazzar " + latest.getFriendlyString() + " downloaded. Restart Minecraft to use it.", ChatFormatting.AQUA);
        markOutdated(latest.getFriendlyString(), true);
        if (forced) {
            scheduleForceClose(latest.getFriendlyString());
        }
    }

    // 1.20 -> 1.25 is 5 versions behind; a major version change always counts as too far behind
    private static int versionsBehind(Version current, Version latest) {
        int[] now = versionParts(current);
        int[] newest = versionParts(latest);
        if (now == null || newest == null) {
            return 0;
        }
        if (newest[0] != now[0]) {
            return newest[0] > now[0] ? Integer.MAX_VALUE : 0;
        }
        return newest[1] - now[1];
    }

    private static int[] versionParts(Version version) {
        String[] parts = version.getFriendlyString().split("[.+-]");
        try {
            return new int[]{Integer.parseInt(parts[0]), parts.length > 1 ? Integer.parseInt(parts[1]) : 0};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean belowMinimum(JsonObject release, Version current) {
        if (!release.has("body") || release.get("body").isJsonNull()) {
            return false;
        }
        java.util.regex.Matcher matcher = MIN_VERSION_PATTERN.matcher(release.get("body").getAsString());
        if (!matcher.find()) {
            return false;
        }
        try {
            return current.compareTo(parseVersion(matcher.group(1))) < 0;
        } catch (VersionParsingException e) {
            return false;
        }
    }

    // the jar can only be swapped while Minecraft shuts down, so close it after a warning
    private static void scheduleForceClose(String latest) {
        if (forceCloseAtMs > 0L) {
            return;
        }
        forceCloseAtMs = System.currentTimeMillis() + FORCE_CLOSE_DELAY_MS;
        post("This Bazzar version is no longer supported. Minecraft will close in 60 seconds to install "
                + latest + ", just open it again after.", ChatFormatting.RED);
    }

    private static void applyPending() {
        try {
            if (!Files.isRegularFile(PENDING_JAR) || !Files.isRegularFile(PENDING_VERSION) || (Files.exists(DISABLED) && !forced)) {
                return;
            }
            Version pending = parseVersion(Files.readString(PENDING_VERSION).trim());
            if (pending.compareTo(currentVersion()) <= 0) {
                clearPending();
                return;
            }
            Path jar = ownJar();
            if (jar == null || !isBazzarJar(PENDING_JAR)) {
                return;
            }
            Path helperClass = HELPER_DIR.resolve(UpdateApplier.class.getName().replace('.', '/') + ".class");
            Files.createDirectories(helperClass.getParent());
            try (InputStream in = AutoUpdater.class.getResourceAsStream("/" + UpdateApplier.class.getName().replace('.', '/') + ".class")) {
                if (in == null) {
                    return;
                }
                Files.copy(in, helperClass, StandardCopyOption.REPLACE_EXISTING);
            }
            new ProcessBuilder(List.of(
                    javaExecutable().toString(),
                    "-cp", HELPER_DIR.toString(),
                    UpdateApplier.class.getName(),
                    Long.toString(ProcessHandle.current().pid()),
                    PENDING_JAR.toString(),
                    jar.toString(),
                    PENDING_VERSION.toString()))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (Exception ignored) {
        }
    }

    private static Path javaExecutable() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path javaw = bin.resolve("javaw.exe");
        if (Files.isRegularFile(javaw)) {
            return javaw;
        }
        Path javaExe = bin.resolve("java.exe");
        return Files.isRegularFile(javaExe) ? javaExe : bin.resolve("java");
    }

    private static Path ownJar() {
        try {
            Optional<ModContainer> container = FabricLoader.getInstance().getModContainer(MOD_ID);
            if (container.isEmpty() || container.get().getOrigin().getKind() != ModOrigin.Kind.PATH) {
                return null;
            }
            for (Path path : container.get().getOrigin().getPaths()) {
                if (Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    return path;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Version currentVersion() throws VersionParsingException {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion())
                .orElse(Version.parse("0"));
    }

    private static String currentVersionText() {
        try {
            return currentVersion().getFriendlyString();
        } catch (VersionParsingException e) {
            return "?";
        }
    }

    private static Version parseVersion(String raw) throws VersionParsingException {
        String cleaned = raw.trim();
        if (cleaned.startsWith("v") || cleaned.startsWith("V")) {
            cleaned = cleaned.substring(1);
        }
        return Version.parse(cleaned);
    }

    private static String target() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getCustomValue(TARGET_KEY))
                .map(value -> value.getAsString())
                .orElse(LEGACY_TARGET);
    }

    private static boolean isBazzarJar(Path path) {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry == null) {
                return false;
            }
            try (InputStreamReader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                JsonObject meta = JsonParser.parseReader(reader).getAsJsonObject();
                if (!meta.has("id") || !MOD_ID.equals(meta.get("id").getAsString())) {
                    return false;
                }
                JsonObject custom = meta.has("custom") ? meta.getAsJsonObject("custom") : null;
                String jarTarget = custom != null && custom.has(TARGET_KEY) ? custom.get(TARGET_KEY).getAsString() : LEGACY_TARGET;
                return jarTarget.equals(target());
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static String sha256(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void clearPending() throws IOException {
        Files.deleteIfExists(PENDING_JAR);
        Files.deleteIfExists(PENDING_VERSION);
        Files.deleteIfExists(PART_JAR);
    }

    private static void post(String text, ChatFormatting color) {
        MESSAGES.add(prefix().append(Component.literal(text).withStyle(color)));
    }

    private static MutableComponent prefix() {
        return BazaarChat.prefix();
    }
}

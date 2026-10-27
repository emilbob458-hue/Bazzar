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
    // Releases before the 26.2 build had one jar, made for this version.
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
        String current;
        try {
            current = currentVersion().getFriendlyString();
        } catch (VersionParsingException e) {
            current = "?";
        }
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

        if (latest.compareTo(current) <= 0) {
            outdatedVersion = null;
            clearPending();
            if (manual) {
                post("You are on the newest version (" + current.getFriendlyString() + ").", ChatFormatting.GREEN);
            }
            return;
        }

        String target = target();
        // Bazzar_v1.12_1.21.11.jar, or Bazzar-1.21.11.jar in v1.11.
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
            return;
        }
        if (!manual && Files.exists(DISABLED)) {
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
    }

    private static void applyPending() {
        try {
            if (!Files.isRegularFile(PENDING_JAR) || !Files.isRegularFile(PENDING_VERSION) || Files.exists(DISABLED)) {
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
        return Component.literal("[Bazaar] ").withStyle(ChatFormatting.DARK_AQUA);
    }
}

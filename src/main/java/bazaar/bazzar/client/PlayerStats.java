package bazaar.bazzar.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.google.gson.JsonObject;
import com.mojang.brigadier.context.CommandContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

final class PlayerStats {
    static final String SITE = "https://bazzar-api.duckdns.org";
    private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("bazzar");
    private static final Path DISABLED = DIR.resolve("player-stats-off");
    private static final Path NOTICE_SHOWN = DIR.resolve("player-stats-notice");
    private static final long REPORT_INTERVAL_SECONDS = 5L * 60L;
    private static final long AFK_MS = 3L * 60_000L;
    private static final long SHARD_LINE_FRESH_MS = 60_000L;
    private static final long KILL_WINDOW_MS = 15_000L;
    private static final List<String> SERVER_HOSTS = List.of("cobblestone.gg", "minehut.gg", "minehut.com");
    private static final Queue<Component> MESSAGES = new ConcurrentLinkedQueue<>();
    private static final Map<Integer, Long> RECENT_HITS = new HashMap<>();
    private static final Object LOCK = new Object();
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Bazzar-PlayerStats");
        thread.setDaemon(true);
        return thread;
    });

    private static long blocks = 0L;
    private static long kills = 0L;
    private static double shards = 0d;
    private static volatile long lastShardLineMs = 0L;
    private static volatile String playerName = null;
    private static volatile boolean onServer = false;
    private static volatile boolean afk = false;
    private static volatile long lastActivityMs = 0L;
    private static double lastX;
    private static double lastY;
    private static double lastZ;
    private static float lastYaw;
    private static float lastPitch;

    private PlayerStats() {
    }

    static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                literal("bazzarstats")
                        .requires(source -> !BazzarGuard.isBlocked())
                        .executes(PlayerStats::runStatus)
                        .then(literal("on").executes(ctx -> runToggle(ctx, true)))
                        .then(literal("off").executes(ctx -> runToggle(ctx, false)))));
    }

    static void start() {
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (entity instanceof LivingEntity && !(entity instanceof Player)) {
                RECENT_HITS.put(entity.getId(), System.currentTimeMillis());
            }
            return InteractionResult.PASS;
        });
        EXECUTOR.scheduleAtFixedRate(PlayerStats::reportQuietly, 60L, REPORT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    static boolean isAfk() {
        return afk;
    }

    static void tick(Minecraft client) {
        if (client.player == null || client.level == null) {
            onServer = false;
            RECENT_HITS.clear();
            return;
        }
        long now = System.currentTimeMillis();
        playerName = client.getUser().getName();
        onServer = isTrackedServer(client.getCurrentServer()) && now - lastShardLineMs < SHARD_LINE_FRESH_MS;
        updateAfk(client, now);
        countKills(client, now);
        if (onServer && !Files.exists(NOTICE_SHOWN) && !Files.exists(DISABLED)) {
            MESSAGES.add(prefix()
                    .append(Component.literal("See the leaderboard and what's new in Bazzar: ").withStyle(ChatFormatting.GRAY))
                    .append(siteLink()));
            try {
                Files.createDirectories(DIR);
                Files.writeString(NOTICE_SHOWN, "");
            } catch (IOException ignored) {
            }
        }
        Component message;
        while ((message = MESSAGES.poll()) != null) {
            McCompat.chat(client.player, message);
        }
    }

    static void onBlockBroken() {
        if (!onServer) {
            return;
        }
        synchronized (LOCK) {
            blocks++;
        }
    }

    static void onShardsSeen(long now) {
        lastShardLineMs = now;
    }

    static void addShards(double amount) {
        if (!onServer || amount <= 0d) {
            return;
        }
        synchronized (LOCK) {
            shards += amount;
        }
    }

    private static boolean isTrackedServer(ServerData server) {
        if (server == null || server.ip == null) {
            return false;
        }
        String host = server.ip.trim().toLowerCase(Locale.ROOT);
        int colon = host.lastIndexOf(':');
        if (colon >= 0 && host.indexOf(':') == colon) {
            host = host.substring(0, colon);
        }
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        for (String allowed : SERVER_HOSTS) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    private static void updateAfk(Minecraft client, long now) {
        Player player = client.player;
        boolean moved = player.getX() != lastX
                || player.getY() != lastY
                || player.getZ() != lastZ
                || player.getYRot() != lastYaw
                || player.getXRot() != lastPitch;
        if (moved || lastActivityMs == 0L) {
            lastX = player.getX();
            lastY = player.getY();
            lastZ = player.getZ();
            lastYaw = player.getYRot();
            lastPitch = player.getXRot();
            lastActivityMs = now;
        }
        afk = now - lastActivityMs >= AFK_MS;
    }

    private static void countKills(Minecraft client, long now) {
        if (RECENT_HITS.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<Integer, Long>> it = RECENT_HITS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Long> hit = it.next();
            Entity entity = client.level.getEntity(hit.getKey());
            if (entity instanceof LivingEntity living && living.isDeadOrDying()) {
                if (onServer) {
                    synchronized (LOCK) {
                        kills++;
                    }
                }
                it.remove();
            } else if (entity == null || now - hit.getValue() > KILL_WINDOW_MS) {
                it.remove();
            }
        }
    }

    private static void reportQuietly() {
        String name = playerName;
        if (!onServer || name == null || Files.exists(DISABLED)) {
            return;
        }
        long sentBlocks;
        long sentKills;
        double sentShards;
        synchronized (LOCK) {
            sentBlocks = blocks;
            sentKills = kills;
            sentShards = shards;
        }
        try {
            JsonObject body = new JsonObject();
            body.addProperty("id", UsageStats.installId());
            body.addProperty("name", name);
            body.addProperty("version", FabricLoader.getInstance().getModContainer("bazzar")
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse(""));
            body.addProperty("blocks", sentBlocks);
            body.addProperty("shards", sentShards);
            body.addProperty("kills", sentKills);
            body.addProperty("afk", afk);
            body.addProperty("afkMs", afk ? System.currentTimeMillis() - lastActivityMs : 0L);
            UsageStats.send("POST", "/report", body.toString());
            synchronized (LOCK) {
                blocks -= sentBlocks;
                kills -= sentKills;
                shards -= sentShards;
            }
        } catch (Exception ignored) {
        }
    }

    private static void forgetQuietly(String name) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("id", UsageStats.installId());
            body.addProperty("name", name);
            UsageStats.send("POST", "/report/forget", body.toString());
            MESSAGES.add(prefix().append(Component.literal("Your stats were removed from the site.").withStyle(ChatFormatting.GRAY)));
        } catch (Exception e) {
            MESSAGES.add(prefix().append(Component.literal("Could not remove your stats: " + e.getMessage()).withStyle(ChatFormatting.RED)));
        }
    }

    private static int runStatus(CommandContext<FabricClientCommandSource> context) {
        boolean enabled = !Files.exists(DISABLED);
        context.getSource().sendFeedback(prefix()
                .append(Component.literal("Leaderboard stats: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(enabled ? "ON" : "OFF").withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED))
                .append(Component.literal("  ").withStyle(ChatFormatting.GRAY))
                .append(siteLink())
                .append(Component.literal("  (/bazzarstats on|off)").withStyle(ChatFormatting.DARK_GRAY)));
        return 1;
    }

    private static int runToggle(CommandContext<FabricClientCommandSource> context, boolean enable) {
        try {
            Files.createDirectories(DIR);
            if (enable) {
                Files.deleteIfExists(DISABLED);
            } else {
                Files.writeString(DISABLED, "");
            }
        } catch (IOException e) {
            context.getSource().sendFeedback(prefix()
                    .append(Component.literal("Could not save setting: " + e.getMessage()).withStyle(ChatFormatting.RED)));
            return 0;
        }
        if (!enable) {
            synchronized (LOCK) {
                blocks = 0L;
                kills = 0L;
                shards = 0d;
            }
            String name = context.getSource().getClient().getUser().getName();
            EXECUTOR.execute(() -> forgetQuietly(name));
        }
        context.getSource().sendFeedback(prefix()
                .append(Component.literal("Leaderboard stats: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(enable ? "ON" : "OFF").withStyle(enable ? ChatFormatting.GREEN : ChatFormatting.RED)));
        return 1;
    }

    private static MutableComponent siteLink() {
        return Component.literal(SITE.replace("https://", ""))
                .withStyle(style -> style.withColor(ChatFormatting.AQUA).withUnderlined(true)
                        .withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create(SITE))));
    }

    private static MutableComponent prefix() {
        return BazaarChat.prefix();
    }
}

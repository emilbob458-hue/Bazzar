package bazaar.bazzar.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

// mod-wide announcements from Runterzz and pokemada: a title in the middle of the screen plus a chat line.
// Only those two accounts can send; the server has Mojang confirm who is sending, like a server join.
final class Announcements {
    private static final Set<UUID> ANNOUNCERS = Set.of(
            UUID.fromString("2b264ea3-b45d-477f-9214-a527ececba0d"),
            UUID.fromString("7732f46f-329a-4808-94fd-7fabe20bfb90"));
    // checked when you join and every couple of minutes after, so an announcement lands while it still matters
    private static final long POLL_MS = 2L * 60_000L;
    private static final long JOIN_DELAY_MS = 5_000L;
    // a fresh install only shows announcements made in the last few minutes, not yesterday's
    private static final long FIRST_SHOW_MS = 10L * 60_000L;

    private static long nextPollMs = 0L;
    private static boolean inGame = false;

    private Announcements() {
    }

    static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                literal("bazzarannounce")
                        .requires(source -> !BazzarGuard.isBlocked() && isAnnouncer(Minecraft.getInstance()))
                        .then(argument("message", StringArgumentType.greedyString()).executes(ctx -> {
                            send(ctx.getSource().getClient(), StringArgumentType.getString(ctx, "message"));
                            return 1;
                        }))));
    }

    private static boolean isAnnouncer(Minecraft client) {
        return client.getUser() != null && ANNOUNCERS.contains(client.getUser().getProfileId());
    }

    private static void send(Minecraft client, String text) {
        String name = client.getUser().getName();
        UUID profileId = client.getUser().getProfileId();
        String accessToken = client.getUser().getAccessToken();
        String serverId = UUID.randomUUID().toString().replace("-", "");
        say(client, "Sending announcement...", ChatFormatting.GRAY);
        // tell Mojang we're joining a made-up server id; the Bazzar server then asks Mojang to confirm it was us
        CompletableFuture.runAsync(() -> {
            try {
                client.services().sessionService().joinServer(profileId, accessToken, serverId);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                client.execute(() -> say(client, "Couldn't verify your account with Mojang, try again.", ChatFormatting.RED));
                return;
            }
            JsonObject body = CelestialMarket.baseBody(name);
            body.addProperty("serverId", serverId);
            body.addProperty("text", text);
            post(client, body);
        });
    }

    private static void post(Minecraft client, JsonObject body) {
        CelestialMarket.post("/announce", body, (code, json) -> {
            if (code == 200) {
                say(client, "Announcement sent. Players see it when they join, or within 2 minutes if they're online.",
                        ChatFormatting.GREEN);
                nextPollMs = 0L;
            } else if (code == 403) {
                say(client, "The server couldn't confirm this is an announcer account.", ChatFormatting.RED);
            } else if (code == 429) {
                say(client, "Wait 30 seconds between announcements.", ChatFormatting.RED);
            } else {
                say(client, "Couldn't send the announcement, try again in a bit.", ChatFormatting.RED);
            }
        });
    }

    static void tick(Minecraft client) {
        long now = System.currentTimeMillis();
        if (client.player == null) {
            inGame = false;
            return;
        }
        if (!inGame) {
            inGame = true;
            nextPollMs = now + JOIN_DELAY_MS;
        }
        if (now < nextPollMs) {
            return;
        }
        nextPollMs = now + POLL_MS;
        CelestialMarket.get("/announce/latest", (code, json) -> {
            if (code != 200 || json == null || !json.has("id") || client.player == null) {
                return;
            }
            long id = json.get("id").getAsLong();
            BazzarConfig.State state = BazzarConfig.load();
            if (id <= 0L || id <= state.lastAnnouncementId) {
                return;
            }
            boolean firstCheck = state.lastAnnouncementId == 0L;
            state.lastAnnouncementId = id;
            BazzarConfig.save(state);
            long age = json.get("now").getAsLong() - json.get("created").getAsLong();
            if (firstCheck && age > FIRST_SHOW_MS) {
                return;
            }
            String author = json.get("author").getAsString();
            // whoever sent it already knows what it says, so it isn't shown back to them
            if (client.getUser() != null && author.equalsIgnoreCase(client.getUser().getName())) {
                return;
            }
            show(client, author, json.get("text").getAsString());
        });
    }

    private static void show(Minecraft client, String author, String text) {
        McCompat.showTitle(client, 10, 100, 20,
                Component.literal("Bazzar Announcement").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                Component.literal(text).withStyle(ChatFormatting.YELLOW));
        McCompat.chat(client.player, BazaarChat.prefix()
                .append(Component.literal("Announcement from " + author + ": ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(Component.literal(text).withStyle(ChatFormatting.YELLOW)));
        client.player.playSound(SoundEvents.PLAYER_LEVELUP, 0.8f, 1.0f);
    }

    private static void say(Minecraft client, String text, ChatFormatting color) {
        if (client.player != null) {
            McCompat.chat(client.player, BazaarChat.prefix().append(Component.literal(text).withStyle(color)));
        }
    }
}

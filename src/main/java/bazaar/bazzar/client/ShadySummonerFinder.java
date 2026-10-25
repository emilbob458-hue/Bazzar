package bazaar.bazzar.client;

import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

final class ShadySummonerFinder {
    private static final String APPEARED_TRIGGER = "shady summoner has appeared";
    private static final String DISAPPEARED_TRIGGER = "shady summoner has disappeared";
    private static final String TARGET_NAME = "shady summoner";
    private static final long HUNT_TIMEOUT_MS = 20L * 60_000L;
    private static final int SCAN_INTERVAL_TICKS = 5;
    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
    private static final String[] COMPASS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    private static boolean enabled = true;
    private static boolean huntActive = false;
    private static long huntStartedMs = 0L;
    private static int scanCooldown = 0;
    private static int targetEntityId = -1;
    private static Vec3 lastKnownPos = null;
    private static boolean sightingAnnounced = false;
    private static ClientLevel trackedLevel = null;

    private static String pendingFocusTitle = null;

    private ShadySummonerFinder() {
    }

    static void applyConfig(BazzarConfig.State state) {
        enabled = state == null || state.shadyFinderEnabled;
    }

    static boolean isEnabled() {
        return enabled;
    }

    static void setEnabled(boolean value) {
        enabled = value;
        if (!enabled) {
            clearTarget();
            huntActive = false;
        }
    }

    static void onChatLine(String rawLine) {
        if (!enabled || rawLine == null || rawLine.isBlank()) {
            return;
        }
        String lower = rawLine.toLowerCase(Locale.ROOT);
        if (lower.contains(APPEARED_TRIGGER)) {
            huntActive = true;
            huntStartedMs = System.currentTimeMillis();
            sightingAnnounced = false;
            Minecraft client = Minecraft.getInstance();
            showTitle(client, "SHADY SUMMONER");
            playPing(client);
            sendChat(client, "Shady Summoner appeared! Hop worlds (F), you'll get a ping when he's in range.");
            if (!client.isWindowActive()) {
                pendingFocusTitle = "SHADY SUMMONER";
            }
            return;
        }
        if (lower.contains(DISAPPEARED_TRIGGER)) {
            huntActive = false;
            clearTarget();
        }
    }

    static void tick(Minecraft client) {
        if (!enabled || client == null || client.level == null || client.player == null) {
            return;
        }

        if (pendingFocusTitle != null && client.isWindowActive()) {
            showTitle(client, pendingFocusTitle);
            playPing(client);
            pendingFocusTitle = null;
        }

        if (client.level != trackedLevel) {
            trackedLevel = client.level;
            clearTarget();
        }
        long now = System.currentTimeMillis();
        if (huntActive && now - huntStartedMs > HUNT_TIMEOUT_MS) {
            huntActive = false;
        }

        if (targetEntityId != -1) {
            Entity live = client.level.getEntity(targetEntityId);
            if (live != null && live.isAlive()) {
                lastKnownPos = live.position();
            } else {

                targetEntityId = -1;
            }
        }

        if (--scanCooldown > 0) {
            return;
        }
        scanCooldown = SCAN_INTERVAL_TICKS;
        if (targetEntityId != -1) {
            return;
        }
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == client.player || !matchesTarget(entity)) {
                continue;
            }
            targetEntityId = entity.getId();
            lastKnownPos = entity.position();
            huntActive = true;
            huntStartedMs = now;
            if (!sightingAnnounced) {
                sightingAnnounced = true;
                announceFound(client);
            }
            break;
        }
    }

    private static boolean matchesTarget(Entity entity) {
        Component custom = entity.getCustomName();
        if (custom != null && custom.getString().toLowerCase(Locale.ROOT).contains(TARGET_NAME)) {
            return true;
        }
        return entity.getName().getString().toLowerCase(Locale.ROOT).contains(TARGET_NAME);
    }

    private static void announceFound(Minecraft client) {
        if (client.player == null || lastKnownPos == null) {
            return;
        }
        int dist = (int) client.player.position().distanceTo(lastKnownPos);
        sendChat(client, String.format(
                Locale.ROOT,
                "Shady Summoner located at %d, %d, %d, %dm %s!",
                (int) lastKnownPos.x,
                (int) lastKnownPos.y,
                (int) lastKnownPos.z,
                dist,
                compassDirection(client, lastKnownPos)
        ));
        showTitle(client, "FOUND!");
        playPing(client);
        if (!client.isWindowActive()) {
            pendingFocusTitle = "FOUND!";
        }
    }

    static boolean hasTarget() {
        return enabled && lastKnownPos != null;
    }

    static boolean isTargetLive() {
        return targetEntityId != -1;
    }

    static String trackerLine(Minecraft client) {
        if (!hasTarget() || client == null || client.player == null) {
            return null;
        }
        int dist = (int) client.player.position().distanceTo(lastKnownPos);
        String line = "Target: " + dist + "m " + relativeArrow(client, lastKnownPos) + " " + compassDirection(client, lastKnownPos);
        return isTargetLive() ? line : line + " (last seen)";
    }

    private static void clearTarget() {
        targetEntityId = -1;
        lastKnownPos = null;
        sightingAnnounced = false;
    }

    private static String compassDirection(Minecraft client, Vec3 pos) {
        double bearing = bearingTo(client, pos);
        return COMPASS[(int) Math.round(bearing / 45.0) % 8];
    }

    private static String relativeArrow(Minecraft client, Vec3 pos) {
        double bearing = bearingTo(client, pos);
        double playerBearing = ((client.player.getYRot() + 180.0) % 360.0 + 360.0) % 360.0;
        double relative = ((bearing - playerBearing) % 360.0 + 540.0) % 360.0 - 180.0;
        return ARROWS[((int) Math.round(relative / 45.0) + 8) % 8];
    }

    private static double bearingTo(Minecraft client, Vec3 pos) {
        double dx = pos.x - client.player.getX();
        double dz = pos.z - client.player.getZ();
        return (Math.toDegrees(Math.atan2(dx, -dz)) % 360.0 + 360.0) % 360.0;
    }

    private static void showTitle(Minecraft client, String text) {
        if (client == null || client.gui == null) {
            return;
        }
        Component title = Component.literal(text)
                .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(BazzarTheme.current().rgb)).withBold(true));
        client.gui.setTimes(5, 45, 10);
        client.gui.setSubtitle(Component.empty());
        client.gui.setTitle(title);
    }

    private static void sendChat(Minecraft client, String message) {
        if (client == null || client.player == null) {
            return;
        }
        client.player.displayClientMessage(
                Component.literal("[Bazaar] ").withStyle(ChatFormatting.DARK_AQUA)
                        .append(Component.literal(message).withStyle(ChatFormatting.AQUA)),
                false
        );
    }

    private static void playPing(Minecraft client) {
        if (client == null || client.player == null) {
            return;
        }
        client.player.playSound(SoundEvents.PLAYER_LEVELUP, 0.8f, 1.5f);
    }
}

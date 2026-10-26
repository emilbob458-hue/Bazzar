package bazaar.bazzar.client;

import java.util.HashMap;
import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;

final class CelestialBoosterReminder {
    private static final long DEFAULT_COOLDOWN_MS = 4L * 60L * 60L * 1000L;
    private static final long COOLDOWN_MSG_WINDOW_MS = 5_000L;
    private static final int CHECK_INTERVAL_TICKS = 20;

    private static long lastBoosterCommandMs = 0L;
    private static int checkCooldown = 0;

    private CelestialBoosterReminder() {
    }

    static void onCommandSent(String command) {
        if (command == null) {
            return;
        }
        String trimmed = command.trim().toLowerCase(Locale.ROOT);
        if (!trimmed.startsWith("celestialbooster")) {
            return;
        }
        lastBoosterCommandMs = System.currentTimeMillis();
        setReminder(DEFAULT_COOLDOWN_MS);
    }

    static void onChatLine(Minecraft client, String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (lastBoosterCommandMs <= 0L || now - lastBoosterCommandMs > COOLDOWN_MSG_WINDOW_MS) {
            return;
        }
        String normalized = SbText.normalizeText(line);
        if (!normalized.contains("use this again in")) {
            return;
        }
        long remaining = SbText.parseDurationMs(normalized);
        if (remaining > 0L) {
            setReminder(remaining);
        }
    }

    static void tick(Minecraft client) {
        if (--checkCooldown > 0) {
            return;
        }
        checkCooldown = CHECK_INTERVAL_TICKS;
        if (client == null || client.player == null || client.level == null) {
            return;
        }
        String key = ShardPayController.normalizeName(client.player.getName().getString());
        if (key.isEmpty()) {
            return;
        }
        BazzarConfig.State state = BazzarConfig.load();
        if (state == null || state.celestialBoosterReadyAt == null) {
            return;
        }
        Long readyAt = state.celestialBoosterReadyAt.get(key);
        if (readyAt == null || readyAt <= 0L || System.currentTimeMillis() < readyAt) {
            return;
        }
        state.celestialBoosterReadyAt.remove(key);
        BazzarConfig.save(state);
        MutableComponent title = Component.literal("BOOSTER READY")
                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
        if (client.gui != null) {
            McCompat.showTitle(client, 5, 60, 10, title);
        }
        client.player.playSound(SoundEvents.PLAYER_LEVELUP, 0.8f, 1.2f);
        McCompat.chat(client.player, Component.literal("[Bazaar] /celestialbooster is off cooldown, use it now!")
                .withStyle(ChatFormatting.AQUA));
    }

    private static void setReminder(long remainingMs) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) {
            return;
        }
        String key = ShardPayController.normalizeName(client.player.getName().getString());
        if (key.isEmpty()) {
            return;
        }
        BazzarConfig.State state = BazzarConfig.load();
        if (state == null) {
            return;
        }
        if (state.celestialBoosterReadyAt == null) {
            state.celestialBoosterReadyAt = new HashMap<>();
        }
        state.celestialBoosterReadyAt.put(key, System.currentTimeMillis() + remainingMs);
        BazzarConfig.save(state);
    }
}

package bazaar.bazzar.client;

import java.util.Locale;

import net.minecraft.client.Minecraft;

final class AutoEggController {
    private static final String EGG_MESSAGE = "egg";
    private static final long COOLDOWN_MS = 5000L;
    private static final long GUILD_FIX_WINDOW_MS = 3000L;
    private static final long GUILD_FIX_SERIES_MS = 15000L;
    private static final int GUILD_FIX_MAX_ATTEMPTS = 2;
    private static final String GUILD_TOGGLE_COMMAND = "gc";

    private static long lastEggSent = 0L;
    private static long guildFixSeriesStart = 0L;
    private static int guildFixAttempts = 0;

    private AutoEggController() {
    }

    static boolean isEnabled() {
        BazzarConfig.State state = BazzarConfig.load();
        return state != null && state.autoEggEnabled;
    }

    static void setEnabled(boolean value) {
        BazzarConfig.State state = BazzarConfig.load();
        if (state == null) {
            return;
        }
        state.autoEggEnabled = value;
        BazzarConfig.save(state);
    }

    static void onChatLine(Minecraft client, String rawLine) {
        if (rawLine == null || rawLine.isBlank() || !isEnabled()) {
            return;
        }
        String line = rawLine.trim();

        if (line.startsWith("[Bazaar]")) {
            return;
        }

        String stripped = stripFormattingCodes(line);
        if (handleGuildLine(client, stripped)) {
            return;
        }
        if (looksLikePlayerChat(stripped)) {
            return;
        }
        String lower = stripped.toLowerCase(Locale.ROOT);

        boolean isPurchase = lower.contains("from the store")
                || ((lower.contains("purchased") || lower.contains("bought")) && lower.contains("store"));

        if (!isPurchase) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastEggSent < COOLDOWN_MS) {
            return;
        }
        sendEgg(client, now);
    }

    private static void sendEgg(Minecraft client, long now) {
        if (client == null || client.player == null || client.player.connection == null) {
            return;
        }
        client.player.connection.sendChat(EGG_MESSAGE);
        lastEggSent = now;
    }

    private static boolean handleGuildLine(Minecraft client, String stripped) {
        if (!stripped.startsWith("GUILD")) {
            return false;
        }
        if (client == null || client.player == null || client.player.connection == null) {
            return true;
        }
        int arrow = stripped.indexOf('»');
        if (arrow < 0) {
            return true;
        }
        String message = stripped.substring(arrow + 1).trim();
        if (!message.equalsIgnoreCase(EGG_MESSAGE)) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (lastEggSent == 0L || now - lastEggSent > GUILD_FIX_WINDOW_MS) {
            return true;
        }
        if (now - guildFixSeriesStart > GUILD_FIX_SERIES_MS) {
            guildFixSeriesStart = now;
            guildFixAttempts = 0;
        }
        if (guildFixAttempts >= GUILD_FIX_MAX_ATTEMPTS) {
            return true;
        }
        guildFixAttempts++;
        CommandSender.send(client, GUILD_TOGGLE_COMMAND);
        sendEgg(client, now);
        return true;
    }

    private static boolean looksLikePlayerChat(String line) {
        return line.contains(">") || line.contains("<") || line.contains(":")
                || line.contains("»") || line.contains("«");
    }

    private static String stripFormattingCodes(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '§' && i + 1 < raw.length()) {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }
}

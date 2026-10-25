package bazaar.bazzar.client;

import java.util.List;

import net.minecraft.client.Minecraft;

final class MacroController {
    static final int KEY_MACRO_COUNT = 8;

    private static long lastTimerSendMs = 0L;

    private MacroController() {
    }

    static String getKeyMessage(int slot) {
        BazzarConfig.State state = BazzarConfig.load();
        List<String> messages = state.macroKeyMessages;
        if (messages == null || slot < 0 || slot >= messages.size()) {
            return "";
        }
        String value = messages.get(slot);
        return value == null ? "" : value;
    }

    static void setKeyMessage(int slot, String value) {
        BazzarConfig.State state = BazzarConfig.load();
        if (state.macroKeyMessages == null || slot < 0 || slot >= state.macroKeyMessages.size()) {
            return;
        }
        state.macroKeyMessages.set(slot, value == null ? "" : value);
        BazzarConfig.save(state);
    }

    static void sendKeyMacro(Minecraft client, int slot) {
        send(client, getKeyMessage(slot));
    }

    static void resetTimer() {
        lastTimerSendMs = System.currentTimeMillis();
    }

    static void tick(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            return;
        }
        BazzarConfig.State state = BazzarConfig.load();
        if (!state.macroTimerEnabled || state.macroTimerMessage == null || state.macroTimerMessage.isBlank()) {
            return;
        }
        long intervalMs = Math.max(5, state.macroTimerIntervalSeconds) * 1000L;
        long now = System.currentTimeMillis();
        if (lastTimerSendMs == 0L) {
            lastTimerSendMs = now;
            return;
        }
        if (now - lastTimerSendMs >= intervalMs) {
            lastTimerSendMs = now;
            send(client, state.macroTimerMessage);
        }
    }

    static void send(Minecraft client, String text) {
        if (client == null || client.player == null || client.player.connection == null || text == null) {
            return;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (trimmed.startsWith("/")) {
            client.player.connection.sendCommand(trimmed.substring(1));
        } else {
            client.player.connection.sendChat(trimmed);
        }
    }
}

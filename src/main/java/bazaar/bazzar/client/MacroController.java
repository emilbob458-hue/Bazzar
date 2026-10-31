package bazaar.bazzar.client;

import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

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
            String command = CommandSender.clean(trimmed);
            if (!runClientCommand(client, command)) {
                CommandSender.send(client, command);
            }
        } else {
            client.player.connection.sendChat(trimmed);
        }
    }

    static boolean runClientCommand(Minecraft client, String command) {
        CommandDispatcher<FabricClientCommandSource> dispatcher = ClientCommandManager.getActiveDispatcher();
        if (dispatcher == null || command == null || command.isBlank()
                || client == null || client.player == null || client.player.connection == null) {
            return false;
        }
        String trimmed = command.trim();
        String root = trimmed.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (dispatcher.getRoot().getChild(root) == null) {
            return false;
        }
        try {
            dispatcher.execute(trimmed, (FabricClientCommandSource) client.player.connection.getSuggestionsProvider());
        } catch (CommandSyntaxException e) {
            McCompat.chat(client.player, BazaarChat.prefix().append(Component.literal("/" + trimmed + ": " + e.getMessage())
                    .withStyle(ChatFormatting.RED)));
        }
        return true;
    }
}

package bazaar.bazzar.client;

import net.minecraft.client.Minecraft;

final class CommandSender {
    private CommandSender() {
    }

    static void send(Minecraft client, String command) {
        if (client == null || client.player == null || client.player.connection == null) {
            return;
        }
        if (command == null) {
            return;
        }
        String trimmed = command.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1).trim();
        }
        if (trimmed.isEmpty()) {
            return;
        }
        client.player.connection.sendCommand(trimmed);
    }
}

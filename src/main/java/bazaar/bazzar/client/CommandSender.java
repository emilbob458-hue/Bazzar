package bazaar.bazzar.client;

import java.util.ArrayDeque;

import net.minecraft.client.Minecraft;

// Every command this mod sends goes out through here. Each feature used to talk to the server
// itself, so a rush cookie switch, a claimall and an auto payout landing in the same tick went out
// together and the server kicked for "sending commands too fast". Nothing stops them happening at
// once, so instead they queue here and leave with a gap between them.
final class CommandSender {
    static final long MIN_GAP_MS = 1_000L;
    // a backlog this long means something is wrong; dropping beats queueing commands for a minute
    static final int MAX_QUEUED = 12;

    private static final ArrayDeque<String> queue = new ArrayDeque<>();
    private static long lastSentMs = 0L;

    private CommandSender() {
    }

    static void send(Minecraft client, String command) {
        if (client == null || client.player == null || client.player.connection == null) {
            return;
        }
        String trimmed = clean(command);
        if (trimmed.isEmpty() || queue.size() >= MAX_QUEUED) {
            return;
        }
        queue.addLast(trimmed);
    }

    // the first command after a quiet spell goes on the next tick; only a run of them is spaced out
    static void tick(Minecraft client) {
        if (queue.isEmpty()) {
            return;
        }
        if (client == null || client.player == null || client.player.connection == null) {
            queue.clear();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSentMs < MIN_GAP_MS) {
            return;
        }
        lastSentMs = now;
        client.player.connection.sendCommand(queue.pollFirst());
    }

    static boolean readyToSend(long lastSentMs, long now) {
        return now - lastSentMs >= MIN_GAP_MS;
    }

    static int queued() {
        return queue.size();
    }

    static String clean(String command) {
        if (command == null) {
            return "";
        }
        String trimmed = command.trim();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1).trim();
        }
        return trimmed;
    }
}

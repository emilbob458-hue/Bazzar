package bazaar.bazzar.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

final class AltCommandController {
    private static final long ACK_REPORT_DELAY_MS = 3_000L;

    private static long lastSeenMs = -1L;
    private static long pendingReportIssuedMs = 0L;
    private static String lastReceivedCommand = "";
    private static long lastReceivedMs = 0L;

    private AltCommandController() {
    }

    static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                literal("alts")
                        .requires(source -> !BazzarGuard.isBlocked())
                        .executes(AltCommandController::runStatus)
                        .then(argument("command", StringArgumentType.greedyString())
                                .executes(AltCommandController::runBroadcast))));
    }

    private static int runStatus(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = context.getSource().getClient();
        SharedFile.Data shared = SharedFile.load();
        BazzarConfig.State state = BazzarConfig.load();
        boolean payer = ShardPayController.isLocalPayer(client, state);
        boolean owner = ShardPayController.isLocalOwner(client, state);
        String role = owner ? "owner" : payer ? "payer" : "not listed";
        StringBuilder text = new StringBuilder("[Bazaar] /alts status, this account: " + role);
        if (shared.broadcastIssuedMs > 0L) {
            long ago = (System.currentTimeMillis() - shared.broadcastIssuedMs) / 1000L;
            text.append(" | last broadcast: /").append(shared.broadcastCommand)
                    .append(" (from ").append(shared.broadcastFrom).append(", ").append(ago).append("s ago, ")
                    .append(shared.broadcastAcks.values().stream()
                            .filter(v -> v != null && v == shared.broadcastIssuedMs).count())
                    .append(" alts ran it)");
        } else {
            text.append(" | no broadcast sent yet");
        }
        if (lastReceivedMs > 0L) {
            long ago = (System.currentTimeMillis() - lastReceivedMs) / 1000L;
            text.append(" | this account last ran: /").append(lastReceivedCommand).append(" ").append(ago).append("s ago");
        }
        context.getSource().sendFeedback(Component.literal(text.toString()).withStyle(ChatFormatting.AQUA));
        return 1;
    }

    private static int runBroadcast(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = context.getSource().getClient();
        String command = StringArgumentType.getString(context, "command");
        if (command == null || command.isBlank()) {
            context.getSource().sendFeedback(
                    Component.literal("[Bazaar] Usage: /alts <command>").withStyle(ChatFormatting.RED));
            return 0;
        }
        String trimmed = command.trim();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1).trim();
        }
        if (trimmed.isBlank()) {
            context.getSource().sendFeedback(
                    Component.literal("[Bazaar] Usage: /alts <command>").withStyle(ChatFormatting.RED));
            return 0;
        }
        String from = client != null && client.player != null
                ? client.player.getName().getString()
                : "";
        String broadcast = trimmed;
        long issuedMs = System.currentTimeMillis();
        SharedFile.update(data -> {
            data.broadcastCommand = broadcast;
            data.broadcastFrom = from;
            data.broadcastIssuedMs = issuedMs;
            data.broadcastAcks.clear();
        });
        pendingReportIssuedMs = issuedMs;
        context.getSource().sendFeedback(
                Component.literal("[Bazaar] Sent to alts on this PC: /" + trimmed).withStyle(ChatFormatting.AQUA));
        return 1;
    }

    static void tick(Minecraft client) {
        if (client == null || client.player == null || client.player.connection == null) {
            return;
        }
        reportAcksIfDue(client);
        SharedFile.Data shared = SharedFile.load();
        if (lastSeenMs < 0L) {
            lastSeenMs = shared.broadcastIssuedMs;
            return;
        }
        if (shared.broadcastIssuedMs == lastSeenMs) {
            return;
        }
        long issuedMs = shared.broadcastIssuedMs;
        boolean execute = AltLogic.shouldExecute(
                shared.broadcastCommand,
                issuedMs,
                lastSeenMs,
                System.currentTimeMillis(),
                ShardPayController.normalizeName(shared.broadcastFrom),
                ShardPayController.normalizeName(client.player.getName().getString()),
                ShardPayController.isLocalPayer(client, BazzarConfig.load()));
        lastSeenMs = issuedMs;
        if (!execute) {
            return;
        }
        String command = shared.broadcastCommand.trim();
        if (command.startsWith("/")) {
            command = command.substring(1).trim();
        }
        if (command.isBlank()) {
            return;
        }
        client.player.connection.sendCommand(command);
        lastReceivedCommand = command;
        lastReceivedMs = System.currentTimeMillis();
        String ackName = ShardPayController.normalizeName(client.player.getName().getString());
        SharedFile.update(data -> data.broadcastAcks.put(ackName, issuedMs));
    }

    private static void reportAcksIfDue(Minecraft client) {
        if (pendingReportIssuedMs <= 0L) {
            return;
        }
        if (System.currentTimeMillis() - pendingReportIssuedMs < ACK_REPORT_DELAY_MS) {
            return;
        }
        long issuedMs = pendingReportIssuedMs;
        pendingReportIssuedMs = 0L;
        SharedFile.Data shared = SharedFile.load();
        List<String> ran = new ArrayList<>();
        for (var entry : shared.broadcastAcks.entrySet()) {
            if (entry.getValue() != null && entry.getValue() == issuedMs) {
                ran.add(entry.getKey());
            }
        }
        ran.sort(String.CASE_INSENSITIVE_ORDER);
        String text = ran.isEmpty()
                ? "[Bazaar] No alts ran the broadcast. Are they on the newest jar? Type /alts on an alt to check its status."
                : "[Bazaar] Alts ran it (" + ran.size() + "): " + String.join(", ", ran);
        client.player.displayClientMessage(
                Component.literal(text).withStyle(ran.isEmpty() ? ChatFormatting.YELLOW : ChatFormatting.AQUA),
                false
        );
    }
}

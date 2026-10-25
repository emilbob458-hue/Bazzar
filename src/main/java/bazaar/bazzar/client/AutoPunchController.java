package bazaar.bazzar.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;

final class AutoPunchController {
    private static long lastPunchMs = 0L;

    private AutoPunchController() {
    }

    static void triggerAuto(Minecraft client) {
        BazzarConfig.State state = BazzarConfig.load();
        if (state == null || !state.autoPunchEnabled) {
            return;
        }
        if (client == null || client.player == null || client.player.connection == null || client.level == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long intervalMs = Math.max(5, state.autoPunchIntervalSeconds) * 1000L;
        if (now - lastPunchMs < intervalMs) {
            return;
        }
        if (!ShardPayController.isLocalPayer(client, state)) {
            return;
        }
        client.player.swing(InteractionHand.MAIN_HAND);
        lastPunchMs = now;
    }
}

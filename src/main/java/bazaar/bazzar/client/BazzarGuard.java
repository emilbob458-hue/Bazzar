package bazaar.bazzar.client;

import java.util.Locale;
import java.util.Set;

import net.minecraft.client.Minecraft;

final class BazzarGuard {
    private static final Set<String> BLOCKED_NAMES = Set.of("ht1playerlt2ely");

    private BazzarGuard() {
    }

    static boolean isBlocked() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) {
            return false;
        }
        String name = client.player.getName().getString().trim().toLowerCase(Locale.ROOT);
        return BLOCKED_NAMES.contains(name);
    }
}
